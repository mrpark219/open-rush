ALTER TABLE performance_seats
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE concert_purchase_quotas
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
