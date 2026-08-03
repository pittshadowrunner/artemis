-- ============================================================
-- WMS Core Schema  v0.1  (PostgreSQL 16+)
-- Multi-tenant, org-hierarchy scoped, RBAC, beta workstreams:
-- Receiving, Put Away, Replenishment, Selection, Outbound
-- ============================================================

CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";    -- case-insensitive emails/skus

-- ------------------------------------------------------------
-- 1. ORGANIZATIONAL HIERARCHY
--    Corporation -> District Region -> Site Location -> Area
--    Every tenant-scoped row carries corporation_id for hard
--    silo enforcement (and Row Level Security below).
-- ------------------------------------------------------------

CREATE TYPE org_level AS ENUM ('CORPORATION','DISTRICT_REGION','SITE_LOCATION','AREA');

CREATE TABLE org_node (
    org_node_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,                    -- self-reference for CORPORATION rows
    parent_id       uuid REFERENCES org_node(org_node_id),
    level           org_level NOT NULL,
    code            text NOT NULL,                    -- short code, unique within parent
    name            text NOT NULL,
    -- address attributes (apply at any level)
    address_line1   text,
    address_line2   text,
    city            text,
    state_province  text,
    postal_code     text,
    country         text,
    timezone        text DEFAULT 'America/New_York',
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (parent_id, code),
    -- Corporations are roots; everything else must have a parent
    CONSTRAINT chk_root CHECK (
        (level = 'CORPORATION' AND parent_id IS NULL)
        OR (level <> 'CORPORATION' AND parent_id IS NOT NULL))
);
CREATE INDEX idx_org_node_corp   ON org_node(corporation_id);
CREATE INDEX idx_org_node_parent ON org_node(parent_id);

-- ------------------------------------------------------------
-- 2. USERS + RBAC
--    Grants attach a role to a user AT an org node.
--    Effective access at any node = highest role granted at
--    that node or any ancestor ("highest level wins").
-- ------------------------------------------------------------

