-- ============================================================
-- WMS Schema V16 — pallet lineage, equipment checkout, order pooling
-- ============================================================

-- Pallet lineage: what the pallet held when it was born (received or
-- split), vs qty (what's on it now). Available never exceeds original.
ALTER TABLE inventory ADD COLUMN original_qty numeric(12,3);
UPDATE inventory SET original_qty = qty;

-- Equipment checkout: operator uses, not trips. We care who had the
-- unit and when — not which assignment or wave rode along.
CREATE TABLE equipment_checkout (
    checkout_id    uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    equipment_id   uuid NOT NULL REFERENCES equipment(equipment_id),
    user_id        uuid NOT NULL REFERENCES app_user(user_id),
    checked_out_at timestamptz NOT NULL DEFAULT now(),
    checked_in_at  timestamptz
);
CREATE INDEX idx_checkout_equipment ON equipment_checkout (equipment_id, checked_out_at DESC);
CREATE UNIQUE INDEX uq_checkout_open ON equipment_checkout (equipment_id)
    WHERE checked_in_at IS NULL;
