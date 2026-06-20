# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CampusCoffeeConsumption is a Spring Boot application that tracks the coffee consumption of the members of
**SE@UHD** (the Software Engineering Group at Heidelberg University, hence the `de.seuhd` package). Each
member has a running coffee count. A member bumps their own count via a secret **capability URL** printed
as a **QR code on the wall**; scanning it opens a small mobile-first Angular web app where they tap **+** /
**−**. Admins create and manage members, can adjust anyone's count, and **reset** a count to zero once the
member has paid. Every change is recorded in an append-only **event log** — the only persistence model.

It follows a **hexagonal (ports-and-adapters) architecture** with strict layer separation enforced by
ArchUnit tests, derived from the CampusCoffee teaching project (the POS/review/OpenStreetMap domain was
replaced by the consumption domain).

## Architecture

The project uses a **multi-module Gradle structure** (Kotlin DSL) with four modules plus an Angular
frontend.

### Module Dependencies
- **domain**: Core business logic, domain models, and port interfaces (no external dependencies except validation).
- **api**: REST API layer with controllers, DTOs, and DTO mappers (depends on: domain).
- **data**: Data layer with JPA entities, repositories, the event sourcing machinery, and the QR/capability token adapters (depends on: domain).
- **application**: Main Spring Boot application that wires everything together (depends on: domain, api, data).
- **frontend**: Angular SPA (sibling of the modules), built by Gradle and bundled into the application's `static/` resources.

### Layer Rules (Enforced by ArchUnit)
From `application/src/test/kotlin/de/seuhd/campuscoffee/tests/architecture/ArchitectureTests.kt`:

- **api** layer may only be accessed by **application**.
- **domain** layer may only be accessed by **api**, **data**, and **application**.
- **data** layer may only be accessed by **application**.
- **application** layer may not be accessed by any layer.

### Ports and Adapters Pattern

The domain defines **port interfaces** that adapters implement:

- **API Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/api/`): Generic service interface `CrudService<DOMAIN, ID>` and the concrete service interfaces `UserService` and `CoffeeConsumptionService`.
- **Data Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/data/`): Generic data service interface `CrudDataService<DOMAIN, ID>`, the concrete `UserDataService` and `CoffeeConsumptionDataService` (the latter adds `getByUserId`), the event-log-backed `ConsumptionHistoryDataService`, and the `PasswordHasher` port.
- **Infrastructure ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/`): `IdGenerator`, `CapabilityTokenGenerator`, `QrCodeGenerator`, `StartupTask`, plus the request-scoped event-metadata ports `ActorProvider` and `ChangeNoteContext`.

Service **implementations**:
- API services in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/implementation/` (`UserServiceImpl`, `CoffeeConsumptionServiceImpl`).
- Data adapters in `data/src/main/kotlin/de/seuhd/campuscoffee/data/implementations/` and the event sourcing decorators in `data/.../persistence/eventsourcing/`.

### Event Sourcing Is the Only Persistence Model

CampusCoffee shipped a configurable relational/event sourcing toggle; this app drops the toggle and keeps
**event sourcing as the sole persistence model**. An append-only **event log** (the `events` table) is the
source of truth, and the relational tables are a **read model** projected from it:

- The event-sourced **decorators** in `data/.../persistence/eventsourcing/`
  (`EventSourcedUserDataService`, `EventSourcedCoffeeConsumptionDataService`) wrap the plain relational
  `*DataServiceImpl` (`: …DataService by delegate`, so the read and query methods auto-delegate) and are
  `@Primary`, so the domain binds to them. Each write request appends one full-state event (`EventStore`)
  and projects it into the tables (`ReadModelProjector`) in one transaction, so a constraint violation
  rolls both back and the log never holds an invalid event.
- `EventSourcedMutator.upsert(domain, getById, buildForInsert, buildForUpdate)` is the shared event-first
  logic; it assigns the id and timestamps, appends, and projects, holding no per-type knowledge.
- The projection reuses the MapStruct entity mappers and preserves the id and timestamps from the event
  body (`Entity.markTimestampsPreassigned()`). Read requests are served from the materialized tables (no
  replay on read).

Two additions versus CampusCoffee's event machinery:

