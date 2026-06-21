SET TIME ZONE 'UTC';

-- The single global price per cup, in euro cents (the projected read model row). Event-sourced like every
-- other entity: one row, created once at bootstrap and updated in place on each admin change, while the
-- append-only event log keeps every past price (the price history). Singleton-ness is enforced by the
-- service (the price is only ever created if none exists, and thereafter only updated), not by the schema.
CREATE TABLE coffee_prices (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    amount_cents int NOT NULL CHECK (amount_cents >= 0),
    -- @Version optimistic-locking column; declared NOT NULL with a default so a fresh insert starts at 0
    version bigint NOT NULL DEFAULT 0,
    -- hard single-row guard: a sentinel column fixed to true with a unique constraint, so even a racing
    -- double-seed (e.g. two instances starting at once) cannot create a second, competing price row. The
    -- entity does not map this column; the database default supplies it on insert.
    is_singleton boolean NOT NULL DEFAULT true CHECK (is_singleton),
    CONSTRAINT uq_coffee_prices_singleton UNIQUE (is_singleton)
);
