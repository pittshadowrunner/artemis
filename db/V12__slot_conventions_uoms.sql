-- ============================================================
-- WMS Schema V12 — Slot conventions + tiered UOMs
-- 1. One item per slot: enforced at the database for STORAGE and
--    PICK_FACE. Docks, drops, staging, cross-dock are exempt —
--    freight legitimately co-mingles in motion. Even sites that
--    co-mingle in practice get the convention here.
-- 2. Item temp zone is authoritative: milk is CHL wherever it
--    sits; frozen chicken stays FRZ in storage AND pick face.
-- 3. AMB standardizes to DRY on placards.
-- 4. item_uom: tiered unit hierarchy (1 CS = 4 EA, 1 PL = 40 CS).
-- ============================================================

-- 3. placard tag: DRY replaces AMB
CREATE OR REPLACE FUNCTION tag_of(tz text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE tz
        WHEN 'FROZEN' THEN 'FRZ' WHEN 'DEEP_FROZEN' THEN 'FRZ'
        WHEN 'REFRIGERATED' THEN 'CHL' WHEN 'HEATED' THEN 'HOT'
        ELSE 'DRY' END;
$$;

-- 1 + 2. slot conventions trigger
CREATE OR REPLACE FUNCTION fn_slot_conventions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    loc record;
    item_zone temp_zone;
    other_sku text;
BEGIN
    IF NEW.location_id IS NULL THEN RETURN NEW; END IF;
    SELECT loc_type, temp_zone, code INTO loc FROM location WHERE location_id = NEW.location_id;
    IF loc.loc_type NOT IN ('STORAGE', 'PICK_FACE') THEN RETURN NEW; END IF;

    SELECT temp_zone INTO item_zone FROM item WHERE item_id = NEW.item_id;
    IF item_zone IS DISTINCT FROM loc.temp_zone THEN
        RAISE EXCEPTION 'Temp zone convention: item requires %, slot % is %.',
            item_zone, loc.code, loc.temp_zone
            USING ERRCODE = '23514';
    END IF;

    SELECT i.sku::text INTO other_sku
    FROM inventory inv JOIN item i ON i.item_id = inv.item_id
    WHERE inv.location_id = NEW.location_id
      AND inv.item_id <> NEW.item_id
      AND inv.inventory_id <> COALESCE(NEW.inventory_id, '00000000-0000-0000-0000-000000000000'::uuid)
      AND inv.status IN ('AVAILABLE','ALLOCATED','PICKED')
    LIMIT 1;
    IF other_sku IS NOT NULL THEN
        RAISE EXCEPTION 'Single-item convention: slot % already holds % — one item per slot.',
            loc.code, other_sku
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_slot_conventions ON inventory;
CREATE TRIGGER trg_slot_conventions
    BEFORE INSERT OR UPDATE OF location_id, item_id ON inventory
    FOR EACH ROW EXECUTE FUNCTION fn_slot_conventions();

-- 4. tiered UOM hierarchy: 1 <code> = qty × <of_code>; EA is the implicit base
CREATE TABLE item_uom (
    item_uom_id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    item_id     uuid NOT NULL REFERENCES item(item_id) ON DELETE CASCADE,
    code        text NOT NULL,
    qty         numeric(12,3) NOT NULL CHECK (qty > 0),
    of_code     text NOT NULL DEFAULT 'EA',
    UNIQUE (item_id, code),
    CHECK (code <> of_code)
);

-- backfill from the flat columns already captured
INSERT INTO item_uom (item_id, code, qty, of_code)
SELECT item_id, 'CS', case_pack_qty, 'EA' FROM item
WHERE case_pack_qty IS NOT NULL AND case_pack_qty > 0;

INSERT INTO item_uom (item_id, code, qty, of_code)
SELECT item_id, 'PL', pallet_ti * pallet_hi, 'CS' FROM item
WHERE pallet_ti IS NOT NULL AND pallet_hi IS NOT NULL
  AND EXISTS (SELECT 1 FROM item_uom u WHERE u.item_id = item.item_id AND u.code = 'CS');
