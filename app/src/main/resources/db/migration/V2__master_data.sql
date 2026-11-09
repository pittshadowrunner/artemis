-- ============================================================
-- Artemis WMS — V2 Master Data
-- Customers, items (+ tiered UOMs), locations, equipment, containers.
--
-- Location conventions baked in:
--   • One location type for racking: STANDARD. Pick-face is a ROLE
--     (replen_item_id set), not a type — customers pick in three
--     dimensions from anywhere.
--   • Names follow Zone-Aisle-Level-Column-Slot (C-B-2-04-A); the
--     pick sequence is DERIVED from the name by trigger. Docks, drops
--     and staging carry no sequence.
--   • Check digits are deterministic from the code, so re-labelling a
--     site never shuffles what operators have memorised.
-- ============================================================

CREATE TYPE temp_zone            AS ENUM ('DEEP_FROZEN','FROZEN','REFRIGERATED','CONTROLLED_AMBIENT','AMBIENT','HEATED');
CREATE TYPE date_label_type      AS ENUM ('USE_BY','SELL_BY','BEST_BY','PACK_DATE','NONE');
CREATE TYPE serial_capture_point AS ENUM ('NONE','RECEIVING','PICKING','SHIPPING');
CREATE TYPE rack_type            AS ENUM ('SELECTIVE','DRIVE_IN','PUSH_BACK','PALLET_FLOW','CARTON_FLOW','SHELVING','FLOOR_STACK','MEZZANINE');
CREATE TYPE location_type        AS ENUM ('STANDARD','DROP','CROSS_DOCK','RECEIVING_DOCK','SHIPPING_DOCK','STAGING');
CREATE TYPE equipment_type       AS ENUM ('CART','FORKLIFT','PALLET_JACK','PALLET_RIDER','REACH_TRUCK','ORDER_PICKER','WALKIE','TURRET');
CREATE TYPE container_type       AS ENUM ('TOTE','CARTON','PALLET','SHIPPING_CONTAINER');

