SET TIME ZONE 'UTC';

CREATE TABLE coffee_ratings (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    bean_id uuid NOT NULL REFERENCES coffee_beans(id),
    value int NOT NULL CHECK (value BETWEEN 1 AND 5),
    version bigint NOT NULL DEFAULT 0
);

CREATE INDEX idx_coffee_ratings_user_created ON coffee_ratings (user_id, created_at);
CREATE INDEX idx_coffee_ratings_bean ON coffee_ratings (bean_id);
