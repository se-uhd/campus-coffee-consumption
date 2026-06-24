# CampusCoffeeConsumption

A coffee consumption tracker for [**SE@UHD**](https://se-uhd.de/), the Software Engineering Group at Heidelberg University. Each
group member has a running coffee count, valued at a global admin-set **price per cup**, which feeds a
per-member **balance** and a communal **kitty**. A member bumps their own count by scanning a **QR code on
the wall**: a secret per-member **capability link**. Scanning it opens a small mobile-first web app where
they add a coffee, **undo** a recent one within a grace period, record their own bean purchases, and see
their balance. Admins create and manage members, set the price, record expenses and kitty deposits, and
correct anyone's count. Settling up records a **deposit** (real money into the kitty); there is no reset.
Every change is recorded in an append-only **event log**, from which a **unified activity feed** (coffees,
purchases, and deposits with a running balance) is read. Money is tracked in **euro cents**.

The app is a Spring Boot / Kotlin backend (hexagonal architecture, event sourcing persistence) with an
Angular 22 single-page frontend, derived from the CampusCoffee teaching project.

## How it works

- **Members** authenticate with their secret **capability token**. The token is encoded in their wall QR
  code as `https://<host>/login/{token}`; scanning it opens the SPA, which sends the token as the
  `X-Capability-Token` header on its API calls. The token never appears in an API URL path, only at the SPA
  entry point, so it stays out of API access logs. A member adds one coffee at a time, may **undo** the most
  recent one within a short grace period, records their own bean purchases, and sees their balance, the
  current price, the kitty balance, and a unified activity feed of their coffees, purchases, and deposits.
- **Admins** authenticate with a **JWT** held in an httpOnly, `SameSite=Strict` session cookie, minted from a
  username-and-password login (`POST /api/auth/token`, ~10-hour work-session token, no refresh flow). The
  credentials are encrypted in the browser (a compact JWE under the backend's published RSA public key, with
  an `iat` so a captured ciphertext cannot be replayed) before they are sent, so the raw password never
  travels as plaintext, and the cookie keeps the token out of JavaScript's reach (an XSS cannot steal it). An
  admin manages members (create, edit, deactivate, change
  role, view and rotate capability links, download any member's QR or all of them as a ZIP or printable PDF
  sheet), sets the global price, records expenses
  with a private/kitty split and kitty deposits and adjustments, corrects any member's count, and reviews
  the kitty history and a per-member overview. There is no reset: settling up is a deposit, and a count
  change is a correction (optionally with a note).
- **SPA routing.** A member's capability link `/login/{token}` opens the member landing, with the member
  profile at `/login/{token}/profile`. The admin area is consolidated under `/admin/`: the login form at
  `/admin/login`, the landing/dashboard at `/admin`, the members, price, expenses, and kitty pages at
  `/admin/users`, `/admin/price`, `/admin/expenses`, and `/admin/kitty`, and the admin profile at
  `/admin/profile`. The root path and any unknown route redirect to `/admin`.
- **Money is in euro cents and each cup is valued at the price when it was drunk.** A member's balance
  reads like a prepaid card (negative means they owe the fund), valuing each coffee at the price in effect
  when it was consumed.
- **The event log is the source of truth.** Each change (to a count, the price, an expense, or a payment)
  appends one full-state event; the relational tables are a read model projected from the log, and the
  unified activity feed and balances are read straight from the event rows (each carries who made the change, when,
  and an optional note). See `doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`.

## Architecture

A multi-module Gradle project (Kotlin DSL) following a hexagonal (ports-and-adapters) architecture, with
layer boundaries enforced by ArchUnit:

- **domain**: domain models, port interfaces, and business logic (no framework dependencies beyond validation).
- **api**: REST controllers, DTOs, MapStruct DTO mappers, the QR/capability URL helpers.
- **data**: JPA entities, repositories, the event sourcing machinery (event store, read model projector,
  decorators), and the ZXing QR and capability token adapters.
- **application**: the Spring Boot app that wires it together: security (JWT + capability token filter),
  configuration profiles, startup fixtures, and the bundled SPA.
- **frontend**: the Angular 22 SPA (TypeScript 6, Angular Material 22, Node 24), built by Gradle and
  bundled into the application's `static/` resources. Its request/response DTOs are generated from the
  backend OpenAPI spec (see Frontend tooling, below).

Event sourcing is the only persistence model. See `CLAUDE.md` for the full architecture and the
`doc/` notes for the design records.

## Prerequisites

- **Java 25** and **Gradle 9.5**, provisioned via [mise](https://mise.jdx.dev/) (`mise.toml`; no Gradle wrapper).
- **Node 24** (for the frontend), also provisioned via mise.
- **Docker**: for a local PostgreSQL database in the `dev` profile and for the Testcontainers-based tests.

## Running locally

Start PostgreSQL 18:

```shell
docker run -d --name db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:18-alpine
```

Run the backend in the `dev` profile (loads the seeded fixtures on first start):

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev'
```

The API is at `http://localhost:8080/api`, with Swagger UI at `http://localhost:8080/api/swagger-ui.html`.

For frontend development, run the Angular dev server (it proxies `/api` to the backend on `:8080`, so the
browser still sees a single origin and no CORS is needed):

```shell
cd frontend && npm start
```

Alternatively, `gradle build` bundles the SPA into the backend jar, so `http://localhost:8080` serves the
whole app from one process.

## Test fixtures (dev)

The `dev` profile seeds one admin and four members with deterministic capability tokens (so demos are
repeatable), each with a coffee consumption at zero. The credentials live in
`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/tests/TestFixtures.kt`:

| Login           | Role  | Capability token (member login)                |
|-----------------|-------|------------------------------------------------|
| `jane_doe`      | ADMIN | `Rh7tK2pXmQ9vL4nB8cD1eF6gH3jZ0sW5yAuToN2kEac`  |
| `maxmustermann` | USER  | `Pq3wE9rT5yU1iO7pA2sD8fG4hJ6kL0zXcVbN3mM1nBqe` |
| `student2023`   | USER  | `Zx1cV7bN3mA9sD5fG2hJ8kL4qW0eR6tYuIoP1lK7jHzx` |
| `lisa_lee`      | USER  | `Lk8jH4gF6dS2aP0oI9uY7tR3eW1qZ5xCvBnM2mN8bVlk` |
| `olivia_lee`    | USER  | `Ty6rE2wQ8aS4dF0gH6jK1lZ3xC9vB5nMqWeR7tY1uIty` |

A member's capability link is `http://localhost:8080/login/<token>`, and members authenticate **only** with
that link (they have no password). The admin `jane_doe` is different: she logs in with a **password** at
`POST /api/auth/token` (the JWT login), **not** with the capability token in the table above. The fixture
admin password is **`aaaMbnPdFYDqkOpS3fVA`** (also in `TestFixtures.kt`). The dev `DevController` (`GET/PUT/DELETE /api/dev/data`) reports the counts, reloads the
fixtures (reassigning the same seeded ids), or clears the data.

On top of these fixtures, the `dev` profile runs a dev-only `DevDemoDataLoader` that adds about nine more
members and an initial kitty float, and also enriches **every existing fixture user** (the admin `jane_doe`
included) with varied consumption, bean-purchase, and deposit history, so the members list paginates and the
activity and history views are not empty on a fresh start. Two members are deliberately left **empty** to demo
the empty state: a freshly created active member `new_user` (no history at all) and the inactive demo member
`hannes_schulz`. It is `@Profile("dev")`, so the tests still see exactly the five-member fixture set above.
The `dev` profile also reseeds on every startup (`campus-coffee.fixtures.reset-on-startup`), returning to
this deterministic state on each restart.

## REST API

All paths are under `/api`. JSON only. See Swagger for the full contract.

**Member (auth: `X-Capability-Token`):**

- `GET  /summary`: the member landing in one call (current count, balance, the current price, the kitty balance, and the first page of the unified activity feed).
- `POST /consumption` (no body): add one coffee.
- `POST /consumption/cancel`: undo the most recent coffee within the grace period (nothing to undo, or past the grace period, returns 409 Conflict).
- `GET  /activity?limit=20&offset=0`: own unified activity feed (coffees, purchases, deposits) with a running balance.
- `POST /expenses` `{ "weightGrams": N, "amountCents": N, "note"?: "…" }`: record an own bean purchase (booked 100% to the member).
- `GET  /profile`, `PUT /profile`: view and edit own name and email (the response includes the capability URL).
- `GET  /profile/qr.png`: own QR code (high-resolution PNG).

**Admin (auth: JWT, `ROLE_ADMIN`):**

- `GET /users`, `POST /users`, `GET/PUT/DELETE /users/{id}`, `GET /users/me`, `GET /users/filter?login_name=…`, `GET /users/overview` (the per-member overview now renders in the member-management page, `/admin/users`).
- `DELETE /users/{id}`: refused (409) if the member has financial history; deactivate instead.
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png`.
- `GET /users/qr.zip`: a streamed ZIP of every active member's QR code (one `<loginName>.png` per member).
- `GET /users/qr.pdf`: a printable PDF grid of every active member's QR code, each labelled by login name.
- `GET  /users/{id}/consumption?limit=5&offset=0`, `GET /users/{id}/activity?limit=20&offset=0`.
- `POST /users/{id}/consumption` `{ "delta": 1 | -1 }`.
- `PUT  /users/{id}/consumption` `{ "total": N, "note": "…" }`: absolute count correction (`note` optional).
- `GET/POST/PUT/DELETE /users/{id}/expenses`: list, record, correct, or delete a member's purchases with a private/kitty split (the buyer cannot be changed on a correction).
- `GET /price`: read the current global price (admin-only; members receive it through their landing summary).
- `PUT /price` `{ "amountCents": N }`, `GET /price/history?limit=50&offset=0`: set the global price, or view a page of its history (newest first).
- `POST /kitty/deposit` `{ "userId", "amountCents", "note"? }`: a member pays money into the kitty.
- `POST /kitty/adjustment` `{ "amountCents", "note"? }`: a pure kitty adjustment (an initial float or a correction).
- `GET /kitty/history?limit=50&offset=0`: the kitty history with the running kitty balance.

**Auth:** `GET /auth/public-key` returns the backend's RSA public key as a JWK; `POST /auth/token`
`{ "encryptedPayload": "<compact JWE of { loginName, password, iat }>" }` sets the JWT in an httpOnly
`SameSite=Strict` cookie and also returns `{ "token": "<jwt>" }` for header-based API clients;
`POST /auth/logout` clears the cookie. The SPA encrypts the credentials with the published key
(`RSA-OAEP-256` + `A256GCM`) so the raw password never travels as plaintext (defense in depth on top of TLS);
a malformed, undecryptable, or stale (replayed) payload returns 400.

Money is in integer euro cents throughout. The HTTP method carries the semantics: `GET` reads, member
`POST /consumption` adds one coffee (and `/cancel` undoes a recent one), and admin `PUT` sets an absolute
total. Consumption has no reset, no `−1`, and no `DELETE`. Settling up records a deposit; a count change
records a correction; both stay in the append-only log.

## Inspecting the event log

Every change (to a count, the price, an expense, or a payment) is one row in the append-only `events`
table. The `created_by` column records the actor's login (a member, an admin, or `"system"` for the
fixtures), and `note` records an admin's reason for an absolute count correction (a deposit, kitty
adjustment, or expense note lives in that entity's own event body, not this column):

```sql
SELECT change_type, entity_type, created_by, note FROM events ORDER BY seq;
```

To rebuild the read tables from the log on startup (an event sourcing demonstration), restart with
`--campus-coffee.persistence.events-to-data-on-startup=true`.

## Testing

```shell
gradle build      # compiles, runs ktlint + detekt, the frontend lint, the test suite, and the coverage gate
gradle test       # the test suite only
```

The backend tests use Testcontainers (PostgreSQL) for the system and integration tests and Cucumber for
the acceptance tests. The frontend uses **Vitest** for unit tests (`cd frontend && npm test`) and
**Playwright** for the end-to-end suite (`npm run e2e`, against a running app).

## Frontend tooling

- **Lint and static analysis.** `npm run lint` runs angular-eslint and Stylelint (with Prettier and Knip
  available via `npm run format` / `npm run knip`). It is wired into `gradle check` (the `frontendLint`
  task), so `gradle build` and CI fail on a frontend lint violation.
- **OpenAPI → DTO codegen.** The TypeScript DTOs in `frontend/src/app/api/model/` are generated from the
  backend OpenAPI spec by `scripts/generate-frontend-dtos.sh` (run automatically by the Gradle build, and
  skipped when the spec is unchanged), so the frontend and backend contracts cannot drift. The rest of the
  SPA imports them through `frontend/src/app/models.ts`; do not hand-edit the generated `api/model/`
  directory.

## Production deployment (Cloud Run + Cloud SQL)

The whole app ships as **one Cloud Run image**: the Angular SPA is bundled into the backend's `static/`
resources, so the browser loads the app and calls `/api` under one origin (no CORS in prod).

The prod profile connects to a **Cloud SQL for PostgreSQL 18** instance
(`se-uhd:europe-west3:campus-coffee-consumption`, project `se-uhd`) via the **Cloud SQL Java connector**,
which does TLS and IAM auth itself (no authorized-networks or client-cert setup). The non-secret connection
details are baked into the prod profile; the DB password, `JWT_SECRET`, the login-encryption private key
(`LOGIN_PRIVATE_KEY_PEM`), and the bootstrap-admin credentials come from the environment / Secret Manager.
Fixtures are off in prod; instead, a **bootstrap admin** is created on first startup from
`campus-coffee.bootstrap-admin.*` when no admin exists.

Deploy notes:
- Attach the instance with `--add-cloudsql-instances se-uhd:europe-west3:campus-coffee-consumption` and
  grant the runtime service account the **Cloud SQL Client** role.
- Inject `DB_PASSWORD`, `JWT_SECRET`, `LOGIN_PRIVATE_KEY_PEM` (a PKCS#8 RSA private key, at least 2048 bits,
  as one line with literal `\n` separators), and the `BOOTSTRAP_ADMIN_*` values from Secret Manager, and set
  `CAMPUS_COFFEE_APP_BASE_URL` to the deployed HTTPS origin (used to build the capability URLs). The same
  login key must be present on every instance, so it is configured, not generated per startup;
  `scripts/deploy-cloudrun.sh` generates one into `deploy.env` on first run.
- The `com.google.cloud.sql:postgres-socket-factory` runtime dependency (named by the prod datasource URL)
  is already declared in `application/build.gradle.kts`.

After deploying, verify `/actuator/health`, the admin login, and a capability URL scan against the deployed
origin.

## License

See [LICENSE](LICENSE).
