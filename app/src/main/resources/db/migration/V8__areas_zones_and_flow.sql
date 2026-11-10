-- ============================================================
-- Artemis WMS — V8 Areas are temperatures; zones are buildings inside them
--
-- One vocabulary for where things are:
--   Area   an org node under the site — DRY, COOLER, FREEZER. The
--          temperature regime and the policy boundary.
--   Zone   a building or room inside an area — A, B, C. Dry has its
--          own Zone A; so does the cooler.
--   then   Aisle → Level → Column → Slot, as before.
--
-- A rack slot's printed code therefore carries the area first:
--   D-A-07-3-12-B   = Dry, Zone A, Aisle 07, Level 3, Column 12, Slot B
-- The older five-part form (Zone-Aisle-Level-Column-Slot) still parses.
--
-- Docks, drops and staging lanes are not racking; they belong to a
-- flow instead — inbound or outbound.
-- ============================================================

ALTER TABLE location ADD COLUMN flow text CHECK (flow IN ('INBOUND','OUTBOUND'));
COMMENT ON COLUMN location.flow IS 'For non-rack locations: which side of the building. NULL = implied by type (receiving and cross-dock are inbound; drop, staging and shipping dock are outbound).';

CREATE OR REPLACE FUNCTION location_flow(p_type location_type, p_flow text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN p_type = 'STANDARD' THEN NULL
                ELSE COALESCE(p_flow, CASE WHEN p_type IN ('RECEIVING_DOCK','CROSS_DOCK') THEN 'INBOUND' ELSE 'OUTBOUND' END) END
$$;

-- Derive components, pick sequence and check digits. The pick path now
-- finishes one zone (building) before starting the next:
--   zone → aisle → column → slot → level.
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
    IF NEW.aisle IS NULL OR (TG_OP = 'UPDATE' AND NEW.code IS DISTINCT FROM OLD.code) THEN
        IF NEW.code ~ '^[A-Z0-9]+-[A-Z0-9]+-[A-Z0-9]+-[0-9]+-[0-9]+-[A-Z0-9]+$' THEN      -- Area-Zone-Aisle-Level-Column-Slot
            parts := string_to_array(NEW.code, '-');
            NEW.zone := parts[2]; NEW.aisle := parts[3]; NEW.level := parts[4];
            NEW.col := parts[5];  NEW.slot := parts[6];
        ELSIF NEW.code ~ '^[A-Z0-9]+-[A-Z0-9]+-[0-9]+-[0-9]+-[A-Z0-9]+$' THEN              -- Zone-Aisle-Level-Column-Slot
            parts := string_to_array(NEW.code, '-');
            NEW.zone := parts[1]; NEW.aisle := parts[2]; NEW.level := parts[3];
            NEW.col := parts[4];  NEW.slot := parts[5];
        END IF;
    END IF;
    IF NEW.aisle IS NOT NULL AND NEW.col IS NOT NULL AND NEW.slot IS NOT NULL THEN
        NEW.pick_sequence := least(fn_rank_token(NEW.zone), 20)  * 100000000
                           + least(fn_rank_token(NEW.aisle), 99) * 1000000
                           + least(fn_rank_token(NEW.col), 999)  * 1000
                           + least(fn_rank_token(NEW.slot), 99)  * 10
                           + least(fn_rank_token(NEW.level), 9);
    END IF;
    RETURN NEW;
END $$;

-- Re-derive what is already there under the new pick path.
SELECT set_config('app.platform_admin', 'on', true);
UPDATE location SET aisle = aisle WHERE loc_type = 'STANDARD';

CREATE INDEX idx_location_area_zone ON location (site_id, area_id, zone, aisle) WHERE loc_type = 'STANDARD';
CREATE INDEX idx_location_face ON location (replen_item_id) WHERE replen_item_id IS NOT NULL;

-- Acknowledging or clearing alerts walks their notifications.
CREATE INDEX IF NOT EXISTS idx_notification_alert ON user_notification (alert_id);
