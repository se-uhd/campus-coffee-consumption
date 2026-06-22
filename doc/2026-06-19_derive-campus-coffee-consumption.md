# CampusCoffee → CampusCoffeeConsumption (SE@UHD)

> **SUPERSEDED historical record (2026-06-19).** This is the original derivation plan, kept verbatim as a
> dated record. Many specifics here are now out of date — the member route is `/login/{token}` (not
> `/coffee/{token}`), the admin routes are consolidated under `/admin/**`, the `+`/`−` step and the reset
> were replaced by add-one-coffee plus a grace-period undo and an absolute count correction, and the
> frontend toolchain is Vitest + Playwright (not Karma/Jasmine/Cypress). For the current design and
> behavior, see the later records
> `doc/2026-06-20_coffee-consumption-event-sourcing-and-capability-urls.md` and
> `doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`, and `CLAUDE.md` / `README.md`.

## Context

CampusCoffee is a Spring Boot / Kotlin app (hexagonal architecture, four Gradle modules, event sourcing
persistence, Spring Security with Basic + JWT) for managing Points of Sale and reviews. We are deriving a
new app, **CampusCoffeeConsumption**, into this repo (`campus-coffee-consumption`).

The app is the coffee consumption tracker for **SE@UHD** (the Software Engineering Group at Heidelberg
University — hence the `de.seuhd` package). Each group member has a running coffee count. Members bump
their own count via a **capability URL** (a secret per-user URL) printed as a **QR code on the wall**;
scanning it opens a small mobile-first web app where they tap **+** / **−**. Admins create and manage
members, can adjust anyone's count, and **reset** a count to zero once that member has paid for their
coffee. Every change is recorded in an append-only **event log** — the only persistence model (the
relational-only mode is dropped). Intended outcome: reuse CampusCoffee's architecture, event sourcing
machinery, build, test, and security scaffolding **unchanged**; drop everything POS/Review/OSM; add the
consumption domain and an Angular frontend.

### Decisions locked in with the user
- **Auth — one mechanism per audience.** Members authenticate **only** with their secret capability token;
  admins authenticate **only** with a **JWT** (minted from a username+password login). **No HTTP Basic**
  (CampusCoffee shipped Basic + JWT to teach both; an SPA + capability links need just one each). The
  capability token reaches the API in a **header (`X-Coffee-Token`), never in an API URL path** — the secret
  appears in a URL only at the SPA/QR entry point (`/coffee/{token}`), and the SPA forwards it as a header on
  clean, resource-shaped POST/GET endpoints (keeps the token out of API access logs).
