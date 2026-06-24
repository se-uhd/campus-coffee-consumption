SET TIME ZONE 'UTC';

CREATE TABLE users (
    id uuid NOT NULL PRIMARY KEY,
    created_at timestamp NOT NULL,
    updated_at timestamp NOT NULL,
    login_name varchar(255) NOT NULL CHECK (login_name <> ''),
    email_address varchar(254) NOT NULL CHECK (length(email_address) > 2),
    first_name varchar(255) NOT NULL CHECK (first_name <> ''),
    last_name varchar(255) NOT NULL CHECK (last_name <> ''),
    role varchar(20) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    active boolean NOT NULL DEFAULT true,
    password_hash text,
    capability_token varchar(255) NOT NULL CHECK (capability_token <> ''),
    version bigint NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_login_name UNIQUE (login_name),
    CONSTRAINT uq_users_email_address UNIQUE (email_address),
    CONSTRAINT uq_users_capability_token UNIQUE (capability_token)
);