1. **`created_by` and `note` metadata on the generic event.** `events` and `EventEntity` carry a
   `created_by` (the actor's **login name** as a string — a member via their token, an admin, or `"system"`
   for startup fixtures/bootstrap) and a nullable `note` (an admin's free-text reason for an absolute
   override / a reset after payment). Both are set at the `EventStore.append*` boundary from the
   request-scoped `ActorProvider` (reads the `SecurityContext`) and `ChangeNoteContext` (a thread-local the
   consumption service sets only around an override/reset). Neither is part of the full-state JSON body, and
   the generic mutator/decorator signatures are untouched. `created_by` is a login string, not a user id,
   so the audit trail is human-readable, represents the non-user `"system"` actor naturally, and does not
   foreign key into the mutable users read model.
2. **`CoffeeConsumption` is a logged entity, modeled exactly like CampusCoffee's `Review`.** It references
   a `user` (flattened to `userId` in the event body, mirroring how a review flattened its author):
   `EventJsonMapper` has a `CoffeeConsumptionEventSerializer`, `ReadModelProjector` has a
   `COFFEE_CONSUMPTION` branch with `reconstructCoffeeConsumption(body)` resolving `userId` against the
   users read table, and `EventSourcedCoffeeConsumptionDataService` decorates the relational impl.

The optional `EventsToDataRunner` (behind `campus-coffee.persistence.events-to-data-on-startup`) rebuilds
the read tables from the log on startup, an event sourcing demonstration.

### Consumption Operations Reuse the Upsert Path

There is no new ledger machinery. Each `+1` / `−1` (a member self-scan or an admin step) and each admin
absolute override (any value, including `0` for a reset after payment) is a plain `upsert` of the member's
`CoffeeConsumption` with the new `count`, which the decorator records as a full-state UPDATE event —
identical to how a review's approval count advanced. `CoffeeConsumptionService` exposes
`applyDelta(userId, delta, actingUser)` and `setTotal(userId, total, note, actingUser)`. Concurrent
self-scans are handled by the entity's `@Version` optimistic-locking column → `ConcurrentUpdateException`
(409); the SPA retries. A `−1` at 0 → 409 (no negative counts); a `delta` other than `±1` → 400.

### The Change Log Is Read from the Event Log

A member's transaction history is not a table. `ConsumptionHistoryDataService` queries the `events` rows
for the consumption (`entity_type = 'CoffeeConsumption'` and `body ->> 'id' = :consumptionId`, ordered by
`seq desc` with `limit`/`offset`; the `idx_events_body_id` index covers `body ->> 'id'`). Each event body
carries the `count` at that time; the event row carries `created_at`, `created_by`, and `note`; each
entry's `delta` is the difference from the previous event.

### Generic Base Classes

The codebase uses extensive generics to reduce duplication:

- **CrudController** (`api/.../controller/CrudController.kt`): generic REST controller for CRUD operations (used by `UserController`).
- **CrudService** / **CrudServiceImpl**, **CrudDataService** / **CrudDataServiceImpl**: generic service and data-service interfaces and base implementations.
- **DtoMapper** / **EntityMapper**: generic mapping interfaces using MapStruct.

Domain-specific controllers/services extend these base classes (e.g., `UserController extends CrudController<User, UserDto, UUID>`; the domain type comes first).

## Build and Run Commands

### Prerequisites
- Docker daemon must be running to use a database in the `dev` profile or to run the tests that use *Testcontainers*.
- Java 25 and Gradle 9.5, provisioned via `mise.toml` (no Gradle wrapper). Run Gradle through mise
  (CI uses `jdx/mise-action`). The build pins a **Java 25 toolchain with no auto-download**, so a
  JDK 25 must be present on the machine — mise supplies it.
- Node is provisioned via `mise.toml` for the frontend build.
- The Java major version has a **single source of truth**: the `java` entry in
  `gradle/libs.versions.toml`. The convention plugins resolve it for the Gradle toolchain and the Kotlin
  `jvmTarget`; `mise.toml` and the Dockerfile runtime image pin the same major by hand.
  `scripts/check-toolchain-versions.sh` (a CI step) fails the build if they drift.

### Build

```shell
gradle build
```

### Format, Lint, and Static Analysis (ktlint + detekt)

The Kotlin sources are formatted and linted with ktlint (official Kotlin style, configured via the root
`.editorconfig`). `gradle build` fails on violations because `ktlintCheck` is wired into `check`; apply
the fixes with:

