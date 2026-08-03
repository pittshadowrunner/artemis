-- ============================================================
-- WMS Schema V3 — Waves, Equipment, Containers, Batch Picking,
--                 Dashboard Metric Views
-- ============================================================

-- ------------------------------------------------------------
-- 1. EQUIPMENT (carts, forklifts, pallet jacks, riders...)
-- ------------------------------------------------------------

CREATE TYPE equipment_type AS ENUM
    ('CART','FORKLIFT','PALLET_JACK','PALLET_RIDER','REACH_TRUCK','ORDER_PICKER','WALKIE','TURRET');

CREATE TABLE equipment (
    equipment_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    code            text NOT NULL,                    -- asset tag, e.g. CART-014
    equipment_type  equipment_type NOT NULL,
    lpn             text,                             -- pallet jacks carrying a pallet LPN
    check_digits    text,                             -- spoken to validate put positioning
    container_positions int,                          -- carts: totes/containers that fit → batch size
    max_weight_kg   numeric(12,3),
    voice_enabled   boolean NOT NULL DEFAULT true,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);

-- Each physical slot on a cart, individually verifiable
CREATE TABLE equipment_position (
    equipment_id    uuid NOT NULL REFERENCES equipment(equipment_id) ON DELETE CASCADE,
    position_no     int NOT NULL,
    check_digits    text NOT NULL,                    -- "put to position 3, say 41"
    PRIMARY KEY (equipment_id, position_no)
);

-- ------------------------------------------------------------
-- 2. CONTAINERS (totes + shipping containers)
-- ------------------------------------------------------------

CREATE TYPE container_type AS ENUM ('TOTE','CARTON','PALLET','SHIPPING_CONTAINER');

CREATE TABLE container (
    container_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    barcode         text NOT NULL,                    -- scanned/spoken ID
    container_type  container_type NOT NULL,
    check_digits    text,                             -- voice put validation
    reusable        boolean NOT NULL DEFAULT true,    -- totes yes, cartons no
    tare_weight_kg  numeric(10,3),
    max_weight_kg   numeric(12,3),
    active          boolean NOT NULL DEFAULT true,
    UNIQUE (site_id, barcode)
);

-- ------------------------------------------------------------
-- 3. WAVES
-- ------------------------------------------------------------

CREATE TYPE wave_type AS ENUM (
    'RUSH',             -- expedited orders jump the queue
    'PROXIMITY',        -- orders clustered by pick_sequence span to minimize travel
    'SHIP_URGENCY',     -- earliest requested_ship_date first
    'CARRIER_CUTOFF',   -- everything that must make a carrier's departure time
    'ROUTE',            -- one delivery route; load in reverse stop_sequence
    'ZONE',             -- confined to one Area; enables zone picking
    'SINGLE_CUSTOMER',  -- large single-customer builds (full trailers)
    'REPLEN_AWARE');    -- deferred until pick faces are replenished

CREATE TYPE wave_status AS ENUM ('PLANNED','RELEASED','PICKING','COMPLETE','CANCELLED');

CREATE TABLE wave (
    wave_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    wave_number     text NOT NULL,
    wave_type       wave_type NOT NULL,
    status          wave_status NOT NULL DEFAULT 'PLANNED',
    carrier_cutoff  timestamptz,                      -- for CARRIER_CUTOFF waves
    route_code      text,                             -- for ROUTE waves
    area_id         uuid REFERENCES org_node(org_node_id),  -- for ZONE waves
    planned_by      uuid REFERENCES app_user(user_id),
    released_at     timestamptz,
    completed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, wave_number)
);
ALTER TABLE wave ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_iso_wave ON wave
    USING (corporation_id = current_setting('app.current_corp', true)::uuid);

CREATE TABLE wave_order (
    wave_id  uuid NOT NULL REFERENCES wave(wave_id) ON DELETE CASCADE,
    order_id uuid NOT NULL REFERENCES customer_order(order_id),
    PRIMARY KEY (wave_id, order_id)
);

-- ------------------------------------------------------------
-- 4. BATCH CART PICKING
--    One assignment = one cart trip covering N orders,
--    N = equipment.container_positions. Each order maps to a
--    container in a cart position. For direct-to-shipping-
--    container flows, an LPN is inducted per order and reused
--    for every subsequent pick on that order.
-- ------------------------------------------------------------

ALTER TABLE assignment
    ADD COLUMN wave_id      uuid REFERENCES wave(wave_id),
    ADD COLUMN equipment_id uuid REFERENCES equipment(equipment_id);

CREATE TABLE assignment_container (
    assignment_container_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id   uuid NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    order_id        uuid NOT NULL REFERENCES customer_order(order_id),
    container_id    uuid REFERENCES container(container_id),  -- tote, or NULL until induction
    cart_position   int,                              -- which slot on the cart
    inducted_lpn    text,                             -- shipping-container LPN, per order
    inducted_at     timestamptz,
    UNIQUE (assignment_id, order_id),
    UNIQUE (assignment_id, cart_position)
);
CREATE INDEX idx_ac_order ON assignment_container(order_id);

-- Put-side validation on each task: which container/position to
-- put to, and what the picker must say/scan to confirm.
ALTER TABLE assignment_task
    ADD COLUMN to_container_id  uuid REFERENCES container(container_id),
    ADD COLUMN cart_position    int,
    ADD COLUMN put_check_digits text;

