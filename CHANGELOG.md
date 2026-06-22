# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.1] - 2026-06-22

A hardening release working through an extensive adversarial review of the whole project (code, docs,
design): production-deployment fixes, accounting and event-sourcing correctness, architecture-leak cleanups,
a fuller API contract, frontend correctness and UX, the previously-missing tests, and a repo-wide em-dash
and prose cleanup. It also aligns the API endpoint names with the frontend vocabulary, a breaking endpoint
rename (the bundled SPA, OpenAPI spec, and docs are updated in lockstep).

### Security

- The insecure JWT-secret fallback is scoped to the dev profile only; a missing `JWT_SECRET` now fails fast
  in every other profile, and the prod compose runs the prod profile with a real secret (it previously ran
  the dev profile with the committed default).
- The runtime container runs as a dedicated non-root user and declares a `/actuator/health` healthcheck.

### Fixed

- **Production deployment.** Prod now uses random id seeds (the deterministic seeds re-issued colliding
  UUIDs on the first entity created after any restart), requires an `https` base URL (a new `ProdConfigGuard`
  fails fast on a missing or non-https value, so wall QR codes are never dead links), and targets managed
  Cloud SQL. `compose.prod.yaml` previously overrode the datasource with an ephemeral `postgres/postgres`
  sidecar that destroyed the event-log money ledger on every redeploy; the deploy now wires `DB_PASSWORD`,
  the base URL, and `BOOTSTRAP_ADMIN_*` so a fresh prod instance comes up with an admin.
- **Login names are immutable after creation.** The ledger walk classifies a member's own scans by the
  actor login recorded in the append-only log, so a rename previously disabled the member's undo and could
  misvalue their balance; an update now pins the stored login (mirroring the capability-token pin).
- A corrected (UPDATE) or deleted expense ledger row shows only its signed delta effect, not an absolute
  private/kitty split that could not be reconciled with the delta.
- A missing as-of price falls back to the earliest known price instead of throwing, and the admin overview
  isolates a per-member failure, so one malformed stream can no longer 500 a whole ledger or overview read.
- **Frontend.** A `+1` retries once on a concurrent-update 409 (the documented self-scan retry); the admin
  count-correction field is reseeded from the current count, so Set no longer silently reverts intervening
  cups; a busy entry-guard stops a fast double-tap double-submitting; a token-less admin 401 redirects to
  the login form; a negative price/expense/deposit is flagged inline; and a non-integer gram weight is
  rejected.
- **Malformed euro amounts now show their validation message.** Every money form validated its euro input
  only through a component method, which disabled the submit button but left the control valid, so Angular
  Material kept the field's `<mat-error>` hidden. A new `ccEuroAmount` template-driven validator marks a
  non-empty, unparseable (or, for a price/expense/deposit, negative) amount invalid so the message shows.
- **The Playwright end-to-end suite passes against the shipped UI.** The suite added in 0.3.0 was never run
  green before release, so several specs had drifted (`Undo last coffee` vs `Undo last cup`, the ambiguous
  `Member` label, a euro `mat-error` that did not yet render, the collapsed kitty-adjustment card). The
  specs now target the shipped UI.

### Changed

