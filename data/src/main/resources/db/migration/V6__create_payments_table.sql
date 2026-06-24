SET TIME ZONE 'UTC';

CREATE TABLE payments (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    user_id uuid REFERENCES users(id),
    amount_cents int NOT NULL,
    note varchar(500),
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX idx_payments_user_id ON payments (user_id);