-- Placard helpers shared by the UI: temp zone → tag / css class.
CREATE OR REPLACE FUNCTION tag_of(tz text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE tz WHEN 'FROZEN' THEN 'FRZ' WHEN 'DEEP_FROZEN' THEN 'FRZ'
                   WHEN 'REFRIGERATED' THEN 'CHL' WHEN 'HEATED' THEN 'HOT' ELSE 'DRY' END;
$$;
CREATE OR REPLACE FUNCTION css_of(tz text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE tz WHEN 'FROZEN' THEN 'frozen' WHEN 'DEEP_FROZEN' THEN 'frozen'
                   WHEN 'REFRIGERATED' THEN 'chill' ELSE '' END;
$$;

-- ------------------------------------------------------------
-- 1. CUSTOMERS
-- ------------------------------------------------------------
CREATE TABLE customer (
    customer_id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id        uuid NOT NULL,
    owner_org_id          uuid NOT NULL REFERENCES org_node(org_node_id),
    code                  text NOT NULL,
    name                  text NOT NULL,
    address_line1         text,
    address_line2         text,
    city                  text,
    state_province        text,
    postal_code           text,
    country               text,
    contact_email         citext,
    contact_phone         text,
    route_code            text,
    stop_sequence         int,
    delivery_window_start time,
    delivery_window_end   time,
    min_shelf_life_days   int,                        -- min remaining life the customer accepts
    requires_gs1_labels   boolean NOT NULL DEFAULT false,
    requires_asn          boolean NOT NULL DEFAULT false,
    pallet_build_pref     text,                       -- STORE_FRIENDLY | SINGLE_SKU | MIXED
    preferred_carrier     text,
    active                boolean NOT NULL DEFAULT true,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corporation_id, code)
);

-- ------------------------------------------------------------
-- 2. ITEMS — deep attribute coverage for food service, retail,
--    cold storage. base_item_id groups variants for ship-together.
-- ------------------------------------------------------------
CREATE TABLE item (
    item_id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id              uuid NOT NULL,
    sku                         citext NOT NULL,
    description                 text NOT NULL,
    base_item_id                uuid REFERENCES item(item_id),
    uom                         text NOT NULL DEFAULT 'EA',
    weight_kg                   numeric(12,3),
    length_cm                   numeric(10,2),
    width_cm                    numeric(10,2),
    height_cm                   numeric(10,2),
    serial_tracked              boolean NOT NULL DEFAULT false,
    lot_tracked                 boolean NOT NULL DEFAULT false,
    expiry_tracked              boolean NOT NULL DEFAULT false,
    shelf_life_days             int,
    -- cold chain
    temp_zone                   temp_zone NOT NULL DEFAULT 'AMBIENT',
    min_temp_c                  numeric(5,1),
    max_temp_c                  numeric(5,1),
    -- food service
    catch_weight                boolean NOT NULL DEFAULT false,
    nominal_weight_kg           numeric(12,3),
    date_label                  date_label_type NOT NULL DEFAULT 'NONE',
    min_shelf_life_receipt_days int,
    min_shelf_life_ship_days    int,
    allergens                   text[],
    certifications              text[],
    country_of_origin           text,
    -- GS1 / packaging
    gtin_each                   text,
    gtin_case                   text,
    inner_pack_qty              int,
    case_pack_qty               int,
    pallet_ti                   int,
    pallet_hi                   int,
    -- handling & compliance
    hazmat_class                text,
    un_number                   text,
    fragile                     boolean NOT NULL DEFAULT false,
    this_side_up                boolean NOT NULL DEFAULT false,
    stack_limit                 int,
    conveyable                  boolean NOT NULL DEFAULT true,
    crushable                   boolean NOT NULL DEFAULT false,
    serial_capture_at           serial_capture_point NOT NULL DEFAULT 'NONE',
    -- retail
    retail_price                numeric(12,2),
    unit_cost                   numeric(12,4),
    style_code                  text,
    color_code                  text,
    size_code                   text,
    season_code                 text,
    -- slotting intent (calculated velocity lives in a view)
    velocity_class              char(1),
    active                      boolean NOT NULL DEFAULT true,
    created_at                  timestamptz NOT NULL DEFAULT now(),
    updated_at                  timestamptz NOT NULL DEFAULT now(),
    UNIQUE (corporation_id, sku),
    CONSTRAINT chk_velocity CHECK (velocity_class IS NULL OR velocity_class IN ('A','B','C')),
    CONSTRAINT chk_expiry_basis CHECK (NOT expiry_tracked OR shelf_life_days IS NOT NULL OR date_label <> 'NONE'),
    CONSTRAINT chk_catch_weight CHECK (NOT catch_weight OR nominal_weight_kg IS NOT NULL)
);
CREATE INDEX idx_item_base ON item(base_item_id);

-- Tiered UOM hierarchy: 1 <code> = qty × <of_code>; EA is the implicit base.
CREATE TABLE item_uom (
    item_uom_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id     uuid NOT NULL REFERENCES item(item_id) ON DELETE CASCADE,
    code        text NOT NULL,
    qty         numeric(12,3) NOT NULL CHECK (qty > 0),
    of_code     text NOT NULL DEFAULT 'EA',
    UNIQUE (item_id, code),
    CHECK (code <> of_code)
);

-- CS and PL tiers derive from the flat pack data automatically.
CREATE OR REPLACE FUNCTION fn_item_auto_uoms() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.case_pack_qty IS NOT NULL AND NEW.case_pack_qty > 0 THEN
        INSERT INTO item_uom (item_id, code, qty, of_code) VALUES (NEW.item_id, 'CS', NEW.case_pack_qty, 'EA')
        ON CONFLICT (item_id, code) DO UPDATE SET qty = EXCLUDED.qty;
        IF NEW.pallet_ti IS NOT NULL AND NEW.pallet_hi IS NOT NULL THEN
            INSERT INTO item_uom (item_id, code, qty, of_code) VALUES (NEW.item_id, 'PL', NEW.pallet_ti * NEW.pallet_hi, 'CS')
            ON CONFLICT (item_id, code) DO UPDATE SET qty = EXCLUDED.qty;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_item_auto_uoms AFTER INSERT OR UPDATE OF case_pack_qty, pallet_ti, pallet_hi ON item
    FOR EACH ROW EXECUTE FUNCTION fn_item_auto_uoms();

-- ------------------------------------------------------------
-- 3. LOCATIONS
-- ------------------------------------------------------------
CREATE TABLE location (
    location_id         uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id      uuid NOT NULL,
    site_id             uuid NOT NULL REFERENCES org_node(org_node_id),
    area_id             uuid REFERENCES org_node(org_node_id),
    code                text NOT NULL,
    loc_type            location_type NOT NULL DEFAULT 'STANDARD',
    zone                text,                         -- name components (parsed from code when absent)
    aisle               text,
    level               text,
    col                 text,
    slot                text,
    pick_sequence       int,                          -- derived; NULL for non-STANDARD
    check_digits        char(2) NOT NULL DEFAULT '',  -- derived
    temp_zone           temp_zone NOT NULL DEFAULT 'AMBIENT',
    rack_type           rack_type,
    width_cm            numeric(8,1),
    depth_cm            numeric(8,1),
    height_cm           numeric(8,1),
    max_weight_kg       numeric(12,3),
    velocity_zone       char(1),
    golden_zone         boolean NOT NULL DEFAULT false,
    equipment_class     text,
    humidity_controlled boolean NOT NULL DEFAULT false,
    hazmat_approved     boolean NOT NULL DEFAULT false,
    -- pick-face role: set replen_item_id and the slot belongs to replenishment
    replen_item_id      uuid REFERENCES item(item_id),
    replen_min_qty      numeric(14,3),
    replen_max_qty      numeric(14,3),
    replen_trigger_qty  numeric(14,3),
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code),
    CONSTRAINT chk_pickface CHECK (replen_item_id IS NULL OR loc_type = 'STANDARD')
);
CREATE INDEX idx_loc_site_type ON location(site_id, loc_type) WHERE active;
CREATE INDEX idx_loc_pickseq   ON location(site_id, pick_sequence);
CREATE INDEX idx_loc_replen    ON location(site_id, replen_item_id) WHERE replen_item_id IS NOT NULL;

