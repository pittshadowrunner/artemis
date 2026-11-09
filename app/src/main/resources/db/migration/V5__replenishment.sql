-- ============================================================
-- Artemis WMS — V5 Replenishment
-- Pick faces (STANDARD slots with replen_item_id) are kept between
-- min and max; on-hand at or under the trigger creates work. Moving
-- part of a reserve pallet to a face splits it: the face gets a child
-- pallet with the same lot/expiry and a derived LPN, the parent keeps
-- the remainder. Lineage is kept so a recall walks both directions.
-- ============================================================

ALTER TABLE inventory ADD COLUMN parent_inventory_id uuid REFERENCES inventory(inventory_id);
CREATE INDEX idx_inv_parent ON inventory(parent_inventory_id) WHERE parent_inventory_id IS NOT NULL;

-- Faces at or below trigger, with what's already on the way.
CREATE VIEW v_replen_pressure AS
SELECT l.corporation_id, l.site_id, l.location_id, l.code AS location_code, l.area_id, l.temp_zone,
       l.replen_item_id AS item_id, i.sku, i.description,
       COALESCE((SELECT sum(v.available_qty) FROM v_available_inventory v WHERE v.location_id = l.location_id), 0) AS on_hand,
       l.replen_min_qty, l.replen_trigger_qty, l.replen_max_qty,
       COALESCE((SELECT sum(t.qty) FROM assignment_task t JOIN assignment a ON a.assignment_id = t.assignment_id
                 WHERE t.to_location = l.location_id AND a.assignment_type = 'REPLENISHMENT' AND t.status <> 'COMPLETE'), 0) AS in_flight,
       (SELECT count(*) FROM allocation al JOIN inventory inv ON inv.inventory_id = al.inventory_id
         WHERE inv.location_id = l.location_id AND al.picked_qty < al.qty) AS open_picks
FROM location l
JOIN item i ON i.item_id = l.replen_item_id
WHERE l.active AND l.replen_item_id IS NOT NULL AND l.replen_trigger_qty IS NOT NULL;

-- Best reserve pallet to feed a face: same item, not at a face, free
-- stock, rotation-ordered (FEFO/FIFO per the face's area policy).
CREATE OR REPLACE FUNCTION replen_source(p_face uuid)
RETURNS TABLE (inventory_id uuid, lpn text, location_code text, available_qty numeric, lot_number text, expiration_date date)
LANGUAGE sql STABLE AS $$
    SELECT v.inventory_id, v.lpn, v.location_code, v.available_qty, v.lot_number, v.expiration_date
    FROM location f
    JOIN v_available_inventory v ON v.site_id = f.site_id AND v.item_id = f.replen_item_id
    WHERE f.location_id = p_face AND v.location_id <> f.location_id AND NOT v.at_pick_face AND v.available_qty > 0
    ORDER BY CASE effective_rotation(f.replen_item_id, COALESCE(f.area_id, f.site_id))
               WHEN 'FEFO' THEN v.expiration_date END ASC NULLS LAST,
             CASE effective_rotation(f.replen_item_id, COALESCE(f.area_id, f.site_id))
               WHEN 'LIFO' THEN v.arrival_date END DESC NULLS LAST,
             v.arrival_date ASC, v.pick_sequence ASC NULLS LAST
    LIMIT 1;
$$;

-- Child LPN for a split: PLT-0201 → PLT-0201.1, .2, …
CREATE OR REPLACE FUNCTION next_child_lpn(p_parent uuid) RETURNS text LANGUAGE sql VOLATILE AS $$
    SELECT p.lpn || '.' || (1 + (SELECT count(*) FROM inventory c WHERE c.parent_inventory_id = p.inventory_id))
    FROM inventory p WHERE p.inventory_id = p_parent;
$$;
