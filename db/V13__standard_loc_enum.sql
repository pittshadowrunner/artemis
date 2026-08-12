-- STANDARD consolidates STORAGE + PICK_FACE: customers pick in three
-- dimensions from anywhere, so pick-face is a *role* (has replen fields),
-- not a location type. Enum value added alone — Postgres requires new
-- enum values to commit before use.
ALTER TYPE location_type ADD VALUE IF NOT EXISTS 'STANDARD';
