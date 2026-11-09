-- ============================================================
-- Artemis WMS — V3 Inventory & Inbound
-- Pallets (LPN-based inventory), movement journal, receiving
-- documents, the assignment work unit, directed putaway.
--
-- Slot conventions enforced at the database on STANDARD slots:
--   • one item per slot (docks/drops/staging exempt — freight
--     legitimately co-mingles in motion)
--   • item temp zone is authoritative: milk is CHL wherever it sits
-- ============================================================

CREATE TYPE inventory_status  AS ENUM ('AVAILABLE','ALLOCATED','PICKED','HOLD','SHIPPED','DAMAGED');
CREATE TYPE manifest_status   AS ENUM ('EXPECTED','ARRIVED','RECEIVING','RECEIVED','CLOSED','CANCELLED');
CREATE TYPE assignment_type   AS ENUM ('RECEIVING','PUTAWAY','REPLENISHMENT','SELECTION','LOADING','SHIPPING','CYCLE_COUNT');
CREATE TYPE assignment_status AS ENUM ('OPEN','ASSIGNED','IN_PROGRESS','COMPLETE','CANCELLED');

-- ------------------------------------------------------------
-- 1. RECEIVING — a linkable operational document with a timeline
-- ------------------------------------------------------------
CREATE TABLE receiving_manifest (
    manifest_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    manifest_number text NOT NULL,
    carrier         text,
    trailer_number  text,
    expected_date   date,
    status          manifest_status NOT NULL DEFAULT 'EXPECTED',
    arrived_at      timestamptz,
    closed_at       timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, manifest_number)
);
CREATE INDEX idx_manifest_site_status ON receiving_manifest(site_id, status);

CREATE TABLE receiving_manifest_line (
    manifest_line_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    manifest_id      uuid NOT NULL REFERENCES receiving_manifest(manifest_id) ON DELETE CASCADE,
    line_number      int NOT NULL,
    item_id          uuid NOT NULL REFERENCES item(item_id),
    expected_qty     numeric(14,3) NOT NULL CHECK (expected_qty > 0),
    received_qty     numeric(14,3) NOT NULL DEFAULT 0,
    rejected_qty     numeric(14,3) NOT NULL DEFAULT 0,
    rejection_reason text,
    UNIQUE (manifest_id, line_number)
);

-- ------------------------------------------------------------
-- 2. INVENTORY — one row per pallet/LPN. original_qty is what the
--    pallet held when it was born (received or split); qty is now.
-- ------------------------------------------------------------
CREATE TABLE inventory (
    inventory_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id   uuid NOT NULL,
    site_id          uuid NOT NULL REFERENCES org_node(org_node_id),
    lpn              text NOT NULL,
    item_id          uuid NOT NULL REFERENCES item(item_id),
    location_id      uuid REFERENCES location(location_id),   -- NULL = in transit on equipment
    qty              numeric(14,3) NOT NULL CHECK (qty >= 0),
    original_qty     numeric(14,3) NOT NULL,
    status           inventory_status NOT NULL DEFAULT 'AVAILABLE',
    lot_number       text,
    expiration_date  date,
    arrival_date     date NOT NULL DEFAULT CURRENT_DATE,
    actual_weight_kg numeric(12,3),                            -- catch-weight capture
    manifest_id      uuid REFERENCES receiving_manifest(manifest_id),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, lpn),
    CONSTRAINT chk_qty_within_original CHECK (qty <= original_qty)
);
CREATE INDEX idx_inv_item     ON inventory(site_id, item_id, status);
CREATE INDEX idx_inv_location ON inventory(location_id);
CREATE INDEX idx_inv_lot      ON inventory(item_id, lot_number);
CREATE INDEX idx_inv_expiry   ON inventory(site_id, expiration_date) WHERE expiration_date IS NOT NULL;

CREATE TABLE inventory_serial (
    inventory_id  uuid NOT NULL REFERENCES inventory(inventory_id) ON DELETE CASCADE,
    serial_number text NOT NULL,
    PRIMARY KEY (inventory_id, serial_number)
);

