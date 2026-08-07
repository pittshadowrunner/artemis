-- ============================================================
-- WMS Schema V11 — Assignment lifecycle
-- Reassignment is a tracked event, not a silent overwrite: the
-- count and prior assignee drive the REASSIGNED display state.
-- Display lifecycle: PENDING (open) -> ASSIGNED -> IN PROGRESS
-- (first task completes) -> COMPLETE, with REASSIGNED flagged
-- whenever work changed hands after first dispatch.
-- ============================================================
ALTER TABLE assignment ADD COLUMN IF NOT EXISTS reassigned_count int NOT NULL DEFAULT 0;
ALTER TABLE assignment ADD COLUMN IF NOT EXISTS previous_assignee uuid REFERENCES app_user(user_id);
ALTER TABLE assignment ADD COLUMN IF NOT EXISTS last_assigned_at timestamptz;
