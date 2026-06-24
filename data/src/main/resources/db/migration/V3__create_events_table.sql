SET TIME ZONE 'UTC';

CREATE TABLE events (
    id uuid NOT NULL PRIMARY KEY,
    seq bigint NOT NULL GENERATED ALWAYS AS IDENTITY,
    change_type varchar(16) NOT NULL CHECK (change_type IN ('INSERT', 'UPDATE', 'DELETE')),
    entity_type varchar(255) NOT NULL,
    entity_version bigint NOT NULL,
    body jsonb NOT NULL,
    created_at timestamp NOT NULL,
    created_by varchar(255) NOT NULL,
    note varchar(500)
);

CREATE UNIQUE INDEX idx_events_seq ON events (seq);
CREATE INDEX idx_events_type_seq ON events (entity_type, seq);
CREATE INDEX idx_events_entity_type ON events (entity_type);
CREATE INDEX idx_events_body_id ON events ((body ->> 'id'));
CREATE INDEX idx_events_body_user_id ON events ((body ->> 'userId'));
CREATE INDEX idx_events_body_buyer_id ON events ((body ->> 'buyerUserId'));
