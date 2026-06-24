CREATE TABLE member_balance (
    user_id uuid PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    balance_cents bigint NOT NULL DEFAULT 0
);

CREATE TABLE kitty_balance (
    id integer PRIMARY KEY DEFAULT 1,
    balance_cents bigint NOT NULL DEFAULT 0,
    CONSTRAINT ck_kitty_balance_singleton CHECK (id = 1)
);