- **API endpoint names aligned with the frontend vocabulary (breaking).** The member and admin activity
  feeds, the member's `GET /api/ledger` and the admin's `GET /api/users/{id}/ledger`, are now `/activity`
  (the UI's "Recent activity"). The kitty's `GET /api/kitty/ledger` is now `GET /api/kitty/history` (its UI
  "Kitty history"), and the two kitty money-movements move from `/api/payments/*` under the kitty resource:
  `POST /api/kitty/deposit` (renamed from "settlement", the UI's "deposit") and `POST /api/kitty/adjustment`.
  `SummaryController` is renamed to `MemberController`, `PaymentController` folds into `KittyController`, and
  `AdminAccountingController` (the per-member overview and activity, both under `/api/users`) folds into
  `UserController`. The endpoint paths and the shared `LedgerEntryDto`/`LedgerEntryType` data types keep
  their names; the OpenAPI spec, the generated frontend DTOs, the SPA services, and the docs are all updated
  together.
- The `events.note` metadata column records only an absolute count correction's reason; a settlement, kitty
  adjustment, or expense note lives in that entity's own event body (the docs are corrected to match).
- **Architecture.** The price-singleton conflict is mapped to a domain `DuplicationException` in the data
  adapter, so no Spring persistence exception is caught in the domain; the event-store `seq` is removed from
  the domain `CancellableIncrement`; the ArchUnit rules now cover the `web`/`configuration` packages and
  assert every class belongs to a layer; and dead code is removed (`ConsumptionProperties`,
  `EventSourcedMutator.create`, the no-op `OnCreate` group, `ValidationException`'s unused constructor).
- **API and OpenAPI.** Settlement, adjustment, and admin-expense create declare `201`; the QR endpoints
  advertise `image/png` and `application/zip`; a dedicated `ProfileUpdateDto` carries the profile edit; and
  the hand-written controllers document their `400/401/403/404/409` responses. The committed OpenAPI spec
  and the generated frontend DTOs are regenerated to match.
- **Frontend UX.** A human browser-tab title with per-route titles, a real not-found page for unknown URLs,
  a `prefers-reduced-motion` block, a `theme-color` meta and a web manifest, one consistent snackbar action
  label, and the ledger now reads "Coffee undone" and "total N cups" (no jargon or math glyph).
- A new `V8` migration adds an `(entity_type, seq)` index and a `UNIQUE(seq)`; the kitty ledger reads one
  SQL-ordered stream instead of re-sorting two in memory.
- **Em-dashes removed from all prose and source comments** across the docs, `README.md`, `CLAUDE.md`, and
  the Kotlin/TypeScript/SCSS/build files, replaced with plain punctuation. This completes the AI-slop
  cleanup the 0.3.0 entry began.

### Added

- The previously-missing tests: two concurrent self-scans (no lost update, a clean 409), the
  expense-correction kitty-overdraw guard, the advisory-lock ordering (the lock is taken before the balance
  read), the money fat-finger caps, and the login-rename invariant.

### Fixed (documentation)

- The three ledger endpoints show their real `@RequestParam` defaults (`/ledger` and `/users/{id}/ledger`
  default to `limit=20`, `/kitty/ledger` to `limit=50`; the change-log default stays 5). `CLAUDE.md` now
  lists `GET /users/filter`, documents the `is_singleton` / `uq_coffee_prices_singleton` single-row guard,
  corrects the domain "no external dependencies" claim (it depends on Spring by design), marks the expense
  `weightGrams` required, and the `StartupDataInitializer` KDoc lists the real startup-task order.

## [0.3.0] - 2026-06-22

A frontend overhaul (Angular 19 to 22, a SE@UHD-branded design system, Karma/Jasmine to Vitest, and a full
static-analysis and end-to-end-test toolchain), an OpenAPI-driven frontend-DTO codegen, "Both"-sided
coverage, a routing and admin-landing redesign, a bulk QR ZIP download, dev demo data, a kitty-overdraw
guard, and admin-deactivation and last-active-admin safeguards. No end-user REST API breaking changes.

### Added

- **Bulk QR ZIP download.** `GET /api/users/qr.zip` (admin) streams a ZIP of every member's QR code as
  `<loginName>.png`, written and flushed entry by entry (no whole-archive buffering) and capped at 1000
  members (a warning is logged and the cap bundled beyond that), so a large member set cannot exhaust
  memory. The admin users page gains a "Download all QR codes" button. The per-member QR PNG now downloads
  with a `<loginName>.png` filename and a transparent background, so it sits on any wall color.
- **A frontend design system.** A SE@UHD / Heidelberg brand-red (`#C61826`) Angular Material M3 theme on an
  8 dp spacing grid, a shared header and balance-summary component, role chips, a paginated members table,
  and consistent loading / error / snackbar / confirm-dialog UX with field-level form validation. A
  member's balance reads as a signed `+`/`−` figure.
- **OpenAPI → frontend-DTO codegen.** `scripts/generate-frontend-dtos.sh` generates the frontend DTOs from
  the backend OpenAPI spec into `frontend/src/app/api/model/` (hash-skipped: it regenerates only when the
  spec changes) and is wired into the Gradle build; `frontend/src/app/models.ts` re-exports the generated
  DTOs, so the frontend and backend contracts cannot drift.
- **Frontend static analysis.** angular-eslint, Prettier, Stylelint, and Knip (dead-code/dependency
  detection) are wired into `gradle check` via a `frontendLint` task, and a Qodana JS/TS CI job runs
  alongside the Kotlin one.
- **"Both" coverage: frontend unit + e2e coverage, and backend e2e coverage merged into the JaCoCo gate.**
  Three coverage collectors join the existing in-JVM JaCoCo aggregate. (1) The Angular Vitest unit-test
  builder now produces a coverage report (`@vitest/coverage-v8`): `npm run test:coverage` writes
  lcov + HTML + text-summary under `frontend/coverage/` (a low, documented floor, as a single service spec
  covers ~1%, which guards against a regression to zero, not a real target). (2) The Playwright e2e run collects
  the SPA's TypeScript coverage the JaCoCo agent can never see: a per-test fixture turns on Chromium V8
  coverage (`PW_COVERAGE=1`) and a global teardown source-maps it back onto the `.ts` sources with
  `monocart-coverage-reports`, emitting lcov under `frontend/coverage-e2e/` (~71% of the app TypeScript).
  (3) The same e2e run also records backend JVM coverage: the new `:coverage:runE2eCoverage` task
  (`scripts/run-e2e-coverage.sh`) builds a source-mapped SPA + jar, launches it under the JaCoCo agent
  (`destfile=coverage/build/jacoco/e2e.exec`) on the dev profile against PostgreSQL, waits for
  `/actuator/health`, drives it with Playwright, and stops it so the agent flushes the exec. The aggregate
  report and `coverageGate` fold `e2e.exec` in when present (and degrade gracefully to the in-JVM coverage
  when absent), so the e2e HTTP traffic counts toward the same gate. A new `e2e` CI job (in `build.yml`)
  runs the whole flow against a PostgreSQL service container and uploads the coverage artifacts; the
  existing `build` job is unchanged.
- **A Playwright end-to-end suite** under `frontend/e2e/`, driving the bundled app through the member and
  admin flows in a real browser.
- **Kitty-overdraw guard.** Any operation that would drive the kitty balance below zero (an admin expense's
  kitty portion or a negative kitty adjustment) is now refused with a 409, serialized by a Postgres advisory
  lock (`KittyLock` port, `PostgresKittyLock` adapter, `pg_advisory_xact_lock`) so two concurrent draws
  cannot both pass the check and overdraw the fund.
- **Dev demo data.** A dev-only `DevDemoDataLoader` startup task (active only under the `dev` profile, so the
  tests are untouched) seeds about nine extra demo members (a mix of roles and active states) and an initial
  kitty float, and also enriches **every existing fixture user** (the admin `jane_doe` included) with varied
  consumption, bean-purchase, and deposit history, so the dev app comes up with enough members to paginate
  (5 per page) and with non-empty ledger and change-log views for almost everyone. Two members are
  deliberately left empty to demo the empty state: a freshly created active member `new_user` (no history)
  and the inactive demo member `hannes_schulz`. It stays `@Profile("dev")`, so the tests still see exactly
  the five-member fixture set. Deterministic and idempotent: it runs after the dev fixture reset+reseed and
  the price seed.
- **Dev `reset-on-startup`.** A `campus-coffee.fixtures.reset-on-startup` flag (on in the dev profile) that
  clears and reseeds the fixtures (and the demo data) on every dev start, so each restart returns to the
  same deterministic state.
- **`GET /api/price` (admin).** Read the current global price per cup directly (admin only); members keep
  receiving the price through their landing summary, so this is an admin-side convenience read alongside
  `PUT /api/price` and `GET /api/price/history`.
- **Kitty history shows the expense split.** A `KITTY_EXPENSE` row in the admin kitty ledger now carries both
  the member-funded (`privateAmountCents`) and kitty-funded (`kittyAmountCents`) portions of a split bean
  purchase, so the kitty history renders the same `person private + savings kitty` footer the member ledger's
  expense row already showed. The member-serving read still strips both portions, so a member never sees the
  split. The `LedgerEntryDto` gains a `privateAmountCents` field.
- **Last-active-admin guard.** Deactivating, demoting, or deleting the last active admin is now refused
  (409), so an admin can no longer lock every admin out of the system. A deactivated admin also loses admin
  access immediately, consistent with a deactivated member losing self-service mutations.
- **npm/frontend dependency updates.** Dependabot now also tracks the frontend's npm dependencies.

### Changed

- **Frontend upgraded to Angular 22 (from 19)**, Angular Material 22, and TypeScript 6, adopting the Angular
  22 idioms (signal `input()` / `output()`, `computed`, `@defer`, `@let`, `afterNextRender`, `DestroyRef`,
  `viewChild`) while keeping constructor dependency injection.
- **Unit testing moved from Karma/Jasmine to Vitest** (`npm test` and `npm run test:coverage` now run
  Vitest).
- **Node upgraded to 24 (from 22)** in `mise.toml`.
- **Money is shown in English format** (a period decimal separator with the `€` after the amount), and a
  member's balance is a signed `+`/`−` figure, dropping the "settled / owes / credit" wording.
- **Member-facing terminology.** A member's payment-in is called a "Deposit" (not a settlement/payment), the
  ledger filter reads "Cups / Expenses / Deposits", and the kitty is shown as "Kitty balance" vs
  "Kitty history".
- **Stable user-list ordering.** The admin user list (`GET /api/users`) and the per-member overview
  (`GET /api/users/overview`) are now ordered by login name ascending, so deactivating a member or rotating a
  capability token no longer reshuffles the rows (Postgres otherwise returns an updated row in a different
  physical position).
- **Paged reads reject out-of-range `limit`/`offset` with 400.** The paged endpoints (`/summary`, `/ledger`,
  `/kitty/ledger`, the admin per-member ledger and consumption) now validate `limit` (`1..100`) and `offset`
  (`>= 0`) with bean validation, so an out-of-range value is a clean 400 rather than a silent clamp.
- **Money amounts are bounded.** The settlement, kitty-adjustment, price, and expense request bodies now cap
  each amount at 100,000 EUR (the adjustment on both sides) as a fat-finger guardrail, rejecting an absurd
  amount with a 400.
- **SPA routing.** The member capability URL is now `/login/{token}` (was `/coffee/{token}`), with the
  member profile at `/login/{token}/profile`. The admin UI is consolidated under `/admin/`: the login form
  moves to `/admin/login` (was `/login`), the landing/dashboard to `/admin` (was the root empty path), and
  the admin profile to `/admin/profile` (was `/profile`); the members, price, expenses, and kitty pages stay
  at `/admin/users`, `/admin/price`, `/admin/expenses`, and `/admin/kitty`. The root path and any unknown
  route redirect to `/admin`.
- **Admin landing redesigned to mirror the member view**, and the all-members overview moved into the
  member-management page (`/admin/users`): the admin landing now presents the same balance-summary and
  ledger view a member sees, while the per-member overview (counts and balances) renders alongside the
  members table in `/admin/users` rather than on the landing.
- **Admin 401 redirects to `/admin/login`.** An admin API call that comes back `401` (an expired or missing
  JWT) now sends the SPA to `/admin/login` rather than failing in place.
- **Tidied admin price and kitty copy.** The price page heading reads "Current price per cup" (the redundant
  "per cup" caption under the value is gone); the kitty page drops the "currently in the communal pot"
  caption under the balance; and the deposit form's blurb is reworded from "A member paid money into the
  fund; this credits their balance." to plainer wording.
- **README "Seeded fixtures" → "Test fixtures."**

### Fixed

- **Kitty-overdraw TOCTOU race.** The kitty-balance check and the write are now serialized by a Postgres
  advisory lock, so two concurrent draws can no longer both read a sufficient balance and overdraw the
  kitty.
- **Member-profile deep-link / refresh 401.** Opening or refreshing a member-profile URL directly now
  registers the capability token from the route, so the page no longer 401s on a deep link.
- **Dev demo-loader startup crash.** The demo loader seeded coffees onto members it had created inactive,
  which the domain rejected; demo members are now created active, given their history, and deactivated last.
- **Price-history `@for` crash.** The price-history list `@for` lacked a `track`, triggering NG0955; it now
  tracks `$index`.
- **Member-switch stale-response race.** Switching the selected member no longer renders a late response
  from the previously selected one.
- **Load-more boundary duplication.** Paging the ledger/change log no longer duplicates the boundary row; the
  walk de-duplicates by `seq`.
- **Idempotent initial-price seeding.** When two instances seed the global price singleton at once, the loser
  (which hits the `uq_coffee_prices_singleton` guard) now re-reads and returns the price the winner seeded
  instead of surfacing a 500.
- **Graceful ledger undo-valuation fallback.** The unified-ledger walk no longer fails outright when an
  owner undo cannot be matched to a stacked increment price; it falls back gracefully rather than erroring
  the whole ledger read.
- **QR filename sanitization.** The login name in a QR PNG's `Content-Disposition` filename and ZIP entry
  name is now whitelisted to `[A-Za-z0-9_-]` (other characters replaced), so download/archive safety no
  longer depends on the upstream login-name validation staying strict.
- **Backend OpenAPI required-but-nullable schema bug.** An `OpenApiCustomizer` corrects the generated spec so
  required fields are no longer also marked nullable, which kept the frontend-DTO codegen honest.
- **Partial AI-slop prose cleanup** across the docs, UI copy, and KDoc, with consistent American spelling.
  Em-dashes were not yet removed; that follows in a later change.
- **Frontend state and error hardening.** The selected member is reset on logout (so a stale selection no
  longer carries into the next admin session), an error on member-switch is now surfaced instead of being
  swallowed, and the capability URL is preserved across a member-profile reload so a deep-linked profile no
  longer drops the token.
- **Ledger and balance figures keep a constant size regardless of sign.** The `.warn` styling (red for a
  negative amount) carried a smaller font-size meant for inline messages, which leaked into the figures: a
  coffee/expense cost (negative) rendered smaller than a positive deposit in the ledger list, and the
  personal balance shrank when it went negative. Both figures are now pinned to a fixed size so only the
  color changes with the sign, making the cost amount consistent across every ledger and kitty-history row.

## [0.2.0] - 2026-06-21

A communal-fund accounting model on top of the consumption tracker: a price per cup, member-recorded bean
purchases, a shared kitty, per-member balances, and a unified ledger. Built entirely on the existing
event-sourcing machinery (append a full-state event, project it in one transaction); no change to that
machinery. See `doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`.

### Added

- **Global coffee price**, admin-set and event-sourced like every other entity, so the append-only log is
  the full price history. Stored in integer euro cents. Endpoints: `PUT /api/price`, `GET /api/price/history`
  (admin); the current price reaches members through their landing summary. Seeded at 50 cents on first
  startup (`campus-coffee.price.initial-cents`).
- **Bean-purchase expenses** (`Expense`), event-sourced. A member records their own purchase
  (`POST /api/expenses`), always booked 100% from their own pocket: the buyer and split are server-derived,
  so a member cannot attribute a purchase to someone else or fund it from the kitty. An admin lists,
  records, corrects, and deletes a member's purchases with an explicit kitty/private split and buyer
  attribution (`GET`/`POST`/`PUT`/`DELETE /api/users/{id}/expenses`); the split must sum to the total, and
  a correction cannot change the buyer (delete and re-record to move one).
- **Communal kitty and settlements** (`Payment`), admin-managed and event-sourced. A settlement records a
  member paying money in (credits their balance, feeds the kitty); a kitty adjustment changes the kitty
  alone (an initial float or a correction). Endpoints: `POST /api/payments/settlement`,
  `POST /api/payments/adjustment`, `GET /api/kitty/ledger` (admin). Members see only the kitty *balance*,
  in their summary.
- **Per-member balance** (a prepaid-card figure: negative means the member owes the fund), valuing each cup
  at the price in effect when it was consumed. The valuation is an "as-of" join over the event log keyed on
  append order (`seq`), not on a wall-clock timestamp.
- **Unified ledger** per member (`GET /api/ledger`, and the landing `GET /api/summary`): one chronological
  view of coffees, the member's own purchases, and their settlements, each with a running balance. The
  admin kitty ledger and a per-member overview (`GET /api/users/overview`, `GET /api/users/{id}/ledger`)
  follow the same read-side walk over the log; new owner-key event indexes keep it efficient.
- **Undo a recent coffee.** A member adds one coffee at a time (`POST /api/consumption`) and may undo the
  most recent one within a grace period (`POST /api/consumption/cancel`, default 5 minutes,
  `campus-coffee.consumption.cancel-grace-period`); the undo credits the exact price the coffee was charged
  at, so it nets to zero. A member no longer has a free `−1`.
- **`LoggedEntityType`** enum as the `events.entity_type` discriminator, making the read-model projector's
  dispatch exhaustive so a new logged entity cannot be forgotten.
- Flyway migrations `V4`–`V7`: `coffee_prices`, `expenses` (with a split-sum CHECK), `payments`, and the
  owner-key event indexes.

### Changed

- **Reset removed.** Settling up is now a settlement (real money into the kitty); an admin count change is
  just a correction (`PUT /api/users/{id}/consumption` stays). The member self-service `−1` is replaced by
  the grace-period undo, and the member `GET`/`POST /api/consumption` shapes changed (the landing reads
  `GET /api/summary`; `POST /api/consumption` adds one coffee and returns the summary).
- **Deleting a member with financial history is refused** (409); an admin deactivates them instead, so the
  audit trail is preserved. `expenses` and `payments` reference `users` with `RESTRICT`.

## [0.1.1] - 2026-06-21

### Added

- Qodana CI job (`.github/workflows/qodana.yml`), a separate workflow from the Gradle build that runs
  JetBrains Qodana (the free JVM Community linter with the `qodana.recommended` profile) to catch the
  idiomatic-Kotlin issues the IDE's default inspections flag but ktlint and detekt do not. It opens the
  project with JDK 25, fails on any problem (`failThreshold: 0`), uploads the report as a `qodana-sarif`
  artifact, and suppresses two systematic false positives for this project: `UnusedSymbol` (the Community
  linter does not recognize Spring/JPA framework entry points) and `UnstableApiUsage` (the Gradle Kotlin
  DSL's `@Incubating` API in the build scripts).
- Project-version drift guard. A new `scripts/check-version-sync.sh` (mirroring
  `scripts/check-toolchain-versions.sh`) fails the build when the latest `## [x.y.z]` header in this
  changelog disagrees with the Gradle build version; it runs as a CI step in `build.yml` before the
  Gradle build.

### Changed

- Move the project `group` and `version` to the root `gradle.properties` (Gradle's `project.group` /
  `project.version`, applied to every module) and out of the `java-conventions` build-logic plugin. The
  `version` in `gradle.properties` is the single source of truth, and the latest changelog release header
  must match it (enforced by `scripts/check-version-sync.sh`).
- Flatten the `de.seuhd.campuscoffee.domain.model.objects` package into `de.seuhd.campuscoffee.domain.model`.
  The `objects` subpackage was vestigial (the counterpart of the upstream CampusCoffee `model.enums`
  subpackage, which this project dropped), so the domain model objects now live directly in `domain.model`.
- Apply idiomatic-Kotlin cleanups in the test sources: reified `returnResult<T>()` instead of
  `returnResult(T::class.java)`, and trailing lambdas instead of a redundant SAM constructor and a
  parenthesized lambda argument.
- Give the frontend `npm` Gradle tasks (`frontendInstall`, `frontendBuild`) descriptions so they appear
  in `gradle tasks`.
- Document the versioning and release process in `CLAUDE.md` (SemVer, the `CHANGELOG.md` ↔
  `gradle.properties` version sync, and a `vX.Y.Z` git tag per release).
- Bump dependencies: ZXing 3.5.3 → 3.5.4 and the Cloud SQL connector 1.21.0 → 1.28.4 (Dependabot), and
  the `actions/checkout` GitHub Action v6 → v7.

## [0.1.0] - 2026-06-20

Initial release of CampusCoffeeConsumption, the coffee consumption tracker for SE@UHD. The app was derived
from the CampusCoffee teaching project, reusing its hexagonal architecture, event sourcing machinery,
build, test, and security scaffolding, and replacing the points-of-sale / review / OpenStreetMap domain
with the consumption domain.

### Added

- **Consumption domain.** A `CoffeeConsumption` per member (one running `count`, unique per user), modeled
  on CampusCoffee's `Review`. Members step their own count by `+1` / `−1`; admins set an absolute total or
  reset it to zero after payment. A count never goes below zero, and concurrent self-scans are resolved by
  optimistic locking (409, the client retries).
- **Capability token member authentication.** Each member has a high-entropy, unguessable capability token,
  encoded in a wall QR code as `https://<host>/coffee/{token}`. The SPA forwards it as the `X-Coffee-Token`
  header, so the token never appears in an API URL path. Tokens are stored (so an admin can re-display and
  re-print them) and rotatable on demand (rotating invalidates the old QR). Handling follows the W3C "Good
  Practices for Capability URLs" finding, with revocation-via-rotation deliberately replacing expiry for
  long-lived wall codes.
- **JWT admin authentication.** A username-and-password login (`POST /api/auth/token`) mints a
  work-session JWT (~10-hour TTL, no refresh flow). HTTP Basic and the `MODERATOR` role were dropped; the
  role model is a single `USER` / `ADMIN` column. Only an admin has a password (creating or promoting one
  requires it); a member has none and authenticates solely with their capability link, so one mechanism
  serves each audience.
- **Event sourcing persistence as the only model.** The append-only `events` log is the source of truth;
  the relational tables are a read model projected from it. Two metadata columns were added to the generic
  event: `created_by` (the actor's login name, or `"system"`) and a nullable `note` (an admin's reason for
  an override or reset), set from request-scoped context at the event-store boundary. A member's change
  history is read straight from the event rows.
- **QR codes.** Generated in the backend (ZXing) as high-resolution PNGs, downloadable from a member's own
  profile and from any member's admin page.
- **REST API.** Member self-service (`/api/consumption`, `/api/profile`, `/api/profile/qr.png`), admin user
  management (`/api/users/**`, including capability link view/rotate and QR), admin consumption by id
  (`/api/users/{id}/consumption`), and the auth and dev endpoints. The consumption resource is the change
  log; the running total is a derived response field, and the HTTP method carries the semantics (`GET`
  reads, `POST` appends a `±1` change, `PUT` sets the absolute total).
- **Angular single-page frontend**, bundled into the backend's `static/` resources by Gradle, so the whole
  app ships as one Cloud Run image with no CORS in production.
- **Database schema.** A fresh Flyway migration set: `users` (single `role`, `active`, `capability_token`),
  `coffee_consumptions` (unique `user_id`, `version`), and the `events` log with `created_by` and `note`.
- **Production deployment.** A `prod` profile targeting Cloud SQL for PostgreSQL 18 via the Cloud SQL Java
  connector, with a bootstrap-admin created on first startup (fixtures are off in production).

[0.3.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.3.1
[0.3.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.3.0
[0.2.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.2.0
[0.1.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.1
[0.1.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.0