CREATE TABLE app_user (
    user_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email           citext NOT NULL UNIQUE,
    password_hash   text NOT NULL,                    -- bcrypt/argon2
    display_name    text NOT NULL,
    email_verified  boolean NOT NULL DEFAULT false,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE email_verification_token (
    token_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    token_hash  text NOT NULL,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX idx_evt_user ON email_verification_token(user_id);

-- Roles are data, not enums, so the future security portal can
-- add custom roles without a migration. rank drives highest-wins.
CREATE TABLE role (
    role_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid,                              -- NULL = system role
    code        text NOT NULL,
    name        text NOT NULL,
    rank        int  NOT NULL,                        -- higher = more access
    UNIQUE (corporation_id, code)
);
INSERT INTO role (code, name, rank) VALUES
    ('ADMIN','Administrator',100),
    ('READ_ONLY','Read Only',10);

-- Future custom roles: capability flags per role
CREATE TABLE role_capability (
    role_id     uuid NOT NULL REFERENCES role(role_id) ON DELETE CASCADE,
    capability  text NOT NULL,                        -- e.g. 'RECEIVING_WRITE'
    PRIMARY KEY (role_id, capability)
);

CREATE TABLE user_org_grant (
    grant_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     uuid NOT NULL REFERENCES app_user(user_id) ON DELETE CASCADE,
    org_node_id uuid NOT NULL REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    role_id     uuid NOT NULL REFERENCES role(role_id),
    granted_by  uuid REFERENCES app_user(user_id),
    created_at  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (user_id, org_node_id, role_id)
);
CREATE INDEX idx_grant_user ON user_org_grant(user_id);

-- Effective role resolution: walk ancestors, take max(rank).
CREATE OR REPLACE FUNCTION effective_role(p_user uuid, p_node uuid)
RETURNS TABLE (role_id uuid, code text, rank int)
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestry AS (
        SELECT org_node_id, parent_id FROM org_node WHERE org_node_id = p_node
        UNION ALL
        SELECT o.org_node_id, o.parent_id
        FROM org_node o JOIN ancestry a ON o.org_node_id = a.parent_id
    )
    SELECT r.role_id, r.code, r.rank
    FROM user_org_grant g
    JOIN role r ON r.role_id = g.role_id
    WHERE g.user_id = p_user
      AND g.org_node_id IN (SELECT org_node_id FROM ancestry)
    ORDER BY r.rank DESC
    LIMIT 1;
$$;

-- ------------------------------------------------------------
-- 3. CUSTOMERS
-- ------------------------------------------------------------

CREATE TABLE customer (
    customer_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    owner_org_id    uuid NOT NULL REFERENCES org_node(org_node_id),
    code            text NOT NULL,
    name            text NOT NULL,
    address_line1   text,
    address_line2   text,
    city            text,
    state_province  text,
    postal_code     text,
    country         text,
    contact_email   citext,
    contact_phone   text,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corporation_id, code)
);

-- ------------------------------------------------------------
-- 4. LOCATIONS
--    Site-scoped, typed, with a pick/travel sequence for
--    proximity-based workflow optimization.
-- ------------------------------------------------------------

CREATE TYPE location_type AS ENUM ('STORAGE','PICK_FACE','DROP','CROSS_DOCK','RECEIVING_DOCK','SHIPPING_DOCK','STAGING');

CREATE TABLE location (
    location_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),  -- SITE_LOCATION node
    area_id         uuid REFERENCES org_node(org_node_id),           -- AREA node
    code            text NOT NULL,                    -- e.g. A-01-03-B
    loc_type        location_type NOT NULL,
    aisle           text,
    bay             text,
    tier            text,
    slot            text,
    pick_sequence   int,                              -- travel-path order for proximity optimization
    x_coord         numeric(10,2),                    -- optional geometry for distance calc
    y_coord         numeric(10,2),
    max_weight_kg   numeric(12,3),
    max_volume_m3   numeric(12,4),
    single_item     boolean NOT NULL DEFAULT false,   -- pick faces usually true
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);
CREATE INDEX idx_loc_site_type ON location(site_id, loc_type) WHERE active;
CREATE INDEX idx_loc_pickseq   ON location(site_id, pick_sequence);

-- ------------------------------------------------------------
-- 5. ITEMS
--    base_item_id groups variants for ship-together rules.
-- ------------------------------------------------------------

CREATE TABLE item (
    item_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    sku             citext NOT NULL,
    description     text NOT NULL,
    base_item_id    uuid REFERENCES item(item_id),    -- NULL = is itself a base item
    uom             text NOT NULL DEFAULT 'EA',
    weight_kg       numeric(12,3),
    length_cm       numeric(10,2),
    width_cm        numeric(10,2),
    height_cm       numeric(10,2),
    serial_tracked  boolean NOT NULL DEFAULT false,
    lot_tracked     boolean NOT NULL DEFAULT false,
    expiry_tracked  boolean NOT NULL DEFAULT false,
    shelf_life_days int,
    active          boolean NOT NULL DEFAULT true,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corporation_id, sku)
);
CREATE INDEX idx_item_base ON item(base_item_id);

-- ------------------------------------------------------------
-- 6. INVENTORY (LPN-based)
--    Lot, expiration, arrival date captured at receipt.
-- ------------------------------------------------------------

CREATE TYPE inventory_status AS ENUM ('AVAILABLE','ALLOCATED','PICKED','HOLD','SHIPPED','DAMAGED');

CREATE TABLE inventory (
    inventory_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    lpn             text NOT NULL,                    -- license plate
    item_id         uuid NOT NULL REFERENCES item(item_id),
    location_id     uuid REFERENCES location(location_id),
    qty             numeric(14,3) NOT NULL CHECK (qty >= 0),
    status          inventory_status NOT NULL DEFAULT 'AVAILABLE',
    lot_number      text,
    expiration_date date,
    arrival_date    date,
    received_from_manifest uuid,                      -- FK added after receiving tables
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, lpn)
);
CREATE INDEX idx_inv_item_loc ON inventory(site_id, item_id, status);
CREATE INDEX idx_inv_location ON inventory(location_id);

