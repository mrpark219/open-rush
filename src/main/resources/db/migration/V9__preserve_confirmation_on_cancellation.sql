ALTER TABLE reservations DROP CONSTRAINT ck_reservation_confirmation;
ALTER TABLE reservations ADD CONSTRAINT ck_reservation_confirmation CHECK (
    (confirmed_at IS NOT NULL AND status IN ('CONFIRMED', 'CANCELLED')
        AND confirmed_at >= held_at AND confirmed_at < expires_at)
    OR (confirmed_at IS NULL AND status <> 'CONFIRMED')
);
