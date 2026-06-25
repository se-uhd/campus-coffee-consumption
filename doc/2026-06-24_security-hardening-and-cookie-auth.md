# Security hardening and cookie-based admin sessions (2026-06-24)

This note records the changes made after an adversarial review of the codebase. The headline is a rework of
**how admins authenticate** (from a JWT held in `localStorage` to an httpOnly cookie session), so that is
covered first and in the most depth; the remaining hardening and correctness work follows.

## Authentication and authorization

### The problem with the previous model

The admin JWT was returned in the login response body and stored in `window.localStorage`. Any script
running on the origin can read `localStorage`, so a single cross-site-scripting (XSS) foothold anywhere in
the SPA (or in a dependency) could read the live admin bearer token and exfiltrate it. The token authorizes
every `/api/users/**`, `/api/price/**`, and `/api/kitty/**` operation, so that is a high-value theft. The
guard also only checked for the presence of a token string, never its expiry.

### The new model: an httpOnly cookie session

On a successful login the token endpoint now sets the JWT in a cookie with these attributes:

- **httpOnly**: JavaScript cannot read it, so an XSS cannot exfiltrate the stored session. This is the core
  of the change.
- **SameSite=Strict**: the browser never sends the cookie on a cross-site request, which is the CSRF defense
  (see below).
- **Secure** in any real deployment (`campus-coffee.auth.cookie.secure`, default `true`; the dev profile,
  served over plain http on localhost, sets it `false` so the cookie is usable there).