CREATE TABLE inventory_serial (
    inventory_id  uuid NOT NULL REFERENCES inventory(inventory_id) ON DELETE CASCADE,
    serial_number text NOT NULL,
    PRIMARY KEY (inventory_id, serial_number)
);

-- Full movement audit trail
CREATE TABLE inventory_movement (
    movement_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    inventory_id    uuid NOT NULL REFERENCES inventory(inventory_id),
    from_location   uuid REFERENCES location(location_id),
    to_location     uuid REFERENCES location(location_id),
    qty             numeric(14,3) NOT NULL,
    movement_type   text NOT NULL,   -- RECEIPT, PUTAWAY, REPLENISH, PICK, DROP, SHIP, ADJUST
    performed_by    uuid REFERENCES app_user(user_id),
    assignment_id   uuid,            -- FK added below
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_move_inv ON inventory_movement(inventory_id);

-- ------------------------------------------------------------
-- 7. RECEIVING
-- ------------------------------------------------------------

CREATE TYPE manifest_status AS ENUM ('EXPECTED','ARRIVED','RECEIVING','RECEIVED','CLOSED','CANCELLED');

CREATE TABLE receiving_manifest (
    manifest_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    manifest_number text NOT NULL,
    carrier         text,
    trailer_number  text,
    expected_date   date,
    status          manifest_status NOT NULL DEFAULT 'EXPECTED',
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, manifest_number)
);

CREATE TABLE receiving_manifest_line (
    manifest_line_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    manifest_id     uuid NOT NULL REFERENCES receiving_manifest(manifest_id) ON DELETE CASCADE,
    line_number     int NOT NULL,
    item_id         uuid NOT NULL REFERENCES item(item_id),
    expected_qty    numeric(14,3) NOT NULL,
    received_qty    numeric(14,3) NOT NULL DEFAULT 0,
    UNIQUE (manifest_id, line_number)
);

ALTER TABLE inventory
    ADD CONSTRAINT fk_inv_manifest FOREIGN KEY (received_from_manifest)
    REFERENCES receiving_manifest(manifest_id);

-- ------------------------------------------------------------
-- 8. ORDERS
-- ------------------------------------------------------------

CREATE TYPE order_status AS ENUM ('NEW','ALLOCATED','RELEASED','PICKING','PICKED','DROPPED','SHIPPED','CANCELLED');

CREATE TABLE customer_order (
    order_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    customer_id     uuid NOT NULL REFERENCES customer(customer_id),
    order_number    text NOT NULL,
    status          order_status NOT NULL DEFAULT 'NEW',
    requested_ship_date date,
    drop_location_id uuid REFERENCES location(location_id),  -- target drop location
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, order_number)
);

CREATE TABLE customer_order_line (
    order_line_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id        uuid NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    line_number     int NOT NULL,
    item_id         uuid NOT NULL REFERENCES item(item_id),
    ordered_qty     numeric(14,3) NOT NULL,
    allocated_qty   numeric(14,3) NOT NULL DEFAULT 0,
    picked_qty      numeric(14,3) NOT NULL DEFAULT 0,
    UNIQUE (order_id, line_number)
);

