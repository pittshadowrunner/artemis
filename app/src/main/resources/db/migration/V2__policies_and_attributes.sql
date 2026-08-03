-- ============================================================
-- WMS Schema V2 — Inventory Control Policies + Industry Attributes
-- Policy cascade: set at SITE_LOCATION or AREA; nearest (smallest)
-- org unit wins, field by field. FEFO/FIFO fallback if unset.
-- ============================================================

-- ------------------------------------------------------------
-- 1. INVENTORY CONTROL POLICY (org-node scoped)
-- ------------------------------------------------------------

CREATE TYPE rotation_strategy AS ENUM ('FEFO','FIFO','LIFO','NONE');

CREATE TABLE inventory_policy (
    policy_id       uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    org_node_id     uuid NOT NULL UNIQUE REFERENCES org_node(org_node_id) ON DELETE CASCADE,
    -- All fields nullable: NULL = inherit from the next level up
    rotation                    rotation_strategy,
    min_shelf_life_receipt_days int,      -- reject receipts under this remaining life
    min_shelf_life_ship_days    int,      -- don't allocate under this remaining life
    expiry_alert_days           int,      -- surface expiring lots this many days out
    allow_lot_mixing            boolean,  -- multiple lots in one location
    allow_item_mixing           boolean,  -- multiple items in one location
    catch_weight_tolerance_pct  numeric(5,2),
    require_lot_capture         boolean,
    require_expiry_capture      boolean,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE inventory_policy ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_iso_inventory_policy ON inventory_policy
    USING (corporation_id = current_setting('app.current_corp', true)::uuid);

-- Field-level "smallest unit wins": walk from the node up its
-- ancestry, COALESCE each field nearest-first. Area overrides
-- Site; Site fills anything the Area left NULL.
CREATE OR REPLACE FUNCTION effective_policy(p_node uuid)
RETURNS inventory_policy
LANGUAGE sql STABLE AS $$
    WITH RECURSIVE ancestry AS (
        SELECT org_node_id, parent_id, 0 AS depth
        FROM org_node WHERE org_node_id = p_node
        UNION ALL
        SELECT o.org_node_id, o.parent_id, a.depth + 1
        FROM org_node o JOIN ancestry a ON o.org_node_id = a.parent_id
    ),
    chain AS (
        SELECT p.*, a.depth
        FROM ancestry a
        JOIN inventory_policy p ON p.org_node_id = a.org_node_id
        ORDER BY a.depth
    )
    SELECT
        gen_random_uuid(),
        min(corporation_id::text)::uuid,
        p_node,
        (array_remove(array_agg(rotation ORDER BY depth), NULL))[1],
        (array_remove(array_agg(min_shelf_life_receipt_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(min_shelf_life_ship_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(expiry_alert_days ORDER BY depth), NULL))[1],
        (array_remove(array_agg(allow_lot_mixing ORDER BY depth), NULL))[1],
        (array_remove(array_agg(allow_item_mixing ORDER BY depth), NULL))[1],
        (array_remove(array_agg(catch_weight_tolerance_pct ORDER BY depth), NULL))[1],
        (array_remove(array_agg(require_lot_capture ORDER BY depth), NULL))[1],
        (array_remove(array_agg(require_expiry_capture ORDER BY depth), NULL))[1],
        now(), now()
    FROM chain;
$$;

-- Final rotation for a specific item at a node:
-- explicit policy wins; otherwise industry default
-- (FEFO if expiry-tracked, else FIFO by arrival date).
CREATE OR REPLACE FUNCTION effective_rotation(p_item uuid, p_node uuid)
RETURNS rotation_strategy
LANGUAGE sql STABLE AS $$
    SELECT COALESCE(
        (SELECT (effective_policy(p_node)).rotation),
        CASE WHEN (SELECT expiry_tracked FROM item WHERE item_id = p_item)
             THEN 'FEFO'::rotation_strategy
             ELSE 'FIFO'::rotation_strategy END);
$$;

-- ------------------------------------------------------------
-- 2. ITEM ATTRIBUTES — food service / retail / cold storage
-- ------------------------------------------------------------

CREATE TYPE temp_zone AS ENUM ('DEEP_FROZEN','FROZEN','REFRIGERATED','CONTROLLED_AMBIENT','AMBIENT','HEATED');
CREATE TYPE date_label_type AS ENUM ('USE_BY','SELL_BY','BEST_BY','PACK_DATE','NONE');
CREATE TYPE serial_capture_point AS ENUM ('NONE','RECEIVING','PICKING','SHIPPING');

ALTER TABLE item
    -- cold chain
    ADD COLUMN temp_zone            temp_zone NOT NULL DEFAULT 'AMBIENT',
    ADD COLUMN min_temp_c           numeric(5,1),
    ADD COLUMN max_temp_c           numeric(5,1),
    -- food service
    ADD COLUMN catch_weight         boolean NOT NULL DEFAULT false,  -- variable-weight (proteins, cheese)
    ADD COLUMN nominal_weight_kg    numeric(12,3),                   -- expected wt for catch-weight items
    ADD COLUMN date_label           date_label_type NOT NULL DEFAULT 'NONE',
    ADD COLUMN min_shelf_life_receipt_days int,                      -- item-level override of policy
    ADD COLUMN min_shelf_life_ship_days    int,
    ADD COLUMN allergens            text[],                          -- ['MILK','PEANUT','GLUTEN',...]
    ADD COLUMN certifications       text[],                          -- ['ORGANIC','KOSHER','HALAL','NON_GMO']
    ADD COLUMN country_of_origin    text,
    -- GS1 / packaging hierarchy
    ADD COLUMN gtin_each            text,
    ADD COLUMN gtin_case            text,
    ADD COLUMN inner_pack_qty       int,
    ADD COLUMN case_pack_qty        int,
    ADD COLUMN pallet_ti            int,                             -- cases per layer
    ADD COLUMN pallet_hi            int,                             -- layers per pallet
    -- handling & compliance
    ADD COLUMN hazmat_class         text,
    ADD COLUMN un_number            text,
    ADD COLUMN fragile              boolean NOT NULL DEFAULT false,
    ADD COLUMN this_side_up         boolean NOT NULL DEFAULT false,
    ADD COLUMN stack_limit          int,                             -- max cases stacked
    ADD COLUMN conveyable           boolean NOT NULL DEFAULT true,
    ADD COLUMN crushable            boolean NOT NULL DEFAULT false,
    ADD COLUMN serial_capture_at    serial_capture_point NOT NULL DEFAULT 'NONE',
    -- retail
    ADD COLUMN retail_price         numeric(12,2),
    ADD COLUMN unit_cost            numeric(12,4),
    ADD COLUMN style_code           text,
    ADD COLUMN color_code           text,
    ADD COLUMN size_code            text,
    ADD COLUMN season_code          text,
    -- slotting
    ADD COLUMN velocity_class       char(1);                         -- A/B/C

-- ------------------------------------------------------------
-- 3. LOCATION ATTRIBUTES
-- ------------------------------------------------------------

CREATE TYPE rack_type AS ENUM ('SELECTIVE','DRIVE_IN','PUSH_BACK','PALLET_FLOW','CARTON_FLOW','SHELVING','FLOOR_STACK','MEZZANINE');

ALTER TABLE location
    ADD COLUMN temp_zone            temp_zone NOT NULL DEFAULT 'AMBIENT',
    ADD COLUMN humidity_controlled  boolean NOT NULL DEFAULT false,
    ADD COLUMN hazmat_approved      boolean NOT NULL DEFAULT false,
    ADD COLUMN rack_type            rack_type,
    ADD COLUMN velocity_zone        char(1),                         -- matches item velocity for slotting
    ADD COLUMN golden_zone          boolean NOT NULL DEFAULT false,  -- waist-to-shoulder ergonomic height
    ADD COLUMN equipment_class      text,                            -- 'REACH','ORDER_PICKER','WALKIE', etc.
    -- pick-face replenishment triggers (drives Replenishment workstream)
    ADD COLUMN replen_item_id       uuid REFERENCES item(item_id),   -- dedicated pick-face item
    ADD COLUMN replen_min_qty       numeric(14,3),
    ADD COLUMN replen_max_qty       numeric(14,3),
    ADD COLUMN replen_trigger_qty   numeric(14,3);

CREATE INDEX idx_loc_replen ON location(site_id, replen_item_id)
    WHERE replen_item_id IS NOT NULL;

-- Putaway/allocation guardrail: item temp zone must match location
-- (enforced in service layer with this helper)
CREATE OR REPLACE FUNCTION temp_compatible(p_item uuid, p_location uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT i.temp_zone = l.temp_zone
    FROM item i, location l
    WHERE i.item_id = p_item AND l.location_id = p_location;
$$;

-- ------------------------------------------------------------
-- 4. CUSTOMER ATTRIBUTES — distribution & retail compliance
-- ------------------------------------------------------------

ALTER TABLE customer
    ADD COLUMN route_code               text,       -- delivery route (food distribution)
    ADD COLUMN stop_sequence            int,        -- stop order on route → drives load sequence
    ADD COLUMN delivery_window_start    time,
    ADD COLUMN delivery_window_end      time,
    ADD COLUMN min_shelf_life_days      int,        -- min remaining life customer will accept
    ADD COLUMN requires_gs1_labels      boolean NOT NULL DEFAULT false,
    ADD COLUMN requires_asn             boolean NOT NULL DEFAULT false,  -- EDI 856 advance ship notice
    ADD COLUMN pallet_build_pref        text,       -- 'STORE_FRIENDLY','SINGLE_SKU','MIXED'
    ADD COLUMN preferred_carrier        text;

-- ------------------------------------------------------------
-- 5. CATCH WEIGHT CAPTURE on inventory + packing list
-- ------------------------------------------------------------

ALTER TABLE inventory
    ADD COLUMN actual_weight_kg numeric(12,3);      -- captured at receiving for catch-weight

ALTER TABLE packing_list_line
    ADD COLUMN actual_weight_kg numeric(12,3),
    ADD COLUMN expiration_date  date;
