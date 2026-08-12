-- ============================================================
-- demo-wipe.sql — clear operational and master data, KEEP the
-- Operation Hierarchy (org nodes, users, roles, grants, IdPs,
-- inventory policies). Idempotent.
-- Run on NAS:
--   sudo docker cp demo-wipe.sql <db>:/tmp/ &&
--   sudo docker exec -it <db> psql -U wms -d wms -f /tmp/demo-wipe.sql
-- ============================================================
BEGIN;
TRUNCATE TABLE
    shipment_order, shipment,
    packing_list_line, packing_list,
    allocation,
    assignment_container, assignment_task, assignment,
    wave_order, wave,
    customer_order_line, customer_order,
    customer,
    user_notification, system_alert, email_outbox,
    inventory_movement, inventory_serial, inventory,
    receiving_manifest_line, receiving_manifest,
    item_uom, item,
    container,
    equipment_position, equipment,
    location
    CASCADE;
COMMIT;
SELECT 'wiped — hierarchy intact: ' || count(*) || ' org nodes, '
       || (SELECT count(*) FROM app_user) || ' users remain' AS result
FROM org_node;
