ALTER TABLE reservation_payment_tx
    ADD COLUMN cancellation_number UUID,
    ADD COLUMN cancellation_idempotency_key VARCHAR(128),
    ADD COLUMN cancel_reason VARCHAR(255);

ALTER TABLE reservation_payment_tx DROP CONSTRAINT ck_reservation_payment_tx_type;
ALTER TABLE reservation_payment_tx
    ADD CONSTRAINT ck_reservation_payment_tx_type CHECK (
        (type = 'APPROVAL_PREPARED' AND amount > 0 AND attempt_number IS NOT NULL AND pg_order_number IS NOT NULL
            AND approval_idempotency_key IS NOT NULL AND payment_id IS NULL AND cancellation_id IS NULL
            AND currency IS NULL AND status IS NULL AND refund_reason IS NULL)
        OR
        (type = 'APPROVED' AND approval_idempotency_key IS NULL AND payment_id IS NOT NULL
            AND cancellation_id IS NULL AND currency IS NOT NULL AND status IS NOT NULL
            AND ((status = 'RESERVATION_CONFIRMED' AND refund_reason IS NULL)
                OR (status = 'REFUND_REQUIRED' AND refund_reason IS NOT NULL
                    AND refund_reason IN ('PAYMENT_MISMATCH', 'RESERVATION_UNAVAILABLE'))))
        OR
        (type = 'CANCEL_PREPARED' AND amount > 0 AND approval_idempotency_key IS NULL
            AND payment_id IS NOT NULL AND cancellation_id IS NULL AND currency IS NOT NULL
            AND status IS NULL AND refund_reason IS NULL)
        OR
        (type = 'CANCELED' AND amount > 0 AND approval_idempotency_key IS NULL AND payment_id IS NOT NULL
            AND cancellation_id IS NOT NULL AND currency IS NOT NULL AND status IS NULL AND refund_reason IS NULL)
    );

-- 기존 취소 완료 이력에는 준비 정보가 없으므로 NULL을 허용한다.
ALTER TABLE reservation_payment_tx
    ADD CONSTRAINT ck_reservation_payment_tx_cancellation CHECK (
        (type <> 'CANCEL_PREPARED' AND cancellation_number IS NULL
            AND cancellation_idempotency_key IS NULL AND cancel_reason IS NULL)
        OR
        (type IN ('CANCEL_PREPARED', 'CANCELED') AND cancellation_number IS NOT NULL
            AND cancellation_idempotency_key IS NOT NULL AND length(trim(cancellation_idempotency_key)) > 0
            AND cancel_reason IS NOT NULL AND length(trim(cancel_reason)) > 0)
    );

CREATE UNIQUE INDEX uk_reservation_payment_tx_cancel_attempt
    ON reservation_payment_tx (reservation_id, cancellation_number) WHERE type = 'CANCEL_PREPARED';
CREATE UNIQUE INDEX uk_reservation_payment_tx_cancel_key
    ON reservation_payment_tx (provider, cancellation_idempotency_key) WHERE type = 'CANCEL_PREPARED';
CREATE UNIQUE INDEX uk_reservation_payment_tx_canceled_attempt
    ON reservation_payment_tx (reservation_id, cancellation_number)
    WHERE type = 'CANCELED' AND cancellation_number IS NOT NULL;
