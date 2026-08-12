-- ============================================================
-- WMS Schema V14 — Standard locations, priority 1-10, operators
-- ============================================================

-- 1. STORAGE + PICK_FACE -> STANDARD. Pick-face-ness = replen_item_id set.
UPDATE location SET loc_type = 'STANDARD' WHERE loc_type IN ('STORAGE','PICK_FACE');

-- 2. Assignment priority becomes a 1-10 scale (was 0-100).
UPDATE assignment SET priority = LEAST(10, GREATEST(1, ROUND(priority / 10.0)::int));
ALTER TABLE assignment ALTER COLUMN priority SET DEFAULT 5;
ALTER TABLE assignment ADD CONSTRAINT chk_assignment_priority CHECK (priority BETWEEN 1 AND 10);

-- 3. Pick sequence DERIVED from naming: Zone-Aisle-Level-Column-Slot
--    (e.g. C-B-2-04-A). Order: aisle alphabetical, then column, then
--    slot, then level. STANDARD only; docks/drops/staging carry no
--    sequence — their names don't encode a pick path.
CREATE OR REPLACE FUNCTION fn_rank36(tok text) RETURNS int
LANGUAGE plpgsql IMMUTABLE AS $fn$
DECLARE r int := 0; ch text;
BEGIN
    IF tok ~ $re$^[0-9]+$$re$ THEN RETURN tok::int; END IF;
    FOREACH ch IN ARRAY string_to_array(upper(tok), NULL) LOOP
        r := r * 27 + (ascii(ch) - 64);
    END LOOP;
    RETURN r;
END $fn$;

CREATE OR REPLACE FUNCTION fn_derive_sequence() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE parts text[];
BEGIN
    IF NEW.loc_type <> 'STANDARD' THEN
        NEW.pick_sequence := NULL;
        RETURN NEW;
    END IF;
    -- prefer explicit components; else parse Z-AISLE-LEVEL-COL-SLOT from code
    IF NEW.aisle IS NULL AND NEW.code ~ '^[A-Z]+-[A-Z0-9]+-[0-9]+-[0-9]+-[A-Z0-9]+$' THEN
        parts := string_to_array(NEW.code, '-');
        NEW.aisle := parts[2]; NEW.tier := parts[3];
        NEW.bay := parts[4]; NEW.slot := parts[5];
    END IF;
    IF NEW.aisle IS NOT NULL AND NEW.bay IS NOT NULL AND NEW.slot IS NOT NULL THEN
        NEW.pick_sequence := fn_rank36(NEW.aisle) * 1000000
                           + fn_rank36(NEW.bay) * 1000
                           + fn_rank36(NEW.slot) * 10
                           + COALESCE(fn_rank36(NEW.tier), 0);
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_derive_sequence ON location;
CREATE TRIGGER trg_derive_sequence
    BEFORE INSERT OR UPDATE OF code, aisle, bay, tier, slot, loc_type ON location
    FOR EACH ROW EXECUTE FUNCTION fn_derive_sequence();

-- re-derive existing standard slots that carry components; clear docks/drops
UPDATE location SET code = code WHERE loc_type = 'STANDARD';
UPDATE location SET pick_sequence = NULL WHERE loc_type <> 'STANDARD';

-- 4. Conventions trigger now speaks STANDARD
CREATE OR REPLACE FUNCTION fn_slot_conventions() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    loc record;
    item_zone temp_zone;
    other_sku text;
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
      AND inv.inventory_id <> COALESCE(NEW.inventory_id, '00000000-0000-0000-0000-000000000000'::uuid)
      AND inv.status IN ('AVAILABLE','ALLOCATED','PICKED')
    LIMIT 1;
    IF other_sku IS NOT NULL THEN
        RAISE EXCEPTION 'Single-item convention: slot % already holds % — one item per slot.',
            loc.code, other_sku USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- 5. Operators: trained workflow allowances on the person
ALTER TABLE app_user ADD COLUMN trained_workflows text[];

-- 6. Directed putaway now targets STANDARD reserve slots. Pick faces
-- (replen_item_id set) belong to replenishment, not inbound putaway.
CREATE OR REPLACE FUNCTION directed_putaway_slot(
    p_site uuid, p_item uuid, p_lot text,
    p_allow_item_mixing boolean, p_allow_lot_mixing boolean)
RETURNS TABLE (location_id uuid, code text, check_digits char(2), pick_sequence int)
LANGUAGE sql STABLE AS $$
    SELECT l.location_id, l.code, l.check_digits, l.pick_sequence
    FROM location l
    JOIN item it ON it.item_id = p_item
    WHERE l.site_id = p_site
      AND l.active
      AND l.loc_type = 'STANDARD'
      AND l.replen_item_id IS NULL
      AND l.temp_zone = it.temp_zone
      AND (it.hazmat_class IS NULL OR l.hazmat_approved)
      AND NOT EXISTS (
          SELECT 1 FROM inventory i
          WHERE i.location_id = l.location_id
            AND i.status IN ('AVAILABLE','ALLOCATED')
            AND (
                 (NOT p_allow_item_mixing AND i.item_id <> p_item)
              OR (NOT p_allow_lot_mixing AND i.item_id = p_item
                  AND i.lot_number IS DISTINCT FROM p_lot)
            ))
    ORDER BY
      EXISTS (SELECT 1 FROM inventory i2
              WHERE i2.location_id = l.location_id
                AND i2.item_id = p_item
                AND i2.status IN ('AVAILABLE','ALLOCATED')) DESC,
      (l.velocity_zone IS NOT DISTINCT FROM it.velocity_class::text) DESC,
      l.pick_sequence ASC NULLS LAST
    LIMIT 1;
$$;
