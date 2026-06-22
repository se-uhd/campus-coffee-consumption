# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CampusCoffeeConsumption is a Spring Boot application that tracks the coffee consumption of the members of
**SE@UHD** (the Software Engineering Group at Heidelberg University, hence the `de.seuhd` package). Each
member has a running coffee count, valued at a global admin-set **price per cup**, which feeds a per-member
**balance** (a prepaid-card figure) and a communal **kitty**. A member bumps their own count via a secret
**capability URL** printed as a **QR code on the wall**; scanning it opens a small mobile-first Angular web
app where they add a coffee (and may **undo** a recent one within a grace period) and record their own bean
purchases. Admins create and manage members, set the price, record expenses and kitty settlements, and
correct anyone's count. Settling up is a **settlement** (real money paid into the kitty); there is no reset.
Every change (consumptions, prices, expenses, and payments) is recorded in an append-only **event log**,
the only persistence model, from which a **unified ledger** (coffees, purchases, and settlements with a
running balance) is read. Money is stored as integer **euro cents** end to end. See
`doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`.

It follows a **hexagonal (ports-and-adapters) architecture** with strict layer separation enforced by
ArchUnit tests, derived from the CampusCoffee teaching project (the POS/review/OpenStreetMap domain was
replaced by the consumption domain).

## Architecture

The project uses a **multi-module Gradle structure** (Kotlin DSL) with four modules plus an Angular
frontend.

### Module Dependencies
- **domain**: Core business logic, domain models, and port interfaces. It depends on Bean Validation and, by design, on Spring (`@Service`/`@Component`/`@Transactional`/`@Value`/slf4j and `spring-tx`); it does not depend on the api, data, or application layers.
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

- **API Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/api/`): Generic service interface `CrudService<DOMAIN, ID>` and the concrete service interfaces `UserService`, `CoffeeConsumptionService`, `CoffeePriceService`, `ExpenseService`, `PaymentService`, and `AccountingService` (the read side: a member's summary and unified ledger, the per-member overview, and the kitty ledger and balance).
- **Data Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/data/`): Generic data service interface `CrudDataService<DOMAIN, ID>`, the concrete `UserDataService`, `CoffeeConsumptionDataService` (the latter adds `getByUserId`), `CoffeePriceDataService`, `ExpenseDataService`, and `PaymentDataService`, the event-log-backed `ConsumptionHistoryDataService` and `LedgerDataService` (the unified-ledger and kitty walk over the log), the `KittyLock` port (a Postgres advisory lock serializing the kitty-overdraw check, implemented by `PostgresKittyLock`), and the `PasswordHasher` port.
- **Infrastructure ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/`): `IdGenerator`, `CapabilityTokenGenerator`, `QrCodeGenerator`, `StartupTask`, plus the request-scoped event-metadata ports `ActorProvider` and `ChangeNoteContext`.

Service **implementations**:
- API services in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/implementation/` (`UserServiceImpl`, `CoffeeConsumptionServiceImpl`, `CoffeePriceServiceImpl`, `ExpenseServiceImpl`, `PaymentServiceImpl`, `AccountingServiceImpl`).
- Data adapters in `data/src/main/kotlin/de/seuhd/campuscoffee/data/implementations/`, and the event sourcing decorators and the ledger walk in `data/.../persistence/eventsourcing/`.

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
   `created_by` (the actor's **login name** as a string: a member via their token, an admin, or `"system"`
   for startup fixtures/bootstrap) and a nullable `note` (an admin's free-text reason for an absolute count
   correction). Both are set at the `EventStore.append*` boundary from the
   request-scoped `ActorProvider` (reads the `SecurityContext`) and `ChangeNoteContext` (a thread-local that
   the coffee-consumption service sets only around the count correction, the one operation that takes a
   reason). A settlement, kitty adjustment, or expense note is written into that entity's own event body
   (by its `Event*Serializer`), not into this metadata `note` column, so a `note` query over `events`
   returns null for those. Neither metadata field is part of the full-state JSON body,
   and the generic mutator/decorator signatures are untouched. `created_by` is a login string, not a user
   id, so the audit trail is human-readable, represents the non-user `"system"` actor naturally, and does
   not foreign key into the mutable users read model.
