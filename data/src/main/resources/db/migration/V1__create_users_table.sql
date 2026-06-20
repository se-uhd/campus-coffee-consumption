SET TIME ZONE 'UTC';

CREATE TABLE users (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    login_name varchar(255) NOT NULL CHECK (login_name <> ''),
    email_address varchar(254) NOT NULL CHECK (length(email_address) > 2), -- https://stackoverflow.com/a/574698/1974143, https://stackoverflow.com/a/1423203/1974143
    first_name varchar(255) NOT NULL CHECK (first_name <> ''),
    last_name varchar(255) NOT NULL CHECK (last_name <> ''),
    -- a single role per user (no user_roles collection table); the CHECK keeps it in step with the enum
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    -- whether the account may still mutate its count; a deactivated member authenticates read-only
    active boolean NOT NULL DEFAULT true,
    -- BCrypt via the delegating encoder stores a prefixed hash ("{bcrypt}$2a$10$...", ~68 chars), so the
    -- column is text. Nullable because a User can be constructed before a hash is set.
    password_hash text,
    -- the member's secret capability token (the unguessable value embedded in their QR/coffee link)
    capability_token varchar(255) NOT NULL CHECK (capability_token <> ''),
    -- explicitly named so the application can map a violation to the offending user field
    CONSTRAINT uq_users_login_name UNIQUE (login_name),
    CONSTRAINT uq_users_email_address UNIQUE (email_address),
    CONSTRAINT uq_users_capability_token UNIQUE (capability_token)
);
