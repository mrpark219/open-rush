CREATE TABLE payment_records (
    provider VARCHAR(32) NOT NULL,
    payment_id VARCHAR(255) NOT NULL,
    reservation_id BIGINT NOT NULL REFERENCES reservations(id),
    amount BIGINT NOT NULL CHECK (amount >= 0),
    currency VARCHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    status VARCHAR(32) NOT NULL,
    refund_reason VARCHAR(32),
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (provider, payment_id),
    CONSTRAINT ck_payment_record_outcome CHECK (
        (status IN ('PROCESSING', 'RESERVATION_CONFIRMED') AND refund_reason IS NULL)
        OR (status = 'REFUND_REQUIRED' AND refund_reason IS NOT NULL
            AND refund_reason IN ('PAYMENT_MISMATCH', 'RESERVATION_UNAVAILABLE'))
    )
);

CREATE INDEX idx_payment_records_reservation ON payment_records (reservation_id);
