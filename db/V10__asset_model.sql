-- ============================================================
-- WMS Schema V10 — Asset model completion
-- Assignments get their own unique document number; wave numbers
-- become datetime-based; slots get physical dimensions; equipment
-- gets a capability list; item velocity gets CALCULATED from
-- trailing pick history (declared velocity_class stays as the
-- slotting intent — the two are compared on the item screen).
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS assignment_number_seq START 10001;
CREATE SEQUENCE IF NOT EXISTS wave_number_seq START 101;

ALTER TABLE assignment ADD COLUMN assignment_number text;
UPDATE assignment
SET assignment_number = 'A-' || to_char(created_at, 'YYMMDD') || '-'
                        || lpad(nextval('assignment_number_seq')::text, 5, '0')
WHERE assignment_number IS NULL;
ALTER TABLE assignment ALTER COLUMN assignment_number SET NOT NULL;
CREATE UNIQUE INDEX uq_assignment_number ON assignment (corporation_id, assignment_number);

-- Slot physical attributes
ALTER TABLE location ADD COLUMN IF NOT EXISTS width_cm      numeric(8,1);
ALTER TABLE location ADD COLUMN IF NOT EXISTS depth_cm      numeric(8,1);
ALTER TABLE location ADD COLUMN IF NOT EXISTS height_cm     numeric(8,1);
ALTER TABLE location ADD COLUMN IF NOT EXISTS max_weight_kg numeric(12,3);  -- exists since V1

-- Powered equipment capabilities ('LIFT_HIGH','COLD_RATED','NARROW_AISLE',...)
ALTER TABLE equipment ADD COLUMN capabilities text[];

-- Calculated ABC velocity: trailing 30 days of completed selection
-- picks, classic 80/15/5 by cumulative line share, per site.
CREATE OR REPLACE VIEW v_item_velocity AS
WITH picks AS (
    SELECT a.site_id, t.item_id,
           count(*) AS lines, sum(t.qty) AS cases,
           max(t.completed_at) AS last_pick_at
    FROM assignment_task t
    JOIN assignment a ON a.assignment_id = t.assignment_id
    WHERE a.assignment_type = 'SELECTION' AND t.status = 'COMPLETE'
      AND t.completed_at >= now() - interval '30 days'
      AND t.item_id IS NOT NULL
    GROUP BY a.site_id, t.item_id
),
ranked AS (
    SELECT p.*,
           sum(lines) OVER (PARTITION BY site_id) AS site_lines,
           sum(lines) OVER (PARTITION BY site_id
                            ORDER BY lines DESC, item_id
                            ROWS UNBOUNDED PRECEDING) AS cum_lines
    FROM picks p
)
SELECT site_id, item_id, lines, cases, last_pick_at,
       CASE WHEN cum_lines <= site_lines * 0.80 THEN 'A'
            WHEN cum_lines <= site_lines * 0.95 THEN 'B'
            ELSE 'C' END AS calc_velocity
FROM ranked;