-- ------------------------------------------------------------
-- 3. ASSIGNMENTS — the universal work unit across workstreams.
--    Priority 1–10, zone-aware defaults at creation. Reassignment
--    is a tracked event, not a silent overwrite.
-- ------------------------------------------------------------
CREATE SEQUENCE assignment_number_seq START 10001;

CREATE TABLE assignment (
    assignment_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id    uuid NOT NULL,
    site_id           uuid NOT NULL REFERENCES org_node(org_node_id),
    assignment_number text NOT NULL,
    assignment_type   assignment_type NOT NULL,
    status            assignment_status NOT NULL DEFAULT 'OPEN',
    priority          int NOT NULL DEFAULT 5 CHECK (priority BETWEEN 1 AND 10),
    temp_zone         temp_zone,                     -- single-zone by construction
    assigned_to       uuid REFERENCES app_user(user_id),
    previous_assignee uuid REFERENCES app_user(user_id),
    reassigned_count  int NOT NULL DEFAULT 0,
    last_assigned_at  timestamptz,
    manifest_id       uuid REFERENCES receiving_manifest(manifest_id),
    started_at        timestamptz,
    completed_at      timestamptz,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corporation_id, assignment_number)
);
CREATE INDEX idx_assign_site ON assignment(site_id, status, assignment_type);
CREATE INDEX idx_assign_user ON assignment(assigned_to) WHERE status IN ('ASSIGNED','IN_PROGRESS');

CREATE OR REPLACE FUNCTION next_assignment_number() RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT 'A-' || to_char(now(), 'YYMMDD') || '-' || lpad(nextval('assignment_number_seq')::text, 5, '0');
$$;

CREATE TABLE assignment_task (
    task_id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id    uuid NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    seq              int NOT NULL,
    inventory_id     uuid REFERENCES inventory(inventory_id),
    item_id          uuid REFERENCES item(item_id),
    from_location    uuid REFERENCES location(location_id),
    to_location      uuid REFERENCES location(location_id),
    qty              numeric(14,3),
    status           assignment_status NOT NULL DEFAULT 'OPEN',
    check_digits     char(2),                        -- pick/putaway side verification
    put_check_digits char(2),                        -- put side (cart position / tote)
    spoken_prompt    text,
    completed_at     timestamptz,
    UNIQUE (assignment_id, seq)
);
CREATE INDEX idx_task_open ON assignment_task(assignment_id) WHERE status <> 'COMPLETE';

-- Assignment rolls to COMPLETE when its last task does; first completion marks IN_PROGRESS.
CREATE OR REPLACE FUNCTION fn_task_rollup() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'COMPLETE' AND OLD.status <> 'COMPLETE' THEN
        UPDATE assignment SET
            status = CASE WHEN EXISTS (SELECT 1 FROM assignment_task t
                                       WHERE t.assignment_id = NEW.assignment_id AND t.status <> 'COMPLETE')
                          THEN 'IN_PROGRESS'::assignment_status ELSE 'COMPLETE'::assignment_status END,
            started_at   = COALESCE(started_at, now()),
            completed_at = CASE WHEN EXISTS (SELECT 1 FROM assignment_task t
                                             WHERE t.assignment_id = NEW.assignment_id AND t.status <> 'COMPLETE')
                                THEN NULL ELSE now() END,
            updated_at = now()
        WHERE assignment_id = NEW.assignment_id;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_task_rollup AFTER UPDATE OF status ON assignment_task
    FOR EACH ROW EXECUTE FUNCTION fn_task_rollup();

