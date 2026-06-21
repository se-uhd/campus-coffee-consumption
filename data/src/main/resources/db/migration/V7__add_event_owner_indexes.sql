SET TIME ZONE 'UTC';

-- The per-member unified ledger and the balance read the events of all of a member's streams by the owning
-- user id embedded in the event body. The existing idx_events_body_id covers body ->> 'id' (an entity's
-- own id); these cover the owner keys: payments and consumptions carry the owner as 'userId', expenses as
-- 'buyerUserId'. A pure kitty adjustment stores 'userId' as JSON null, which these indexes simply skip, so
-- it never matches a member.
CREATE INDEX idx_events_body_user_id ON events ((body ->> 'userId'));
CREATE INDEX idx_events_body_buyer_id ON events ((body ->> 'buyerUserId'));