-- Rank a name token: numeric → its value; alpha → base-27 position.
CREATE OR REPLACE FUNCTION fn_rank_token(tok text) RETURNS int
LANGUAGE plpgsql IMMUTABLE AS $fn$
DECLARE r int := 0; ch text;
BEGIN
    IF tok IS NULL THEN RETURN 0; END IF;
    IF tok ~ '^[0-9]+$' THEN RETURN tok::int; END IF;
    FOREACH ch IN ARRAY string_to_array(upper(tok), NULL) LOOP
        r := r * 27 + (ascii(ch) - 64);
    END LOOP;
    RETURN r;
END $fn$;

-- Derive components, pick sequence (aisle → column → slot → level) and check digits.
CREATE OR REPLACE FUNCTION fn_location_derive() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parts text[];
BEGIN
    NEW.code := upper(trim(NEW.code));
    NEW.check_digits := lpad((abs(hashtext(NEW.code)) % 100)::text, 2, '0');
    IF NEW.loc_type <> 'STANDARD' THEN
        NEW.pick_sequence := NULL;
        RETURN NEW;
    END IF;
    IF NEW.aisle IS NULL AND NEW.code ~ '^[A-Z0-9]+-[A-Z0-9]+-[0-9]+-[0-9]+-[A-Z0-9]+$' THEN
        parts := string_to_array(NEW.code, '-');
        NEW.zone := parts[1]; NEW.aisle := parts[2]; NEW.level := parts[3];
        NEW.col := parts[4];  NEW.slot := parts[5];
    END IF;
    IF NEW.aisle IS NOT NULL AND NEW.col IS NOT NULL AND NEW.slot IS NOT NULL THEN
        NEW.pick_sequence := fn_rank_token(NEW.aisle) * 1000000
                           + fn_rank_token(NEW.col)   * 1000
                           + fn_rank_token(NEW.slot)  * 10
                           + fn_rank_token(NEW.level);
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_location_derive BEFORE INSERT OR UPDATE OF code, aisle, level, col, slot, loc_type ON location
    FOR EACH ROW EXECUTE FUNCTION fn_location_derive();

-- Guardrail used by putaway/allocation: item temp zone must match the slot.
CREATE OR REPLACE FUNCTION temp_compatible(p_item uuid, p_location uuid)
RETURNS boolean LANGUAGE sql STABLE AS $$
    SELECT i.temp_zone = l.temp_zone FROM item i, location l
    WHERE i.item_id = p_item AND l.location_id = p_location;
