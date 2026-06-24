SET TIME ZONE 'UTC';

CREATE TABLE expenses (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    buyer_user_id uuid NOT NULL REFERENCES users(id),
    weight_grams int NOT NULL CHECK (weight_grams >= 0),
    amount_cents int NOT NULL CHECK (amount_cents >= 0),
    private_amount_cents int NOT NULL CHECK (private_amount_cents >= 0),
    kitty_amount_cents int NOT NULL CHECK (kitty_amount_cents >= 0),
    note varchar(500),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_expenses_split CHECK (private_amount_cents + kitty_amount_cents = amount_cents)
);

CREATE INDEX idx_expenses_buyer_user_id ON expenses (buyer_user_id);
