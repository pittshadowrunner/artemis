-- ============================================================
-- WMS Schema V17 — Zone Orders
-- A customer order is customer-shaped; an assignment is zone-shaped.
-- Zone Orders are the join: each order splits by item temp zone, and
-- a zone order is the unit that gets waved and picked. Waves carry a
-- zone designation; every assignment inherits single-zone work by
-- construction instead of by convention.
-- ============================================================

CREATE TABLE zone_order (
    zone_order_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    corporation_id uuid NOT NULL,
    order_id       uuid NOT NULL REFERENCES customer_order(order_id) ON DELETE CASCADE,
    temp_zone      temp_zone NOT NULL,
    wave_id        uuid REFERENCES wave(wave_id),
    assignment_id  uuid REFERENCES assignment(assignment_id),
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (order_id, temp_zone)
);
CREATE INDEX idx_zone_order_wave ON zone_order (wave_id);
CREATE INDEX idx_zone_order_assignment ON zone_order (assignment_id);

ALTER TABLE wave ADD COLUMN temp_zone temp_zone;

-- Backfill: zone orders from existing order lines
INSERT INTO zone_order (corporation_id, order_id, temp_zone)
SELECT DISTINCT co.corporation_id, co.order_id, i.temp_zone
FROM customer_order co
JOIN customer_order_line col ON col.order_id = co.order_id
JOIN item i ON i.item_id = col.item_id
ON CONFLICT DO NOTHING;

-- Legacy waves were single-zone by data discipline: link and stamp
UPDATE zone_order zo SET wave_id = wo.wave_id
FROM wave_order wo WHERE wo.order_id = zo.order_id AND zo.wave_id IS NULL;

UPDATE wave w SET temp_zone = z.tz FROM (
    SELECT wave_id, min(temp_zone::text)::temp_zone AS tz
    FROM zone_order WHERE wave_id IS NOT NULL
    GROUP BY wave_id HAVING count(DISTINCT temp_zone) = 1) z
WHERE w.wave_id = z.wave_id AND w.temp_zone IS NULL;

-- Assignment linkage: the wave assignment whose tasks live in this zone
UPDATE zone_order zo SET assignment_id = a.assignment_id
FROM assignment a
WHERE a.wave_id = zo.wave_id AND zo.assignment_id IS NULL
  AND EXISTS (SELECT 1 FROM assignment_task t JOIN item i ON i.item_id = t.item_id
              WHERE t.assignment_id = a.assignment_id AND i.temp_zone = zo.temp_zone
                AND t.inventory_id IN (SELECT al.inventory_id FROM allocation al
                                       JOIN customer_order_line col ON col.order_line_id = al.order_line_id
                                       WHERE col.order_id = zo.order_id));