$$;

-- Final rotation for an item at a node: explicit policy, else industry
-- default (FEFO when expiry-tracked, else FIFO by arrival).
CREATE OR REPLACE FUNCTION effective_rotation(p_item uuid, p_node uuid)
RETURNS rotation_strategy LANGUAGE sql STABLE AS $$
    SELECT COALESCE((effective_policy(p_node)).rotation,
        CASE WHEN (SELECT expiry_tracked FROM item WHERE item_id = p_item)
             THEN 'FEFO'::rotation_strategy ELSE 'FIFO'::rotation_strategy END);
$$;

-- ------------------------------------------------------------
-- 4. EQUIPMENT — carts, powered units; positions are individually
--    verifiable; custody via checkout (who / out / in).
-- ------------------------------------------------------------
CREATE TABLE equipment (
    equipment_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id      uuid NOT NULL,
    site_id             uuid NOT NULL REFERENCES org_node(org_node_id),
    code                text NOT NULL,
    equipment_type      equipment_type NOT NULL,
    lpn                 text,
    check_digits        char(2),
    container_positions int,                          -- carts: drives batch size
    capabilities        text[] NOT NULL DEFAULT '{}', -- LIFT_HIGH, COLD_RATED, NARROW_AISLE ...
    max_weight_kg       numeric(12,3),
    voice_enabled       boolean NOT NULL DEFAULT true,
    active              boolean NOT NULL DEFAULT true,
    created_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, code)
);

CREATE TABLE equipment_position (
    equipment_id uuid NOT NULL REFERENCES equipment(equipment_id) ON DELETE CASCADE,
    position_no  int NOT NULL,
    check_digits char(2) NOT NULL,
    PRIMARY KEY (equipment_id, position_no)
);

CREATE TABLE equipment_checkout (
    checkout_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    equipment_id   uuid NOT NULL REFERENCES equipment(equipment_id) ON DELETE CASCADE,
    user_id        uuid NOT NULL REFERENCES app_user(user_id),
    checked_out_at timestamptz NOT NULL DEFAULT now(),
    checked_in_at  timestamptz
);
CREATE INDEX idx_checkout_equipment ON equipment_checkout(equipment_id, checked_out_at DESC);
CREATE UNIQUE INDEX uq_checkout_open ON equipment_checkout(equipment_id) WHERE checked_in_at IS NULL;

-- Cart positions auto-populate with stable digits.
CREATE OR REPLACE FUNCTION fn_equipment_positions() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.check_digits IS NULL THEN
        UPDATE equipment SET check_digits = lpad((abs(hashtext(NEW.code)) % 100)::text, 2, '0')
        WHERE equipment_id = NEW.equipment_id;
    END IF;
    IF NEW.container_positions IS NOT NULL THEN
        INSERT INTO equipment_position (equipment_id, position_no, check_digits)
        SELECT NEW.equipment_id, p, lpad((abs(hashtext(NEW.code || '#' || p)) % 100)::text, 2, '0')
        FROM generate_series(1, NEW.container_positions) p
        ON CONFLICT DO NOTHING;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_equipment_positions AFTER INSERT ON equipment
    FOR EACH ROW EXECUTE FUNCTION fn_equipment_positions();

-- ------------------------------------------------------------
-- 5. CONTAINERS — totes, cartons, shipping containers
-- ------------------------------------------------------------
CREATE TABLE container (
    container_id   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid NOT NULL,
    site_id        uuid NOT NULL REFERENCES org_node(org_node_id),
    barcode        text NOT NULL,
    container_type container_type NOT NULL,
    check_digits   char(2),
    reusable       boolean NOT NULL DEFAULT true,
    tare_weight_kg numeric(10,3),
    max_weight_kg  numeric(12,3),
    active         boolean NOT NULL DEFAULT true,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (site_id, barcode)
);

-- ------------------------------------------------------------
-- 6. ROW LEVEL SECURITY
-- ------------------------------------------------------------
SELECT tenant_isolate(t) FROM unnest(ARRAY['customer','item','location','equipment','container']) AS t;
