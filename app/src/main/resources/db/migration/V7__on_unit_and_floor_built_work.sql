-- ============================================================
-- Artemis WMS — V7 Picked stock rides on the unit; work built on the floor
--
-- A pick used to make the quantity vanish from the slot and reappear
-- only as a number on the order. Now it is inventory the whole way:
-- the picked quantity becomes a pick unit (PK-n) that sits ON the
-- equipment, in its put position, for its zone order — then moves to
-- the drop location with the load, and leaves the building on the
-- shipment. location_id NULL has always meant "in transit on
-- equipment"; equipment_id now says which unit.
-- ============================================================

ALTER TABLE inventory
    ADD COLUMN equipment_id  uuid REFERENCES equipment(equipment_id) ON DELETE SET NULL,
    ADD COLUMN cart_position int,
    ADD COLUMN picked_from   uuid REFERENCES inventory(inventory_id),     -- the pallet it was picked out of
    ADD COLUMN zone_order_id uuid REFERENCES zone_order(zone_order_id) ON DELETE SET NULL;
CREATE INDEX idx_inv_equipment  ON inventory(equipment_id)  WHERE equipment_id IS NOT NULL;
CREATE INDEX idx_inv_zone_order ON inventory(zone_order_id) WHERE zone_order_id IS NOT NULL;
CREATE INDEX idx_inv_picked_from ON inventory(picked_from)  WHERE picked_from IS NOT NULL;

CREATE SEQUENCE pick_unit_seq START 1;

-- Lots count pallets that came in the door, not the pick units cut from them.
CREATE OR REPLACE VIEW v_lot AS
SELECT inv.corporation_id, inv.site_id, inv.item_id, i.sku, i.description, i.temp_zone, inv.lot_number,
       min(inv.expiration_date) AS expiration_date,
       min(inv.arrival_date)    AS first_arrival,
       max(inv.arrival_date)    AS last_arrival,
       count(DISTINCT inv.arrival_date) AS arrival_dates,
       count(*) AS pallets,
       count(*) FILTER (WHERE inv.qty > 0 AND inv.status IN ('AVAILABLE','ALLOCATED')) AS pallets_on_hand,
       COALESCE(sum(inv.qty) FILTER (WHERE inv.status IN ('AVAILABLE','ALLOCATED')), 0) AS qty_on_hand,
       COALESCE(sum(inv.original_qty) FILTER (WHERE inv.parent_inventory_id IS NULL), 0) AS qty_received
FROM inventory inv JOIN item i ON i.item_id = inv.item_id
WHERE inv.lot_number IS NOT NULL AND inv.picked_from IS NULL
GROUP BY inv.corporation_id, inv.site_id, inv.item_id, i.sku, i.description, i.temp_zone, inv.lot_number;

-- Released zone orders nobody has built into an assignment yet: the
-- floor builds these by hand, or an operator asking for work gets them.
CREATE VIEW v_unbuilt_zone_order AS
SELECT z.zone_order_id, z.corporation_id, o.site_id, z.temp_zone, z.wave_id, w.wave_number, w.batch_size,
       o.order_id, o.order_number, o.rush, o.priority, o.requested_ship_date, o.route_code, o.stop_sequence,
       c.code AS customer, c.name AS customer_name,
       default_priority('SELECTION', z.temp_zone) AS work_priority,
       (SELECT count(*) FROM customer_order_line ol JOIN item i ON i.item_id = ol.item_id
         JOIN allocation a ON a.order_line_id = ol.order_line_id AND a.picked_qty < a.qty
         WHERE ol.order_id = o.order_id AND i.temp_zone = z.temp_zone) AS picks
FROM zone_order z
JOIN wave w ON w.wave_id = z.wave_id AND w.status IN ('RELEASED','PICKING')
JOIN customer_order o ON o.order_id = z.order_id AND o.status NOT IN ('SHIPPED','CANCELLED')
JOIN customer c ON c.customer_id = o.customer_id
WHERE z.assignment_id IS NULL AND z.dropped_at IS NULL;
