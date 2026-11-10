-- ============================================================
-- Artemis WMS — V6 System LPNs and lookup support
--
-- Receiving assigns the inbound LPN: the dock should never have to
-- invent one. A supplier's own label (SSCC) can still be passed in
-- and wins; otherwise the pallet is born with the next system LPN.
--
-- Also: the read models the linked UI needs — where a pallet is
-- headed (its open putaway/replenishment task), and case-insensitive
-- prefix lookups for universal search.
-- ============================================================

CREATE SEQUENCE lpn_seq START 1000001;

-- Next free system LPN at a site: PLT-1000001, PLT-1000002, …
-- Skips anything already taken by an imported or supplier label.
CREATE OR REPLACE FUNCTION next_lpn(p_site uuid) RETURNS text
LANGUAGE plpgsql VOLATILE AS $$
DECLARE candidate text;
BEGIN
    LOOP
        candidate := 'PLT-' || nextval('lpn_seq');
        EXIT WHEN NOT EXISTS (SELECT 1 FROM inventory WHERE site_id = p_site AND lpn = candidate);
    END LOOP;
    RETURN candidate;
END $$;

-- Where each pallet is headed right now: its open directed task.
-- One row per open PUTAWAY / REPLENISHMENT task.
CREATE VIEW v_pallet_task AS
SELECT t.inventory_id, a.corporation_id, a.site_id, a.assignment_id, a.assignment_number,
       a.assignment_type, a.status AS assignment_status, a.priority, a.assigned_to,
       u.display_name AS operator, t.task_id, t.qty,
       lf.code AS from_code, lt.code AS to_code, t.check_digits
FROM assignment_task t
JOIN assignment a ON a.assignment_id = t.assignment_id
LEFT JOIN app_user u ON u.user_id = a.assigned_to
LEFT JOIN location lf ON lf.location_id = t.from_location
LEFT JOIN location lt ON lt.location_id = t.to_location
WHERE t.status = 'OPEN' AND a.assignment_type IN ('PUTAWAY','REPLENISHMENT')
  AND a.status IN ('OPEN','ASSIGNED','IN_PROGRESS');

-- Lots: one row per (site, item, lot) with what's on hand and when it came in.
CREATE VIEW v_lot AS
SELECT inv.corporation_id, inv.site_id, inv.item_id, i.sku, i.description, i.temp_zone, inv.lot_number,
       min(inv.expiration_date) AS expiration_date,
       min(inv.arrival_date)    AS first_arrival,
       max(inv.arrival_date)    AS last_arrival,
       count(DISTINCT inv.arrival_date) AS arrival_dates,
       count(*) AS pallets,
       count(*) FILTER (WHERE inv.qty > 0 AND inv.status IN ('AVAILABLE','ALLOCATED')) AS pallets_on_hand,
       COALESCE(sum(inv.qty) FILTER (WHERE inv.status IN ('AVAILABLE','ALLOCATED')), 0) AS qty_on_hand,
       -- received = pallets that entered the building (splits would double count)
       COALESCE(sum(inv.original_qty) FILTER (WHERE inv.parent_inventory_id IS NULL), 0) AS qty_received
FROM inventory inv JOIN item i ON i.item_id = inv.item_id
WHERE inv.lot_number IS NOT NULL
GROUP BY inv.corporation_id, inv.site_id, inv.item_id, i.sku, i.description, i.temp_zone, inv.lot_number;

-- Search is "starts with or contains, any case" over short identifiers.
CREATE INDEX idx_inv_lpn_upper   ON inventory (upper(lpn) text_pattern_ops);
CREATE INDEX idx_loc_code_upper  ON location (upper(code) text_pattern_ops);
CREATE INDEX idx_order_no_upper  ON customer_order (upper(order_number) text_pattern_ops);

-- Item velocity is calculated, not declared: trailing-30-day selection
-- lines, classed by cumulative share (A = first 80% of lines, B = next
-- 15%, C = the rest). An item with no picks in the window has no class.
CREATE VIEW v_item_velocity AS
WITH picks AS (
    SELECT i.corporation_id, i.item_id,
           count(t.task_id) AS lines_30d, COALESCE(sum(t.qty), 0) AS cases_30d
    FROM item i
    LEFT JOIN assignment_task t ON t.item_id = i.item_id AND t.status = 'COMPLETE'
         AND t.completed_at > now() - interval '30 days'
         AND EXISTS (SELECT 1 FROM assignment a WHERE a.assignment_id = t.assignment_id AND a.assignment_type = 'SELECTION')
    GROUP BY i.corporation_id, i.item_id),
ranked AS (
    SELECT p.*,
           sum(lines_30d) OVER (PARTITION BY corporation_id ORDER BY lines_30d DESC, item_id) AS running,
           sum(lines_30d) OVER (PARTITION BY corporation_id) AS total
    FROM picks p)
SELECT corporation_id, item_id, lines_30d, cases_30d,
       CASE WHEN lines_30d = 0 THEN NULL
            WHEN running - lines_30d < 0.80 * total THEN 'A'
            WHEN running - lines_30d < 0.95 * total THEN 'B'
            ELSE 'C' END AS velocity
FROM ranked;
