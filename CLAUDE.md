# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CampusCoffeeConsumption is a Spring Boot application that tracks the coffee consumption of the users of
**SE@UHD** (the Software Engineering Group at Heidelberg University, hence the `de.seuhd` package). Each
user has a running coffee count, valued at a global admin-set **price per cup**, which feeds a per-user
**balance** (a prepaid-card figure) and a communal **kitty**. A user bumps their own count via a secret
**capability URL** printed as a **QR code on the wall**; scanning it opens a small mobile-first Angular web
app where they add a coffee (and may **undo** a recent one within a grace period) and record their own bean
purchases. Admins create and manage users, set the price, record expenses and kitty deposits, and
correct anyone's count. Settling up is a **deposit** (real money paid into the kitty); there is no reset.
Every change (consumptions, prices, expenses, and payments) is recorded in an append-only **event log**,
the only persistence model, from which a **unified activity feed** (coffees, purchases, and deposits with a
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
- **api**: REST API layer: controllers, DTOs, DTO mappers, and the inbound web security (the Spring Security filter chain, the capability-token filter, the `UserDetailsService`, the JWT config, and the public base-url guard) (depends on: domain).
- **data**: Data layer with JPA entities, repositories, the event sourcing machinery, and the QR/capability token adapters (depends on: domain).
- **application**: The composition root: the Spring Boot main class plus the startup/bootstrap seeders and their `@ConfigurationProperties`. It holds no web, security, or business code (depends on: domain, api, data).
- **frontend**: Angular SPA (sibling of the modules), built by Gradle and bundled into the application's `static/` resources.

### Layer Rules (Enforced by ArchUnit)
From `application/src/test/kotlin/de/seuhd/campuscoffee/tests/architecture/ArchitectureTests.kt`:

- **api** layer may only be accessed by **application**.
- **domain** layer may only be accessed by **api**, **data**, and **application**.
- **data** layer may only be accessed by **application**.
- **application** layer may not be accessed by any layer.

### Ports and Adapters Pattern

The domain defines **port interfaces** that adapters implement:

- **API Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/api/`): Generic service interface `CrudService<DOMAIN, ID>` and the concrete service interfaces `UserService`, `CoffeeConsumptionService`, `CoffeePriceService`, `ExpenseService`, `PaymentService`, `AccountingService` (the read-side money **numbers**: a user's summary, the kitty balance, and the per-user overview), and `ActivityService` (the read-side chronological **feeds**: a user's unified activity, the kitty history, and the admin global activity across everyone plus its CSV export). The two mirror the data-layer split (`ActivityDataService` for feeds beside `BalanceDataService` for the scalar balances).
- **Data Ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/data/`): Generic data service interface `CrudDataService<DOMAIN, ID>`, the concrete `UserDataService`, `CoffeeConsumptionDataService` (the latter adds `getByUserId`), `CoffeePriceDataService`, `ExpenseDataService`, and `PaymentDataService`, the event-log-backed `ConsumptionHistoryDataService` and `ActivityDataService` (one shared `ActivityWalk` over the log with three views: a user's unified activity, the kitty history, and `globalActivity` across everyone), the `BalanceDataService` port (reads the maintained `user_balance`/`kitty_balance` projections, implemented by `BalanceDataServiceImpl`), the `BalanceLockService` port (two Postgres advisory locks, `lockKitty()` serializing the kitty-overdraw check and `lockUser(userId)` serializing a user's balance recompute, implemented by `BalanceLockServiceImpl`), and the `PasswordHasherService` port.
- **SPI / infrastructure ports** (`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/ports/system/`): `IdGeneratorService`, `CapabilityTokenGeneratorService`, `QrCodeService` (the single QR port: a high-resolution PNG **and** the printable PDF grid), `StartupTaskService`, and the request-scoped `ActorProviderService`. The change-note metadata holder is a concrete class, not a port: `ChangeNoteContext` lives in `domain/.../model/`.

Service **implementations**:
- API services in `domain/src/main/kotlin/de/seuhd/campuscoffee/domain/implementation/` (`UserServiceImpl`, `CoffeeConsumptionServiceImpl`, `CoffeePriceServiceImpl`, `ExpenseServiceImpl`, `PaymentServiceImpl`, `AccountingServiceImpl`).
- Data services (`*DataServiceImpl`) in `data/.../implementations/`; the technology adapters (`QrCodeServiceImpl`, `PasswordHasherServiceImpl`, `CapabilityTokenGeneratorServiceImpl`, `IdGeneratorServiceImpl`) in `data/.../adapters/`; the Postgres advisory-lock `BalanceLockServiceImpl`, the event-sourcing decorators, the activity walk, and `BalanceDataServiceImpl` in `data/.../persistence/` (and its `eventsourcing/` sub-package).

### Naming and File Conventions

Follow these in new code (enforced by review):

