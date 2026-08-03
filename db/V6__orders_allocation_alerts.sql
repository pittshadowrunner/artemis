-- ============================================================
-- WMS Schema V6 — System Alerts + Allocation/Shipping support
-- ============================================================

CREATE TYPE alert_severity AS ENUM ('INFO','WARNING','CRITICAL');

-- Area/Site scoped operational alerts. The first producer is the
-- allocation engine's shelf-life bypass: when strict rotation is
-- skipped because a customer's freshness rule disqualified the
-- oldest lot, the team gets told the system made that call.
CREATE TABLE system_alert (
    alert_id        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id  uuid NOT NULL,
    site_id         uuid NOT NULL REFERENCES org_node(org_node_id),
    area_id         uuid REFERENCES org_node(org_node_id),
    alert_type      text NOT NULL,           -- e.g. ROTATION_BYPASS, AGING_STOCK
    severity        alert_severity NOT NULL DEFAULT 'INFO',
    message         text NOT NULL,
    order_id        uuid REFERENCES customer_order(order_id),
    item_id         uuid REFERENCES item(item_id),
    inventory_id    uuid REFERENCES inventory(inventory_id),
    acknowledged_by uuid REFERENCES app_user(user_id),
    acknowledged_at timestamptz,
    created_at      timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_alert_site_open ON system_alert(site_id, created_at DESC)
    WHERE acknowledged_at IS NULL;

ALTER TABLE system_alert ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_iso_system_alert ON system_alert
    USING (corporation_id = current_setting('app.current_corp', true)::uuid);

-- Available (unallocated) quantity per inventory record
CREATE OR REPLACE VIEW v_available_inventory AS
SELECT inv.inventory_id, inv.corporation_id, inv.site_id, inv.item_id,
       inv.lpn, inv.lot_number, inv.expiration_date, inv.arrival_date,
       inv.location_id, l.code AS location_code, l.pick_sequence, l.area_id,
       l.check_digits,
       inv.qty - COALESCE(a.allocated, 0) AS available_qty
FROM inventory inv
JOIN location l ON l.location_id = inv.location_id
LEFT JOIN (SELECT inventory_id, sum(qty) AS allocated
           FROM allocation GROUP BY inventory_id) a USING (inventory_id)
WHERE inv.status = 'AVAILABLE'
  AND inv.qty - COALESCE(a.allocated, 0) > 0;

-- Packing list document numbers
CREATE SEQUENCE IF NOT EXISTS packing_list_seq START 10001;
