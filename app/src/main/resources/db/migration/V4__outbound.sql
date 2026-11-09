-- ============================================================
-- Artemis WMS — V4 Outbound
-- Orders → zone orders → waves → allocation → selection → drop →
-- shipment + packing list.
--
-- Structural, not conventional:
--   • A Zone Order is the unit of outbound work: (order × temp zone),
--     derived by trigger from the order's lines. A wave is single-zone
--     BY CONSTRUCTION — the database refuses a zone order that doesn't
--     match the wave's zone. Frozen never waits on dry.
--   • Allocation is a row per (order line × pallet); the shelf-life
--     bypass is recorded on the row and raises an alert.
-- ============================================================

CREATE TYPE order_status    AS ENUM ('NEW','ALLOCATED','PICKING','PICKED','DROPPED','SHIPPED','CANCELLED');
CREATE TYPE wave_type       AS ENUM ('RUSH','PROXIMITY','SHIP_URGENCY','CARRIER_CUTOFF','ROUTE','ZONE','SINGLE_CUSTOMER');
CREATE TYPE wave_status     AS ENUM ('PLANNED','RELEASED','PICKING','COMPLETE','CANCELLED');
CREATE TYPE shipment_status AS ENUM ('OPEN','LOADING','SHIPPED','CANCELLED');

-- ------------------------------------------------------------
-- 1. ORDERS
-- ------------------------------------------------------------
CREATE TABLE customer_order (
    order_id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id      uuid NOT NULL,
    site_id             uuid NOT NULL REFERENCES org_node(org_node_id),
    customer_id         uuid NOT NULL REFERENCES customer(customer_id),
    order_number        text NOT NULL,
    status              order_status NOT NULL DEFAULT 'NEW',
    rush                boolean NOT NULL DEFAULT false,
    priority            int NOT NULL DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    requested_ship_date date,
    route_code          text,                        -- snapshot from the customer at creation
    stop_sequence       int,
    drop_location_id    uuid REFERENCES location(location_id),
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, order_number)
);
CREATE INDEX idx_order_site_status ON customer_order(site_id, status);
CREATE INDEX idx_order_ship_date   ON customer_order(site_id, requested_ship_date);

CREATE TABLE customer_order_line (
    order_line_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id      uuid NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    line_number   int NOT NULL,
    item_id       uuid NOT NULL REFERENCES item(item_id),
    ordered_qty   numeric(14,3) NOT NULL CHECK (ordered_qty > 0),
    allocated_qty numeric(14,3) NOT NULL DEFAULT 0,
    picked_qty    numeric(14,3) NOT NULL DEFAULT 0,
    UNIQUE (order_id, line_number)
);
CREATE INDEX idx_order_line_item ON customer_order_line(item_id);

-- ------------------------------------------------------------
-- 2. WAVES — single temp zone by construction
-- ------------------------------------------------------------
CREATE SEQUENCE wave_number_seq START 1001;

CREATE TABLE wave (
    wave_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid NOT NULL,
    site_id        uuid NOT NULL REFERENCES org_node(org_node_id),
    wave_number    text NOT NULL,
    wave_type      wave_type NOT NULL,
    temp_zone      temp_zone NOT NULL,
    status         wave_status NOT NULL DEFAULT 'PLANNED',
    batch_size     int NOT NULL DEFAULT 6 CHECK (batch_size BETWEEN 1 AND 24),
    carrier_cutoff timestamptz,
    route_code     text,
    area_id        uuid REFERENCES org_node(org_node_id),
    customer_id    uuid REFERENCES customer(customer_id),
    planned_by     uuid REFERENCES app_user(user_id),
    released_at    timestamptz,
    completed_at   timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, wave_number)
);
CREATE INDEX idx_wave_site_status ON wave(site_id, status);

CREATE OR REPLACE FUNCTION next_wave_number() RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT 'W-' || to_char(now(), 'YYMMDD') || '-' || lpad(nextval('wave_number_seq')::text, 4, '0');
$$;

-- ------------------------------------------------------------
-- 3. ZONE ORDERS — (order × temp zone), derived from the lines
-- ------------------------------------------------------------
CREATE TABLE zone_order (
    zone_order_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid NOT NULL,
    order_id       uuid NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    temp_zone      temp_zone NOT NULL,
    wave_id        uuid REFERENCES wave(wave_id),
    assignment_id  uuid REFERENCES assignment(assignment_id),
    dropped_at     timestamptz,
    drop_location_id uuid REFERENCES location(location_id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, temp_zone)
);
CREATE INDEX idx_zone_order_wave       ON zone_order(wave_id);
CREATE INDEX idx_zone_order_assignment ON zone_order(assignment_id);
CREATE INDEX idx_zone_order_open       ON zone_order(temp_zone) WHERE wave_id IS NULL;

