CREATE TABLE reservation_creation_records (
    user_id BIGINT NOT NULL CHECK (user_id > 0),
    request_key VARCHAR(128) NOT NULL,
    request_payload TEXT NOT NULL,
    reservation_id BIGINT REFERENCES reservations(id),
    response JSONB,
    PRIMARY KEY (user_id, request_key),
    CHECK ((reservation_id IS NULL) = (response IS NULL))
);
