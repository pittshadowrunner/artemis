-- ============================================================
-- WMS Schema V5 — Receiving & Put Away support
-- ============================================================

-- Stable per-location check digits for voice verification.
-- Deterministic backfill from the code hash so re-running is idempotent
-- and re-labeling a site doesn't shuffle digits.
ALTER TABLE location ADD COLUMN IF NOT EXISTS check_digits char(2);
UPDATE location SET check_digits = lpad((abs(hashtext(code)) % 100)::text, 2, '0')
WHERE check_digits IS NULL;

-- Receipts can arrive damaged or partially rejected
ALTER TABLE receiving_manifest_line
    ADD COLUMN IF NOT EXISTS rejected_qty numeric(14,3) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS rejection_reason text;

-- Directed putaway candidate search. One implementation in SQL so the
-- service layer, voice dialog, and any future slotting simulator all
-- agree on what "the right slot" means.
--   Ranking: consolidate with same item first, then velocity-zone match,
--   then shortest travel (pick_sequence). Mixing rules come from the
--   effective policy of the site/area cascade.
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
      AND l.loc_type = 'STORAGE'
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