-- ------------------------------------------------------------
-- 4. MOVEMENT JOURNAL — every touch, forever
-- ------------------------------------------------------------
CREATE TABLE inventory_movement (
    movement_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id  uuid NOT NULL REFERENCES inventory(inventory_id) ON DELETE CASCADE,
    from_location uuid REFERENCES location(location_id),
    to_location   uuid REFERENCES location(location_id),
    qty           numeric(14,3) NOT NULL,
    movement_type text NOT NULL,   -- OPENING_BALANCE RECEIPT PUTAWAY PUTAWAY_OVERRIDE REPLENISH PICK DROP SHIP ADJUST
    performed_by  uuid REFERENCES app_user(user_id),
    assignment_id uuid REFERENCES assignment(assignment_id),
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_move_inv ON inventory_movement(inventory_id, created_at);

-- ------------------------------------------------------------
-- 5. SLOT CONVENTIONS (STANDARD slots only)
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_slot_conventions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE loc record; item_zone temp_zone; other_sku text;
BEGIN
    IF NEW.location_id IS NULL THEN RETURN NEW; END IF;
    SELECT loc_type, temp_zone, code INTO loc FROM location WHERE location_id = NEW.location_id;
    IF loc.loc_type <> 'STANDARD' THEN RETURN NEW; END IF;

    SELECT temp_zone INTO item_zone FROM item WHERE item_id = NEW.item_id;
    IF item_zone IS DISTINCT FROM loc.temp_zone THEN
        RAISE EXCEPTION 'Temp zone convention: item requires %, slot % is %.',
            item_zone, loc.code, loc.temp_zone USING ERRCODE = '23514';
    END IF;

    SELECT i.sku::text INTO other_sku
    FROM inventory inv JOIN item i ON i.item_id = inv.item_id
    WHERE inv.location_id = NEW.location_id
      AND inv.item_id <> NEW.item_id
      AND inv.inventory_id <> NEW.inventory_id
      AND inv.status IN ('AVAILABLE','ALLOCATED','PICKED')
    LIMIT 1;
    IF other_sku IS NOT NULL THEN
        RAISE EXCEPTION 'Single-item convention: slot % already holds % — one item per slot.',
            loc.code, other_sku USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_slot_conventions BEFORE INSERT OR UPDATE OF location_id, item_id ON inventory
    FOR EACH ROW EXECUTE FUNCTION fn_slot_conventions();

-- ------------------------------------------------------------
-- 6. DIRECTED PUTAWAY — one implementation, so the API, the voice
--    dialog and any future slotting simulator agree on "the right
--    slot". Reserve slots only (pick faces belong to replenishment).
--    Rank: consolidate with same item → velocity match → shortest travel.
-- ------------------------------------------------------------
CREATE OR REPLACE FUNCTION directed_putaway_slot(
    p_site uuid, p_item uuid, p_lot text, p_allow_item_mixing boolean, p_allow_lot_mixing boolean)
RETURNS TABLE (location_id uuid, code text, check_digits char(2), pick_sequence int)
LANGUAGE sql STABLE AS $$
    SELECT l.location_id, l.code, l.check_digits, l.pick_sequence
    FROM location l
    JOIN item it ON it.item_id = p_item
    WHERE l.site_id = p_site AND l.active
      AND l.loc_type = 'STANDARD'
      AND l.replen_item_id IS NULL
      AND l.temp_zone = it.temp_zone
      AND (it.hazmat_class IS NULL OR l.hazmat_approved)
      AND NOT EXISTS (
          SELECT 1 FROM inventory i
          WHERE i.location_id = l.location_id
            AND i.status IN ('AVAILABLE','ALLOCATED')
            AND ((NOT p_allow_item_mixing AND i.item_id <> p_item)
              OR (NOT p_allow_lot_mixing AND i.item_id = p_item AND i.lot_number IS DISTINCT FROM p_lot)))
    ORDER BY
      EXISTS (SELECT 1 FROM inventory i2 WHERE i2.location_id = l.location_id
                AND i2.item_id = p_item AND i2.status IN ('AVAILABLE','ALLOCATED')) DESC,
      (l.velocity_zone IS NOT DISTINCT FROM it.velocity_class) DESC,
      l.pick_sequence ASC NULLS LAST
    LIMIT 1;
$$;

-- Zone-aware default priority for new work.
CREATE OR REPLACE FUNCTION default_priority(p_type assignment_type, p_zone temp_zone, p_critical boolean DEFAULT false)
RETURNS int LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_type
        WHEN 'SELECTION'     THEN CASE WHEN p_zone IN ('FROZEN','DEEP_FROZEN') THEN 8 WHEN p_zone = 'REFRIGERATED' THEN 7 ELSE 5 END
        WHEN 'PUTAWAY'       THEN CASE WHEN p_zone IN ('FROZEN','DEEP_FROZEN') THEN 7 WHEN p_zone = 'REFRIGERATED' THEN 6 ELSE 4 END
        WHEN 'REPLENISHMENT' THEN CASE WHEN p_critical THEN 9 WHEN p_zone IN ('FROZEN','DEEP_FROZEN') THEN 8 WHEN p_zone = 'REFRIGERATED' THEN 7 ELSE 6 END
        ELSE 5 END;
$$;

-- ------------------------------------------------------------
-- 7. VIEWS
-- ------------------------------------------------------------
CREATE VIEW v_receiving_progress AS
SELECT m.corporation_id, m.site_id, m.manifest_id, m.manifest_number, m.carrier, m.status,
       m.expected_date, m.arrived_at, m.closed_at,
       sum(ml.expected_qty) AS expected_qty,
       sum(ml.received_qty) AS received_qty,
       sum(ml.rejected_qty) AS rejected_qty,
       round(100.0 * sum(ml.received_qty) / NULLIF(sum(ml.expected_qty), 0), 1) AS pct_complete
FROM receiving_manifest m
LEFT JOIN receiving_manifest_line ml ON ml.manifest_id = m.manifest_id
GROUP BY m.corporation_id, m.site_id, m.manifest_id;

-- Dock dwell: pallets received but not yet put away.
CREATE VIEW v_dock_dwell AS
SELECT inv.corporation_id, inv.site_id, inv.inventory_id, inv.lpn, inv.item_id, i.sku, i.temp_zone,
       l.code AS dock_code, inv.created_at AS received_at,
       now() - inv.created_at AS dwell
FROM inventory inv
JOIN location l ON l.location_id = inv.location_id AND l.loc_type IN ('RECEIVING_DOCK','CROSS_DOCK')
JOIN item i ON i.item_id = inv.item_id
WHERE inv.status = 'AVAILABLE';

CREATE VIEW v_expiry_risk AS
SELECT inv.corporation_id, inv.site_id, inv.item_id, i.sku, inv.lot_number, inv.expiration_date,
       (inv.expiration_date - CURRENT_DATE) AS days_remaining,
       sum(inv.qty) AS qty
FROM inventory inv JOIN item i ON i.item_id = inv.item_id
WHERE inv.expiration_date IS NOT NULL AND inv.status IN ('AVAILABLE','ALLOCATED')
GROUP BY inv.corporation_id, inv.site_id, inv.item_id, i.sku, inv.lot_number, inv.expiration_date;

CREATE VIEW v_labor_productivity AS
SELECT a.corporation_id, a.site_id, a.assigned_to AS user_id, u.display_name, a.assignment_type,
       date_trunc('day', t.completed_at) AS day,
       count(*) AS tasks, sum(t.qty) AS cases
FROM assignment_task t
JOIN assignment a ON a.assignment_id = t.assignment_id
JOIN app_user u ON u.user_id = a.assigned_to
WHERE t.status = 'COMPLETE'
GROUP BY a.corporation_id, a.site_id, a.assigned_to, u.display_name, a.assignment_type, date_trunc('day', t.completed_at);

-- ------------------------------------------------------------
-- 8. ROW LEVEL SECURITY
-- ------------------------------------------------------------
SELECT tenant_isolate(t) FROM unnest(ARRAY['receiving_manifest','inventory','assignment']) AS t;