- **Path=/**, with a lifetime matching the token TTL.

The resource server reads the bearer token from **either** the cookie **or** the `Authorization: Bearer`
header, via `CookieOrHeaderBearerTokenResolver`. The header takes precedence when both are present. This lets
two flows coexist on one resource server:

- the **browser SPA** authenticates with the cookie (it sends no token header and stores no token);
- **programmatic API clients and the system/e2e tests** authenticate with the `Authorization` header.

The SPA keeps only a non-sensitive marker in `localStorage` so the admin guard knows a session is active
across reloads. That marker is not a credential (it carries no token), so reading it gains an attacker
nothing. The real authority is the cookie, enforced server-side: if the cookie is missing or expired, the
request 401s and the interceptor signs out and redirects to the login form. This also fixes the old
"expired token still passes the guard" gap, since expiry is now enforced by the server on every request
rather than decoded (or not) on the client.

A `POST /api/auth/logout` endpoint clears the cookie (the SPA cannot clear an httpOnly cookie itself).

#### A deliberate residual

The token endpoint still returns the JWT in the response body, for the header-based API and test clients. The
SPA ignores that body and does not persist it, so there is no token in JavaScript-readable storage. The
remaining exposure is narrow: an XSS active during the single login XHR could read that one response. Removing
the body token entirely would force every system and e2e test to extract the JWT from the `Set-Cookie`
header instead, a large test change for a small marginal gain; the body token is retained as the documented
API contract and the SPA's non-use of it is what delivers the security benefit.

### CSRF

The cookie reintroduces the question of cross-site request forgery, which the previous header-only model was
immune to. The defense here is **SameSite=Strict**: the browser does not attach the cookie to any cross-site
request, so a malicious page cannot ride the admin's session. Spring's token-based CSRF protection was
deliberately **not** enabled, because it would impose a CSRF token on the header-authenticated, already
CSRF-immune flows (the member `X-Capability-Token` endpoints and the `Authorization`-header API/test clients)
for no added protection on a same-origin SPA, and would break the system tests. SameSite=Strict on a
same-origin cookie is a complete CSRF defense for this surface.

### Content-Security-Policy

A CSP is now sent on every response. All of the SPA's resources are same-origin (there are no CDN scripts,
fonts, or styles), so the policy is tight: `default-src 'self'`, `script-src 'self'`, `connect-src 'self'`
(an injected script cannot load from or exfiltrate to another origin), `object-src 'none'`, `base-uri
'self'`, and `frame-ancestors 'none'` (clickjacking). `style-src` allows `'unsafe-inline'` because Angular
Material injects runtime `<style>` tags; `img-src`/`font-src` allow `data:`/`blob:` for the QR image blobs.
The CSP is the structural mitigation that makes the residual `localStorage` marker and the login-XHR window
much harder to exploit.

### Login-payload replay protection

The login credentials are sent as a compact JWE (`RSA-OAEP-256` + `A256GCM`); that was already in place. The
encrypted JSON now also carries an `iat` (client-set epoch-millis timestamp), and `LoginPayloadDecryptor`
rejects a payload whose `iat` is further than `campus-coffee.login-encryption.max-payload-age` (default two
minutes) from the server clock, in either direction to tolerate skew. This bounds the window in which a
captured ciphertext could be replayed against the token endpoint. A stale payload is a 400
(`LoginPayloadException`), the same as any other unreadable payload, so it is not a credential oracle.

### Smaller authorization fixes

- `GET /api/price` now resolves the live admin via `CurrentUserProvider`, so a deactivated or demoted admin's
  in-flight JWT is rejected there too, matching every other admin endpoint.
- `PublicBaseUrlGuard` (prod) now rejects a loopback or bare-hostname base URL, not just an empty or
  non-https one, so a misconfigured `https://localhost` base cannot bake unreachable links into the wall QR
  codes. The stale `ProdConfigGuard` references in `application.yaml` were corrected to the real class name.
- `WeakDevSecretGuard` (prod) fails startup if the committed dev-only fallback JWT secret or RSA login key is
  in effect, so a stray `SPRING_PROFILES_ACTIVE=dev` (or a copied fallback) cannot run a public deployment
  with public credentials.

## Secrets and deployment

- `.dockerignore` now excludes `deploy*.env` (it previously excluded only `deploy.env`), so `deploy.prod.env`
  no longer enters the build context that `gcloud run compose up` uploads to Cloud Build.
- The deploy env files are `chmod 600`, and `scripts/deploy-cloudrun.sh` runs at `umask 077` and chmods what
  it writes.
- The prod app runs as a **least-privilege** database role (`campus_coffee_app`), provisioned once with
  `scripts/sql/create-app-role.sql`, instead of the Cloud SQL `postgres` superuser. `deploy.env.example` and
  the deploy script document sourcing the secrets from Secret Manager.

### Auth secrets in the event log (accepted)

The append-only `events` log stores the full state of each `User` event, including the `capability_token` and
the bcrypt `password_hash` (the raw password is stripped via `UserSecretsMixin`, but the hash and token are
kept so a rebuild from the log can still authenticate). So the log retains superseded values: an old
capability token after a rotation, an old hash after a password change. This is accepted rather than encrypted
in the log, for three reasons: it is no new exposure beyond the access-controlled `users` read table, which
holds the same secrets; a rotated capability token no longer authenticates anyone (auth checks the current
token in `users`), so an old token in the log grants no access; and a bcrypt hash is designed to be stored.
Field-level encryption of the event body would add key management and a rebuild that decrypts, for little gain
at this threat model. If the log is ever exported beyond the database trust boundary, revisit this.

## Money read model: a maintained balance projection

The hottest member reads used to replay event streams on every call: `GET /summary` walked the whole global
payment-and-expense stream just to read the kitty balance, and the per-member overview replayed each
member's whole stream. There are now two maintained read-model projections, `member_balance` (keyed by
member) and `kitty_balance` (a single row), updated inside the same transaction as every money write by
recomputing from the existing event-log walks (so the materialized value can never drift from the
authoritative walk). The member overview and the kitty-overdraw guard now read one indexed row instead of
replaying a stream. The events-to-data rebuild recomputes both projections after replaying the log, and it
now replays in bounded `seq`-ordered batches rather than loading the whole log into memory.

The single-row `kitty_balance` table uses the idiomatic `id integer PRIMARY KEY DEFAULT 1 CHECK (id = 1)`
single-row pattern. (A `coffee_prices`-style projected entity stays its own table; these two derived caches
are kept separate because one is keyed per member and the other is a global scalar.)

## Event-log note handling, unified

A deposit, kitty-adjustment, or expense note lives in that entity's own event body (it is entity state),
while an admin count-correction reason is pure event metadata with no entity field. Both are now written to
the generic `events.note` column at the single append boundary (from the `ChangeNoteContext` reason if
present, else the body's note), so `events.note` is the one canonical, queryable note for every event and the
activity feed reads one field. Previously the activity and kitty feeds read only the metadata column, so the
notes on three of the four note-bearing operations were silently dropped from the read API.

## Other correctness fixes

- `PageQuery.offset` is bounded (it was only floored), `/price/history` is paged, and the
  `GlobalExceptionHandler` no longer echoes framework exception class names or parser detail in any profile.
- An admin expense rejects a zero total (matching the member path); a member's email is normalized so the
  case-sensitive unique constraint catches case-variant duplicates.
- The concurrent first-ever price write surfaces a clean 409 instead of a recovery that a real Postgres
  transaction cannot perform (the constraint violation aborts the transaction); the bootstrap seeder tolerates
  the seed race with a fresh read.
- The member self-scan retries a 409 a bounded number of times with backoff, and the admin profile edit no
  longer re-posts a stale `role`/`active` snapshot over a concurrent admin's change.