CREATE OR REPLACE FUNCTION fn_zone_order_from_line() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO zone_order (corporation_id, order_id, temp_zone)
    SELECT o.corporation_id, o.order_id, i.temp_zone
    FROM customer_order o, item i WHERE o.order_id = NEW.order_id AND i.item_id = NEW.item_id
    ON CONFLICT (order_id, temp_zone) DO NOTHING;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_zone_order_from_line AFTER INSERT ON customer_order_line
    FOR EACH ROW EXECUTE FUNCTION fn_zone_order_from_line();

-- The guarantee: a zone order can only join a wave of its own zone.
CREATE OR REPLACE FUNCTION fn_zone_order_wave_match() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE wz temp_zone;
BEGIN
    IF NEW.wave_id IS NULL THEN RETURN NEW; END IF;
    SELECT temp_zone INTO wz FROM wave WHERE wave_id = NEW.wave_id;
    IF wz IS DISTINCT FROM NEW.temp_zone THEN
        RAISE EXCEPTION 'Zone order is % but wave is % — waves are single-zone.', NEW.temp_zone, wz USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_zone_order_wave_match BEFORE INSERT OR UPDATE OF wave_id ON zone_order
    FOR EACH ROW EXECUTE FUNCTION fn_zone_order_wave_match();

-- ------------------------------------------------------------
-- 4. ALLOCATION — (order line × pallet). bypass = strict rotation
--    was skipped for this row because of customer shelf-life terms.
-- ------------------------------------------------------------
CREATE TABLE allocation (
    allocation_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_line_id uuid NOT NULL REFERENCES customer_order_line(order_line_id) ON DELETE CASCADE,
    inventory_id  uuid NOT NULL REFERENCES inventory(inventory_id),
    qty           numeric(14,3) NOT NULL CHECK (qty > 0),
    picked_qty    numeric(14,3) NOT NULL DEFAULT 0,
    bypass        boolean NOT NULL DEFAULT false,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_alloc_line ON allocation(order_line_id);
CREATE INDEX idx_alloc_inv  ON allocation(inventory_id);

-- Free stock = on hand minus open allocations. Every allocator reads this.
CREATE VIEW v_available_inventory AS
SELECT inv.inventory_id, inv.corporation_id, inv.site_id, inv.lpn, inv.item_id, inv.location_id,
       inv.lot_number, inv.expiration_date, inv.arrival_date,
       inv.qty,
       inv.qty - COALESCE((SELECT sum(a.qty - a.picked_qty) FROM allocation a WHERE a.inventory_id = inv.inventory_id), 0) AS available_qty,
       COALESCE(l.replen_item_id = inv.item_id, false) AS at_pick_face,   -- never NULL: NULLs would sort first under DESC
       l.pick_sequence, l.code AS location_code, l.area_id
FROM inventory inv
LEFT JOIN location l ON l.location_id = inv.location_id
WHERE inv.status = 'AVAILABLE' AND inv.location_id IS NOT NULL
  AND l.loc_type = 'STANDARD';

-- ------------------------------------------------------------
-- 5. SELECTION — assignments carry a wave, a cart, and one container
--    (cart position / tote / inducted shipping LPN) per zone order.
-- ------------------------------------------------------------
ALTER TABLE assignment
    ADD COLUMN wave_id      uuid REFERENCES wave(wave_id),
    ADD COLUMN equipment_id uuid REFERENCES equipment(equipment_id);
CREATE INDEX idx_assign_wave ON assignment(wave_id);

CREATE TABLE assignment_container (
    assignment_container_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id    uuid NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    zone_order_id    uuid NOT NULL REFERENCES zone_order(zone_order_id),
    cart_position    int,
    put_check_digits char(2),
    container_id     uuid REFERENCES container(container_id),   -- tote, once inducted
    inducted_lpn     text,                                     -- shipping-container LPN
    inducted_at      timestamptz,
    UNIQUE (assignment_id, zone_order_id),
    UNIQUE (assignment_id, cart_position)
);

ALTER TABLE assignment_task
    ADD COLUMN allocation_id uuid REFERENCES allocation(allocation_id),
    ADD COLUMN cart_position int;

-- ------------------------------------------------------------
-- 6. SHIPMENTS & PACKING LISTS
-- ------------------------------------------------------------
CREATE SEQUENCE shipment_number_seq START 5001;

CREATE TABLE shipment (
    shipment_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    shipment_number text NOT NULL,
    customer_id     uuid NOT NULL REFERENCES customer(customer_id),
    carrier         text,
    trailer_number  text,
    dock_location_id uuid REFERENCES location(location_id),
    status          shipment_status NOT NULL DEFAULT 'OPEN',
    shipped_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, shipment_number)
);

