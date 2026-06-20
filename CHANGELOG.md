# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.1] - 2026-06-21

### Added

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

## [0.1.0] - 2026-06-20

Initial release of CampusCoffeeConsumption, the coffee-consumption tracker for SE@UHD. The app was derived
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

[0.1.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.0
