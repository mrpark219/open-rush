ALTER TABLE reservation_refund_jobs
    ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN lease_token UUID,
    ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    ADD COLUMN failure_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN last_failure VARCHAR(32),
    DROP CONSTRAINT ck_reservation_refund_jobs_status,
    ADD CONSTRAINT ck_reservation_refund_jobs_status CHECK (
        (status = 'COMPLETED' AND completed_at IS NOT NULL)
        OR (status IN ('PENDING', 'NEEDS_REVIEW') AND completed_at IS NULL)
    ),
    ADD CONSTRAINT ck_reservation_refund_jobs_lease CHECK (
        (lease_token IS NULL AND lease_expires_at IS NULL)
        OR (status = 'PENDING' AND lease_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    ),
    ADD CONSTRAINT ck_reservation_refund_jobs_failures CHECK (
        (failure_count = 0 AND last_failure IS NULL)
        OR (failure_count > 0 AND last_failure IS NOT NULL)
    );

CREATE INDEX idx_reservation_refund_jobs_due
    ON reservation_refund_jobs (next_attempt_at, id)
    WHERE status = 'PENDING';
