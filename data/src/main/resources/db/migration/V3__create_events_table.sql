SET TIME ZONE 'UTC';

-- Append-only event log: the source of truth (event sourcing is the only persistence mode). Every write
-- appends one full-state event here and projects it into the read tables above in the same transaction.
CREATE TABLE events (
    -- The event's own identity, an application-assigned UUID like every other entity (from the IdGenerator
    -- port). It is NOT monotonic, so it does not define the replay order; seq does.
    id uuid NOT NULL PRIMARY KEY,
    -- Append order: a strictly increasing counter the database assigns on insert (may have gaps from
    -- rolled-back inserts), so the log can be replayed in the order the events were appended (the UUID id
    -- cannot give that order).
    seq bigint NOT NULL GENERATED ALWAYS AS IDENTITY,
    -- INSERT, UPDATE, or DELETE (the ChangeType enum, stored as its name).
    change_type varchar(16) NOT NULL CHECK (change_type IN ('INSERT', 'UPDATE', 'DELETE')),
    -- The simple name of the domain type the event concerns (User, CoffeeConsumption).
    entity_type varchar(255) NOT NULL,
    -- The event payload schema version, reserved for evolving the body format.
    entity_version bigint NOT NULL,
    -- The full state of the domain object as JSON (the domain object's own id lives inside the body; a
    -- DELETE carries only the id). jsonb so the column is validated and queryable.
    body jsonb NOT NULL,
    created_at timestamp NOT NULL,
    -- The actor's login name (a member, an admin, or "system"), so a member's changes are retrievable and
    -- displayable without parsing the body or joining to the mutable users read model.
    created_by varchar(255) NOT NULL,
    -- An admin's optional reason for a count override/reset (e.g. the payment that prompted a reset).
    note varchar(500)
);

-- The log is read back in append order on a rebuild (events -> data).
CREATE INDEX idx_events_seq ON events (seq);

-- Look up the events of one domain object by the id embedded in the body (an entity's history); also
-- speeds up checking whether a domain type already has events.
CREATE INDEX idx_events_entity_type ON events (entity_type);
CREATE INDEX idx_events_body_id ON events ((body ->> 'id'));
