SET TIME ZONE 'UTC';

CREATE TABLE coffee_beans (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    name varchar(200) NOT NULL,
    active boolean NOT NULL DEFAULT true,
    merged_into_id uuid REFERENCES coffee_beans(id),
    version bigint NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX uq_coffee_beans_active_name ON coffee_beans (lower(name)) WHERE merged_into_id IS NULL;
