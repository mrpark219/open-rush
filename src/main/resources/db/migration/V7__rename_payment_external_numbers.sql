ALTER TABLE reservation_payment_tx RENAME COLUMN attempt_id TO attempt_number;
ALTER TABLE reservation_payment_tx RENAME COLUMN pg_order_id TO pg_order_number;