2. **Several logged entities, each modeled exactly like CampusCoffee's `Review`.** `CoffeeConsumption`,
   `CoffeePrice`, `Expense`, and `Payment` are all logged the same way: a domain model, a JPA read-model
   entity, a relational `*DataServiceImpl`, and a `@Primary` event-sourced decorator. Each flattens its user
   reference to an id in the event body (mirroring how a review flattened its author): a consumption and a
   payment to `userId` (a payment's is nullable, a pure kitty adjustment has none), an expense to
   `buyerUserId`. `EventJsonMapper` has a serializer per type, and `ReadModelProjector` has a branch per
   type (resolving the user reference against the users read table). A new **`LoggedEntityType`** enum (data
   layer) is the `events.entity_type` discriminator: each constant carries the persisted label
   (`User`, `CoffeeConsumption`, `CoffeePrice`, `Expense`, `Payment`) and its domain class. The projector
   dispatches a `when` over this enum, so the compiler forces a projection branch for every logged type, so a
   new logged entity cannot be forgotten. The `events.entity_type` column stays an unconstrained varchar
   (the log must remain extensible); the valid set is enforced in the application via the enum.

The optional `EventsToDataRunner` (behind `campus-coffee.persistence.events-to-data-on-startup`) rebuilds
the read tables from the log on startup, an event sourcing demonstration.

### Consumption and Money Operations Reuse the Upsert Path

The four logged entities reuse the same event-first `upsert` path; there is no new ledger-table machinery.
A coffee `+1` (a member self-scan or an admin step), an admin absolute count correction, and a price change,
expense, or payment is each a plain `upsert` of the relevant entity with its new full state, which the
decorator records as a full-state event, identical to how a review's approval count advanced. The services
expose:

- `CoffeeConsumptionService.applyDelta(userId, delta, actingUser)` and `setTotal(userId, total, note,
  actingUser)` (the admin absolute count correction; no separate reset).
- `CoffeePriceService` sets the global price (the first write creates the singleton, later writes update it
  in place; no fixed sentinel id, no special insert path).
- `ExpenseService` records and corrects bean purchases; `PaymentService` records settlements and kitty
  adjustments.

A member adds **one** coffee at a time and may **undo** their most recent un-cancelled own coffee within a
grace period (`campus-coffee.consumption.cancel-grace-period`, default 5 minutes), recorded by the owner so
the event is attributed to the member. There is no free `−1` any more. Concurrent self-scans are handled by
the entity's `@Version` optimistic-locking column → `ConcurrentUpdateException` (409); the SPA retries. An
undo past the grace period or with nothing to undo → 409; a count correction below zero → 400.

#### The Money Model and the `seq`-based As-of Valuation

Money is integer **euro cents** end to end (the read side accumulates in `Long`); there is no
floating-point arithmetic; cents are formatted to euros only in the UI. A member's **balance** is a
prepaid-card figure: **negative means they owe the fund**, positive means the fund owes them. A coffee `+1`
lowers it by the price; an undo raises it by the price of the `+1` it reverses; a member's own bean purchase
raises it; a settlement raises it (and feeds the kitty). The **kitty** is fed by settlements and admin
adjustments and drawn down by the kitty portion of admin expenses.

The kitty must never go negative. Any operation that would drive it below zero (the kitty portion of an
admin expense or a negative kitty adjustment) is refused with a 409 (`ConflictException`). The check and the
write are serialized by a Postgres advisory lock (the `KittyLock` domain port, implemented by
`PostgresKittyLock` in the data layer via `pg_advisory_xact_lock`) so two concurrent draws cannot both read
a sufficient balance and overdraw the fund (a TOCTOU race). `ExpenseService` and `PaymentService` take the
lock around the read-then-write.

The balance values each cup at the price **in effect when it was consumed** via an "as-of" join over the
log keyed on the event **append order (`seq`)**, never a wall-clock timestamp (the two per-write
`createdAt` clocks are not comparable, and the in-place price singleton would collapse a timestamp-keyed
price history to one instant). `priceAsOf(seq)` is the amount of the `CoffeePrice` event with the highest
`seq ≤ that seq`; a `+1` is valued at `priceAsOf` its own seq, an undo at the exact price of the increment
it reverses (found by walking the member's own increments LIFO), and an admin count correction as a single
lump at the correction event's seq price. See
`doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md` for the full description.

### The Change Log and Unified Ledger Are Read from the Event Log

A member's transaction history is not a table. `ConsumptionHistoryDataService` queries the `events` rows
for the consumption (`entity_type = 'CoffeeConsumption'` and `body ->> 'id' = :consumptionId`, ordered by
`seq desc` with `limit`/`offset`; the `idx_events_body_id` index covers `body ->> 'id'`). Each event body
carries the `count` at that time; the event row carries `created_at`, `created_by`, and `note`; each
entry's `delta` is the difference from the previous event.

The **unified ledger** is the same idea, broadened. `LedgerDataService` (in the event-sourcing package)
walks the log with no ledger table: a member's ledger is one ascending `seq` pass over their three streams
(consumptions, the expenses they bought, and the settlements they paid), keyed on the owning user id in each
body (`userId` for consumptions and payments, `buyerUserId` for expenses), each entry carrying a signed
effect and the running balance (only the **private** portion of an expense touches a member's balance, so an
admin split never leaks the kitty portion into the member's view). The member balance is the last running
value; the API pages it newest-first. The **kitty ledger** is the same walk over the global payment and
expense-kitty streams (admin-only; members see only the kitty balance, in their summary). New owner-key
expression indexes (`body->>'userId'`, `body->>'buyerUserId'`) keep those scans efficient.

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
  JDK 25 must be present on the machine (mise supplies it).
- Node 24 is provisioned via `mise.toml` for the frontend build, lint, and tests.
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
- Resets the data on every dev start (`campus-coffee.fixtures.reset-on-startup: true`): clears the data and
  reseeds the fixtures (and the demo data below), so each restart returns to the same deterministic state.
- Layers on **dev demo data** via the dev-only `DevDemoDataLoader` (`@Profile("dev")`, a `StartupTask` at
  order 260, after the fixture reset+reseed and the price seed): about nine extra members (a mix of roles and
  active states) and an initial kitty float, and it also enriches **every existing fixture user** (the admin
  `jane_doe` included) with varied consumption, bean-purchase, and deposit history, so the members list
  paginates and the ledger and change-log views are non-empty for almost everyone. Two members are
  deliberately left **empty** to demo the empty state: a freshly created active member `new_user` (no history
  at all) and the inactive demo member `hannes_schulz`. Demo members that get history are created active,
  given their history, then deactivated last (seeding history onto an inactive member is rejected by the
  domain). It is `@Profile("dev")`, so the tests still see exactly the five-member fixture set.
- Registers the dev-only `DevController` (in the `api` layer) under `/api/dev`:
  `GET /api/dev/data` reports the counts, `PUT /api/dev/data` replaces the data with the fixtures
  (clear + seed; idempotent, reassigning the same seeded ids), and `DELETE /api/dev/data` clears it.

### Rebuild the read model from the event log (optional)

Event sourcing is always on. To rebuild the relational read tables from the log on startup (clear the
tables, replay the whole log in append order, an event sourcing demonstration), restart with:

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

The `:application:test` task (the system, acceptance, and architecture tests, the slow part of the build)
runs in parallel across several JVM processes; `maxParallelForks` defaults to `min(4, cpu / 2)`. This is
safe because each fork is a separate JVM with its own `SystemTestUtils` (an `object` with a shared mutable
`RestTestClient`) and its own Testcontainers PostgreSQL instance, and JUnit runs the classes within a fork
serially, so two tests never touch the shared client at once and `clearAll()` never wipes a running test's
data (in-JVM parallelism would race on both). Each fork boots a full Spring context and a database
container, so on a machine with little memory override the count with `-PtestForks=N`; `-PtestForks=1`
disables parallelism.

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
- **"Both" coverage, the e2e run feeds both gates.** The Playwright e2e contributes coverage to *both*
  sides:
  - **Backend (JVM) e2e coverage merged into the JaCoCo gate.** `gradle :coverage:runE2eCoverage`
    (`scripts/run-e2e-coverage.sh`) builds the source-mapped SPA + jar, launches it under the JaCoCo agent
    (`-javaagent:…=destfile=coverage/build/jacoco/e2e.exec,output=file,append=false`) on the dev profile
    against PostgreSQL on `:5432`, waits for `/actuator/health`, runs the e2e (`PW_COVERAGE=1 npm run
    e2e`), then SIGTERMs the app so the agent flushes `e2e.exec`. The aggregate report and `coverageGate`
    in `coverage/build.gradle.kts` add `coverage/build/jacoco/e2e.exec` to their `executionData` *when it
    exists*, so an e2e run's HTTP traffic counts toward the same gate; a plain `gradle build` (no
    `e2e.exec`) degrades to the in-JVM coverage alone. The agent jar is resolved from the `jacocoAgentJar`
    configuration. The task is opt-in (not wired into `check`) because it is orchestration-heavy and needs
    a running Postgres + Playwright's chromium. After it runs, `gradle :coverage:coverageGate` folds
    `e2e.exec` in.
  - **Frontend unit coverage (Vitest).** `npm run test:coverage` (the `coverage` configuration of the
    Angular `@angular/build:unit-test` builder, `@vitest/coverage-v8`) writes lcov + HTML + text-summary
    under `frontend/coverage/`. The thresholds in `angular.json` are a deliberately *low floor*
    (statements/functions/lines 1%, branches 0%): the suite is a single service spec (~1% of the app), so
    the floor only guards against a regression to zero, not a real coverage target. Raise it as the unit
    suite grows.
  - **Frontend e2e (browser) coverage.** The e2e run also captures the SPA TypeScript coverage the JaCoCo
    agent can never see: a per-test Playwright fixture (`frontend/e2e/fixtures.ts`) turns on Chromium V8
    coverage under `PW_COVERAGE=1`, and a global teardown (`frontend/e2e/coverage.global-teardown.ts`)
    source-maps it onto the `.ts` sources with `monocart-coverage-reports`, emitting lcov + HTML under
    `frontend/coverage-e2e/` (~70% of the app TS). The source maps come from the `coverage` Angular build
    configuration (`npm run build:coverage`, `sourceMap.scripts: true`, no output hashing), which
    `scripts/run-e2e-coverage.sh` builds and `:application:bootJar -PskipFrontendBuild` bundles as-is. A
    plain `npm run e2e` (no `PW_COVERAGE`) is unaffected.
  - **CI.** A separate `e2e` job in `.github/workflows/build.yml` runs the whole flow against a PostgreSQL
    service container and uploads the coverage artifacts (`e2e.exec`, the aggregate report, and
    `frontend/coverage-e2e/`); the core `build` job is unchanged.

### Frontend

The Angular 22 SPA (TypeScript 6, Angular Material 22, on Node 24 via mise) lives in `frontend/` and is
built by Gradle (a Node-Gradle task runs `npm ci` + `npm run build` and copies `frontend/dist/**/browser/`
into `application/src/main/resources/static/`, wired before `:application:processResources`/`bootJar`), so
`gradle build` produces one self-contained jar. For frontend development run the Angular dev server, which
proxies `/api` to the backend on `:8080`:

```shell
cd frontend && npm start
```

**Frontend static analysis (wired into `gradle check`).** The `frontendLint` Gradle task (in
`application/build.gradle.kts`) runs `npm run lint` (**angular-eslint** plus **Stylelint** for the SCSS)
and is bound to `check`, so `gradle build` and CI fail on a lint violation. **Prettier** (`npm run format` /
`format:check`) and **Knip** (`npm run knip`, dead-code/unused-dependency detection) round out the toolset.

**OpenAPI → frontend-DTO codegen.** The TypeScript request/response DTOs are generated from the backend
OpenAPI spec, not written by hand. The `generateFrontendDtos` Gradle task runs
`scripts/generate-frontend-dtos.sh`, which generates into `frontend/src/app/api/model/` and is **hash-skip**
(it regenerates only when the committed spec `frontend/src-gen/api-docs.json` changes). It is a dependency
of `frontendBuild` and `frontendLint`, so the build keeps the DTOs current. `frontend/src/app/models.ts`
re-exports the generated DTOs (aliasing request bodies and surfacing the enum unions), so the rest of the
SPA imports from `models.ts` and the frontend/backend contracts cannot drift. **Do not hand-edit
`frontend/src/app/api/model/`**: regenerate it.

**Frontend tests and coverage** (run via mise's Node): `npm test` (Vitest unit tests), `npm run
test:coverage` (the same with a coverage report under `frontend/coverage/`), `npm run e2e` (Playwright
against an already-running app on `:8080`, the suite in `frontend/e2e/`), and `PW_COVERAGE=1 npm run e2e`
(the e2e with browser V8 coverage written to `frontend/coverage-e2e/`). See **Code Coverage and Mutation
Testing** for how the e2e also feeds the backend JaCoCo gate.

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
  `project.version`). The newest `## [x.y.z]` release header in `CHANGELOG.md` must equal it, enforced by
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

The schema is a seven-migration set:
- `V1__create_users_table.sql`: `users` (uuid PK, timestamps, `login_name` unique, `email_address` unique,
  `first_name`, `last_name`, a single `role` column (`USER`/`ADMIN`, no `user_roles` table), `active`,
  `password_hash` nullable, `capability_token` unique).
- `V2__create_coffee_consumptions_table.sql`: `coffee_consumptions` (uuid PK, timestamps, `user_id` unique
  FK with `ON DELETE CASCADE`, `count` not null default 0, `version` for optimistic locking).
- `V3__create_events_table.sql`: the append-only `events` log, including the `created_by` (varchar) and
  nullable `note` columns and the `idx_events_seq`, `idx_events_entity_type`, `idx_events_body_id` indexes.
  The `entity_type` column is an unconstrained varchar; the valid set (`User`, `CoffeeConsumption`,
  `CoffeePrice`, `Expense`, `Payment`) is enforced in the application by the `LoggedEntityType` enum.
- `V4__create_coffee_prices_table.sql`: `coffee_prices` (uuid PK, timestamps, `amount_cents`, `version`,
  plus an `is_singleton boolean` column (NOT NULL DEFAULT true, with a CHECK) and a
  `uq_coffee_prices_singleton` UNIQUE constraint over it that enforces the one-row invariant at the schema
  level; the entity does not map the column, the DB default supplies it); a single global row whose every
  change is a full-state event, so the log is the price history.
- `V5__create_expenses_table.sql`: `expenses` (uuid PK, timestamps, `buyer_user_id` FK with the default
  RESTRICT, `weight_grams`, `amount_cents`, `private_amount_cents`, `kitty_amount_cents`, `note`, `version`,
  and a `ck_expenses_split` CHECK that `private_amount_cents + kitty_amount_cents = amount_cents`).
- `V6__create_payments_table.sql`: `payments` (uuid PK, timestamps, nullable `user_id` FK with the default
  RESTRICT (present is a settlement, null is a pure kitty adjustment), `amount_cents` signed, `note`,
  `version`).
- `V7__add_event_owner_indexes.sql`: the owner-key expression indexes on `events`
  (`idx_events_body_user_id` on `body->>'userId'`, `idx_events_body_buyer_id` on `body->>'buyerUserId'`)
  that keep the unified-ledger and kitty walks efficient.

The `expenses` and `payments` FKs keep PostgreSQL's default RESTRICT (NO ACTION) so a member's financial
history is never silently dropped
(the user service refuses to hard-delete a member with any financial footprint, see Error Handling, so an
admin deactivates them instead); `coffee_consumptions` stays `CASCADE` because every user always has a
(often zero) consumption row, so a `RESTRICT` there would make no user deletable.

## Testing Strategy

- **Unit and Integration Tests**: In `domain/src/test/kotlin/` (e.g., `UserServiceTest`, `CoffeeConsumptionServiceTest`).
- **System Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/system/`
  - Use Testcontainers for PostgreSQL and Spring's `RestTestClient`; extend `AbstractSystemTest`.
  - Cover the member self-service flow via the `X-Coffee-Token` header (summary / add a coffee / undo /
    change log / unified ledger / own expense / profile / QR), the admin flow via JWT (CRUD, role change,
    link rotate, count correction with a note, price change, expenses with a split, settlements and kitty
    adjustments, the kitty ledger and the per-member overview), the money model (balances valued at the
    as-of price, the kitty balance), deactivation → mutations 403, deleting a member with financial history
    → 409, unknown/rotated token → 401, the response shapes, and event-log assertions (an event per change
    with the right body / `created_by` / `note`).
- **Acceptance Tests**: Cucumber BDD tests in `application/src/test/kotlin/de/seuhd/campuscoffee/tests/acceptance/`
  with `.feature` files under `application/src/test/resources/...` (consumption, pricing and the fund,
  user administration, authorization).
- **Architecture Tests**: ArchUnit tests enforcing the hexagonal layer rules.
- Because event sourcing is the only mode, there is a single backend (no dual relational/event sourcing test split).

### Test Naming

Test methods (those annotated with `@Test` or `@ParameterizedTest`) use Kotlin backtick names that read as
a sentence describing the behavior under test: active voice, present tense, the subject under test first,
then the **outcome stated as the fact the test actually asserts**: the explicit HTTP status for system
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
- **Angular 22** (standalone components, signal `input()`/`output()` and `computed`, `@defer`/`@let`,
  Angular Material 22, `HttpClient`) on **TypeScript 6** and **Node 24** for the frontend. Unit tests run on
  **Vitest** (not Karma/Jasmine); end-to-end tests on **Playwright**.
- **angular-eslint + Prettier + Stylelint + Knip** for frontend static analysis, all wired into
  `gradle check`; **Qodana** (a JVM job and a JS/TS job) in CI on top of ktlint/detekt.

## Authentication and Authorization

Two authentication mechanisms, one per audience; there is **no HTTP Basic**:

- **Admins, JWT bearer.** `POST /api/auth/token` exchanges a username and password for a signed JWT (a
  work-session TTL of ~10 hours, no refresh flow). The SPA sends it as `Authorization: Bearer …`. The
  resource server maps the token's `roles` claim to a `ROLE_ADMIN` authority. The
  `AuthenticationManager` / `DaoAuthenticationProvider` / `CampusUserDetailsService` / password-encoder
  beans exist only for this login step.
- **Members, capability token.** `CapabilityTokenAuthenticationFilter` reads the `X-Coffee-Token` header,
  resolves it to a member via `UserService.findByCapabilityToken`, and sets a `ROLE_USER` principal. The
  capability principal is **always** `ROLE_USER`, never `ROLE_ADMIN`, so an admin's own token grants only
  self-service. A missing, unknown, or rotated token leaves the request unauthenticated → 401. A
  deactivated member is still authenticated (reads work), but the domain rejects their mutations → 403.

The access rules gate the API by audience (`/api/users/**`, `/api/price/**`, and `/api/kitty/**` → `ROLE_ADMIN`; `/api/consumption/**`, `/api/expenses/**`, `/api/profile/**`,
`/api/summary`, and `/api/activity` → `ROLE_USER`; `/api/auth/token`, actuator health, Swagger, dev
endpoints, and the SPA routes are public); the finer ownership rules live in the domain services.
`ActorProvider` returns the
current principal's login for `created_by`; `CurrentUserProvider` resolves the principal to a domain
`User`. CORS is not configured (the SPA is same-origin); a default-empty `campus-coffee.cors.allowed-origins`
allowlist is the escape hatch if the SPA is ever hosted on a separate origin. The capability URL handling
follows the W3C "Good Practices for Capability URLs" finding (see
`doc/2026-06-20_coffee-consumption-event-sourcing-and-capability-urls.md`).

## REST API Endpoints

Base URL: `http://localhost:8080/api`. JSON only. The `/api` base is applied centrally by `ApiPathConfig`;
controllers map paths relative to the resource.

### Member self-service (auth: `X-Coffee-Token` header; principal = the token's member)

- `GET  /summary?ledgerLimit=10&ledgerOffset=0`: the member landing in one call (`MemberSummaryDto`):
  current total, balance, the current price, the kitty balance, whether the most recent coffee is still
  `cancellable`, and the first page of the unified `ledger` (`ledgerLimit` defaults to 10).
- `POST /consumption` (no body): add one coffee, returns the summary.
- `POST /consumption/cancel`: undo the most recent un-cancelled own coffee within the grace period (nothing
  to undo / past the grace period → 409).
- `GET  /activity?limit=20&offset=0`: own unified ledger (coffees, own purchases, settlements) newest-first,
  each entry with a running balance.
- `POST /expenses` `{ amountCents, weightGrams, note? }`: record an own bean purchase (booked 100% private
  to the member; the buyer and split are server-derived).
- `GET  /profile` / `PUT /profile`: view / edit own `firstName`, `lastName`, `emailAddress`; the response includes the assembled capability URL.
- `GET  /profile/qr.png`: own capability QR code (high-resolution PNG, the single format).

### Admin and user management (auth: JWT, `ROLE_ADMIN`)

- `GET /users`, `POST /users` (create; the server assigns the capability token and creates the consumption at 0), `GET/PUT/DELETE /users/{id}`.
- `PUT /users/{id}` edits the profile, `role`, and `active` (deactivate/reactivate).
- `DELETE /users/{id}` hard-deletes a member; refused (409) if the member has any financial history (deactivate instead).
- `GET /users/me`: the signed-in admin's own user (the admin landing default).
- `GET /users/filter?login_name=…`: filter users by query params (e.g. by login name).
- `GET /users/overview`: a per-member overview (counts and balances); it now renders in the member-management page (`/admin/users`).
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png` (downloads as
  `<loginName>.png`, transparent background).
- `GET /users/qr.zip`: a streamed ZIP of every member's QR code as `<loginName>.png` (capped at 1000
  members; powers the admin "Download all QR codes" button).
- `GET  /users/{id}/consumption?limit=5&offset=0`: a member's total plus a page of the change log.
- `GET  /users/{id}/activity?limit=20&offset=0`: a member's unified ledger.
- `POST /users/{id}/consumption` `{ delta: 1 | -1 }`: a single-step change.
- `PUT  /users/{id}/consumption` `{ total, note? }`: the absolute count correction (`note` is the optional admin reason, ≤ 500 chars).
- `POST/PUT/DELETE /users/{id}/expenses` `{ amountCents, privateAmountCents, kittyAmountCents, weightGrams, note? }`: record / correct / delete a member's bean purchase with an explicit private/kitty split (must sum to the total) attributed to the member as buyer.
- `GET /price`: read the current global price (admin-only; members receive it through their landing summary).
- `PUT /price` `{ amountCents }`: set the global price; `GET /price/history` reads the full price history from the log.
- `POST /kitty/deposit` `{ userId, amountCents, note? }`: a member pays money into the kitty (credits the member, feeds the kitty).
- `POST /kitty/adjustment` `{ amountCents, note? }`: a pure kitty adjustment (an initial float or a correction).
- `GET /kitty/history?limit=50&offset=0`: the kitty ledger (settlements and admin expenses, with the running kitty balance).

### Auth and dev

- `POST /auth/token`: username + password → JWT (the only admin credential; no Basic).
- `GET/PUT/DELETE /dev/data` (dev profile only): report counts / seed fixtures / clear.

Notes on semantics:
- `/consumption` is the change log; the running `total` is a derived field in the response. Paths name
  resources, not verbs; there is no `/increment`, `/decrement`, `/reset`, or `/transactions`. The HTTP
  method carries the semantics: `GET` reads (safe), member `POST /consumption` adds one coffee
  (non-idempotent), `POST /consumption/cancel` undoes the most recent one within the grace period, and admin
  `PUT` sets the absolute total (idempotent, admin-only). There is no reset; settling up is a settlement
  (real money) and an admin count change is a correction; both keep the prior entries in the append-only
  log.
- A member adds one coffee at a time and may undo a recent one; any other count adjustment is the admin's
  `PUT`. Money is integer **euro cents** in every request and response.
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
  - `campus-coffee.price.initial-cents` (`CoffeePriceProperties`, application module): the initial price per
    cup, in euro cents, seeded on first startup when no price exists yet. Default 50.
  - `campus-coffee.consumption.cancel-grace-period` (`ConsumptionProperties`, api module): how long after
    adding a coffee a member may still undo it. A `Duration`, default 5 minutes.
  - `campus-coffee.jwt.secret` (`JwtProperties`, application module): HMAC signing secret for the JWTs.
    Required and at least 32 bytes; supplied via `JWT_SECRET` (the dev profile has an insecure fallback,
    the prod profile none).
  - `campus-coffee.fixtures.load-on-startup` (`FixturesProperties`, application module): when `true` and
    the database has no users yet, load the fixtures on startup (on in dev, off in prod).
  - `campus-coffee.fixtures.reset-on-startup` (`FixturesProperties`, application module): when `true`, clear
    the data and reseed the fixtures on every startup (on in dev, off in prod), so each dev restart returns
    to the deterministic seeded state.
  - `campus-coffee.bootstrap-admin.*` (`BootstrapAdminProperties`, application module): when set and no
    admin exists yet, create one admin on startup (used in prod, where fixtures are off).
  - `campus-coffee.cors.allowed-origins` (`CorsProperties`, application module): a default-empty CORS
    allowlist; unused while the SPA is same-origin.

The startup tasks run before the embedded web server accepts requests (via a `SmartInitializingSingleton`,
`StartupDataInitializer`, that runs every registered `StartupTask` in `order`): the optional event-log
rebuild (order 100), then the fixture loader (200), then the price seeder (`CoffeePriceStartupLoader`, 250,
seeds `campus-coffee.price.initial-cents` when no price exists yet so a price exists before any coffee is
consumed), then the dev-only `DevDemoDataLoader` (260, `@Profile("dev")`), then the bootstrap admin (300).
So in dev the fixtures seed an admin and the bootstrap step is a no-op; in prod the bootstrap step creates
the admin (and the demo loader does not run).

## Important Patterns

### Error Handling

Domain exceptions in `domain/.../exceptions/`:
- `NotFoundException`: Entity not found (404).
- `DuplicationException`: Duplicate unique fields (409).
- `ValidationException`: Malformed input / business rule violation (400), e.g. a `delta` other than `±1`, a count correction below zero, or an expense whose split does not sum to its total.
- `MissingFieldException`: Required field missing (400).
- `ConflictException`: A well-formed request that conflicts with the resource's current state (409), e.g. a `−1` at 0, an undo with nothing to undo or past the grace period, or an operation that would drive the kitty below zero (the kitty-overdraw guard, see below).
- `ConcurrentUpdateException`: Optimistic-locking conflict (409), a concurrent self-scan; the SPA retries.
- `ForbiddenException`: Authorization failure (403), not the owner / not an admin, or a deactivated member mutating.
- `DeletionConflictException`: Deletion blocked because other data references the entity (409), e.g. hard-deleting a member who has any financial history (a non-zero count, or any expense or settlement); the admin deactivates them instead.

Global exception handler: `api/.../exceptions/GlobalExceptionHandler.kt`. It extends
`ResponseEntityExceptionHandler`, so the standard Spring MVC exceptions also map to their proper status
codes (an unmapped path returns 404, a wrong HTTP method 405) instead of a generic 500. It also maps a
Jakarta `ConstraintViolationException` (an out-of-range paging `limit`/`offset` that violates the
`@Max`/`@Min`/`@Positive` bounds) to a clean 400 instead of letting it fall through to a generic 500. The
REST API is JSON-only (`ApiPathConfig` removes the XML message converter and pins UTF-8 on JSON).

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
9. Register it as a logged entity: add a constant to the `LoggedEntityType` enum (the `events.entity_type`
   discriminator; the projector's `when` is exhaustive over it, so the compiler will require the next
   step), add a serializer to `EventJsonMapper`, a branch to `ReadModelProjector` (with its
   `DOMAIN_CLASSES`/`DUPLICATION_RULES` entries), and an `EventSourced…DataService` decorator. An entity
   with no database-unique key passes `emptySet()` constraints (and contributes no `DUPLICATION_RULES`).
10. Create the DTO in `api/.../dtos/` (extend `Dto<ID>`) and the DTO mapper in `api/.../mapper/`.
11. Create the controller in `api/.../controller/` (extend `CrudController<DOMAIN, DTO, ID>`). Map paths
    relative to the resource; the `/api` base is applied centrally by `ApiPathConfig`.
12. Create a Flyway migration in `data/src/main/resources/db/migration/`.

### Constraint Violations

Database uniqueness constraints are converted to `DuplicationException` via `ConstraintMapping` in
`data/.../constraints/`, declared in each data-service impl (login name, email, capability token, and the
one-per-user consumption constraint). An entity with no database-unique key (`CoffeePrice`, `Expense`,
`Payment`) declares `emptySet()`. The constraint name is the single source of truth shared between the
entity companion constant, the `ConstraintMapping`, and the Flyway DDL. In event sourcing mode the
`ReadModelProjector` maps the same constraint names via its `DUPLICATION_RULES`.
