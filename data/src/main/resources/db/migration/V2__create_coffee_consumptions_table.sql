SET TIME ZONE 'UTC';

-- A member's running coffee count (the projected read model row). Modeled on CampusCoffee's reviews
-- table: a single user reference, one row per user, and a version column for optimistic locking.
CREATE TABLE coffee_consumptions (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    -- every user has exactly one consumption (created with them); ON DELETE CASCADE so deleting a user
    -- removes their consumption row, exactly as CampusCoffee cascaded review_approvals when a review was
    -- deleted. A user-DELETE event cascades the row at runtime and on a rebuild (the replay applies the
    -- DELETE in append order), so no per-consumption DELETE event is needed.
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    count int NOT NULL DEFAULT 0 CHECK (count >= 0),
    -- @Version optimistic-locking column; declared NOT NULL with a default so a fresh insert starts at 0
    version bigint NOT NULL DEFAULT 0,
    -- one consumption per user; named so a violation maps to a 409 on user_id
    CONSTRAINT uq_coffee_consumptions_user UNIQUE (user_id)
);