CREATE TABLE shipment_order (
    shipment_id uuid NOT NULL REFERENCES shipment(shipment_id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES customer_order(order_id),
    PRIMARY KEY (shipment_id, order_id),
    UNIQUE (order_id)                                          -- an order ships once
);

CREATE TABLE packing_list (
    packing_list_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id     uuid NOT NULL UNIQUE REFERENCES shipment(shipment_id) ON DELETE CASCADE,
    document_number text NOT NULL,
    generated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE packing_list_line (
    packing_list_id uuid NOT NULL REFERENCES packing_list(packing_list_id) ON DELETE CASCADE,
    line_number     int NOT NULL,
    order_id        uuid NOT NULL REFERENCES customer_order(order_id),
    item_id         uuid NOT NULL REFERENCES item(item_id),
    qty             numeric(14,3) NOT NULL,
    lot_number      text,
    expiration_date date,
    lpn             text,
    PRIMARY KEY (packing_list_id, line_number)
);

CREATE OR REPLACE FUNCTION next_shipment_number() RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT 'S-' || to_char(now(), 'YYMMDD') || '-' || lpad(nextval('shipment_number_seq')::text, 4, '0');
$$;

-- ------------------------------------------------------------
-- 7. VIEWS
-- ------------------------------------------------------------
CREATE VIEW v_order_progress AS
SELECT o.corporation_id, o.site_id, o.order_id, o.order_number, o.status, o.rush, o.priority, o.requested_ship_date,
       o.route_code, o.stop_sequence, c.code AS customer_code, c.name AS customer_name,
       count(ol.*) AS lines, sum(ol.ordered_qty) AS ordered_qty, sum(ol.allocated_qty) AS allocated_qty, sum(ol.picked_qty) AS picked_qty,
       (SELECT string_agg(z.temp_zone::text, ',' ORDER BY z.temp_zone) FROM zone_order z WHERE z.order_id = o.order_id) AS zones,
       (SELECT count(*) FROM zone_order z WHERE z.order_id = o.order_id AND z.wave_id IS NOT NULL) AS zones_waved,
       (SELECT count(*) FROM zone_order z WHERE z.order_id = o.order_id AND z.dropped_at IS NOT NULL) AS zones_dropped,
       o.created_at
FROM customer_order o
JOIN customer c ON c.customer_id = o.customer_id
LEFT JOIN customer_order_line ol ON ol.order_id = o.order_id
GROUP BY o.order_id, c.code, c.name;

CREATE VIEW v_wave_progress AS
SELECT w.corporation_id, w.site_id, w.wave_id, w.wave_number, w.wave_type, w.temp_zone, w.status, w.batch_size,
       w.released_at, w.completed_at,
       (SELECT count(*) FROM zone_order z WHERE z.wave_id = w.wave_id) AS zone_orders,
       (SELECT count(*) FROM assignment a WHERE a.wave_id = w.wave_id) AS assignments,
       (SELECT count(*) FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE a.wave_id = w.wave_id) AS tasks,
       (SELECT count(*) FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id WHERE a.wave_id = w.wave_id AND t.status = 'COMPLETE') AS tasks_complete,
       w.created_at
FROM wave w;

CREATE VIEW v_shipping_progress AS
SELECT s.corporation_id, s.site_id, s.shipment_id, s.shipment_number, s.status, s.carrier, s.trailer_number, s.shipped_at,
       c.code AS customer_code, c.name AS customer_name,
       (SELECT count(*) FROM shipment_order so WHERE so.shipment_id = s.shipment_id) AS orders,
       (SELECT count(*) FROM shipment_order so JOIN customer_order o ON o.order_id = so.order_id
         WHERE so.shipment_id = s.shipment_id AND o.status IN ('DROPPED','SHIPPED')) AS orders_ready,
       s.created_at
FROM shipment s JOIN customer c ON c.customer_id = s.customer_id;

-- Pick-face velocity: lines and cases by face over the trailing 30 days.
CREATE VIEW v_pick_face_velocity AS
SELECT l.corporation_id, l.site_id, l.location_id, l.code AS location_code, i.sku,
       count(t.*) AS lines_30d, COALESCE(sum(t.qty), 0) AS cases_30d,
       count(DISTINCT date_trunc('day', t.completed_at)) AS active_days_30d
FROM location l
JOIN item i ON i.item_id = l.replen_item_id
LEFT JOIN assignment_task t ON t.from_location = l.location_id AND t.status = 'COMPLETE'
     AND t.completed_at > now() - interval '30 days'
LEFT JOIN assignment a ON a.assignment_id = t.assignment_id AND a.assignment_type = 'SELECTION'
WHERE l.replen_item_id IS NOT NULL
GROUP BY l.corporation_id, l.site_id, l.location_id, l.code, i.sku;

-- ------------------------------------------------------------
-- 8. ROW LEVEL SECURITY
-- ------------------------------------------------------------
SELECT tenant_isolate(t) FROM unnest(ARRAY['customer_order','wave','zone_order','shipment']) AS t;
