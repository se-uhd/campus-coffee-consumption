SET TIME ZONE 'UTC';

CREATE TABLE coffee_consumptions (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    count int NOT NULL DEFAULT 0 CHECK (count >= 0),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_coffee_consumptions_user UNIQUE (user_id)
);
