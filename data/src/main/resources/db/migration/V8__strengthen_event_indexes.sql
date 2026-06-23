-- Strengthen the event-log indexes (review M9, L13).

-- A composite (entity_type, seq) index so a type-filtered, seq-ordered stream read (the kitty walk over
-- payments + expenses, the price history, any single type's events) is served from one index instead of a
-- type-index scan followed by a sort. It also covers entity_type-only lookups (its leftmost prefix).
CREATE INDEX idx_events_type_seq ON events (entity_type, seq);

-- seq is the authoritative replay/valuation order, generated monotonically by the identity column, but an
-- IDENTITY column creates no implicit unique index, so uniqueness/monotonicity was enforced only by the
-- generator. Enforce it at the schema level. The unique index doubles as the seq ordering index, so the old
-- plain idx_events_seq is redundant; replace it in place (the name is reused for the unique version).
DROP INDEX idx_events_seq;
CREATE UNIQUE INDEX idx_events_seq ON events (seq);