```shell
gradle ktlintFormat
```

Static analysis runs via detekt (`dev.detekt`, pinned at `2.0.0-alpha.5`; detekt 2.0 alphas require the exact
Kotlin version they were built against, which is why Kotlin is pinned at 2.4.0). It is wired into `check`,
so `gradle build` and CI fail on findings. A per-module `detekt-baseline.xml` grandfathers pre-existing
findings; regenerate it with `gradle detektBaseline`.

On top of detekt's defaults the build enforces a **custom KDoc rule set** (`campus-coffee-kdoc`),
authored in the `:detekt-rules` tooling subproject and loaded via `detektPlugins`. It requires KDoc on
every non-local, non-override function (with `@param` for every parameter of a public function) and on
every non-local class, interface, object, and enum class. Local declarations, overrides, and **test
sources** are exempt. Edit the rules in `detekt-rules/src/main/kotlin/de/seuhd/campuscoffee/detekt/` and
its `META-INF/services` provider; they are covered by `gradle :detekt-rules:test`.

### Start PostgreSQL Database

```shell
docker run -d --name db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p 5432:5432 postgres:18-alpine
```

### Run Application (dev profile)

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev'
```

The `dev` profile:
- Enables Swagger UI at `http://localhost:8080/api/swagger-ui.html` and API docs at `http://localhost:8080/api/api-docs`.
- Loads the fixture dataset on startup (`campus-coffee.fixtures.load-on-startup: true`, when the database
  has no users yet): one admin and four members with deterministic capability tokens, each with a coffee
  consumption at zero, so the app comes up with the seeded ids and demo-able coffee links ready.
- Registers the dev-only `DevController` (in the `api` layer) under `/api/dev`:
  `GET /api/dev/data` reports the counts, `PUT /api/dev/data` replaces the data with the fixtures
  (clear + seed; idempotent, reassigning the same seeded ids), and `DELETE /api/dev/data` clears it.

### Rebuild the read model from the event log (optional)

Event sourcing is always on. To rebuild the relational read tables from the log on startup (clear the
tables, replay the whole log in append order — an event sourcing demonstration), restart with:

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev --campus-coffee.persistence.events-to-data-on-startup=true'
```

Inspect the log at any time:

```sql
SELECT change_type, entity_type, created_by, note FROM events ORDER BY seq;
```

### Run Tests

All tests:

```shell
gradle test
```

Single test class (scope the task to the module that contains the test; the bare `test` task runs in
every module, and the `--tests` filter fails the modules that have no matching test):

```shell
gradle :domain:test --tests "CoffeeConsumptionServiceTest"
```

Test methods use backtick sentence names; quote the filter:

```shell
gradle :domain:test --tests "CoffeeConsumptionServiceTest.decrementing a count at zero throws ValidationException"
```

### Code Coverage and Mutation Testing

- **Coverage (JaCoCo)**: the `coverage` subproject (the `jacoco-report-aggregation` plugin) aggregates
  execution data from all modules into one report at
  `coverage/build/reports/jacoco/testCodeCoverageReport/`. Aggregation is required because
  `domain`/`api`/`data` are largely covered by the `application` system and acceptance tests. `gradle
  build` enforces the gate via the `coverageGate` task (a `JacocoCoverageVerification` in
  `coverage/build.gradle.kts`, wired into `check`); raise the minimums as coverage grows, never lower them
  to make a build pass.
- **Mutation testing (PITest)**: opt-in and local via the `-Pmutation` property and the per-module
  `pitest` task (e.g., `gradle :domain:pitest -Pmutation`). The `application` cross-module run
  (`gradle :application:pitest -Pmutation`) additionally mutates `api.*`/`data.*` against the system and
  acceptance tests. The generated `*MapperImpl` classes are excluded, mirroring the JaCoCo gate.

### Frontend

The Angular SPA lives in `frontend/` and is built by Gradle (a Node-Gradle task runs `npm ci` + `npm run
build` and copies `frontend/dist/**/browser/` into `application/src/main/resources/static/`, wired before
`:application:processResources`/`bootJar`), so `gradle build` produces one self-contained jar. For frontend
development run the Angular dev server, which proxies `/api` to the backend on `:8080`:

```shell
cd frontend && npm start
```

### Docker

Build image:

```shell
docker build -t campus-coffee-consumption:latest .
```

Run with Docker Compose (the Compose file defaults `DB_HOST` to `localhost` for Cloud Run, so set
`DB_HOST=db` locally):

```shell
docker compose down && DB_HOST=db docker compose up
```

### Dependency Updates

Dependencies and tools are kept current automatically:
- **Dependabot** (`.github/dependabot.yml`) opens weekly PRs for the GitHub Actions and the Gradle
  dependencies and plugins (resolved from the `libs.versions.toml` catalog).
- A weekly **`mise-outdated`** workflow opens or updates an issue when the mise-managed tools fall behind.

## Versioning and Releases

The project follows [Semantic Versioning](https://semver.org/) and keeps a [Keep a Changelog](https://keepachangelog.com/)-style `CHANGELOG.md`.

- **Every notable change updates `CHANGELOG.md`** under the appropriate version header (`Added` / `Changed` /
  `Fixed` / etc.). Patch bump for tooling, CI, and internal cleanups; minor for features; major for
  breaking changes.
- **`version` in the root `gradle.properties` is the single source of truth** (applied to every module's
  `project.version`). The newest `## [x.y.z]` release header in `CHANGELOG.md` must equal it — enforced by
  `scripts/check-version-sync.sh`, a `build.yml` CI step that fails the build on drift.