- **Event sourcing stays identical to CampusCoffee.** The consumption domain object is **`CoffeeConsumption`**,
  a normal logged entity **with a `user` property**, modeled exactly like `Review` (full-state events; one
  projected read table). The changes to the generic event infrastructure are two metadata columns on
  `events`: **`created_by`** (the actor's **login name** as a string — the member via their token, the
  admin, or `"system"` for startup fixtures/bootstrap — so a member's changes are retrievable, and
  displayable, without parsing the JSON body or joining back to the mutable `users` read model) and a
  nullable **`note`** (an admin's free-text reason for an absolute override / a reset after payment). Both
  are event metadata, set at the `EventStore` boundary from a request-scoped actor/note context, not part
  of the full-state JSON body; the generic mutator/decorator signatures stay untouched.
- **Capability token:** stored (not just hashed) so an admin can re-display and re-print it, and
  **rotatable** on demand (rotating issues a new URL and invalidates the old QR).
- **Frontend:** a small, reactive, mobile-first Angular SPA modeled on
  `/Users/sebastian/Git/teaching/ase24-taskboard/frontend` (latest stable Angular, standalone components,
  Angular Material, `HttpClient` services, dev `proxy.conf.json`), **bundled into the Spring Boot app's
  `static/` resources** and built from Gradle — one Cloud Run image, no CORS in prod.
- **QR codes:** generated in the **backend**; a single **high-resolution PNG** (prints and embeds
  everywhere — Docs/Slides/Word, label printers, non-technical members). A 1024px PNG covers large-format
  printing too, so the SVG option was dropped as unnecessary for an internal tool. Each profile page shows
  the full capability URL (as "your coffee link") and a QR download; admins can download any member's QR.
- **Admin UI:** the landing page is shared. A signed-in admin sees their own consumption by default, can
  **select any user**, enter **edit mode** (set the total directly), and use a **reset** button (hidden
  from regular users). Admins get the same profile page as users, plus a separate **user-admin page**
  (roles, view/rotate capability URLs, download QR, create/deactivate/delete users).
- **Prod:** PostgreSQL via **Cloud SQL**; local dev and tests via Postgres / Testcontainers.
- **Consumption is a resource, not an action surface (refined 2026-06-20).** `/consumption` *is* the
  change log; the running `total` is a derived field in the response. Paths name resources and the HTTP
  method carries the semantics — `GET` reads a page of changes (safe), `POST { delta: ±1 }` appends one
  step (non-idempotent; PUT would be wrong because incrementing is not idempotent and infrastructure may
  retry it), `PUT { total, note? }` sets the absolute total (idempotent; admin-only, and `{ total: 0 }` is
  the reset after payment). No `/increment`, `/decrement`, `/reset`, `/transactions`, or `DELETE`. A member
  steps by ±1 only; any other value is the admin's `PUT`. Also refined the same day: drop the domain
  `version` field (entity `@Version` only); a single high-res PNG QR (no SVG); a JWT TTL of ≈10 h; and the
  admin `note` recorded on overrides/resets.

---

## Target architecture

Keep the hexagonal four-module Gradle layout + the `coverage` module, ArchUnit layer rules, build-logic
convention plugins, mise (Java 25, Gradle 9.5; add Node), ktlint/detekt, JaCoCo gate, PITest, Testcontainers,
Cucumber. Add a **`frontend/`** Angular project (sibling of the modules). Keep the base package
`de.seuhd.campuscoffee` and the `campus-coffee.*` config prefix; rename only the Gradle root project and
`spring.application.name` to `campus-coffee-consumption`. **Persistence is event sourcing only.**

### Domain model (`domain` module)
- **`User`** — `id: UUID`, `createdAt`, `updatedAt`, `loginName` (unique username), `firstName`,
  `lastName`, `emailAddress`, `role: Role`, `active: Boolean`, `capabilityToken: String` (unique),
  `passwordHash: String?`, `password: String?` (input only; only admins set one).
- **`Role`** — simplify to `{ USER, ADMIN }` (drop `MODERATOR` and the `Set<Role>` / `user_roles`
  collection table; a single `role` column). Authorities `ROLE_USER` / `ROLE_ADMIN`.
- **`CoffeeConsumption`** — a logged domain object **with a `user` property**, modeled exactly like
  `Review` (which holds `pos`/`author`): `id: UUID`, `createdAt`, `updatedAt`, `user: User`, `count: Int`.
  One per user (unique `user_id`). Its `count` is what `+1`/`−1`/override/reset change; this is the object
  whose changes the event log records. Optimistic locking lives **only in the data layer** (the entity's
  `@Version` column), exactly as for `Review` — the domain model carries no `version` field; each change is
  a load-modify-save in one transaction, so the loser of a concurrent self-scan gets a
  `ConcurrentUpdateException` (409) and the SPA retries.
- Keep the relevant domain exceptions (`NotFoundException`, `DuplicationException`, `ValidationException`,
  `MissingFieldException`, `ConcurrentUpdateException`, `ForbiddenException`, `DeletionConflictException`).
  Drop `ExternalServiceException` unless still used.
- Ports: keep generic `CrudService`/`CrudDataService`. Add `UserService`, `CoffeeConsumptionService` (api
  ports) and `UserDataService`, `CoffeeConsumptionDataService` (data ports; the latter adds
  `getByUserId`). Drop `PosService`, `ReviewService`, `OsmDataService`, etc. Keep `IdGenerator` and the
  password-hasher port.

### Event sourcing — the generic machinery is unchanged; two additions
`EventStore` appends full-state INSERT/UPDATE/DELETE events; `EventSourcedMutator.upsert(domain, getById,
buildForInsert, buildForUpdate)` assigns id/timestamps, appends, and projects in one transaction;
`ReadModelProjector` writes each entity's read table via its MapStruct mapper + repository; the
event-sourced **decorators** (`: …DataService by delegate`) route writes through the mutator and delegate
reads. We keep all of this. Two changes vs CampusCoffee:

1. **`created_by` (and `note`) metadata on the generic event.** Add two columns to `events` and
   `EventEntity`: `created_by` (the actor's **login name**, a `varchar`) and a nullable `note`. Both are
   set in `EventStore.append*` from small **request-scoped** context ports — an `ActorProvider` (reads the
   authenticated principal's login from the `SecurityContext`: the member via their token, or the admin;
   `"system"` when there is no request principal, i.e. startup fixtures/bootstrap) and a `ChangeNoteContext`
   (a thread-local the consumption service sets, in a `try/finally`, only around an admin override/reset).
   `created_by` is a login string, not a user UUID — it is audit metadata shown to humans (rendered
   directly in the change-log DTO), it represents the non-user `"system"` actor naturally, and an
   append-only log should not foreign key into the mutable `users` read model (a renamed/deleted user must
   not rewrite or break history). The mutator/decorator signatures are untouched, and neither column is part
   of the full-state JSON body. This makes "all of a member's changes" — with who and (for overrides) why —
   retrievable from the log without parsing the body.
2. **Register `CoffeeConsumption` as a logged entity — a copy of the `Review` wiring** (it references a
   `user`, so it is flattened to an id exactly as `Review` flattens `author`):
   - add a `CoffeeConsumptionEventSerializer` to `EventJsonMapper` flattening `user` → `userId` (mirrors
     `ReviewEventSerializer`'s `author` → `authorId`);
   - add a `COFFEE_CONSUMPTION` branch to `ReadModelProjector` (`insert`/`update`/`delete`) with a
     `reconstructCoffeeConsumption(body)` resolving `userId` against the users read table (mirrors
     `reconstructReview`), plus its `DOMAIN_CLASSES` and `DUPLICATION_RULES` entries (unique `user_id`);
   - add `EventSourcedCoffeeConsumptionDataService(delegate, mutator) : CoffeeConsumptionDataService by
     delegate` overriding `upsert`/`delete`/`clear` — a copy of `EventSourcedReviewDataService`.
   - Dropping the persistence-mode toggle: remove the `@ConditionalOnProperty(... "event-sourcing")` from
     every decorator (they become the only adapters), delete `PersistenceProperties`/`PersistenceMode` and
     `DataToEventsRunner` (no legacy relational DB to adopt). Optionally keep an events-to-data rebuild
     runner behind a flag (an ES demonstration: rebuild the read model from the log).

### Consumption operations — no new machinery
Each `+1` / `−1` (member or admin) and each admin absolute override (any value, including `0` for a reset
after payment) is a plain `upsert` of the member's `CoffeeConsumption` with the new `count`, which the
decorator records as a **full-state UPDATE event** — identical to how `Review.approvalCount` advances
through the approval workflow. `CoffeeConsumptionService` exposes `applyDelta(userId, delta=±1, actingUser)`
and `setTotal(userId, total, note, actingUser)`: each loads the `CoffeeConsumption` by `userId`, applies
the new `count`, `upsert(copy(count = new))`, and returns the updated `CoffeeConsumption` (the controller
assembles the `ConsumptionDto`). Creating a user also creates that user's `CoffeeConsumption` at `count = 0`
(an INSERT event, logged after the user for FK order). Concurrent self-scans are handled by the existing
**`@Version`** optimistic-locking path on the entity → `ConcurrentUpdateException` (409); the SPA retries.
No balance/ledger tables, no row locks. A `−1` at 0 → 409 (no negative counts); a `delta` other than ±1 →
400.

### Read model & history (`data` module, projected from the log)
- `users` table (+ `role`, `active`, `capability_token`); no `user_roles` table.
- One projected read table **`coffee_consumptions`** (`id`, `created_at`, `updated_at`, `user_id` unique FK,
  `count`, `version`). The `version` column backs the entity's `@Version` (data-layer optimistic locking;
  the domain model has no version field). Repository `findByUserId`. Current count = that row's `count`
  (O(1) reads). Entity + MapStruct `CoffeeConsumptionEntityMapper`, `CoffeeConsumptionDataServiceImpl`
  extending `CrudDataServiceImpl`, exactly per the CLAUDE.md "Adding a New Entity" recipe.
- **The change log** comes straight from the **event log** via a `ConsumptionHistoryDataService`: query
  `events` where `entity_type = 'CoffeeConsumption'` and `body ->> 'id' = :consumptionId` ordered by
  `seq desc` with `limit`/`offset` (the existing `idx_events_body_id` already indexes `body ->> 'id'`; the
  default page is 5). Each event body carries the `count` at that time; the event row carries `created_at`,
  `created_by`, and `note`; each entry's `delta` is the diff to the previous event. This is the "full
  trail … based on the event log."

---

## REST API (build this first)

All paths under `/api` (central `ApiPathConfig`). JSON only.

**The consumption resource is the change log, not an action surface.** `/consumption` *is* the list of a
member's coffee changes; the current `total` is a derived field carried in the response for convenience.
Paths therefore name resources, not verbs — there are **no** `/increment`, `/decrement`, `/reset`, or
`/transactions` sub-paths. The HTTP method carries the semantics: `GET` reads (safe), `POST` appends a
single-step `±1` change (non-idempotent), `PUT` sets the absolute total (idempotent). A member may only
step by one; any other value is the admin's `PUT`.

**Response DTOs** — Kotlin `data class`es in `api/.../dtos/` (plain, like the existing `TokenResponseDto`;
no `Dto<ID>` base since they carry no entity id):
- `ConsumptionDto(total: Int, changes: List<ConsumptionChangeDto>)` — the one shape returned by **every**
  consumption endpoint (the read view *and* every mutation): the authoritative current `total` plus a page
  of the change log (newest first), so one request paints the whole view and a mutation needs no follow-up
  read. The SPA shows an optimistic +1/−1 and reconciles to the returned `total` (re-reading on a 409).
- `ConsumptionChangeDto(count: Int, delta: Int, createdAt: LocalDateTime, createdBy: String, note: String?)`
  — one entry in the change log, built from the event rows (`delta` = diff to the previous event;
  `createdBy` = the actor's login; `note` = an admin's reason for an override/reset, omitted when absent).
  Named "change" rather than "transaction" to avoid the ACID/DB connotation, and distinct from the generic
  event sourcing "event".
- Request bodies: `ConsumptionDeltaDto(delta: Int)` (the `±1` step; the exact-±1 rule is checked in the
  domain → 400 otherwise) and `ConsumptionOverrideDto(total: Int, note: String?)` (the admin absolute set;
  `total ≥ 0`, `note ≤ 500` chars).

User CRUD reuses a `UserDto : Dto<UUID>` (single `role`, `active`, a read-only assembled `capabilityUrl`);
the auth `TokenRequestDto` / `TokenResponseDto` stay.

### Member self-service endpoints (auth: `X-Coffee-Token` header; principal = the token's member)
The QR encodes the SPA route `https://<host>/coffee/{token}` (the capability URL); the SPA reads the token from
that route and sends it as the `X-Coffee-Token` header on these clean endpoints — **the token never appears
in an API path**. Missing/unknown/rotated token → 401; a deactivated member is authenticated **read-only**,
so mutations → 403.
- `GET  /api/consumption?limit=5&offset=0` → `ConsumptionDto` (own total + a page of recent changes).
- `POST /api/consumption` `{ delta: ±1 }` → `ConsumptionDto` (a −1 at 0 → 409; delta ≠ ±1 → 400).
- `GET  /api/profile` / `PUT /api/profile` → view / edit own `firstName, lastName, emailAddress`; response includes the full capability URL.
- `GET  /api/profile/qr.png` → own QR (high-res PNG, the single format).

### Admin + user-management endpoints (auth: JWT, `ROLE_ADMIN`)
- `GET /api/users`, `POST /api/users` (create; returns the user incl. capability URL), `GET/PUT/DELETE /api/users/{id}`.
- `PUT /api/users/{id}` edits profile, `role`, and `active` (deactivate/reactivate).
- `GET /api/users/me` → the signed-in admin's own user (landing default).
- `GET /api/users/{id}/link`, `POST /api/users/{id}/link/rotate`, `GET /api/users/{id}/qr.png`.
- Consumption by id (admin landing + edit mode + reset), same resource shape as the member's, under the
  user, plus the admin-only absolute override:
  `GET /api/users/{id}/consumption?limit=5&offset=0`, `POST …/consumption` `{ delta: ±1 }`,
  `PUT …/consumption` `{ total, note? }` (override / edit mode; `{ total: 0 }` is the reset after payment).
  There is no `DELETE` on consumption — the ledger is append-only and a reset is the `PUT { total: 0 }`
  balancing entry, which keeps the prior counts in the log.

### Auth & dev
- `POST /api/auth/token`: username+password → JWT (the only admin credential; no Basic). The access token
  has a work-session TTL (≈10 h) and no refresh flow (deliberately omitted for an internal, few-admin tool).
- `GET/PUT/DELETE /api/dev/data` (dev profile): counts / seed fixtures / clear.

Drop all `/api/pos/**` and `/api/reviews/**` endpoints. Reuse the generic `CrudController` for user CRUD;
the consumption/profile and auth endpoints are purpose-built controllers. Keep the `@CrudOperation`
OpenAPI customizer (trim the resource/operation enums to the new surface).

---

## Authentication & authorization (`application` + `api`)

Revise `application/.../security/SecurityConfig.kt`. Two authentication mechanisms, one per audience —
**no HTTP Basic**:
- **Admins — JWT bearer** resource server (keep `oauth2ResourceServer { jwt }`, the JWT converter, JSON
  401/403 handlers).
- **Members — a custom capability token filter:** an `AuthenticationConverter` / `OncePerRequestFilter` that
  reads `X-Coffee-Token`, resolves it to the member `User`, and sets a `ROLE_USER` principal. An
  unknown/rotated token → 401. A deactivated member is still authenticated (so reads work) but the mutating
  operations reject with 403 (the account is kept, read-only). The capability token principal **never** gets
  `ROLE_ADMIN`, so an admin's own token grants only self-service.
- **Admin login & session.** The admin submits username+password **once** to `POST /api/auth/token`; the
  server verifies it (via the retained `AuthenticationManager` / `DaoAuthenticationProvider` /
  `CampusUserDetailsService` / password encoder — these beans exist only for this step) and returns a JWT.
  The SPA stores the JWT and sends it as `Authorization: Bearer …` on later admin calls; the password is
  never stored or re-sent. **No refresh token:** the access token gets a moderate TTL (≈8–12 h, a work
  session) and the SPA redirects to login on expiry. A refresh-token flow (short access token +
  `/api/auth/refresh` + refresh-token storage/rotation/revocation) is deliberately omitted — it is
  over-engineering for an internal, few-admin tool, and a refresh token stored in the SPA wouldn't reduce
  the real (XSS) risk anyway. If long, revocable "stay-logged-in" sessions are ever wanted, the cleaner
  upgrade for this **same-origin** SPA is a server-side httpOnly session cookie, not a refresh token. Drop
  the `httpBasic { }` filter.
- Rules: `permitAll` for `/api/auth/token`, `/actuator/health`, Swagger, dev endpoints, SPA static assets;
  `ROLE_ADMIN` for `/api/users/**` and `/actuator/metrics`; `/api/consumption/**` + `/api/profile/**` need
  an authenticated member (the capability token principal); `authenticated` otherwise.
- `ActorProvider` returns the current principal's login (member or admin) for `created_by`;
  `CurrentUserProvider` resolves the principal → domain `User`. Single-role mapping in
  `CampusUserDetailsService` and the JWT `roles` claim.
- SPA deep links: serve `index.html` for non-`/api`, non-asset routes (a forwarding resolver/controller)
  so `/coffee/:token`, `/profile`, `/admin/**` survive a refresh.
- **CORS:** none is configured, and the plan **assumes none is needed**, because the SPA and the API are
  served from the **same origin**. The Angular build is bundled into the backend's `static/` resources, so
  in prod the browser loads the app and calls `/api/...` under one scheme+host+port — the browser never
  classifies the API calls as cross-origin, so no `Access-Control-Allow-*` headers or preflight handling
  are required. In dev the two run separately (`:4200` and `:8080`), but the Angular dev server's
  `proxy.conf.json` forwards `/api` to `:8080` server-side, so from the browser it is still same-origin —
  again no CORS. A configurable, **default-empty CORS allowlist bean** is added purely as an escape hatch;
  it stays unused unless this same-origin assumption is broken (e.g. the SPA is later hosted on a separate
  origin such as Firebase Hosting or a second Cloud Run service), in which case that origin is added there.

### Capability URL good practices (per the W3C TAG finding, 2014)
Follow [Good Practices for Capability URLs](https://www.w3.org/TR/capability-urls/), with one deliberate
deviation:
- **Unguessable, not sequential:** the token is a high-entropy random value (e.g. 256-bit, base64url-encoded),
  not a guessable/sequential id (§5.2). Dev fixtures may use deterministic seeded tokens for repeatable
  demos; prod generates cryptographically random ones.
- **HTTPS only:** capability URLs are `https` in prod (Cloud Run TLS); the token never travels over plain HTTP (§5.1).
- **Revocation instead of expiry (the deviation):** the finding recommends expiry, but wall-printed QR codes
  are meant to be long-lived, so URLs are **persistent by design** and we rely on admin **rotation/revocation**
  (one token per user; rotating issues a new URL and invalidates the old QR). A rotated/unknown token fails
  authentication → **401**; a deactivated member is read-only (mutations → 403) (§5.1).
- **Permissions, not actions; no side effects on GET:** opening `/coffee/{token}` (the SPA route) only loads the
  page; every data change is a `POST` API call, so scanning/opening the URL never mutates anything (§5.1).
- **Minimize leakage:** the token appears in a URL **only** at the SPA entry point (`/coffee/{token}`), never in
  an API path (the SPA forwards it as the `X-Coffee-Token` header), so it stays out of API server/proxy
  access logs. The token page avoids third-party scripts/assets and sends `Referrer-Policy: no-referrer`
  (and `rel="noreferrer"` on outbound links) so the `/coffee/{token}` URL can't leak via `Referer`; keep it out
  of analytics (§5.1).
- **Keep crawlers out:** disallow the `/coffee/` path in `robots.txt` and send `X-Robots-Tag: noindex` on the
  token page (list the path, never individual URLs) (§5.1).
- **Tell users the risk (UI copy):** the profile page presents the URL as **"your coffee link"** with a
  plain-language note that anyone holding it can change their coffee count, so they should not share or post
  it (§5.3).

---

## Frontend (`frontend/`, build last)

Mirror `ase24-taskboard/frontend`: latest **stable Angular**, standalone components, **Angular Material**,
`HttpClient` + RxJS services, plain CSS with mobile-first media queries, `proxy.conf.json` → `:8080`.
Optionally generate the API client/DTOs from the backend OpenAPI spec (as taskboard does).

Routes / pages:
- `/login` — admin username/password → `POST /api/auth/token`; store the JWT. An HTTP interceptor attaches
  `Authorization: Bearer <jwt>` to admin (`/api/users/**`) calls and `X-Coffee-Token: <token>` (from the
  active `/coffee/:token` route) to member (`/api/consumption/**`, `/api/profile/**`) calls.
- `/coffee/:token` — **landing (member mode)**: header (login name + profile icon), current total, big **+** /
  **−** buttons, the recent changes. Reads `token` from the route, holds it for the interceptor.
- `/coffee/:token/profile` — **member profile**: edit name/email, show **"your coffee link"** (the capability
  URL) with the sharing-risk note + QR download (high-res PNG).
- `/` (admin landing, after login) — same consumption view for the admin's own user by default, **plus** a
  **user selector** (any user), an **edit icon** → edit mode (override total), and a **reset** button.
- `/admin/users` — **user-admin page**: list/search users, create, edit/deactivate/delete, change role,
  view/rotate capability URL, download QR.
- `/profile` — admin's own profile (same component as the member profile).

Build integration: a Gradle task (e.g. `com.github.node-gradle.node`) runs `npm ci` + `npm run build` and
copies `frontend/dist/**/browser/` into `application/src/main/resources/static/`, wired before
`:application:processResources`/`bootJar`. Node provisioned via `mise.toml`. Karma/Jasmine unit tests;
optional Playwright/Cypress e2e (note as optional).

---

## What to drop from CampusCoffee
POS (entity/controller/dto/service/data-service/mapper, `CampusType`, `PosType`, `AddressEntity`,
`HouseNumberConverter`), all Review + ReviewApproval code, the OSM client (`OsmClient`, `OsmDataService`,
`OsmNode`, `OsmApiProperties`, XML config), open self-registration (POST `/api/users` becomes admin-only),
HTTP Basic, `MODERATOR`, `approval.min-count`, the `persistence.mode` toggle + relational-only path +
`PersistenceProperties`/`PersistenceMode` + `DataToEventsRunner` + the `@ConditionalOnProperty` on the
decorators. Drop their tests, feature files, and the XML-removal config if no longer needed.

## Database migrations (fresh, clean set in `data/src/main/resources/db/migration/`)
- `V1__create_users_table.sql` — `users` (uuid PK, timestamps, `login_name` unique, `email_address`
  unique, `first_name`, `last_name`, `role`, `active`, `password_hash` nullable, `capability_token` unique).
- `V2__create_coffee_consumptions_table.sql` — `coffee_consumptions` (uuid PK, timestamps, `user_id` unique
  FK, `count` not null default 0, `version` not null default 0).
- `V3__create_events_table.sql` — append-only `events` incl. **`created_by`** (varchar) and a nullable
  **`note`**, plus the existing `idx_events_seq`, `idx_events_entity_type`, `idx_events_body_id` indexes.

## Configuration & profiles (`application.yaml`)
- **PostgreSQL 18** everywhere — pin `postgres:18-alpine` for the dev `compose.yaml`, local runs, and the
  Testcontainers image (CampusCoffee used 17; this is an upgrade).
- Keep `campus-coffee.id.seed` / `event-seed`, `jwt.secret`, Flyway `validate`, JPA `ddl-auto: validate`.
- Remove `osm.*`, `approval.*`, `persistence.mode` + the migration flags. Add
  `campus-coffee.app.base-url` (used to build capability URLs + QR), a bootstrap-admin block, and the
  optional CORS allowlist.
- **dev:** local Postgres (`jdbc:postgresql://localhost:5432/postgres`), Swagger on, fixtures on (seed one
  admin + a few members with deterministic capability tokens via the seeded id generator for repeatable demos).
- **prod (concrete — Cloud SQL instance already provisioned, retrieved & re-verified via `gcloud` on
  2026-06-19).** Instance `campus-coffee-consumption` in project **`se-uhd`**, region **`europe-west3`**,
  **POSTGRES_18**, tier
  `db-g1-small` (ZONAL, backups on), public IP `34.159.74.192`, `sslMode=ENCRYPTED_ONLY`; the default
  database `postgres` and built-in `postgres` user exist. **Connection name:**
  `se-uhd:europe-west3:campus-coffee-consumption`.
  - Connect with the **Cloud SQL Java connector** (add `com.google.cloud.sql:postgres-socket-factory`); it
    does TLS + IAM auth itself, so no authorized-networks / client-cert setup despite `ENCRYPTED_ONLY`. The
    prod profile bakes in the non-secret connection details; only the DB password, `JWT_SECRET`, and
    bootstrap-admin creds come from the environment / Secret Manager. Fixtures are **off** in prod (real
    members), replaced by a bootstrap admin. Draft prod profile:

    ```yaml
    spring:
      config:
        activate:
          on-profile: prod
      datasource:
        url: "jdbc:postgresql:///postgres?cloudSqlInstance=se-uhd:europe-west3:campus-coffee-consumption&socketFactory=com.google.cloud.sql.postgres.SocketFactory"
        username: ${DB_USERNAME:postgres}
        password: ${DB_PASSWORD}            # Secret Manager
    jwt:
      secret: ${JWT_SECRET}                 # required, no fallback
    campus-coffee:
      fixtures:
        load-on-startup: false              # prod has real members, not fixtures
      bootstrap-admin:                       # create one admin on first startup if none exists
        login-name: ${BOOTSTRAP_ADMIN_LOGIN}
        password: ${BOOTSTRAP_ADMIN_PASSWORD}
        email-address: ${BOOTSTRAP_ADMIN_EMAIL:admin@se.uni-heidelberg.de}
    management:
      endpoints:
        web:
          exposure:
            include: health, metrics
    ```
  - **Cloud Run deploy:** attach the instance with
    `--add-cloudsql-instances se-uhd:europe-west3:campus-coffee-consumption` and grant the runtime service
    account the **Cloud SQL Client** role; inject `DB_PASSWORD`, `JWT_SECRET`, and the `BOOTSTRAP_ADMIN_*`
    values from Secret Manager. (Optional: create a dedicated `campus_coffee_consumption` database instead
    of reusing `postgres` via `gcloud sql databases create`.)
  - Update `Dockerfile` (build the SPA stage / provision Node), `compose.yaml` (dev `postgres:18-alpine`),
    and `compose.prod.yaml` (Cloud SQL connector env + `JWT_SECRET`).

## Testing strategy
- **Domain unit tests:** `UserServiceTest`, `CoffeeConsumptionServiceTest` (`+1`/`−1` via `applyDelta`,
  absolute override/reset via `setTotal`, the exact-±1 and no-negative rules, authz/active rules), backtick
  sentence names.
- **System tests** (Testcontainers + `RestTestClient`, extend `AbstractSystemTest`): member self-service
  flow via the `X-Coffee-Token` header (view / +1 / −1 / change log / profile / QR), admin flow via JWT
  (CRUD, role change, link rotate, override, reset with a note), deactivation → mutations 403,
  unknown/rotated token → 401, the `ConsumptionDto` response shape, and **event-log assertions** (a
  `CoffeeConsumption` UPDATE event per change with the right `count` / `created_by` / `note`). Keep the
  seeded-fixtures base. Drop the dual
  relational/event sourcing test split (only one mode now).
- **Cucumber** features: `consumption.feature`, `user-admin.feature`, `authorization.feature` (replace
  pos/reviews features) + step defs.
- **ArchUnit** layer tests unchanged. **JaCoCo** gate kept at 95%/82% (raise as coverage grows, never
  lower). **PITest** retargeted to the new packages. **Frontend:** Karma/Jasmine.

## Documentation (update/trim every downstream artifact)
Rewrite `README.md` and `CLAUDE.md` for the SE@UHD consumption domain, the QR/landing flow, the
capability-token-vs-JWT auth split, and Cloud SQL deploy. **Drop `INSTRUCTOR.md`** — it is not carried over
into the new app. Start a **fresh `CHANGELOG.md` at version `0.1.0`** — this is a new project, not a continuation of
CampusCoffee, so CampusCoffee's own version is left untouched — per the changelog/downstream-docs rule,
done once the feature is implemented.
Add a `doc/` note for the `CoffeeConsumption` event sourcing model (a `Review`-shaped entity + `created_by`)
and the capability URL scheme (citing the W3C finding). Update `.feature` files and fixture credentials.

## Implementation sequence
0. **This document** is the persisted design record, committed at
   `doc/2026-06-19_derive-campus-coffee-consumption.md` (matching CampusCoffee's `doc/YYYY-MM-DD_<kebab-title>.md` naming).
1. Scaffold the target repo from CampusCoffee (build-logic, gradle, mise, modules, security/ES machinery);
   delete POS/Review/OSM and the persistence-mode toggle.
2. **API first:** DTOs + controllers (member self-service, user-admin, consumption-by-id, auth, QR) + mappers + OpenAPI — the contract.
3. **Services:** `UserServiceImpl`, `CoffeeConsumptionServiceImpl` (load → upsert new count → return `ConsumptionDto`), authz, `ActorProvider`.
4. **Data:** `User`/`CoffeeConsumption` entities, repositories, mappers; extend the event sourcing wiring
   (`created_by`, `CoffeeConsumption` serializer + projector branch + decorator); migrations.
5. **Application wiring:** `SecurityConfig` (JWT + capability token filter, no Basic), `application.yaml`
   profiles, SPA static serving + fallback, fixtures + bootstrap admin, Cloud SQL.
6. **Frontend:** Angular app + Gradle build integration into `static/`.
7. Tests + docs; start a fresh `CHANGELOG.md` at **version `0.1.0`**; `gradle build`
   green (ktlint, detekt, coverage gate) and `npm test` green.

## Verification (end-to-end)
- **Local:** `docker run … postgres:18-alpine`; `gradle :application:bootRun --args='--spring.profiles.active=dev'`.
  Dev SPA: `cd frontend && npm start` (proxy → 8080), or `gradle build` then hit `http://localhost:8080`.
  Walk through: admin logs in (JWT) → creates a member → downloads/opens that member's `/coffee/{token}`
  capability URL → taps +/− → the count and last-5 update → admin selects the member, enters edit mode
  (override), then resets → deactivate and confirm +/− returns 403. Inspect the log
  (`SELECT change_type, entity_type, created_by FROM events ORDER BY seq`) to confirm the
  `CoffeeConsumption` UPDATE trail. Swagger at `/api/swagger-ui.html`.
- **Automated:** `gradle build` (unit/system/acceptance/arch tests + coverage gate) and `npm test`.
- **Prod:** deploy the single image to Cloud Run with Cloud SQL; set `JWT_SECRET`, `SPRING_DATASOURCE_*`,
  `INSTANCE_CONNECTION_NAME`, and the bootstrap-admin env; verify `/actuator/health`, admin login, and a
  capability URL scan against the deployed origin.