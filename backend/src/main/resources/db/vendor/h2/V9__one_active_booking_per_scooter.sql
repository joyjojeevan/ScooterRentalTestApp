-- H2 equivalent of the PostgreSQL partial unique index (H2 has no partial indexes): a unique index over a
-- generated column that holds scooter_id only while the booking is ACTIVE (NULLs never collide).
ALTER TABLE bookings ADD COLUMN active_scooter_id BIGINT GENERATED ALWAYS AS (
    CASE WHEN status = 'ACTIVE' THEN scooter_id END);
CREATE UNIQUE INDEX ux_bookings_one_active_per_scooter ON bookings (active_scooter_id);
