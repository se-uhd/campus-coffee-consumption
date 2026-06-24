SET TIME ZONE 'UTC';

CREATE TABLE coffee_prices (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    amount_cents int NOT NULL CHECK (amount_cents >= 0),
    version bigint NOT NULL DEFAULT 0,
    is_singleton boolean NOT NULL DEFAULT true CHECK (is_singleton),
    CONSTRAINT uq_coffee_prices_singleton UNIQUE (is_singleton)
);