- **Every released version is tagged.** Create an annotated git tag `vX.Y.Z` on the release commit (the one
  that sets the version and adds the changelog entry) and push it: `git tag -a vX.Y.Z -m "vX.Y.Z" && git
  push origin vX.Y.Z`. Add the matching `[x.y.z]: …/releases/tag/vX.Y.Z` link reference at the bottom of
  `CHANGELOG.md`. No workflow triggers on tags, so pushing a tag does not run CI.

## Database

- **Database**: PostgreSQL 18.
- **Migrations**: Flyway (`data/src/main/resources/db/migration/`).
- **ORM**: JPA with Spring Data.
- **Connection**: Configured in `application/src/main/resources/application.yaml`.

The schema is a fresh, three-migration set:
- `V1__create_users_table.sql` — `users` (uuid PK, timestamps, `login_name` unique, `email_address` unique,
  `first_name`, `last_name`, a single `role` column (`USER`/`ADMIN`, no `user_roles` table), `active`,
  `password_hash` nullable, `capability_token` unique).
- `V2__create_coffee_consumptions_table.sql` — `coffee_consumptions` (uuid PK, timestamps, `user_id` unique
  FK, `count` not null default 0, `version` for optimistic locking).
- `V3__create_events_table.sql` — the append-only `events` log, including the `created_by` (varchar) and
  nullable `note` columns and the `idx_events_seq`, `idx_events_entity_type`, `idx_events_body_id` indexes.
  Valid `entity_type` values are `User` and `CoffeeConsumption`.

## Testing Strategy

