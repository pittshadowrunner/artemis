-- Receiving is a linkable operational document: it needs its own
-- timeline. arrived_at / closed_at capture when the trailer hit the
-- dock and when receiving completed.
ALTER TABLE receiving_manifest ADD COLUMN arrived_at timestamptz;
ALTER TABLE receiving_manifest ADD COLUMN closed_at  timestamptz;
UPDATE receiving_manifest SET closed_at = created_at WHERE status = 'CLOSED';
UPDATE receiving_manifest SET arrived_at = created_at
WHERE status IN ('ARRIVED','RECEIVING','CLOSED');
