# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
  (`POST /api/expenses`), always booked 100% from their own pocket — the buyer and split are server-derived,
  so a member cannot attribute a purchase to someone else or fund it from the kitty. An admin lists,
  records, corrects, and deletes a member's purchases with an explicit kitty/private split and buyer
  attribution (`GET`/`POST`/`PUT`/`DELETE /api/users/{id}/expenses`); the split must sum to the total, and
  a correction cannot change the buyer (delete and re-record to move one).
- **Communal kitty and settlements** (`Payment`), admin-managed and event-sourced. A settlement records a
  member paying money in (credits their balance, feeds the kitty); a kitty adjustment changes the kitty
  alone (an initial float or a correction). Endpoints: `POST /api/payments/settlement`,
  `POST /api/payments/adjustment`, `GET /api/kitty/ledger` (admin). Members see only the kitty *balance*,
  in their summary.
- **Per-member balance** (a prepaid-card figure — negative means the member owes the fund), valuing each cup
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
  JetBrains Qodana — the free JVM Community linter with the `qodana.recommended` profile — to catch the
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
  The `objects` subpackage was vestigial — the counterpart of the upstream CampusCoffee `model.enums`
  subpackage, which this project dropped — so the domain model objects now live directly in `domain.model`.
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

[0.2.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.2.0
[0.1.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.1
[0.1.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.0
