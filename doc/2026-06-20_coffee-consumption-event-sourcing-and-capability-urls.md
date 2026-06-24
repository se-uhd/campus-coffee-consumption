# The CoffeeConsumption event sourcing model and the capability URL scheme

> **Partly superseded (noted 2026-06-24).** The event-sourcing and capability-URL design below still holds,
> but some consumption specifics here are out of date: there is no member free `-1` and no reset. A member
> adds one coffee at a time and may undo a recent one within a grace period, and an admin makes an absolute
> count correction (never a `PUT { total: 0 }` reset); settling up is a deposit, not a reset. The member
> header is `X-Capability-Token` (this note's `X-Coffee-Token` is stale), and `events.note` now carries the
> note for every event, not only the count-correction reason. For current behavior see `CLAUDE.md`,
> `README.md`, `doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`, and
> `doc/2026-06-24_security-hardening-and-cookie-auth.md`.

This note records two design decisions in CampusCoffeeConsumption: how a member's coffee count is modeled
as a logged entity on top of the event sourcing machinery inherited from CampusCoffee, and how the secret
capability URLs follow the W3C "Good Practices for Capability URLs" finding.

## CoffeeConsumption as a Review-shaped logged entity

### The model

`CoffeeConsumption` is a normal logged domain object with a `user` property, modeled exactly like
CampusCoffee's `Review` (which held a `pos` and an `author`):

```
CoffeeConsumption(id: UUID, createdAt, updatedAt, user: User, count: Int)
```

There is one consumption per user (a unique `user_id`). Its `count` is the running number of coffees, and
it is the object whose changes the event log records. Optimistic locking lives only in the data layer (the
entity's `@Version` column), exactly as for `Review`; the domain model carries no `version` field. Each
change is a load-modify-save in one transaction, so the loser of two concurrent self-scans gets a
`ConcurrentUpdateException` (409) and the SPA retries.

### Operations reuse the upsert path: no new machinery

Each `+1` / `−1` (a member self-scan or an admin step) and each admin absolute override (any value,
including `0` for a reset after payment) is a plain `upsert` of the member's `CoffeeConsumption` with the
new `count`, which the event-sourced decorator records as a full-state UPDATE event. This is identical to
how a review's approval count advanced through its approval workflow. There is no balance table, ledger,
or row locking. `CoffeeConsumptionService` exposes `applyDelta(userId, delta, actingUser)` and
`setTotal(userId, total, note, actingUser)`; each loads the consumption by user id, applies the new count,
and upserts. Creating a user also creates that user's consumption at `count = 0` (an INSERT event, logged
after the user so the `user_id` foreign key resolves). A `−1` at 0 yields 409 (no negative counts); a
`delta` other than `±1` yields 400.

### Registering it on the event machinery

The generic event sourcing machinery (`EventStore`, `EventSourcedWriter`, `ReadModelProjector`, the
decorators) is unchanged. `CoffeeConsumption` is registered the same way `Review` was: because it
references a `user`, it is flattened to a `userId` in the event body (mirroring how a review flattened its
author to an `authorId`), so a consumption event records a reference rather than a copy of the user (a
copy would leak the user's `passwordHash`).

- `EventJsonMapper` has a `CoffeeConsumptionEventSerializer` writing `id`, `createdAt`, `updatedAt`,
  `userId`, and `count`.
- `ReadModelProjector` has a `COFFEE_CONSUMPTION` branch (`insert` / `update` / `delete`) with a
  `reconstructCoffeeConsumption(body)` that resolves `userId` against the already-projected users read
  table, plus its `DOMAIN_CLASSES` and `DUPLICATION_RULES` entries (the unique `user_id`).
- `EventSourcedCoffeeConsumptionDataService` decorates the relational `CoffeeConsumptionDataServiceImpl`,
  routing `upsert` / `delete` / `clear` through the writer.

### The `created_by` and `note` event metadata

The one change to the generic event infrastructure is two metadata columns on `events` (and `EventEntity`):

- **`created_by`**: the actor's **login name**, a `varchar`: the member via their capability token, the
  admin via their JWT, or `"system"` for the startup fixtures and the bootstrap admin.
- **`note`**: a nullable `varchar`, an admin's free-text reason for an absolute override or a reset (for
  example, the payment that prompted clearing a count).

Both are set at the `EventStore.append*` boundary from small request-scoped context ports: `ActorProvider`
(reads the authenticated principal's login from the `SecurityContext`, or `"system"` when there is no
request principal) and `ChangeNoteContext` (a thread-local the consumption service sets, in a
`try`/`finally`, only around an override or reset). Neither is part of the full-state JSON body, and the
generic writer and decorator signatures are untouched.

`created_by` is a login string rather than a user id on purpose. It is audit metadata shown to humans
(rendered directly in the change-log DTO), it represents the non-user `"system"` actor naturally, and an
append-only log should not foreign key into the mutable users read model: a renamed or deleted user must
not rewrite or break history.

### The change log is read from the event log

A member's transaction history is not a dedicated table. `ConsumptionHistoryDataService` queries the
`events` rows for the consumption (`entity_type = 'CoffeeConsumption'` and `body ->> 'id' = :consumptionId`,
ordered by `seq desc` with `limit` / `offset`; the `idx_events_body_id` index covers `body ->> 'id'`). Each
event body carries the `count` at that time; the event row carries `created_at`, `created_by`, and `note`;
each entry's `delta` is the difference from the previous event. The default page is 5. A reset is not a
deletion: it is a `PUT { total: 0 }` that appends one balancing UPDATE event, so the prior counts stay in
the log.

## The capability URL scheme

A member authenticates only with a secret **capability URL**, a per-member URL that grants the holder the
ability to change that member's coffee count. It is encoded in a wall QR code as
`https://<host>/login/{token}`. The scheme follows the W3C TAG finding
[Good Practices for Capability URLs](https://www.w3.org/TR/capability-urls/) (2014), with one deliberate
deviation.

- **Unguessable, not sequential (§5.2).** The token is a high-entropy random value (256 bits from a
  `SecureRandom`, base64url-encoded), generated by the `CapabilityTokenGenerator` adapter, not a guessable
  or sequential id. The dev fixtures use deterministic seeded tokens for repeatable demos; production
  generates cryptographically random ones.
- **HTTPS only (§5.1).** Capability URLs are `https` in production (Cloud Run TLS); the token never travels
  over plain HTTP. `campus-coffee.app.base-url` carries the public origin used to build the URL.
- **Revocation instead of expiry (the deliberate deviation, §5.1).** The finding recommends expiry, but
  wall-printed QR codes are meant to be long-lived, so the URLs are persistent by design and the app relies
  on admin **rotation / revocation** instead (one token per user; rotating issues a new URL and invalidates
  the old QR). A rotated or unknown token fails authentication (401); a deactivated member is read-only
  (mutations return 403, but reads still work, so the account is kept rather than deleted).
- **Permissions, not actions; no side effects on GET (§5.1).** Opening `/login/{token}` (the SPA route)
  only loads the page; every data change is a `POST`/`PUT` API call, so scanning or opening the URL never
  mutates anything.
- **Minimize leakage (§5.1).** The token appears in a URL only at the SPA entry point (`/login/{token}`),
  never in an API path (the SPA forwards it as the `X-Coffee-Token` header), so it stays out of API server
  and proxy access logs. The token page should avoid third-party scripts and assets and send
  `Referrer-Policy: no-referrer` (and `rel="noreferrer"` on outbound links) so the URL cannot leak via the
  `Referer` header, and should be kept out of analytics.
- **Keep crawlers out (§5.1).** Disallow the `/login/` path in `robots.txt` and send `X-Robots-Tag:
  noindex` on the token page (list the path, never individual URLs).
- **Tell users the risk (§5.3).** The profile page presents the URL as "your coffee link" with a
  plain-language note that anyone holding it can change the member's coffee count, so it should not be
  shared or posted.

### Why one mechanism per audience

Members authenticate only with the capability token; admins only with a JWT. CampusCoffee shipped HTTP
Basic and JWT to teach both, but an SPA plus capability links needs just one mechanism each, so HTTP Basic
was dropped. The capability token principal is always `ROLE_USER`, never `ROLE_ADMIN`, so an admin's own
token grants only self-service; admin operations require the JWT. The JWT is a work-session token (~10-hour
TTL) with no refresh flow: over-engineering for an internal, few-admin tool, and a refresh token stored in
the SPA would not reduce the real (XSS) risk. If long, revocable sessions are ever wanted, the cleaner
upgrade for this same-origin SPA is a server-side httpOnly session cookie, not a refresh token.