-- Batch candidate scoring: for a set of open orders, how tightly
-- do their pick locations cluster? Lower span = better batch.
CREATE OR REPLACE FUNCTION order_pick_span(p_order uuid)
RETURNS int LANGUAGE sql STABLE AS $$
    SELECT COALESCE(max(l.pick_sequence) - min(l.pick_sequence), 0)
    FROM customer_order_line col
    JOIN allocation a   ON a.order_line_id = col.order_line_id
    JOIN inventory  inv ON inv.inventory_id = a.inventory_id
    JOIN location   l   ON l.location_id = inv.location_id
    WHERE col.order_id = p_order;
$$;

-- ------------------------------------------------------------
-- 5. DASHBOARD METRIC VIEWS
--    Read-only, tenant-filtered by RLS on base tables.
-- ------------------------------------------------------------

-- Pick-face velocity: lines, visits, cases per face per day.
-- A "visit" = a distinct assignment stopping at that face.
CREATE VIEW v_pick_face_velocity AS
SELECT
    a.site_id,
    t.from_location                       AS location_id,
    l.code                                AS location_code,
    t.item_id,
    i.sku,
    date_trunc('day', t.completed_at)     AS day,
    count(*)                              AS lines,
    count(DISTINCT t.assignment_id)       AS visits,
    sum(t.qty)                            AS cases
FROM assignment_task t
JOIN assignment a ON a.assignment_id = t.assignment_id
JOIN location   l ON l.location_id = t.from_location
LEFT JOIN item  i ON i.item_id = t.item_id
WHERE a.assignment_type = 'SELECTION'
  AND t.status = 'COMPLETE'
GROUP BY a.site_id, t.from_location, l.code, t.item_id, i.sku,
         date_trunc('day', t.completed_at);

-- Receiving day progress: expected vs received per manifest today
CREATE VIEW v_receiving_progress AS
SELECT
    m.site_id, m.manifest_id, m.manifest_number, m.carrier, m.status,
    sum(ml.expected_qty) AS expected_qty,
    sum(ml.received_qty) AS received_qty,
    round(100.0 * sum(ml.received_qty) / NULLIF(sum(ml.expected_qty),0), 1) AS pct_complete
FROM receiving_manifest m
JOIN receiving_manifest_line ml ON ml.manifest_id = m.manifest_id
GROUP BY m.site_id, m.manifest_id, m.manifest_number, m.carrier, m.status;

-- Shipping day progress: order pipeline counts by status
CREATE VIEW v_shipping_progress AS
SELECT site_id, status, count(*) AS orders,
       min(requested_ship_date) AS earliest_ship_date
FROM customer_order
WHERE status NOT IN ('SHIPPED','CANCELLED')
GROUP BY site_id, status;

-- Wave progress
CREATE VIEW v_wave_progress AS
SELECT
    w.site_id, w.wave_id, w.wave_number, w.wave_type, w.status,
    count(t.task_id)                                    AS total_tasks,
    count(t.task_id) FILTER (WHERE t.status='COMPLETE') AS done_tasks
FROM wave w
LEFT JOIN assignment a ON a.wave_id = w.wave_id
LEFT JOIN assignment_task t ON t.assignment_id = a.assignment_id
GROUP BY w.site_id, w.wave_id, w.wave_number, w.wave_type, w.status;

-- Replenishment pressure: pick faces at/below trigger
CREATE VIEW v_replen_pressure AS
SELECT
    l.site_id, l.location_id, l.code AS location_code,
    l.replen_item_id, i.sku,
    COALESCE(sum(inv.qty),0)  AS on_hand,
    l.replen_trigger_qty, l.replen_max_qty
FROM location l
LEFT JOIN inventory inv ON inv.location_id = l.location_id
                       AND inv.status = 'AVAILABLE'
JOIN item i ON i.item_id = l.replen_item_id
WHERE l.replen_item_id IS NOT NULL AND l.active
GROUP BY l.site_id, l.location_id, l.code, l.replen_item_id, i.sku,
         l.replen_trigger_qty, l.replen_max_qty
HAVING COALESCE(sum(inv.qty),0) <= l.replen_trigger_qty;

-- Labor productivity: completed tasks + cases per user per day
CREATE VIEW v_labor_productivity AS
SELECT
    a.site_id, a.assigned_to AS user_id, u.display_name,
    a.assignment_type,
    date_trunc('day', t.completed_at) AS day,
    count(*)  AS tasks,
    sum(t.qty) AS cases
FROM assignment_task t
JOIN assignment a ON a.assignment_id = t.assignment_id
JOIN app_user  u ON u.user_id = a.assigned_to
WHERE t.status = 'COMPLETE'
GROUP BY a.site_id, a.assigned_to, u.display_name, a.assignment_type,
         date_trunc('day', t.completed_at);

-- Expiry risk rollup for the dashboard tile
CREATE VIEW v_expiry_risk AS
SELECT
    inv.site_id, inv.item_id, i.sku, inv.lot_number,
    inv.expiration_date,
    (inv.expiration_date - CURRENT_DATE) AS days_remaining,
    sum(inv.qty) AS qty
FROM inventory inv
JOIN item i ON i.item_id = inv.item_id
WHERE inv.expiration_date IS NOT NULL
  AND inv.status IN ('AVAILABLE','ALLOCATED')
GROUP BY inv.site_id, inv.item_id, i.sku, inv.lot_number, inv.expiration_date;
