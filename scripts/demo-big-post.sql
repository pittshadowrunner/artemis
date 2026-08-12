-- ============================================================
-- demo-big-post.sql — run AFTER demo-big.sh
-- Ages the receiving/putaway timeline so dock dwell reads true.
-- ============================================================

-- Open putaway assignments: created 2-12 hours ago (spread by row)
WITH aged AS (
    SELECT a.assignment_id,
           (2 + (row_number() OVER (ORDER BY a.assignment_id)) * 2 % 11) AS hrs
    FROM assignment a
    WHERE a.assignment_type = 'PUTAWAY'
      AND EXISTS (SELECT 1 FROM assignment_task t
                  WHERE t.assignment_id = a.assignment_id AND t.status = 'OPEN')
)
UPDATE assignment a SET created_at = now() - (aged.hrs || ' hours')::interval
FROM aged WHERE a.assignment_id = aged.assignment_id;

-- Dock pallets age to match their putaway assignment
UPDATE inventory inv SET created_at = a.created_at
FROM assignment_task t
JOIN assignment a ON a.assignment_id = t.assignment_id
WHERE a.assignment_type = 'PUTAWAY' AND t.status = 'OPEN'
  AND t.inventory_id = inv.inventory_id;

-- The closed manifest reads as finished earlier today
UPDATE receiving_manifest
SET arrived_at = now() - interval '9 hours',
    closed_at  = now() - interval '7 hours'
WHERE status = 'CLOSED';

SELECT 'aged: ' || count(DISTINCT a.assignment_id) || ' open putaway assignments show dock dwell'
FROM assignment a JOIN assignment_task t USING (assignment_id)
WHERE a.assignment_type = 'PUTAWAY' AND t.status = 'OPEN';
