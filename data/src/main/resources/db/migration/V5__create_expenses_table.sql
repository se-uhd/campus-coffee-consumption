SET TIME ZONE 'UTC';

-- A recorded purchase of coffee beans (the projected read model row). All money is in euro cents.
CREATE TABLE expenses (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    -- the member who bought the beans. No ON DELETE clause, so the default NO ACTION (RESTRICT) blocks
    -- deleting a member who has expenses: financial history is preserved, and the domain service refuses
    -- the delete with a 409 before it ever reaches the database.
    buyer_user_id uuid NOT NULL REFERENCES users(id),
    weight_grams int NOT NULL CHECK (weight_grams >= 0),
    amount_cents int NOT NULL CHECK (amount_cents >= 0),
    -- the split: the portion paid from the buyer's own pocket (credits the buyer) and the portion paid
    -- from the communal kitty (draws the kitty down). Both non-negative.
    private_amount_cents int NOT NULL CHECK (private_amount_cents >= 0),
    kitty_amount_cents int NOT NULL CHECK (kitty_amount_cents >= 0),
    note varchar(500),
    -- @Version optimistic-locking column; declared NOT NULL with a default so a fresh insert starts at 0
    version bigint NOT NULL DEFAULT 0,
    -- the split must always cover exactly the total. The domain service validates this before the upsert
    -- (a 400); this CHECK is a backstop only.
    CONSTRAINT ck_expenses_split CHECK (private_amount_cents + kitty_amount_cents = amount_cents)
);

-- Look up a buyer's expenses (the per-member ledger reads the events, but the relational lookups and the
-- foreign key both benefit from indexing the buyer).
CREATE INDEX idx_expenses_buyer_user_id ON expenses (buyer_user_id);