- **Unit and Integration Tests**: In `domain/src/test/kotlin/` (e.g., `UserServiceTest`, `CoffeeConsumptionServiceTest`).
- **System Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/system/`
  - Use Testcontainers for PostgreSQL and Spring's `RestTestClient`; extend `AbstractSystemTest`.
  - Cover the member self-service flow via the `X-Coffee-Token` header (view / +1 / −1 / change log /
    profile / QR), the admin flow via JWT (CRUD, role change, link rotate, override, reset with a note),
    deactivation → mutations 403, unknown/rotated token → 401, the `ConsumptionDto` response shape, and
    event-log assertions (a `CoffeeConsumption` UPDATE event per change with the right `count` /
    `created_by` / `note`).
- **Acceptance Tests**: Cucumber BDD tests in `application/src/test/kotlin/de/seuhd/campuscoffee/tests/acceptance/`
  with `.feature` files under `application/src/test/resources/...` (consumption, user administration, authorization).
- **Architecture Tests**: ArchUnit tests enforcing the hexagonal layer rules.
- Because event sourcing is the only mode, there is a single backend (no dual relational/event sourcing test split).

### Test Naming

Test methods (those annotated with `@Test` or `@ParameterizedTest`) use Kotlin backtick names that read as
a sentence describing the behavior under test: active voice, present tense, the subject under test first,
then the **outcome stated as the fact the test actually asserts** — the explicit HTTP status for system
tests (`409 Conflict`, `404 Not Found`), the exception type, or the returned value. Avoid `should` and
vague status nouns. Examples:

- ``fun `decrementing a count at zero returns 409 Conflict`()`` (system test)
- ``fun `setTotal throws ForbiddenException for a non-admin`()`` (service test)
- ``fun `findByCapabilityToken returns the matching member and null when none matches`()`` (repository test)

Non-test functions (setup methods, `@MethodSource` providers, Cucumber step definitions, private helpers)
keep conventional camelCase names.

## Key Technologies

- **Spring Boot 4** (Spring Framework 7).
- **Kotlin** on JDK 25; nullability is expressed with Kotlin's nullable types.
- **MapStruct** for object mapping (DTOs <-> domain models <-> entities), run via kapt.
- **ZXing** for backend QR-code generation.
- **ktlint** for formatting and linting (the official Kotlin style), **detekt** for static analysis, plus the custom `campus-coffee-kdoc` rule set.
- **Bean Validation** (Jakarta Validation) for input validation (in the controllers, before mapping DTOs to domain models).
- **OpenAPI/Swagger** (SpringDoc) for API documentation.
- **Spring Security**: a JWT (bearer-token) resource server for admins and a custom capability token filter for members (no HTTP Basic).
- **Testcontainers** for system tests, **Cucumber** for BDD, **ArchUnit** for architecture testing.
- **Angular** (standalone components, Angular Material, `HttpClient`) for the frontend.

## Authentication and Authorization

Two authentication mechanisms, one per audience — there is **no HTTP Basic**:

- **Admins — JWT bearer.** `POST /api/auth/token` exchanges a username and password for a signed JWT (a
  work-session TTL of ~10 hours, no refresh flow). The SPA sends it as `Authorization: Bearer …`. The
  resource server maps the token's `roles` claim to a `ROLE_ADMIN` authority. The
  `AuthenticationManager` / `DaoAuthenticationProvider` / `CampusUserDetailsService` / password-encoder
  beans exist only for this login step.
- **Members — capability token.** `CapabilityTokenAuthenticationFilter` reads the `X-Coffee-Token` header,
  resolves it to a member via `UserService.findByCapabilityToken`, and sets a `ROLE_USER` principal. The
  capability principal is **always** `ROLE_USER`, never `ROLE_ADMIN`, so an admin's own token grants only
  self-service. A missing, unknown, or rotated token leaves the request unauthenticated → 401. A
  deactivated member is still authenticated (reads work), but the domain rejects their mutations → 403.

The access rules gate the API by audience (`/api/users/**` → `ROLE_ADMIN`; `/api/consumption/**` and
`/api/profile/**` → `ROLE_USER`; `/api/auth/token`, actuator health, Swagger, dev endpoints, and the SPA
routes are public); the finer ownership rules live in the domain services. `ActorProvider` returns the
current principal's login for `created_by`; `CurrentUserProvider` resolves the principal to a domain
`User`. CORS is not configured (the SPA is same-origin); a default-empty `campus-coffee.cors.allowed-origins`
allowlist is the escape hatch if the SPA is ever hosted on a separate origin. The capability URL handling
follows the W3C "Good Practices for Capability URLs" finding — see
`doc/2026-06-20_coffee-consumption-event-sourcing-and-capability-urls.md`.

## REST API Endpoints

Base URL: `http://localhost:8080/api`. JSON only. The `/api` base is applied centrally by `ApiPathConfig`;
controllers map paths relative to the resource.

### Member self-service (auth: `X-Coffee-Token` header; principal = the token's member)

- `GET  /consumption?limit=5&offset=0` — own current total plus a page of the change log (`ConsumptionDto`).
- `POST /consumption` `{ delta: 1 | -1 }` — apply a single-step change (a `−1` at 0 → 409; `delta` ≠ ±1 → 400).
- `GET  /profile` / `PUT /profile` — view / edit own `firstName`, `lastName`, `emailAddress`; the response includes the assembled capability URL.
- `GET  /profile/qr.png` — own capability QR code (high-resolution PNG, the single format).

### Admin and user management (auth: JWT, `ROLE_ADMIN`)

- `GET /users`, `POST /users` (create; the server assigns the capability token and creates the consumption at 0), `GET/PUT/DELETE /users/{id}`.
- `PUT /users/{id}` edits the profile, `role`, and `active` (deactivate/reactivate).
- `GET /users/me` — the signed-in admin's own user (the admin landing default).
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png`.
- `GET  /users/{id}/consumption?limit=5&offset=0` — a member's total plus a page of the change log.
- `POST /users/{id}/consumption` `{ delta: 1 | -1 }` — a single-step change.
- `PUT  /users/{id}/consumption` `{ total, note? }` — the absolute override (`{ total: 0 }` is the reset after payment; `note` is the optional admin reason, ≤ 500 chars).

### Auth and dev

- `POST /auth/token` — username + password → JWT (the only admin credential; no Basic).
- `GET/PUT/DELETE /dev/data` (dev profile only) — report counts / seed fixtures / clear.

Notes on semantics:
- `/consumption` is the change log; the running `total` is a derived field in the response. Paths name
  resources, not verbs — there is no `/increment`, `/decrement`, `/reset`, `/transactions`, or `DELETE` on
  consumption. The HTTP method carries the semantics: `GET` reads (safe), `POST` appends a `±1` change
  (non-idempotent), `PUT` sets the absolute total (idempotent, admin-only). A reset is the
  `PUT { total: 0 }` balancing entry, which keeps the prior counts in the append-only log.
- A member may step their own count by `±1` only; any other adjustment is the admin's `PUT`.
- `POST /users` rejects a request body that carries an `id` (400); the server assigns ids.

## Configuration

- Main config: `application/src/main/resources/application.yaml`.
- The dev and prod profiles activate on `spring.config.activate.on-profile`.
- Custom properties (each has a `@ConfigurationProperties` class so the keys resolve in the IDE's
  `application.yaml` editor):
  - `campus-coffee.app.base-url` (`AppProperties`, api module): the public origin used to build the
    capability URLs and QR codes; `https` in prod so the token never travels over plain HTTP.
  - `campus-coffee.id.entity-seed` / `event-seed` (`IdProperties`, data module): seeds for the
    application-assigned entity and event UUIDs. A number (the default) makes the assigned ids
    deterministic and reproducible (so the seeded fixtures have stable ids); `random` uses random UUIDs.
  - `campus-coffee.persistence.events-to-data-on-startup` (data module): when `true`, rebuild the read
    tables from the log on startup. Off by default.
  - `campus-coffee.jwt.secret` (`JwtProperties`, application module): HMAC signing secret for the JWTs.
    Required and at least 32 bytes; supplied via `JWT_SECRET` (the dev profile has an insecure fallback,
    the prod profile none).
  - `campus-coffee.fixtures.load-on-startup` (`FixturesProperties`, application module): when `true` and
    the database has no users yet, load the fixtures on startup (on in dev, off in prod).
  - `campus-coffee.bootstrap-admin.*` (`BootstrapAdminProperties`, application module): when set and no
    admin exists yet, create one admin on startup (used in prod, where fixtures are off).
  - `campus-coffee.cors.allowed-origins` (`CorsProperties`, application module): a default-empty CORS
    allowlist; unused while the SPA is same-origin.

The startup tasks run before the embedded web server accepts requests (via a `SmartInitializingSingleton`,
`StartupDataInitializer`, that runs every registered `StartupTask` in `order`): the optional event-log
rebuild (order 100), then the fixture loader (200), then the bootstrap admin (300). So in dev the fixtures
seed an admin and the bootstrap step is a no-op; in prod the bootstrap step creates the admin.

## Important Patterns

### Error Handling

Domain exceptions in `domain/.../exceptions/`:
- `NotFoundException`: Entity not found (404).
- `DuplicationException`: Duplicate unique fields (409).
- `ValidationException`: Business rule violation (400) — e.g. a `delta` other than `±1`, or a decrement below zero.
- `MissingFieldException`: Required field missing (400).
- `ConcurrentUpdateException`: Optimistic-locking conflict (409) — a concurrent self-scan.
- `ForbiddenException`: Authorization failure (403) — not the owner / not an admin, or a deactivated member mutating.
- `DeletionConflictException`: Deletion blocked because other data references the entity (409).

Global exception handler: `api/.../exceptions/GlobalExceptionHandler.kt`. It extends
`ResponseEntityExceptionHandler`, so the standard Spring MVC exceptions also map to their proper status
codes (an unmapped path returns 404, a wrong HTTP method 405) instead of a generic 500. The REST API is
JSON-only (`ApiPathConfig` removes the XML message converter and pins UTF-8 on JSON).

### MapStruct Configuration

MapStruct runs as a Kotlin annotation processor via kapt, applied through the
`de.seuhd.campuscoffee.kotlin-kapt-conventions` convention plugin; the `api` and `data` modules declare
`kapt(mapstruct-processor)`. The generated `*MapperImpl` classes are excluded from the coverage and
mutation gates. A consumption is flattened to its owner's login name in the response DTO; a user's
`capabilityUrl` is assembled by the controller from the stored token (not a `User` field), and the secret
token is never accepted from a request body.

### IDE Configuration Metadata

The custom `campus-coffee.*` keys resolve in IntelliJ's `application.yaml` editor because the IDE reads the
`@ConfigurationProperties` classes from source. Two rules keep that working: every custom key has a
`*Properties` class (documented with KDoc on the class, not with comments in `application.yaml`), and the
data module is a **compile** dependency of `application` (`implementation(project(":data"))`, not
`runtimeOnly`) so the IDE resolves the data-owned keys.

### Identifier Generation

Entity ids are application-assigned `UUID`s. The domain defines an `IdGenerator` port; the data-layer
`IdGeneratorConfiguration` selects the adapter from the `campus-coffee.id.entity-seed` property. A numeric
seed (the default) yields a deterministic `SeededUuidGenerator`, so the loaded fixture ids are reproducible
across runs; `random` yields `UUID.randomUUID()`. A separate generator with its own seed
(`campus-coffee.id.event-seed`) assigns the event log's ids. The base `Entity` implements Spring Data's
`Persistable<UUID>` with a transient new-entity flag, so `repository.save()` issues an INSERT for a freshly
built entity with no preceding SELECT. There are no database sequences.

### OpenAPI Customization

Custom OpenAPI annotations in `api/.../openapi/`: `@CrudOperation` for common CRUD operations and
`CrudOperationCustomizer` for customizing the spec, reducing repetitive annotations in controllers. The
`Resource` and `Operation` enums are trimmed to the current surface (`USER`, `COFFEE_CONSUMPTION`).

## Working with the Codebase

### Adding a New Entity

1. Create the domain model in `domain/.../model/`.
2. Create the service interface in `domain/.../ports/api/` (extend `CrudService<DOMAIN, ID>`).
3. Create the data service interface in `domain/.../ports/data/` (extend `CrudDataService<DOMAIN, ID>`).
4. Create the service implementation in `domain/.../implementation/` (extend `CrudServiceImpl<DOMAIN, ID>`).
5. Create the JPA entity in `data/.../persistence/entities/`.
6. Create the repository in `data/.../persistence/repositories/` (extend `JpaRepository`).
7. Create the entity mapper in `data/.../mapper/` (extend `EntityMapper`).
8. Create the data service implementation in `data/.../implementations/` (extend `CrudDataServiceImpl<DOMAIN, ENTITY, REPOSITORY, ID>`).
9. Register it as a logged entity: add a serializer to `EventJsonMapper`, a branch to `ReadModelProjector`
   (with its `DOMAIN_CLASSES`/`DUPLICATION_RULES` entries), and an `EventSourced…DataService` decorator.
10. Create the DTO in `api/.../dtos/` (extend `Dto<ID>`) and the DTO mapper in `api/.../mapper/`.
11. Create the controller in `api/.../controller/` (extend `CrudController<DOMAIN, DTO, ID>`). Map paths
    relative to the resource; the `/api` base is applied centrally by `ApiPathConfig`.
12. Create a Flyway migration in `data/src/main/resources/db/migration/`.

### Constraint Violations

Database uniqueness constraints are converted to `DuplicationException` via `ConstraintMapping` in
`data/.../constraints/`, declared in each data-service impl (login name, email, capability token, and the
one-per-user consumption constraint). The constraint name is the single source of truth shared between the
entity companion constant, the `ConstraintMapping`, and the Flyway DDL. In event sourcing mode the
`ReadModelProjector` maps the same constraint names via its `DUPLICATION_RULES`.
