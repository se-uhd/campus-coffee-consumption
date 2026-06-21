SET TIME ZONE 'UTC';

-- A movement of money into the communal kitty (the projected read model row). All money is in euro cents.
CREATE TABLE payments (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    -- the member who paid in (a settlement), or NULL for a pure kitty adjustment (initial float /
    -- correction). No ON DELETE clause, so the default NO ACTION (RESTRICT) blocks deleting a member who
    -- has settlements; the domain service refuses the delete with a 409 first.
    user_id uuid REFERENCES users(id),
    -- signed: a settlement is positive; a kitty adjustment may be negative to correct the kitty down.
    amount_cents int NOT NULL,
    note varchar(500),
    -- @Version optimistic-locking column; declared NOT NULL with a default so a fresh insert starts at 0
    version bigint NOT NULL DEFAULT 0
);

-- Look up a member's settlements; also lets the database check the foreign key efficiently.
CREATE INDEX idx_payments_user_id ON payments (user_id);