-- Hard allocation of inventory to an order line
CREATE TABLE allocation (
    allocation_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_line_id   uuid NOT NULL REFERENCES customer_order_line(order_line_id) ON DELETE CASCADE,
    inventory_id    uuid NOT NULL REFERENCES inventory(inventory_id),
    qty             numeric(14,3) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_alloc_inv ON allocation(inventory_id);

-- ------------------------------------------------------------
-- 9. ASSIGNMENTS (unit of work across all workstreams)
--    assignment_type is an attribute, per spec.
-- ------------------------------------------------------------

CREATE TYPE assignment_type AS ENUM ('RECEIVING','PUTAWAY','REPLENISHMENT','SELECTION','LOADING','SHIPPING','CYCLE_COUNT');
CREATE TYPE assignment_status AS ENUM ('OPEN','ASSIGNED','IN_PROGRESS','COMPLETE','CANCELLED');

CREATE TABLE assignment (
    assignment_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    assignment_type assignment_type NOT NULL,
    status          assignment_status NOT NULL DEFAULT 'OPEN',
    assigned_to     uuid REFERENCES app_user(user_id),
    priority        int NOT NULL DEFAULT 50,
    -- optional anchors depending on type
    manifest_id     uuid REFERENCES receiving_manifest(manifest_id),
    order_id        uuid REFERENCES customer_order(order_id),
    started_at      timestamptz,
    completed_at    timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_assign_site ON assignment(site_id, status, assignment_type);

ALTER TABLE inventory_movement
    ADD CONSTRAINT fk_move_assignment FOREIGN KEY (assignment_id)
    REFERENCES assignment(assignment_id);

-- Individual tasks within an assignment (a pick, a putaway move...)
-- sequenced by location.pick_sequence for proximity optimization.
CREATE TABLE assignment_task (
    task_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    assignment_id   uuid NOT NULL REFERENCES assignment(assignment_id) ON DELETE CASCADE,
    seq             int NOT NULL,
    inventory_id    uuid REFERENCES inventory(inventory_id),
    item_id         uuid REFERENCES item(item_id),
    from_location   uuid REFERENCES location(location_id),
    to_location     uuid REFERENCES location(location_id),
    qty             numeric(14,3),
    status          assignment_status NOT NULL DEFAULT 'OPEN',
    -- voice fields (VoiceLink-friendly)
    check_digits    text,                             -- location verification digits
    spoken_prompt   text,
    completed_at    timestamptz,
    UNIQUE (assignment_id, seq)
);

-- ------------------------------------------------------------
-- 10. OUTBOUND / SHIPPING
-- ------------------------------------------------------------

CREATE TYPE shipment_status AS ENUM ('OPEN','LOADING','SHIPPED','CANCELLED');

CREATE TABLE shipment (
    shipment_id     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    shipment_number text NOT NULL,
    customer_id     uuid NOT NULL REFERENCES customer(customer_id),
    carrier         text,
    trailer_number  text,
    status          shipment_status NOT NULL DEFAULT 'OPEN',
    shipped_at      timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, shipment_number)
);

CREATE TABLE shipment_order (
    shipment_id uuid NOT NULL REFERENCES shipment(shipment_id) ON DELETE CASCADE,
    order_id    uuid NOT NULL REFERENCES customer_order(order_id),
    PRIMARY KEY (shipment_id, order_id)
);

-- Packing list generated from picked/dropped inventory
CREATE TABLE packing_list (
    packing_list_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    shipment_id     uuid NOT NULL REFERENCES shipment(shipment_id) ON DELETE CASCADE,
    generated_at    timestamptz NOT NULL DEFAULT now(),
    document_number text NOT NULL
);

CREATE TABLE packing_list_line (
    packing_list_id uuid NOT NULL REFERENCES packing_list(packing_list_id) ON DELETE CASCADE,
    line_number     int NOT NULL,
    order_id        uuid NOT NULL REFERENCES customer_order(order_id),
    item_id         uuid NOT NULL REFERENCES item(item_id),
    qty             numeric(14,3) NOT NULL,
    lot_number      text,
    serials         text[],
    PRIMARY KEY (packing_list_id, line_number)
);

-- ------------------------------------------------------------
-- 11. ROW LEVEL SECURITY (tenant hard-silo)
--    App sets: SET app.current_corp = '<uuid>';
-- ------------------------------------------------------------

DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'org_node','customer','location','item','inventory',
    'receiving_manifest','customer_order','assignment','shipment'
  ] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_iso_%1$s ON %1$s
       USING (corporation_id = current_setting(''app.current_corp'', true)::uuid)', t);
  END LOOP;
END $$;
