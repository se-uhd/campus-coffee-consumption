# CampusCoffeeConsumption

A coffee-consumption tracker for **SE@UHD**, the Software Engineering Group at Heidelberg University. Each
group member has a running coffee count. A member bumps their own count by scanning a **QR code on the
wall** — a secret per-member **capability link** that opens a small mobile-first web app with big **+** and
**−** buttons. Admins create and manage members, can adjust anyone's count, and **reset** a count to zero
once the member has paid for their coffee. Every change is recorded in an append-only **event log**.

The app is a Spring Boot / Kotlin backend (hexagonal architecture, event sourcing persistence) with an
Angular single-page frontend, derived from the CampusCoffee teaching project.

## How it works

- **Members** authenticate with their secret **capability token**. The token is encoded in their wall QR
  code as `https://<host>/coffee/{token}`; scanning it opens the SPA, which sends the token as the
  `X-Coffee-Token` header on its API calls. The token never appears in an API URL path, only at the SPA
  entry point, so it stays out of API access logs. Members can tap **+1** / **−1** and see their recent
  changes; a count never goes below zero.
- **Admins** authenticate with a **JWT**, minted from a username-and-password login (`POST /api/auth/token`,
  ~10-hour work-session token, no refresh flow). An admin manages members (create, edit, deactivate,
  delete, change role, view and rotate capability links, download any member's QR), adjusts any member's
  count by `±1`, and uses an absolute **override** to set a count directly or **reset** it to zero after
  payment, optionally recording a note.
- **The event log is the source of truth.** Each change to a member's count appends one full-state event;
  the relational `coffee_consumptions` table is a read model projected from the log. A member's change
  history is read straight from the event rows (each carries the count, who made the change, when, and an
  optional admin note).

## Architecture

A multi-module Gradle project (Kotlin DSL) following a hexagonal (ports-and-adapters) architecture, with
layer boundaries enforced by ArchUnit:

- **domain** — domain models, port interfaces, and business logic (no framework dependencies beyond validation).
- **api** — REST controllers, DTOs, MapStruct DTO mappers, the QR/capability URL helpers.
- **data** — JPA entities, repositories, the event sourcing machinery (event store, read model projector,
  decorators), and the ZXing QR and capability token adapters.
- **application** — the Spring Boot app that wires it together: security (JWT + capability token filter),
  configuration profiles, startup fixtures, and the bundled SPA.
- **frontend** — the Angular SPA, built by Gradle and bundled into the application's `static/` resources.

Event sourcing is the only persistence model. See `CLAUDE.md` for the full architecture and the
`doc/` notes for the design records.

## Prerequisites

- **Java 25** and **Gradle 9.5**, provisioned via [mise](https://mise.jdx.dev/) (`mise.toml`; no Gradle wrapper).
- **Node** (for the frontend), also provisioned via mise.
- **Docker** — for a local PostgreSQL database in the `dev` profile and for the Testcontainers-based tests.

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

## Seeded fixtures (dev)

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

A member's capability link is `http://localhost:8080/coffee/<token>`. Only the admin has a password (in
`TestFixtures.kt`), used for the JWT login; members have no password and authenticate solely with their
capability link. The dev `DevController` (`GET/PUT/DELETE /api/dev/data`) reports the counts, reloads the
fixtures (reassigning the same seeded ids), or clears the data.

## REST API

All paths are under `/api`. JSON only. See Swagger for the full contract.

**Member (auth: `X-Coffee-Token`):**

- `GET  /consumption?limit=5&offset=0` — own current total plus a page of the change log.
- `POST /consumption` `{ "delta": 1 | -1 }` — apply a single-step change (a `−1` at 0 → 409 Conflict).
- `GET  /profile`, `PUT /profile` — view / edit own name and email (the response includes the capability URL).
- `GET  /profile/qr.png` — own QR code (high-resolution PNG).

**Admin (auth: JWT, `ROLE_ADMIN`):**

- `GET /users`, `POST /users`, `GET/PUT/DELETE /users/{id}`, `GET /users/me`, `GET /users/filter?login_name=…`.
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png`.
- `GET  /users/{id}/consumption?limit=5&offset=0`.
- `POST /users/{id}/consumption` `{ "delta": 1 | -1 }`.
- `PUT  /users/{id}/consumption` `{ "total": N, "note": "…" }` — absolute override (`{ "total": 0 }` is the reset; `note` is optional).

**Auth:** `POST /auth/token` `{ "loginName": "…", "password": "…" }` → `{ "token": "<jwt>" }`.

Every consumption endpoint returns the same shape: `{ "total": N, "changes": [ { "count", "delta",
"createdAt", "createdBy", "note" } ] }`. The SPA shows an optimistic `+1`/`−1` and reconciles to the
returned `total` (re-reading on a 409 from a concurrent change).

## Inspecting the event log

Every count change is one row in the append-only `events` table. The `created_by` column records the
actor's login (a member, an admin, or `"system"` for the fixtures), and `note` records an admin's reason
for an override or reset:

```sql
SELECT change_type, entity_type, created_by, note FROM events ORDER BY seq;
```

To rebuild the read tables from the log on startup (an event sourcing demonstration), restart with
`--campus-coffee.persistence.events-to-data-on-startup=true`.

## Testing

```shell
gradle build      # compiles, runs ktlint + detekt, the test suite, and the JaCoCo coverage gate
gradle test       # the test suite only
```

The backend tests use Testcontainers (PostgreSQL) for the system and integration tests and Cucumber for
the acceptance tests. The frontend uses Karma/Jasmine (`cd frontend && npm test`).

## Production deployment (Cloud Run + Cloud SQL)

The whole app ships as **one Cloud Run image**: the Angular SPA is bundled into the backend's `static/`
resources, so the browser loads the app and calls `/api` under one origin (no CORS in prod).

The prod profile connects to a **Cloud SQL for PostgreSQL 18** instance
(`se-uhd:europe-west3:campus-coffee-consumption`, project `se-uhd`) via the **Cloud SQL Java connector**,
which does TLS and IAM auth itself (no authorized-networks or client-cert setup). The non-secret connection
details are baked into the prod profile; the DB password, `JWT_SECRET`, and the bootstrap-admin credentials
come from the environment / Secret Manager. Fixtures are off in prod; instead, a **bootstrap admin** is
created on first startup from `campus-coffee.bootstrap-admin.*` when no admin exists.

Deploy notes:
- Attach the instance with `--add-cloudsql-instances se-uhd:europe-west3:campus-coffee-consumption` and
  grant the runtime service account the **Cloud SQL Client** role.
- Inject `DB_PASSWORD`, `JWT_SECRET`, and the `BOOTSTRAP_ADMIN_*` values from Secret Manager, and set
  `CAMPUS_COFFEE_APP_BASE_URL` to the deployed HTTPS origin (used to build the capability URLs).
- The `com.google.cloud.sql:postgres-socket-factory` runtime dependency (named by the prod datasource URL)
  is already declared in `application/build.gradle.kts`.

After deploying, verify `/actuator/health`, the admin login, and a capability URL scan against the deployed
origin.

## License

See [LICENSE](LICENSE).
