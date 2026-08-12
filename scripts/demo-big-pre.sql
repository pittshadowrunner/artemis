-- ============================================================
-- demo-big-pre.sql — run BEFORE demo-big.sh
-- Seeds 8 operators with trained-workflow allowances. Idempotent.
--   sudo docker cp demo-big-pre.sql <db>:/tmp/ &&
--   sudo docker exec -it <db> psql -U wms -d wms -f /tmp/demo-big-pre.sql
-- ============================================================
INSERT INTO app_user (email, password_hash, display_name, email_verified, account_source, active, trained_workflows)
VALUES
  ('m.alvarez@artemis.local', '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'M. Alvarez', true, 'LOCAL', true, ARRAY['SELECTION','REPLENISHMENT']),
  ('d.chen@artemis.local',    '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'D. Chen',    true, 'LOCAL', true, ARRAY['SELECTION','PUTAWAY']),
  ('j.okafor@artemis.local',  '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'J. Okafor',  true, 'LOCAL', true, ARRAY['SELECTION','SHIPPING']),
  ('t.rivas@artemis.local',   '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'T. Rivas',   true, 'LOCAL', true, ARRAY['SELECTION']),
  ('p.nowak@artemis.local',   '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'P. Nowak',   true, 'LOCAL', true, ARRAY['PUTAWAY','RECEIVING']),
  ('k.diaz@artemis.local',    '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'K. Diaz',    true, 'LOCAL', true, ARRAY['RECEIVING','PUTAWAY']),
  ('s.boateng@artemis.local', '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'S. Boateng', true, 'LOCAL', true, ARRAY['REPLENISHMENT','PUTAWAY']),
  ('l.tran@artemis.local',    '$2a$10$demoDemoDemoDemoDemoDeuJ9c1zQ5rW6xY7zA8bC9dE0fG1hI2jK', 'L. Tran',    true, 'LOCAL', true, ARRAY['SHIPPING','SELECTION'])
ON CONFLICT (email) DO UPDATE SET
    trained_workflows = EXCLUDED.trained_workflows,
    display_name = EXCLUDED.display_name,
    active = true;

SELECT display_name, array_to_string(trained_workflows, ', ') AS allowances
FROM app_user WHERE sysadmin = false ORDER BY display_name;