- **A cross-module interface is a port, named `*Service` (or `*DataService` for a data-layer port), and its implementation is `<Port>Impl`.** "Cross-module" means the interface is defined in one module and *implemented or consumed in another*: the domain API services are consumed by `api` (`UserService`/`UserServiceImpl`); the domain data ports are implemented by `data` (`UserDataService`/`UserDataServiceImpl`); the SPI ports are implemented by `data` (`QrCodeService`/`QrCodeServiceImpl`, `IdGeneratorService`/`IdGeneratorServiceImpl`). An interface defined, implemented, **and** used entirely within one module is free-named.
- **An implementation name never leaks a library or vendor.** A single-impl port's adapter is `<Port>Impl`, never `ZxingQrCodeService` / `PostgresBalanceLock` / `BCryptPasswordHasher`. When a port genuinely has several implementations, name them by behavior/role, not the library: the `EventSourced*` decorators that wrap the relational `*DataServiceImpl`, and the `StartupTaskService` loaders.
- **A concrete class is not a port:** it keeps a plain descriptive name and stays out of `ports/` (e.g. `ChangeNoteContext`, a request-scoped holder, lives in `domain/.../model/`).
- **Utility helpers are `*Util`:** a file (or class) of pure, stateless functions with no injected dependencies (`ReadUtil`, `ActivityWalkUtil`). This differs from a package of injected collaborator beans (the controllers' `api/support` package): those are wired `@Component`s, not free functions, so they are not `*Util`.
- **Database entities are `*Entity`** (`UserEntity`, `EventEntity`).
- **One top-level type per file.** Each file holds one class / interface / object / enum. Related top-level functions, constants, and extension functions may sit beside the single type they support (`WalkedRecord.kt` carries its mapper functions; `ReadUtil.kt` is functions only, no type). The only multi-type exception is a tightly-bound sealed hierarchy.
- **Frontend Angular services are `*Service`** (`AdminUserService`, `AdminSelectionService`), including the stateful signal-holding singletons. No `*Store` (an NgRx idiom this app does not use).

Package layout reflecting the above: `domain/ports/{api,data,system}/`; `data/{implementations (only `*DataServiceImpl`), adapters (technology adapters), persistence (entities, repositories, eventsourcing, the advisory-lock impl)}`; `api/{app (the SPA-forwarding controller), support (controller-delegate helper beans), controller, dtos, mapper, security, openapi, configuration, exceptions}`.

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
- `EventSourcedWriter.upsert(domain, getById, buildForInsert, buildForUpdate)` is the shared event-first
  logic; it assigns the id and timestamps, appends, and projects, holding no per-type knowledge.
- The projection reuses the MapStruct entity mappers and preserves the id and timestamps from the event
  body (`Entity.markTimestampsPreassigned()`). Read requests are served from the materialized tables (no
  replay on read).

Two additions versus CampusCoffee's event machinery:

1. **`created_by` and `note` metadata on the generic event.** `events` and `EventEntity` carry a
   `created_by` (the actor's **login name** as a string: a user via their token, an admin, or `"system"`
   for startup fixtures/bootstrap) and a nullable `note` (the free-text note associated with the event). Both
   are set at the single `EventStore.append*` boundary from the request-scoped `ActorProviderService` (reads the
   `SecurityContext`) and the note source. The note is unified at that boundary: it is the admin's
   count-correction reason when present (from `ChangeNoteContext`, the thread-local the coffee-consumption
   service sets only around the count correction, the one operation that takes a reason with no entity field
   of its own), otherwise the entity's own note carried in the event body (a deposit, kitty adjustment, or
   expense note, which is also entity state and is projected to that entity's read-model `note` column). So
   `events.note` is the one canonical, queryable note for every event, and the activity and kitty feeds read
   that single column. A DELETE body carries only the id, so it contributes no note. Neither metadata field
   is part of the full-state JSON body, and the generic writer/decorator signatures are untouched.
   `created_by` is a login string, not a user id, so the audit trail is human-readable, represents the
   non-user `"system"` actor naturally, and does not foreign key into the mutable users read model.
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

The four logged entities reuse the same event-first `upsert` path; there is no new activity-table machinery.
A coffee `+1` (a user self-scan or an admin step), an admin absolute count correction, and a price change,
expense, or payment is each a plain `upsert` of the relevant entity with its new full state, which the
decorator records as a full-state event, identical to how a review's approval count advanced. The services
expose:

- `CoffeeConsumptionService.applyDelta(userId, delta, actingUser)` and `setTotal(userId, total, note,
  actingUser)` (the admin absolute count correction; no separate reset).
- `CoffeePriceService` sets the global price (the first write creates the singleton, later writes update it
  in place; no fixed sentinel id, no special insert path).
- `ExpenseService` records and corrects bean purchases; `PaymentService` records deposits and kitty
  adjustments.

A user adds **one** coffee at a time and may **undo** their most recent un-cancelled own coffee within a
grace period (`campus-coffee.consumption.cancel-grace-period`, default 5 minutes), recorded by the owner so
the event is attributed to the user. There is no free `−1` any more. Concurrent self-scans are handled by
the entity's `@Version` optimistic-locking column → `ConcurrentUpdateException` (409); the SPA retries. An
undo past the grace period or with nothing to undo → 409; a count correction below zero → 400.

#### The Money Model and the `seq`-based As-of Valuation

Money is integer **euro cents** end to end (the read side accumulates in `Long`); there is no
floating-point arithmetic; cents are formatted to euros only in the UI. A user's **balance** is a
prepaid-card figure: **negative means they owe the fund**, positive means the fund owes them. A coffee `+1`
lowers it by the price; an undo raises it by the price of the `+1` it reverses; a user's own bean purchase
raises it; a deposit raises it (and feeds the kitty). The **kitty** is fed by deposits and admin
adjustments and drawn down by the kitty portion of admin expenses.

The kitty must never go negative. Any operation that would drive it below zero (the kitty portion of an
admin expense or a negative kitty adjustment) is refused with a 409 (`ConflictException`). The check and the
write are serialized by a Postgres advisory lock (the `BalanceLockService` domain port, implemented by
`BalanceLockServiceImpl` in the data layer via `pg_advisory_xact_lock`) so two concurrent draws cannot both read
a sufficient balance and overdraw the fund (a TOCTOU race). The overdraw-checking paths (`ExpenseService.record`/
`update` and `PaymentService.adjustKitty`) take `lockKitty()` around the read-then-write. The other
balance-projection writes are serialized at one place, `BalanceDataServiceImpl.maintain`: it takes `lockKitty()`
around every kitty recompute and a per-user `lockUser(userId)` around every user recompute, in the fixed
order kitty-before-user so the paths cannot deadlock. So a write that moves the kitty but has no overdraw
check (a deposit, an expense delete) still cannot lost-update the kitty, and a user's balance, recomputed by
self-scans, purchases, deposits, and admin steps with no shared versioned row, cannot lost-update either.

The balance values each cup at the price **in effect when it was consumed** via an "as-of" join over the
log keyed on the event **append order (`seq`)**, never a wall-clock timestamp (the two per-write
`createdAt` clocks are not comparable, and the in-place price singleton would collapse a timestamp-keyed
price history to one instant). `priceAsOf(seq)` is the amount of the `CoffeePrice` event with the highest
`seq ≤ that seq`; a `+1` is valued at `priceAsOf` its own seq, an undo at the exact price of the increment
it reverses (found by walking the user's own increments LIFO), and an admin count correction as a single
lump at the correction event's seq price. See
`doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md` for the full description.

### The Change Log and Unified Activity Feed Are Read from the Event Log

A user's transaction history is not a table. `ConsumptionHistoryDataService` queries the `events` rows
for the consumption (`entity_type = 'CoffeeConsumption'` and `body ->> 'id' = :consumptionId`, ordered by
`seq desc` with `limit`/`offset`; the `idx_events_body_id` index covers `body ->> 'id'`). Each event body
carries the `count` at that time; the event row carries `created_at`, `created_by`, and `note`; each
entry's `delta` is the difference from the previous event.

The **unified activity feed** is the same idea, broadened. `ActivityDataService` (in the event-sourcing package)
walks the log with no activity table: a user's activity is one ascending `seq` pass over their three streams
(consumptions, the expenses they bought, and the deposits they paid), keyed on the owning user id in each
body (`userId` for consumptions and payments, `buyerUserId` for expenses), each entry carrying a signed
effect and the running balance (only the **private** portion of an expense touches a user's balance, so an
admin split never leaks the kitty portion into the user's view). The user balance is the last running
value; the API pages it newest-first. The **kitty history** is the same walk over the global payment and
expense-kitty streams (admin-only; users see only the kitty balance, in their summary). New owner-key
expression indexes (`body->>'userId'`, `body->>'buyerUserId'`) keep those scans efficient.

The activity and kitty **lists** are read by walking the log (the per-entry running balances are intrinsic to
that walk), but the **balance numbers** that used to force a whole-stream replay on hot reads are served from
two maintained projections instead (`user_balance`, `kitty_balance`; the `BalanceDataService` port,
implemented by `BalanceDataServiceImpl` in the event-sourcing package). They are kept consistent by recomputing the
affected user (and the kitty) from the same walk inside each money write's transaction, so a stored balance
cannot drift from the authoritative walk, and they roll back with the write. So the per-user overview and
the kitty-overdraw guard read one indexed row rather than replaying a stream, and the events-to-data rebuild
recomputes both projections after replaying the log (which it now does in bounded `seq`-ordered batches).

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
- Node 24 (an LTS) is provisioned via `mise.toml` for the frontend build, lint, and tests.
- The Java major version has a **single source of truth**: the `java` entry in
  `gradle/libs.versions.toml`. The convention plugins resolve it for the Gradle toolchain and the Kotlin
  `jvmTarget`; `mise.toml` and the Dockerfile runtime image pin the same major by hand.
  `scripts/check-toolchain-versions.sh` (a CI step) fails the build if they drift.
- **The runtimes stay on LTS releases.** That same `scripts/check-toolchain-versions.sh` also validates the
  pinned **Node** major (`mise.toml`, the source of truth) against the official Node release schedule, so a
  non-LTS line is rejected in CI: both an odd "Current" major (e.g. 25, never LTS) and an even-but-not-yet
  LTS major (e.g. 26, which is "Current" until October 2026). It also asserts `@types/node` and the
  `frontend/package.json` `engines.node` floor track that same major. Dependabot does not manage `mise.toml`
  (so a non-LTS Node can only enter by a human edit, which the guard then catches), and a Dependabot rule
  ignores `@types/node` major bumps so the types never run ahead of the runtime. Bump the Node line by hand
  (mise + `@types/node` + `engines`, together) only when moving to the next Node LTS.

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

`bootRun` serves the **full app on `http://localhost:8080`**, the SPA included: the `stageFrontendForBootRun`
task (in `application/build.gradle.kts`) builds the Angular SPA and stages it under `static/` on bootRun's
classpath, so the root URL serves `index.html` and not the API's 404, exactly like the packaged jar. The
first run after a frontend change pays the Angular production build (cached afterward, so a backend-only
restart is fast and a bare `gradle test` never triggers the npm build). For **live frontend reload**, prefer
the Angular dev server (`cd frontend && npm start`), which serves on its own port and proxies `/api` to the
backend on `:8080`.

The `dev` profile:
- Enables Swagger UI at `http://localhost:8080/api/swagger-ui.html` and API docs at `http://localhost:8080/api/api-docs`.
- Loads the fixture dataset on startup (`campus-coffee.fixtures.load-on-startup: true`, when the database
  has no users yet): one admin and four users with deterministic capability tokens, each with a coffee
  consumption at zero, so the app comes up with the seeded ids and demo-able coffee links ready.
- Resets the data on every dev start (`campus-coffee.fixtures.reset-on-startup: true`): clears the data and
  reseeds the fixtures (and the demo data below), so each restart returns to the same deterministic state.
- Layers on **dev demo data** via `DevDemoDataLoader` (`@Profile("dev")`; a `StartupTaskService` at
  order 260, after the fixture reset+reseed and the price seed): about nine extra users (a mix of roles and
  active states) and an initial kitty float, and it also enriches **every existing fixture user** (the admin
  `jane_doe` included) with varied consumption, bean-purchase, and deposit history, so the users list
  paginates and the activity and change-log views are non-empty for almost everyone. Two users are
  deliberately left **empty** to demo the empty state: a freshly created active user `new_user` (no history
  at all) and the inactive demo user `hannes_schulz`. Demo users that get history are created active,
  given their history, then deactivated last (seeding history onto an inactive user is rejected by the
  domain). It is `@Profile("dev")`, so the tests still see exactly the five-user fixture set.
- Registers the dev-only `DevController` (in the `api` layer) under `/api/dev`:
  `GET /api/dev/data` reports the counts, `PUT /api/dev/data` replaces the data with the fixtures
  (clear + seed; idempotent, reassigning the same seeded ids), and `DELETE /api/dev/data` clears it.

### Run frontend and backend separately (live frontend reload)

`bootRun` above serves one self-contained app on `:8080` (API + the bundled SPA), which is the simplest way
to run the whole thing, but it rebuilds the SPA whenever the frontend sources change and has no hot reload.
For **active frontend development**, run the two halves separately: the Spring Boot backend on `:8080` and
the **Angular dev server** (`ng serve`) on `:4200` with hot module reload. The dev server proxies every
`/api` request to the backend (`frontend/src/proxy.conf.json` → `target: http://localhost:8080`), so the SPA
and the API still look same-origin to the browser and no CORS config is needed.

1. Start PostgreSQL (see above) if it is not already running.
2. Start the **backend** on `:8080`. You do not need the bundled SPA in this mode, so skip the (now
   redundant) frontend build for a faster, lighter start:

   ```shell
   mise exec -- gradle :application:bootRun --args='--spring.profiles.active=dev' -PskipFrontendBuild
   ```

   (`-PskipFrontendBuild` makes `stageFrontendForBootRun` stage only whatever is already in
   `frontend/dist`, so the backend comes up serving the API; the SPA you actually use is the dev server's.
   Drop the flag if you also want the bundled SPA on `:8080` as a fallback.)
3. In a second terminal, start the **Angular dev server** (proxying `/api` to `:8080`):

   ```shell
   cd frontend && mise exec -- npm start
   ```

4. Open the SPA at **`http://localhost:4200`** (not `:8080`). Edits to `frontend/src/**` hot-reload in the
   browser; the backend keeps running untouched. This is the loop to use for any UI work.

This is the same two-process split used in production-style deployments where the static SPA is served by a
separate web server/CDN and the backend is a standalone API: point the SPA's `/api` calls at the backend
origin (here via the dev-server proxy; in production via the deployment's reverse proxy or an absolute API
base URL) instead of relying on the single-jar bundling.

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
built by Gradle (a Node-Gradle task runs `npm ci` + `npm run build`; `frontend/dist/frontend/browser/` is
then bundled into the jar's `static/` resources by `bootJar` and staged onto the classpath by
`stageFrontendForBootRun` for `bootRun`), so both `gradle build` (one self-contained jar) and
`gradle :application:bootRun` serve the full app, SPA included, on `:8080`. The frontend build is wired into
`bootJar`/`bootRun` only, never into `processResources`/`classes`, so a bare `gradle test` does not trigger
the npm build. For **live frontend reload** run the Angular dev server instead, which proxies `/api` to the
backend on `:8080`:

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

## Development Workflow

Substantial changes follow a **plan → review → implement → review → deploy** loop, with an adversarial review
as a first-class step at both ends (not an afterthought):

1. **Plan.** Explore the code, then write a concrete plan (the files to touch, the approach, the reuse,
   verification). Resolve genuinely open decisions with the requester before finalizing.
2. **Review the plan.** Run an adversarial review of the plan against the actual code (an independent pass that
   hunts for wrong assumptions, missed edge cases, and regressions, and verifies each finding in the source).
   Fold the confirmed findings into the plan before writing any code.
3. **Implement.** Build it in coherent steps, keeping `gradle build` (and the frontend lint/build) green as you
   go. Reuse existing patterns and helpers rather than adding parallel ones.
4. **Review the implementation.** Run an adversarial review of the actual diff for real correctness, security,
   and behavior-regression bugs the green build does not catch (untested edge cases, injection, silent wrong
   output, data divergence). Verify each finding against the code and apply the confirmed fixes, then re-run
   the build green.
5. **Deploy.** Only after the second review: cut the release (version bump, changelog, tag) and deploy (see
   **Versioning and Releases** and the deploy notes in `README.md`).

A green build is necessary but not sufficient: The second review exists precisely to find what the tests do
not. Scale the review depth to the change (a small fix needs a light pass; a feature or refactor warrants a
thorough, multi-perspective one).

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

## Commit Messages

Write commit messages that stand on their own in the permanent history: a reader of `git log` (with no
access to your working notes or any review document) must understand the change from the message alone.

- **No ephemeral or internal references.** Never cite review-finding IDs (`review M13`, `H1-H3`, `L20-L27`),
  ticket shorthands, batch numbers, or any artifact that does not live in the repository. They mean nothing
  to a future reader and are noise; describe *what* changed and *why* in plain words instead.
- A concise imperative subject line, then a body that explains the why and any non-obvious decisions. Group
  related work into a few cohesive commits rather than many tiny ones keyed to an external checklist.
- End every commit message with the `Co-Authored-By:` trailer (see the harness instructions).

## Database

- **Database**: PostgreSQL 18.
- **Migrations**: Flyway (`data/src/main/resources/db/migration/`).
- **ORM**: JPA with Spring Data.
- **Connection**: Configured in `application/src/main/resources/application.yaml`.

The schema is an eight-migration set, broadly one `CREATE` per table (V7 adds the two balance-projection
tables together as one cohesive read-model unit): the incremental index/version migrations were folded into
their table's create before the first production deployment, so each table is defined in one place (there is
no deployed database whose checksums this would break; from here the migrations are append-only, see below).
- `V1__create_users_table.sql`: `users` (uuid PK, timestamps, `login_name` unique, `email_address` unique,
  `first_name`, `last_name`, a single `role` column (`USER`/`ADMIN`, no `user_roles` table), `active`,
  `password_hash` nullable, `capability_token` unique, and `version` for optimistic locking).
- `V2__create_coffee_consumptions_table.sql`: `coffee_consumptions` (uuid PK, timestamps, `user_id` unique
  FK with `ON DELETE CASCADE`, `count` not null default 0, `version` for optimistic locking).
- `V3__create_events_table.sql`: the append-only `events` log, including the `created_by` (varchar) and
  nullable `note` columns and its indexes: a UNIQUE `idx_events_seq` on `seq` (the authoritative append/
  replay order), the composite `idx_events_type_seq` on `(entity_type, seq)` (type-filtered, seq-ordered
  stream reads, e.g. the kitty walk and the price history), `idx_events_entity_type`, `idx_events_body_id`
  on `body->>'id'`, and the owner-key expression indexes `idx_events_body_user_id`/`idx_events_body_buyer_id`
  (`body->>'userId'` / `body->>'buyerUserId'`) that keep the per-user unified-activity and kitty walks
  efficient. The `entity_type` column is an unconstrained varchar; the valid set (`User`, `CoffeeConsumption`,
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
  RESTRICT (present is a deposit, null is a pure kitty adjustment), `amount_cents` signed, `note`,
  `version`).
- `V7__create_balance_projection_tables.sql`: the two maintained balance projections (see the money model
  below). `member_balance` (`user_id` uuid PK, FK to `users` with `ON DELETE CASCADE`, `balance_cents`
  bigint; later renamed to `user_balance` by V8) holds each user's running balance; `kitty_balance` (`id integer PK DEFAULT 1` with a
  `CHECK (id = 1)`, `balance_cents` bigint) holds the single global kitty balance. The two single-row tables
  use different one-row idioms on purpose: `coffee_prices` is an event-sourced entity with a meaningful UUID
  id, so it needs the separate `is_singleton` guard column, whereas `kitty_balance` is a plain derived cache
  with no natural id, so its fixed integer id is itself the guard (the lighter form). These projection tables
  are unversioned (no optimistic-locking `version`); they are maintained inside each money write's transaction.
- `V8__rename_member_balance_to_user_balance.sql`: renames the `member_balance` projection table to
  `user_balance` (an `ALTER TABLE ... RENAME`; the read model is unchanged).

Each of `users`, `coffee_consumptions`, `coffee_prices`, `expenses`, and `payments` carries a `version`
column for optimistic locking; the append-only `events` log and the `user_balance`/`kitty_balance`
projections are deliberately unversioned.

**Keep the migration files lean: plain DDL, no explanatory comments.** A column's or constraint's rationale
belongs in the entity class's KDoc and the changelog, never in the `.sql`. And never edit an applied
migration in place: Flyway's `validate-on-migrate` checksums the whole file, so even a comment change on an
applied migration fails startup (needing a `flyway repair` or a fresh migration); land a schema change as a
new migration. The consolidation above was a one-time pre-production exception, safe only because no database
had the old migrations applied yet.

The `expenses` and `payments` FKs keep PostgreSQL's default RESTRICT (NO ACTION) so a user's financial
history is never silently dropped
(the user service refuses to hard-delete a user with any financial footprint, see Error Handling, so an
admin deactivates them instead); `coffee_consumptions` stays `CASCADE` because every user always has a
(often zero) consumption row, so a `RESTRICT` there would make no user deletable.

## Testing Strategy

- **Unit and Integration Tests**: In `domain/src/test/kotlin/` (e.g., `UserServiceTest`, `CoffeeConsumptionServiceTest`).
- **System Tests**: In `application/src/test/kotlin/de/seuhd/campuscoffee/tests/system/`
  - Use Testcontainers for PostgreSQL and Spring's `RestTestClient`; extend `AbstractSystemTest`.
  - Cover the user self-service flow via the `X-Capability-Token` header (summary / add a coffee / undo /
    change log / unified activity feed / own expense / profile / QR), the admin flow via JWT (CRUD, role change,
    link rotate, count correction with a note, price change, expenses with a split, deposits and kitty
    adjustments, the kitty history and the per-user overview), the money model (balances valued at the
    as-of price, the kitty balance), deactivation → mutations 403, deleting a user with financial history
    → 409, unknown/rotated token → 401, the response shapes, and event-log assertions (an event per change
    with the right body / `created_by` / `note`).
- **Acceptance Tests**: Cucumber BDD tests in `application/src/test/kotlin/de/seuhd/campuscoffee/tests/acceptance/`
  with `.feature` files under `application/src/test/resources/...` (consumption, pricing and the fund,
  user administration, authorization).
- **Architecture Tests**: ArchUnit tests enforcing the hexagonal layer rules.
- Because event sourcing is the only mode, there is a single backend (no dual relational/event sourcing test split).
- **The automated tests all run the `dev` profile, so prod-only behaviors are not covered.** The system tests,
  acceptance tests, and the Playwright e2e all run under `dev`, so anything that activates only in another
  profile is exercised by no test: the Content-Security-Policy, the `Secure` session cookie, and Angular's
  production build optimizations (such as critical-CSS inlining). **When you change the CSP, the security
  config, or the production build, verify against the `prod` profile, not just the dev e2e.** Example: the
  first production deploy served an unstyled UI because the prod CSP's `script-src 'self'` blocked the inline
  `onload` handler in Angular's deferred-stylesheet markup; the fix was to disable
  `optimization.styles.inlineCritical` in `frontend/angular.json` (a plain render-blocking stylesheet, so the
  CSP stays strict).

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
- **Spring Security**: a JWT (bearer-token) resource server for admins and a custom capability token filter for users (no HTTP Basic).
- **Testcontainers** for system tests, **Cucumber** for BDD, **ArchUnit** for architecture testing.
- **Angular 22** (standalone components, signal `input()`/`output()` and `computed`, `@defer`/`@let`,
  Angular Material 22, `HttpClient`) on **TypeScript 6** and **Node 24** for the frontend. Unit tests run on
  **Vitest** (not Karma/Jasmine); end-to-end tests on **Playwright**.
- **angular-eslint + Prettier + Stylelint + Knip** for frontend static analysis, all wired into
  `gradle check`; **Qodana** (a JVM job and a JS/TS job) in CI on top of ktlint/detekt.

## Authentication and Authorization

Two authentication mechanisms, one per audience; there is **no HTTP Basic**:

- **Admins, JWT bearer via a session cookie.** `POST /api/auth/token` exchanges a username and password for a
  signed JWT (a work-session TTL of ~10 hours, no refresh flow). The credentials are **encrypted in the
  browser**: the request body is a compact JWE (`RSA-OAEP-256` + `A256GCM`), not plaintext, and carries an
  `iat` so a captured ciphertext cannot be replayed beyond `campus-coffee.login-encryption.max-payload-age`
  (default 2 minutes). The backend publishes its RSA public key at the public `GET /api/auth/public-key` (a
  JWK); the SPA encrypts `{ loginName, password, iat }` with it (the `jose` library), and
  `LoginPayloadDecryptor` (api layer) decrypts it (rejecting a stale payload) before authentication. A
  malformed, undecryptable, or stale payload is a 400 (`LoginPayloadException`), distinct from the 401 for
  wrong-but-readable credentials, so it is not a credential oracle. The decryptor also fingerprints each
  ciphertext and rejects a second presentation of the same one as a replay, so a captured ciphertext is
  single-use within its freshness window. Every authentication failure (wrong password, unknown login,
  deactivated account) returns the **same** 401 body, so it does not reveal whether a login name exists. The
  endpoint is **rate-limited** per client IP (`LoginAttemptLimiter`, a Bucket4j token bucket): too many failed
  attempts in the window yield a 429 before any decrypt or bcrypt work, bounding online guessing and a bcrypt
  CPU flood. An admin password must be at least 24 characters with a lowercase letter, an uppercase letter,
  and a digit (`UserDto` and the bootstrap path; users have no password). The token endpoint sets the JWT in
  an **httpOnly, `SameSite=Strict`, Secure (outside dev) cookie**, so the browser stores it where JavaScript
  cannot read or exfiltrate it (an XSS cannot steal the session) and sends it automatically;
  `POST /api/auth/logout` clears the cookie. `CookieOrHeaderBearerTokenResolver` reads the bearer token from
  that cookie (the SPA) or the `Authorization` header (API clients and the system tests, which still use the
  header). The resource server maps the token's `roles` claim to a `ROLE_ADMIN` authority. The
  `AuthenticationManager` / `DaoAuthenticationProvider` / `CampusUserDetailsService` / password-encoder
  beans exist only for this login step. See `doc/2026-06-24_login-payload-encryption.md` and
  `doc/2026-06-24_security-hardening-and-cookie-auth.md` for the threat model and key management.
- **Users, capability token.** `CapabilityTokenAuthenticationFilter` reads the `X-Capability-Token` header,
  resolves it to a user via `UserService.findByCapabilityToken`, and sets a `ROLE_USER` principal. The
  capability principal is **always** `ROLE_USER`, never `ROLE_ADMIN`, so an admin's own token grants only
  self-service. A missing, unknown, or rotated token leaves the request unauthenticated → 401. A
  deactivated user is still authenticated (reads work), but the domain rejects their mutations → 403.

The access rules gate the API by audience (`/api/users/**`, `/api/price/**`, and `/api/kitty/**` → `ROLE_ADMIN`; `/api/consumption/**`, `/api/expenses/**`, `/api/profile/**`,
`/api/summary`, and `/api/activity` → `ROLE_USER`; `/api/auth/token`, `/api/auth/logout`, `/api/auth/public-key`, actuator
health, Swagger, dev endpoints, and the SPA routes are public); the finer ownership rules live in the domain services.
The chain sets a Content-Security-Policy (`default-src 'self'`, `connect-src 'self'`, `frame-ancestors 'none'`,
inline styles allowed for Angular Material) as the structural XSS mitigation. CSRF token protection stays
disabled: the only cookie (the admin session) is `SameSite=Strict` so it is never sent cross-site, and the
user/API flows authenticate with a custom header a cross-site page cannot set, so a token-based scheme
would only burden those CSRF-immune flows.
`ActorProviderService` returns the
current principal's login for `created_by`; `CurrentUserProvider` resolves the principal to a domain
`User`. CORS is not configured: the SPA is same-origin, so the security chain needs no CORS source (add one
in `api` if the SPA is ever hosted on a separate origin). The capability URL handling
follows the W3C "Good Practices for Capability URLs" finding (see
`doc/2026-06-20_coffee-consumption-event-sourcing-and-capability-urls.md`).

## REST API Endpoints

Base URL: `http://localhost:8080/api`. JSON only. The `/api` base is applied centrally by `ApiWebConfig`;
controllers map paths relative to the resource.

### User self-service (auth: `X-Capability-Token` header; principal = the token's user)

- `GET  /summary?limit=10&offset=0`: the user landing in one call (`UserSummaryDto`):
  current total, balance, the current price, the kitty balance, whether the most recent coffee is still
  `cancellable`, and the first page of the unified `activity` (`limit` defaults to 10).
- `POST /consumption` (no body): add one coffee, returns the summary.
- `POST /consumption/cancel`: undo the most recent un-cancelled own coffee within the grace period (nothing
  to undo / past the grace period → 409).
- `GET  /activity?limit=20&offset=0`: own unified activity feed (coffees, own purchases, deposits) newest-first,
  each entry with a running balance.
- `POST /expenses` `{ amountCents, weightGrams, note? }`: record an own bean purchase (booked 100% private
  to the user; the buyer and split are server-derived).
- `GET  /profile` / `PUT /profile`: view / edit own `firstName`, `lastName`, `emailAddress`; the response includes the assembled capability URL.
- `GET  /profile/qr.png`: own capability QR code (high-resolution PNG, the single format).

### Admin and user management (auth: JWT, `ROLE_ADMIN`)

- `GET /users`, `POST /users` (create; the server assigns the capability token and creates the consumption at 0), `GET/PUT/DELETE /users/{id}`.
- `PUT /users/{id}` edits the profile, `role`, and `active` (deactivate/reactivate).
- `DELETE /users/{id}` hard-deletes a user; refused (409) if the user has any financial history (deactivate instead).
- `GET /users/me`: the signed-in admin's own user (the admin landing default).
- `GET /users/filter?login_name=…`: filter users by query params (e.g. by login name).
- `GET /users/overview`: a per-user overview (counts and balances); it now renders in the user-management page (`/admin/users`).
- `GET /users/activity?limit=20&offset=0`: the whole-installation **global activity feed** (every user's coffees, purchases, and deposits, the kitty adjustments, and price changes), newest first; each row carries the subject user, the actor (`created_by`), and the user and kitty running balances the event moved (the all-users analogue of `/users/{id}/activity`). Renders in the admin **Activity** page (`/admin/activity`).
- `GET /users/activity.csv`: the same global feed as a streamed CSV download (`activity.csv`), the full dataset unpaged, with a UTF-8 BOM, ISO-8601 UTC timestamps, and raw integer euro cents; free-text cells (user names, notes) are guarded against spreadsheet formula injection. Powers the Activity page's Download CSV button.

The global feed (paged and CSV) is read by replaying the whole event log per request (the running balances are
intrinsic to the walk, the same trade-off the per-user/kitty feeds make, see the activity-feed section), so
its cost is `O(log size)` per request rather than `O(page)`. This is fine at SE@UHD's single-group scale. The
subject login is resolved from the log's own `User` events so a hard-deleted user's rows stay classified and
labeled, and the unpaged CSV export fails with a clear 409 above a generous row cap rather than silently
truncating its running balances or exhausting heap.
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png` (downloads as
  `<loginName>.png`, transparent background).
- `GET /users/qr.zip`: a streamed ZIP of every active user's QR code as `<loginName>.png` (capped at 1000
  users).
- `GET /users/qr.pdf`: a printable PDF grid of every active user's QR code, each labeled by login name
  (the same active-user selection and 1000-user cap as the ZIP). Both power the admin bulk QR-download
  buttons on the users page.
- `GET  /users/{id}/consumption?limit=5&offset=0`: a user's total plus a page of the change log.
- `GET  /users/{id}/activity?limit=20&offset=0`: a user's unified activity feed.
- `POST /users/{id}/consumption` `{ delta: 1 | -1 }`: a single-step change.
- `PUT  /users/{id}/consumption` `{ total, note? }`: the absolute count correction (`note` is the optional admin reason, ≤ 500 chars).
- `POST/PUT/DELETE /users/{id}/expenses` `{ amountCents, privateAmountCents, kittyAmountCents, weightGrams, note? }`: record / correct / delete a user's bean purchase with an explicit private/kitty split (must sum to the total) attributed to the user as buyer.
- `GET /price`: read the current global price (admin-only; users receive it through their landing summary).
- `PUT /price` `{ amountCents }`: set the global price; `GET /price/history?limit=50&offset=0` reads a page of the price history from the log (newest first).
- `POST /kitty/deposit` `{ userId, amountCents, note? }`: a user pays money into the kitty (credits the user, feeds the kitty).
- `POST /kitty/adjustment` `{ amountCents, note? }`: a pure kitty adjustment (an initial float or a correction).
- `GET /kitty/history?limit=50&offset=0`: the kitty history (deposits and admin expenses, with the running kitty balance).

### Auth and dev

- `GET  /auth/public-key`: the RSA public key (a JWK) the SPA uses to encrypt the login payload (public, no auth).
- `POST /auth/token`: `{ encryptedPayload }`, a compact JWE of `{ loginName, password, iat }` → JWT, set in an httpOnly `SameSite=Strict` session cookie (the body also returns the token for header-based API clients). The only admin credential; no Basic. The server decrypts and freshness-checks it before authenticating; a malformed, undecryptable, or stale payload is a 400.
- `POST /auth/logout`: clears the admin session cookie (public; clearing one's own cookie needs no auth).
- `GET/PUT/DELETE /dev/data` (dev profile only): report counts / seed fixtures / clear.

Notes on semantics:
- `/consumption` is the change log; the running `total` is a derived field in the response. Paths name
  resources, not verbs; there is no `/increment`, `/decrement`, `/reset`, or `/transactions`. The HTTP
  method carries the semantics: `GET` reads (safe), user `POST /consumption` adds one coffee
  (non-idempotent), `POST /consumption/cancel` undoes the most recent one within the grace period, and admin
  `PUT` sets the absolute total (idempotent, admin-only). There is no reset; settling up is a deposit
  (real money) and an admin count change is a correction; both keep the prior entries in the append-only
  log.
- A user adds one coffee at a time and may undo a recent one; any other count adjustment is the admin's
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
  - `campus-coffee.consumption.cancel-grace-period` (`ConsumptionProperties`, domain module): how long after
    adding a coffee a user may still undo it. A `Duration`, default 5 minutes. The typed holder lives in the
    domain because the rule is enforced there and the domain cannot depend on the api module.
  - `campus-coffee.jwt.secret` (`JwtProperties`, api module): HMAC signing secret for the JWTs.
    Required and at least 32 bytes; supplied via `JWT_SECRET` (the dev profile has an insecure fallback,
    the prod profile none; in prod a Google Secret Manager secret bound onto the service as this env var).
  - `campus-coffee.login-encryption.private-key-pem` (`LoginEncryptionProperties`, api module): the RSA
    private key (PKCS#8 PEM, at least 2048 bits) that decrypts the login payload; its public half is
    published as a JWK at `GET /api/auth/public-key`. Required, supplied via `LOGIN_PRIVATE_KEY_PEM` (the dev
    profile has an insecure committed fallback, the prod profile none; in prod a Google Secret Manager secret
    bound onto the service as this env var). It must be the **same key on every instance** (a client may
    fetch the public key from one instance and post the ciphertext to another), so it is one configured key
    every instance reads rather than generated per startup. `LoginEncryptionConfig` turns any literal `\n`
    back into newlines, so the value parses whether it is a real multi-line PEM (the Secret Manager form) or
    a single line with `\n` separators, via Spring Security's `RsaKeyConverters`.
  - `campus-coffee.login-encryption.max-payload-age` (`LoginEncryptionProperties`, api module): how far the
    encrypted login payload's `iat` may differ from the server clock before it is rejected as stale (the
    replay window). A `Duration`, default 2 minutes.
  - `campus-coffee.auth.cookie.secure` / `name` (`AuthCookieProperties`, api module): whether the admin
    session cookie is marked `Secure` (default `true`; the dev profile, served over plain http, sets it
    `false`) and the cookie name. The cookie is always httpOnly and `SameSite=Strict`.
  - `campus-coffee.auth.rate-limit.enabled` / `max-failures` / `window` (`LoginRateLimitProperties`, api
    module): the login brute-force guard on `POST /api/auth/token`. A client (keyed on its IP) gets
    `max-failures` failed attempts per `window` (default 10 per 15 minutes) before a 429; on by default, off
    only where a test drives many failures from one client.
  - `campus-coffee.fixtures.load-on-startup` (`FixturesProperties`, application module): when `true` and
    the database has no users yet, load the fixtures on startup (on in dev, off in prod).
  - `campus-coffee.fixtures.reset-on-startup` (`FixturesProperties`, application module): when `true`, clear
    the data and reseed the fixtures on every startup (on in dev, off in prod), so each dev restart returns
    to the deterministic seeded state.
  - `campus-coffee.bootstrap-admin.*` (`BootstrapAdminProperties`, application module): when set and no
    admin exists yet, create one admin on startup (used in prod, where fixtures are off). The class declares
    only the structure; the values and defaults live in `application.yaml`'s prod block. In prod the password
    is a Google Secret Manager secret (`BOOTSTRAP_ADMIN_PASSWORD`); the rest of the identity (login, email,
    name) is non-secret deploy config.

The startup tasks run before the embedded web server accepts requests (via a `SmartInitializingSingleton`,
`StartupDataInitializer`, that runs every registered `StartupTaskService` in `order`): the optional event-log
rebuild (order 100), then the fixture loader (200), then the price seeder (`CoffeePriceStartupLoader`, 250,
seeds `campus-coffee.price.initial-cents` when no price exists yet so a price exists before any coffee is
consumed), then `DevDemoDataLoader` (260, `@Profile("dev")`, so it runs in local dev only), then the
bootstrap admin (300). So in dev the fixtures seed an admin and the bootstrap step is a no-op; in prod the
fixtures are off, so the bootstrap step creates the admin (and `DevDemoDataLoader` does not run).

## Important Patterns

### Error Handling

Domain exceptions in `domain/.../exceptions/`:
- `NotFoundException`: Entity not found (404).
- `DuplicationException`: Duplicate unique fields (409).
- `ValidationException`: Malformed input / business rule violation (400), e.g. a `delta` other than `±1`, a count correction below zero, or an expense whose split does not sum to its total.
- `MissingFieldException`: Required field missing (400).
- `ConflictException`: A well-formed request that conflicts with the resource's current state (409), e.g. a `−1` at 0, an undo with nothing to undo or past the grace period, or an operation that would drive the kitty below zero (the kitty-overdraw guard, see below).
- `ConcurrentUpdateException`: Optimistic-locking conflict (409), a concurrent self-scan; the SPA retries.
- `ForbiddenException`: Authorization failure (403), not the owner / not an admin, or a deactivated user mutating.
- `DeletionConflictException`: Deletion blocked because other data references the entity (409), e.g. hard-deleting a user who has any financial history (a non-zero count, or any expense or deposit); the admin deactivates them instead.

Global exception handler: `api/.../exceptions/GlobalExceptionHandler.kt`. It extends
`ResponseEntityExceptionHandler`, so the standard Spring MVC exceptions also map to their proper status
codes (an unmapped path returns 404, a wrong HTTP method 405) instead of a generic 500. It also maps a
Jakarta `ConstraintViolationException` (an out-of-range paging `limit`/`offset` that violates the
`@Max`/`@Min`/`@Positive` bounds) to a clean 400 instead of letting it fall through to a generic 500. The
REST API is JSON-only (`ApiWebConfig` removes the XML message converter and pins UTF-8 on JSON).

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

Entity ids are application-assigned `UUID`s. The domain defines an `IdGeneratorService` port; the data-layer
`IdGeneratorConfiguration` selects the adapter from the `campus-coffee.id.entity-seed` property. A numeric
seed (the default) yields a deterministic `IdGeneratorServiceImpl`, so the loaded fixture ids are reproducible
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
    relative to the resource; the `/api` base is applied centrally by `ApiWebConfig`.
12. Create a Flyway migration in `data/src/main/resources/db/migration/`.

### Constraint Violations

Database uniqueness constraints are converted to `DuplicationException` via `ConstraintMapping` in
`data/.../constraints/`, declared in each data-service impl (login name, email, capability token, and the
one-per-user consumption constraint). An entity with no database-unique key (`CoffeePrice`, `Expense`,
`Payment`) declares `emptySet()`. The constraint name is the single source of truth shared between the
entity companion constant, the `ConstraintMapping`, and the Flyway DDL. In event sourcing mode the
`ReadModelProjector` maps the same constraint names via its `DUPLICATION_RULES`.
