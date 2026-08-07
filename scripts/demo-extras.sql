-- ============================================================
-- Artemis WMS — demo extras that have no API endpoint yet.
--
-- NOTE: as of v0.5 both original gaps are closed in the API:
--   POST /api/v1/equipment            registers carts with positions
--   POST /api/v1/assignments/{id}/assign   sets assigned_to for labor metrics
-- This file remains useful for backfilling databases seeded before
-- those endpoints existed, and includes a heal for the pre-fix
-- partial-allocation bug.
--
-- Apply on the Docker host:
--   docker cp demo-extras.sql <db-container>:/tmp/
--   docker exec -it <db-container> psql -U wms -d wms -f /tmp/demo-extras.sql
-- ============================================================

-- A 4-position picking cart at the Pittsburgh DC, with per-position
-- check digits. Release a wave with {"equipmentCode":"CART-014"} and
-- batching kicks in: 4 orders per trip, tasks merged and walked in
-- pick_sequence order.
INSERT INTO equipment (corporation_id, site_id, code, equipment_type,
                       check_digits, container_positions, voice_enabled)
SELECT corporation_id, org_node_id, 'CART-014', 'CART', '55', 4, true
FROM org_node WHERE code = 'PIT1' AND level = 'SITE_LOCATION'
ON CONFLICT (site_id, code) DO NOTHING;

INSERT INTO equipment_position (equipment_id, position_no, check_digits)
SELECT e.equipment_id, p.n, lpad((p.n * 23 % 100)::text, 2, '0')
FROM equipment e, generate_series(1, 4) AS p(n)
WHERE e.code = 'CART-014'
ON CONFLICT DO NOTHING;

-- A second cart for pallet-scale work.
INSERT INTO equipment (corporation_id, site_id, code, equipment_type,
                       lpn, check_digits, container_positions, voice_enabled)
SELECT corporation_id, org_node_id, 'JACK-03', 'PALLET_JACK', '00099001', '71', 1, true
FROM org_node WHERE code = 'PIT1' AND level = 'SITE_LOCATION'
ON CONFLICT (site_id, code) DO NOTHING;

-- Totes with check digits, so the Totes & Containers screens have assets.
INSERT INTO container (corporation_id, site_id, barcode, container_type, check_digits, reusable, tare_weight_kg, max_weight_kg)
SELECT corporation_id, org_node_id, b.code, 'TOTE', b.digits, true, 1.8, 25
FROM org_node, (VALUES ('TOTE-0101','14'),('TOTE-0102','62'),('TOTE-0103','38'),('TOTE-0104','87')) AS b(code, digits)
WHERE org_node.code = 'PIT1' AND org_node.level = 'SITE_LOCATION'
ON CONFLICT (site_id, barcode) DO NOTHING;

-- Demo floor crew, so labor productivity has names attached.
INSERT INTO app_user (email, display_name, email_verified, active, account_source)
VALUES ('m.alvarez@artemis.local', 'M. Alvarez', true, true, 'LOCAL'),
       ('d.chen@artemis.local',    'D. Chen',    true, true, 'LOCAL'),
       ('j.okafor@artemis.local',  'J. Okafor',  true, true, 'LOCAL'),
       ('t.rivas@artemis.local',   'T. Rivas',   true, true, 'LOCAL')
ON CONFLICT (email) DO NOTHING;

-- Grant them READ_ONLY at the site so they resolve as real users.
INSERT INTO user_org_grant (user_id, org_node_id, role_id)
SELECT u.user_id, o.org_node_id, r.role_id
FROM app_user u, org_node o, role r
WHERE u.email IN ('m.alvarez@artemis.local','d.chen@artemis.local',
                  'j.okafor@artemis.local','t.rivas@artemis.local')
  AND o.code = 'PIT1' AND o.level = 'SITE_LOCATION'
  AND r.code = 'READ_ONLY' AND r.corporation_id IS NULL
ON CONFLICT DO NOTHING;

-- Spread existing assignments across the crew so the labor view fills in.
WITH crew AS (
    SELECT user_id, row_number() OVER (ORDER BY display_name) - 1 AS n
    FROM app_user WHERE email LIKE '%@artemis.local' AND email <> 'admin@artemis.local'
),
numbered AS (
    SELECT assignment_id, row_number() OVER (ORDER BY created_at) - 1 AS n
    FROM assignment WHERE assigned_to IS NULL
)
UPDATE assignment a
SET assigned_to = c.user_id
FROM numbered nm
JOIN crew c ON c.n = nm.n % (SELECT count(*) FROM crew)
WHERE a.assignment_id = nm.assignment_id;

-- Heal for databases written by builds before the partial-allocation
-- fix: LPNs flipped to ALLOCATED while only partially allocated were
-- invisible to v_available_inventory. Restore the remainder.
UPDATE inventory i SET status = 'AVAILABLE'
WHERE status = 'ALLOCATED'
  AND qty > COALESCE((SELECT sum(a.qty) FROM allocation a
                      WHERE a.inventory_id = i.inventory_id), 0);

SELECT 'equipment' AS seeded, count(*) FROM equipment
UNION ALL SELECT 'cart positions', count(*) FROM equipment_position
UNION ALL SELECT 'crew users', count(*) FROM app_user WHERE email LIKE '%.%@artemis.local'
UNION ALL SELECT 'assignments with an owner', count(*) FROM assignment WHERE assigned_to IS NOT NULL;
