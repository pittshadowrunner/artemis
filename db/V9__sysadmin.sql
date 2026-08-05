-- ============================================================
-- WMS Schema V9 — Platform SYSADMIN tier
-- Sysadmin sits ABOVE the tenant model: it is a flag on the
-- user, not a grant at an org node, because its defining power
-- (creating corporations, platform-wide administration) happens
-- before any org node exists to grant against. Tenant roles
-- (corporation/district/site/area admins) remain grants and
-- propagate downward via effective_role() as before. No
-- customer-facing role can reach sysadmin.
-- ============================================================

ALTER TABLE app_user ADD COLUMN sysadmin boolean NOT NULL DEFAULT false;

-- Continuity for existing dev/beta installs: the local-auth
-- bootstrap admin keeps working as the platform operator.
UPDATE app_user SET sysadmin = true WHERE email = 'admin@artemis.local';

-- Placard helpers shared by the server-rendered UI: temp zone -> the
-- 3-letter tag and css class used on rack placards.
CREATE OR REPLACE FUNCTION tag_of(tz text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE tz
        WHEN 'FROZEN' THEN 'FRZ' WHEN 'DEEP_FROZEN' THEN 'FRZ'
        WHEN 'REFRIGERATED' THEN 'CHL' WHEN 'CONTROLLED_AMBIENT' THEN 'AMB'
        WHEN 'HEATED' THEN 'HOT' ELSE 'AMB' END;
$$;

CREATE OR REPLACE FUNCTION css_of(tz text) RETURNS text
LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE tz
        WHEN 'FROZEN' THEN 'frozen' WHEN 'DEEP_FROZEN' THEN 'frozen'
        WHEN 'REFRIGERATED' THEN 'chill' ELSE '' END;
$$;
