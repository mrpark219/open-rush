ALTER TABLE reservation_payment_tx ADD COLUMN requested_payment_key VARCHAR(200);
ALTER TABLE reservation_payment_tx ADD CONSTRAINT ck_reservation_payment_tx_requested_key
    CHECK (requested_payment_key IS NULL OR
        (type = 'APPROVAL_PREPARED' AND length(trim(requested_payment_key)) > 0));
