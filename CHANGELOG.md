# Changelog

All notable changes to this project are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres
to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.6.0] - 2026-06-26

### Added

- An admin **Activity** page (`/admin/activity`) that lists every activity across all members, the kitty, and
  the price in one paginated table: coffees, cancels, admin count corrections, private and kitty expenses,
  deposits, kitty adjustments, and price changes. Each row shows the member it concerns (the subject) and who
  performed it (the actor); the two differ on an admin correction. Each row has separate member-balance and
  kitty-balance columns, a client-side type filter, and "Load more" paging. Backed by a new admin-only
  `GET /api/users/activity`.
- A **Download CSV** button on the Activity page (and `GET /api/users/activity.csv`) that exports the entire
  feed (the whole dataset, not just the loaded rows) as a UTF-8 CSV with a byte-order mark, so a spreadsheet
  renders member names with umlauts correctly. Money stays as raw integer euro cents and timestamps are
  ISO-8601 UTC. Built with Apache Commons CSV, with free-text cells (member-controlled names and notes)
  guarded against spreadsheet formula injection.
- A new `PRICE_CHANGE` activity type for the global feed. The dev demo data now seeds a price change and an
  admin count correction, so every activity type appears on a fresh dev start.
- A branded favicon: a coffee cup in the SE@UHD brand red (the same cup the activity feed shows), replacing
  the default icon. It ships as an SVG with PNG and ICO fallbacks, plus apple-touch and PWA (maskable) icons.

### Changed

- Unify the event-log activity walk: the per-member, kitty, and new global feeds now share one
  `ActivityWalk` over the log (replacing the duplicated per-member and kitty walks), and the chronological
  feed reads move from `AccountingService` to a dedicated `ActivityService` (mirroring the data layer's
  `ActivityDataService`). Behavior of the existing member and kitty feeds is unchanged.

### Fixed

- Stop the `ConsumptionDeltaDto.deltaIsSingleStep` validation getter (an `@AssertTrue` helper) from leaking
  into the request-body JSON and the OpenAPI schema (and so the generated frontend DTO): mark it
  `@JsonIgnore` and `@Schema(hidden = true)`. It is a validation-only derived flag, never a request field.

## [0.5.1] - 2026-06-25

### Changed

- Deploy to Cloud Run with `gcloud run deploy --source . --set-secrets` instead of `gcloud beta run compose
  up`. Compose up cannot bind a Secret Manager secret as an environment variable, so the cloud deploy moved
  to `gcloud run deploy` (it still builds the image from the `Dockerfile` via Cloud Build). The secrets and
  non-secret config are read from `deploy.prod.env` and passed via `--set-secrets` / `--set-env-vars`.
- Silence the JDK 25 build/test log warnings: pass `--sun-misc-unsafe-memory-access=allow` and
  `--enable-native-access=ALL-UNNAMED` to the forked test JVMs (the `java-conventions` and
  `detekt-rules-conventions` plugins) and add the native-access flag to the Gradle daemon (`gradle.properties`),
  since those JVMs do not inherit the daemon flags. This drops the `sun.misc.Unsafe` notices (from
  detekt-test-utils' kotlin-compiler-embeddable and caffeine) and the JNA restricted-native-access notice from
  the build log. Also `@Suppress("DEPRECATION")` the deliberate `JWEAlgorithm.RSA1_5` use in
  `LoginPayloadDecryptorTest`, which verifies the decryptor rejects an algorithm downgrade.
- Cap the prod Hikari connection pool at `maximum-pool-size: 5` so a zero-downtime rolling Cloud Run deploy
  (the old and new revisions overlap, each holding its own pool, across several instances) cannot exhaust the
  small `db-g1-small` Cloud SQL instance's connection limit.

### Removed

- Remove `compose.prod.yaml`: the cloud deploy no longer uses a Compose file. The dev `compose.yaml` stays
  for local runs.

### Fixed

- Fix the production UI rendering unstyled under the Content-Security-Policy. Angular's production build inlined
  critical CSS and loaded the full stylesheet asynchronously through an inline `onload="this.media='all'"`
  handler, which the strict `script-src 'self'` policy blocks, so the stylesheet stayed `media="print"` and the
  Angular Material theme and page layout never applied on screen (the dev e2e missed it: the CSP and the
  critical-CSS optimization are prod-only). Disable critical-CSS inlining (`optimization.styles.inlineCritical:
  false` in `frontend/angular.json`), so the build emits a plain render-blocking stylesheet link with no inline
  JS, keeping the CSP strict.

### Security

- Route the production deployment secrets through Google Secret Manager. `scripts/deploy-cloudrun.sh` syncs
  `JWT_SECRET`, `LOGIN_PRIVATE_KEY_PEM`, `DB_PASSWORD`, and `BOOTSTRAP_ADMIN_PASSWORD` from the gitignored
  `deploy.prod.env` (the local source of truth) into Secret Manager and binds them onto the Cloud Run service
  from there (`--set-secrets`), so the runtime gets encryption at rest, access audit, and versioning, and no
  secret rides in the git history or the build context. The runtime service account needs
  `roles/secretmanager.secretAccessor`. See `doc/2026-06-25_secret-manager-for-deployment-secrets.md`.

## [0.5.0] - 2026-06-25

### Added

- Add an admin "Download all QR codes (PDF sheet)" action that renders every active member's capability QR
  code into one printable A4 PDF grid, each code labelled with the member's login name, next to the existing
  ZIP download (`GET /api/users/qr.pdf`, admin-only). Backed by Apache PDFBox (Apache-2.0) behind a new
  `QrGridPdfGenerator` domain port, so the PDF library stays out of the web and domain layers.

### Changed

- Restrict the bulk QR downloads to active members: both the new PDF sheet and the existing
  `GET /api/users/qr.zip` now skip deactivated members, whose wall codes are retired.
- Tidy the browser tab titles: capitalized, with SE@UHD in parentheses and no middle-dot separator, e.g.
  "My Coffee (SE@UHD)".
- Give every entity except the append-only `events` log a `version` column for optimistic locking:
  `UserEntity` gains a `@Version` field and the `users` table a `version` column (`NOT NULL DEFAULT 0`), so
  two concurrent admin edits of the same member can no longer silently overwrite each other; the existing
  version fields default to 0 to match.
- Consolidate the Flyway migrations to one `CREATE` per table and strip their comments: the incremental
  index and version migrations are folded into their table's create, and the rationale lives in the entity
  classes and this changelog rather than in the SQL. A one-time pre-production cleanup (no deployed database
  had the old migrations applied); from here migrations stay append-only.
- `scripts/check-version-sync.sh` compares the full version token (including any pre-release or build
  suffix), not just `x.y.z`.
- Unify the form and panel vertical rhythm: tighten the gap between form fields (and `.form-row` groups) to
  12px, give every panel heading the same 16px gap to its first element (the profile "Your details", the
  "Members" card, and the collapsible "Record expense" headers were flush against their content), and
  balance the count card so the visible gap above the number matches the gap below the action button (the
  empty "undo" slot no longer reserves space, and extra bottom padding offsets the big number's line-box and
  the button's drop-shadow).
- Center the members-table row controls vertically (the slide-toggle now shares the icon buttons' center)
  and render the table only once its data has loaded, so the active/inactive toggles no longer animate into
  place on first paint. Center the admin member-select dropdown in its card (its empty subscript row is no
  longer reserved).
- Tidy form and table copy: shorten the "Download all QR codes" button to "Download all" (the tooltip and
  aria-label keep the full text), remove the amount-format hints (the euro input already flags a bad value),
  drop the standing private/kitty split hint (the rule is still enforced and shown on a mismatched submit),
  show "Member"/"Admin" in the role dropdown and chips instead of the raw enum, match the activity filter
  labels to the entry labels (Coffee/Expense/Deposit), phrase the delete-confirmation titles as statements,
  and trim several redundant or jargon-laden hints.
- Compact the unified activity list: replace Material's airy three-line list item (a fixed ~88px height that
  spread the title, date, and balance apart) with a tight custom row, so an entry's lines sit close together
  while entries stay clearly separated.
- Reserve a form field's subscript row only when it has an error to show (`subscriptSizing: dynamic`), so a
  field with no visible error no longer leaves an empty row below it, keeping the field rhythm and the
  field-to-button gap tight.
- Pin every page to one width (`max-width: 740px`) sized to the members table at its minimum content width
  (no column overflows), replacing the old 480px / 920px split, and give the cards 24px horizontal padding
  so content is not cramped against the side edges.
- `scripts/deploy-cloudrun.sh` enables Cloud Run startup CPU boost (`--cpu-boost`) so the JVM and Spring
  context boot faster, shortening a scale-to-zero cold start.
- Declare the Kotlin Gradle plugin once at the root project (a new `build.gradle.kts`, `apply false`, version
  from a new `kotlin-jvm` catalog alias that tracks the existing `kotlin` version), so a single classloader
  provides it to every subproject's `build-logic` convention plugin. This silences Gradle's "The Kotlin
  Gradle plugin was loaded multiple times in different subprojects" warning; the build output is unchanged.
- Maintain `member_balance` and `kitty_balance` read-model projections (updated in the write transaction by
  recomputing from the event-log walks), so the per-member overview and the kitty-overdraw guard read one
  indexed row instead of replaying a stream; the events-to-data rebuild recomputes them and now replays the
  log in bounded batches.
- Unify event-note handling at the append boundary: `events.note` now carries every event's note (the
  count-correction reason or the entity's own body note), so the activity and kitty feeds show deposit,
  adjustment, and expense notes that were previously dropped.
- Serialize every balance-projection recompute so a concurrent write cannot lost-update an unversioned
  projection row. The `KittyLock` port becomes `BalanceLock` with `lockKitty()` and a new per-member
  `lockMember(userId)` (a second Postgres advisory lock keyed on the member id), and `BalanceProjection`
  acquires the right lock (kitty before member, a fixed order so the paths cannot deadlock) around every
  recompute. This closes two gaps where a kitty write skipped the lock: `PaymentService.recordDeposit` and
  `ExpenseService.delete` both move the kitty but took no lock, so a deposit or a delete racing a guarded draw
  could lose the draw's kitty recompute and let a later expense overdraw the fund. The per-member balance,
  recomputed by self-scans, purchases, deposits, and admin steps with no shared versioned row, is now
  serialized the same way.
- Clear the `member_balance` and `kitty_balance` projections explicitly at the start of the events-to-data
  rebuild instead of relying on the `member_balance` cascade from the user delete, so the kitty row cannot go
  stale during the replay.
- Read the cancel grace period through a typed `ConsumptionProperties` (`campus-coffee.consumption`,
  default 5 minutes) in the domain instead of a raw `@Value`, so the key resolves in the IDE's
  `application.yaml` editor like every other custom property.
- Add the synchronous re-entrancy guard (`if (this.busy) return`) to the admin mutation handlers that lacked
  it: the +1/-1 count button, the absolute count override, record purchase, create user, set price, and save
  profile. A fast double-tap fired two same-tick handlers before the disabled state re-rendered, so a
  non-idempotent count change or a purchase could post twice; the sibling member and kitty handlers already
  had this guard.
- Run the remaining frontend quality gates in `gradle check` (so CI catches them): the Vitest unit suite,
  Knip dead-code detection, and the Prettier format check, alongside the existing eslint/stylelint. Only the
  eslint/stylelint gate ran before, so a broken unit test, an unused export, or unformatted TypeScript could
  merge green. The committed OpenAPI spec dump (`src-gen/api-docs.json`) joins the generated DTOs in
  `.prettierignore`, since springdoc owns its formatting.
- Build the Docker image in CI (a new `docker-build` job), so a broken Dockerfile fails the build instead of
  the first production deploy.
- Pin the runtime base image by digest (`eclipse-temurin:25-jre-alpine@sha256:...`) for a reproducible image,
  keeping the `25` major in the `FROM` line so the toolchain-version check still reads it.
- Size the production runtime: set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75.0` so the heap tracks the
  container limit, and set `--memory`, `--cpu`, `--concurrency`, and `--max-instances` on the Cloud Run
  deploy instead of relying on the platform defaults.
- Fix `scripts/run-e2e-coverage.sh`: under `set -e` the e2e exit-code capture was dead code, so a failing
  e2e aborted the script before the coverage flush and the real status was lost. The e2e now runs so its
  exit code is captured, the JaCoCo flush always happens, and the script exits with the e2e's real status.

### Removed

- Remove the public demo deployment: the `demo` Spring profile, `compose.demo.yaml`, the throwaway Cloud Run
  demo, and the demo-only `deploy-cloudrun.sh` instance-count knobs are gone. Production deploys scale to zero
  (with startup CPU boost to shorten the cold start) and use the shared Cloud SQL database directly, since
  nothing reseeds it on boot any more. `DevDemoDataLoader` stays for local dev and the e2e suite, now
  `@Profile("dev")` only.

### Fixed

- Fix the member landing page: the count/hero card and the balance card sat flush with no gap (the hero
  "+" button overlapped the balance card); they now keep the same 16px spacing as the rest of the page.
- Page `GET /api/price/history` and cap `PageQuery.offset`; stop the `GlobalExceptionHandler` from echoing
  framework exception class names or parser detail in any profile.
- Reject a zero-total admin expense (matching the member path), normalize a member's email so the unique
  constraint catches case-variant duplicates, and surface a concurrent first-ever price write as a clean 409
  instead of a recovery a real transaction cannot perform.
- Retry a member self-scan 409 a bounded number of times with backoff, and stop an admin profile edit (or
  active-state toggle) from reverting a concurrent admin's `role`/`active` change with a stale snapshot.
- Reject a zero or negative `delta` on the admin single-step count change at the validation layer (it was
  caught only in the domain before), and require a bean purchase to weigh at least 100 grams (the expense DTOs
  accepted a zero-weight purchase that still moved money).
- Log a warning when a coffee is valued at 0 because the price history is empty, instead of valuing it as free
  with no signal. A price is always seeded before any coffee in normal operation, so this only fires on a
  hand-edited or misordered log; the as-of lookup still falls back to the earliest known price, never 0, when
  any price exists.
- Fix documentation that no longer matched the code: the README's event-log note (`events.note` carries every
  event's note, not only a count-correction reason) and its claim that the domain has no framework
  dependencies (it depends on Spring), the `AdminAccountingController.overview` KDoc (member balances are read
  from the projection, not replayed per member), the `ConsumptionProperties` location and the migration count
  in `CLAUDE.md`, and the login-payload design note in `doc/` (the payload carries `iat` and the replay guard
  is implemented, not deferred).

### Security

- Encrypt the admin login payload in the browser. `POST /api/auth/token` now takes a compact JWE
  (`RSA-OAEP-256` + `A256GCM`) of `{ loginName, password, iat }` instead of a plaintext body: the backend
  publishes its RSA public key at the new public `GET /api/auth/public-key` (a JWK), and the SPA encrypts the
  credentials with it (via the `jose` library) before signing in. This keeps the raw password out of
  TLS-terminating proxies and request-body logs (defense in depth on top of TLS). The RSA private key is
  configured like the JWT secret, `campus-coffee.login-encryption.private-key-pem` (a PKCS#8 PEM, at least
  2048 bits), required in prod via `LOGIN_PRIVATE_KEY_PEM` (delivered as one line with `\n` separators) with
  an insecure dev fallback; the same key must be present on every instance, so it is configured rather than
  generated per startup. A malformed or undecryptable payload returns 400, distinct from the 401 for
  wrong-but-readable credentials, so it is not a credential oracle. This is defense in depth, not a new trust
  boundary: it does not replace TLS, does not protect against a compromised client or XSS, and is not
  zero-knowledge (the server decrypts and still verifies the password with bcrypt).
- Gate the dev data endpoints (`/api/dev/**`) open rule on the `dev` profile, so it is never registered in a
  deployed profile (defense in depth; `DevController` is already `@Profile("dev")`).
- Move the admin session from a JWT in `localStorage` to an **httpOnly, SameSite=Strict cookie** that
  JavaScript cannot read or exfiltrate, so an XSS can no longer steal the admin token. The resource server
  reads the bearer token from the cookie (the SPA) or the `Authorization` header (API and test clients) via a
  new `CookieOrHeaderBearerTokenResolver`; `POST /api/auth/logout` clears the cookie. The cookie is `Secure`
  in every profile but dev (`campus-coffee.auth.cookie.secure`). SameSite=Strict is the CSRF defense, so
  token-based CSRF (which would burden the CSRF-immune header flows) stays off. See
  `doc/2026-06-24_security-hardening-and-cookie-auth.md`.
- Send a Content-Security-Policy on every response (`default-src 'self'`, `connect-src 'self'`,
  `frame-ancestors 'none'`, with inline styles allowed for Angular Material), the structural XSS mitigation.
- Bound the replay window of the encrypted login payload: it now carries an `iat`, and the decryptor rejects a
  payload outside `campus-coffee.login-encryption.max-payload-age` (default 2 minutes) as a 400.
- Reject the committed dev-only fallback JWT secret and RSA login key on a non-dev startup (`WeakDevSecretGuard`),
  and reject a loopback or bare-hostname prod base URL (`PublicBaseUrlGuard`).
- Read `GET /api/price` through the live-user check so a deactivated or demoted admin's in-flight token is
  rejected there too.
- Stop leaking `deploy.prod.env` into the Docker/Cloud Build context (`.dockerignore` now matches
  `deploy*.env`), `chmod 600` the deploy env files and harden `scripts/deploy-cloudrun.sh` (`umask 077`), and
  run the prod app as a least-privilege database role (`scripts/sql/create-app-role.sql`) instead of the
  Cloud SQL superuser.
- Rate-limit `POST /api/auth/token` to stop online password guessing and a bcrypt CPU-exhaustion flood. A
  Bucket4j token bucket per client IP (held in a Caffeine cache) allows `campus-coffee.auth.rate-limit.max-failures`
  failed attempts per `campus-coffee.auth.rate-limit.window` (default 10 per 15 minutes); the next attempt is
  refused with 429 and a `Retry-After` header before any decrypt or bcrypt work runs. A malformed payload and
  a wrong password both count as a failure; a successful login clears the count. Behind a proxy the client IP
  comes from `X-Forwarded-For`. The limiter is on by default and can be turned off with
  `campus-coffee.auth.rate-limit.enabled=false`.
- Return the same `401 Unauthorized` body for every login failure (wrong password, unknown login, deactivated
  account), so the response no longer reveals whether a login name exists or is deactivated; the real cause is
  logged server-side only.
- Make a captured login ciphertext single-use within its freshness window. The decryptor fingerprints each
  compact JWE and rejects a second presentation of the same ciphertext as a replay (400), closing the residual
  window the `iat` check alone left open. No client change: the existing per-encryption randomness already
  makes each genuine login a distinct ciphertext.
- Strengthen the admin password policy to at least 24 characters with a lowercase letter, an uppercase letter,
  and a digit, on both the admin API (`UserDto`) and the bootstrap-admin path. Members are unaffected (they
  authenticate with a capability link and have no password). The seeded dev and test admin passwords are
  updated to match.
- Close a deactivated-admin lockout gap on `DELETE /api/users/{id}`: the delete now resolves the acting user
  like the other admin endpoints, so an admin who was deactivated while their JWT is still valid can no longer
  hard-delete members. A stale or absent principal on any endpoint now reads as a clean 401 (re-authenticate)
  rather than a 404 or 500.
- Document the event log's retention of superseded auth secrets in
  `doc/2026-06-24_security-hardening-and-cookie-auth.md`: the append-only log keeps the full state of each
  `User` event, including the capability token and bcrypt password hash, so an old token or hash lingers after
  a rotation or password change. This is no new exposure beyond the access-controlled `users` table (a rotated
  token no longer authenticates, and a bcrypt hash is a hash), so it is accepted and documented rather than
  encrypted in the log.

## [0.4.0] - 2026-06-23

This release renames the API and code vocabulary to the words the UI and endpoints already use. It is a
**breaking API change**: the bundled SPA, the committed OpenAPI spec, and the docs all move in lockstep.

### Added

- Guard tests pin the Actuator authorization contract: `/actuator/health` stays anonymously reachable (200),
  while every other actuator path is refused to anonymous callers (401) and to members (403). Together they
  fail loudly if the security matcher order ever regresses (the SPA `GET /** -> permitAll` slipping ahead of
  the `/actuator/** -> ADMIN` rule), which would otherwise expose actuator endpoints anonymously.
- **A `demo` Spring profile for a public, throwaway demo on Cloud Run.** Activated together with prod as
  `prod,demo`, it is a thin overlay: it keeps every prod hardening (managed Cloud SQL, required https base
  URL, real JWT secret, Swagger and the `/api/dev` endpoints off) and only adds the seed data, loading the
  full fixture + `DevDemoDataLoader` dataset (~15 members with populated ledgers, balances, and a kitty
  float) with deterministic ids and clearing+reseeding it on every boot, so the demo is deterministic,
  self-cleaning, and discards any prior deployment's data. `DevDemoDataLoader` now runs on `dev` and `demo`;
  `scripts/deploy-cloudrun.sh` takes a compose file (`compose.demo.yaml`) plus a `MAX_INSTANCES=1` cap so the
  boot-time reset runs on a single instance. The https base-URL fail-fast `ProdConfigGuard` is renamed
  `PublicBaseUrlGuard` and now guards both internet-facing profiles (`prod` and `demo`).

### Changed

- **Breaking: the kitty "settlement" is now a "deposit".** The request body `SettlementRequestDto` is renamed
  `DepositRequestDto` and `PaymentService.recordSettlement` becomes `recordDeposit`. The persisted entity
  stays `Payment`, and the `POST /api/kitty/deposit` endpoint is unchanged.
- **Breaking: the unified "ledger" becomes the member's "activity" feed, and the kitty's becomes its
  "history".** `LedgerEntry`/`LedgerEntryType` become `ActivityEntry`/`ActivityEntryType` (the `SETTLEMENT`
  value becomes `DEPOSIT`), `LedgerEntryDto` becomes `ActivityEntryDto`, `LedgerDataService` becomes
  `ActivityDataService`, `AccountingService.kittyLedger()` becomes `kittyHistory()`, and the
  `MemberSummaryDto.ledger` field becomes `activity`. The `/api/activity` and `/api/kitty/history` endpoints
  are unchanged.
- **Breaking: the read-side "Member" types become "User" types.** `MemberSummaryDto`/`MemberBalanceDto`
  become `UserSummaryDto`/`UserBalanceDto`, `MemberExpenseDto` becomes `OwnExpenseDto`, the member
  self-service controller becomes `SelfServiceController`, and `AccountingService.memberSummary`/`memberLedger`
  become `userSummary`/`userActivity`. The persisted entity was already `User`; the SPA keeps "member" in its
  UI copy.
- **Breaking: the member capability header is renamed `X-Coffee-Token` to `X-Capability-Token`** (and the
  `COFFEE_TOKEN_HEADER` constant to `CAPABILITY_TOKEN_HEADER`), matching the capability-token and
  capability-URL vocabulary. The capability-token filter, the SPA HTTP interceptor, the tests, and the docs
  move together.
- Two small review cleanups land alongside the renames: a redundant clear-order comment is dropped from the
  Cucumber test config (the ordering is already explained in `EventsToDataRunner`'s KDoc), and the
  deactivation check in `CurrentUserProvider`/`DomainUserDetailsService` is unified on `active != true` to
  match the domain services.
- Log messages identify an entity consistently by its **id, in single quotes**, never by a login name. A
  login name is PII; the id is the canonical, privacy-preserving key. The `UserServiceImpl.describe` /
  `describeId` overrides that appended the user's login name to the create/update/delete audit log are
  removed (along with the now-unused `CrudServiceImpl` extension hooks, so the base type+id format is used
  everywhere); `AuthController` logs the bare `Token requested.` event without the supplied login name (no id
  is resolvable at the credential boundary, and a failed attempt has no user); and `BootstrapAdminLoader`
  logs the created admin's id. The dev-only demo loader still names its hardcoded demo fixtures (compile-time
  constants, not user data) for readable startup output.
- **The runtimes are guarded to stay on LTS.** `scripts/check-toolchain-versions.sh` now also validates the
  pinned **Node** major (`mise.toml`) against the official Node release schedule, failing CI on a non-LTS
  line: an odd "Current" major (e.g. 25) or an even-but-not-yet-LTS major (e.g. 26, "Current" until October
  2026). It also asserts `@types/node` and the `engines.node` floor track that major. Paired with the
  Dependabot rule that ignores `@types/node` major bumps, this keeps the frontend toolchain on the Node 24
  LTS line; the runtime is bumped by hand only when moving to the next Node LTS.
- **Web security moved into the `api` layer, where the inbound HTTP adapter belongs.** The Spring Security
  filter chain, the capability-token filter, the `UserDetailsService`, the JSON auth entry-point/handlers,
  the `ActorProvider` adapter, the JWT config + `JwtProperties`, and the public base-url guard all moved from
  `application` (the composition root) into `api` (packages `de.seuhd.campuscoffee.api.security` / `.api.web`
  / `.api.configuration`). The ArchUnit layer definition is updated to match; `application` now holds only
  the Spring Boot main class and the bootstrap/seeding (the loaders plus the `Fixtures`/`CoffeePrice`/
  `BootstrapAdmin` properties). `SpaForwardingController` is renamed `SinglePageAppController`.
- `BootstrapAdminProperties` declares only the structure; its default values (the admin email and name) move
  into `application.yaml`'s prod `campus-coffee.bootstrap-admin` block, so a default has a single source.

### Removed

- The unused CORS configuration (`CorsConfig` + `CorsProperties` + `campus-coffee.cors.allowed-origins`) is
  removed: the SPA is same-origin, so the security chain needs no CORS source (re-add it in `api` if the SPA
  ever moves to a separate origin).

## [0.3.1] - 2026-06-22

A hardening release working through an extensive adversarial review of the whole project (code, docs,
design): production-deployment fixes, accounting and event-sourcing correctness, architecture-leak cleanups,
a fuller API contract, frontend correctness and UX, the previously-missing tests, and a repo-wide em-dash
and prose cleanup. It also aligns the API endpoint names with the frontend vocabulary, a breaking endpoint
rename (the bundled SPA, OpenAPI spec, and docs are updated in lockstep).

### Added

- The previously-missing tests: two concurrent self-scans (no lost update, a clean 409), the
  expense-correction kitty-overdraw guard, the advisory-lock ordering (the lock is taken before the balance
  read), the money fat-finger caps, and the login-rename invariant.

### Changed

- **API endpoint names aligned with the frontend vocabulary (breaking).** The member and admin activity
  feeds, the member's `GET /api/ledger` and the admin's `GET /api/users/{id}/ledger`, are now `/activity`
  (the UI's "Recent activity"). The kitty's `GET /api/kitty/ledger` is now `GET /api/kitty/history` (its UI
  "Kitty history"), and the two kitty money-movements move from `/api/payments/*` under the kitty resource:
  `POST /api/kitty/deposit` (renamed from "settlement", the UI's "deposit") and `POST /api/kitty/adjustment`.
  `SummaryController` is renamed to `MemberController` and `PaymentController` folds into `KittyController`.
  The shared `LedgerEntryDto`/`LedgerEntryType` data types keep their names; the OpenAPI spec, the generated
  frontend DTOs, the SPA services, and the docs are all updated together.
- **Controller paging validation unified on a shared `PageQuery` (one breaking parameter rename).** The four
  paged reads (the member summary and activity, the kitty history, and the admin per-member activity and
  change log) now bind their `limit`/`offset` through a single `@Valid @ParameterObject PageQuery` object
  rather than each repeating `@Max`/`@Min`/`@Positive` parameters and a private `MAX_PAGE_LIMIT`. This removes
  the class-level `@Validated` from every controller and validates paging through `@Valid` request binding
  (the same path a request body uses, raising the same 400). `@Validated` cannot be used on the
  `CrudController`-extending `UserController` regardless: its overridden `create`/`update` would trip Bean
  Validation's HV000151 (the Liskov rule on parameter constraints) and 500 every inherited handler, so the
  per-member admin reads stay in their own controllers. The member `GET /api/summary` first-page parameters
  are renamed from `ledgerLimit`/`ledgerOffset` to `limit`/`offset` to match (breaking).
- The `events.note` metadata column records only an absolute count correction's reason; a settlement, kitty
  adjustment, or expense note lives in that entity's own event body (the docs are corrected to match).
- **Architecture.** The price-singleton conflict is mapped to a domain `DuplicationException` in the data
  adapter, so no Spring persistence exception is caught in the domain; the event-store `seq` is removed from
  the domain `CancellableIncrement`; the ArchUnit rules now cover the `web`/`configuration` packages and
  assert every class belongs to a layer; and dead code is removed (`ConsumptionProperties`,
  `EventSourcedWriter.create`, the no-op `OnCreate` group, `ValidationException`'s unused constructor).
- **API and OpenAPI.** Settlement, adjustment, and admin-expense create declare `201`; the QR endpoints
  advertise `image/png` and `application/zip`; a dedicated `ProfileUpdateDto` carries the profile edit; and
  the handwritten controllers document their `400/401/403/404/409` responses. The committed OpenAPI spec
  and the generated frontend DTOs are regenerated to match.
- **Frontend UX.** A human browser-tab title with per-route titles, a real not-found page for unknown URLs,
  a `prefers-reduced-motion` block, a `theme-color` meta and a web manifest, one consistent snackbar action
  label, and the ledger now reads "Coffee undone" and "total N cups" (no jargon or math glyph).
- A new `V8` migration adds an `(entity_type, seq)` index and a `UNIQUE(seq)`; the kitty ledger reads one
  SQL-ordered stream instead of re-sorting two in memory.
- **Em-dashes removed from all prose and source comments** across the docs, `README.md`, `CLAUDE.md`, and
  the Kotlin/TypeScript/SCSS/build files, replaced with plain punctuation. This completes the AI-slop
  cleanup the 0.3.0 entry began.
- Run the `:application:test` suite (the system, acceptance, and architecture tests, the slow part of the
  build) in parallel across several JVM processes. `maxParallelForks` on the `application` `test` task now
  defaults to `min(4, availableProcessors / 2)` (override with `-PtestForks=N`; `-PtestForks=1` disables
  parallelism), with a `1g` per-fork heap cap so the forks cannot collectively overcommit. Process-level
  forking is the only safe form of parallelism here: `SystemTestUtils` is an `object` with a shared mutable
  `RestTestClient` and the test bases wipe the whole database between tests with `clearAll()`, so two tests
  must never run concurrently in the same JVM or against the same database. Each fork is a separate JVM with
  its own `SystemTestUtils` and its own Testcontainers PostgreSQL instance (the container lives in
  `AbstractSystemTest`'s companion object, one per JVM), and JUnit runs the classes within a fork serially,
  while Spring's per-JVM context cache still pays off within each fork.
- Log through **kotlin-logging** (`io.github.oshai:kotlin-logging-jvm`) instead of the SLF4J API directly:
  every `LoggerFactory.getLogger(X::class.java)` becomes `KotlinLogging.logger {}` and the call sites move
  from the SLF4J parameterized form (`log.info("... {}", arg)`) to the lambda form (`log.info { "... $arg" }`),
  which builds the message only when the level is enabled. kotlin-logging is a thin Kotlin layer over SLF4J,
  so the backend stays Logback (via the Spring Boot starters) and the resolved logger names and message
  text are unchanged.
- A second adversarial review (run after all the changes above) confirmed the renames and refactors
  introduced no behavioral regressions, and its findings were fixed: the event-store `seq` also leaked
  through the unified-ledger `LedgerEntry`/`LedgerEntryDto`, which now expose a stable per-entry `id`
  sourced from the event (not the append position, which stays inside the data-layer walk); `CLAUDE.md`'s
  `/summary` paging parameters were stale (two `V3` migration comments are also stale, but editing an
  already-applied migration would break its Flyway checksum on any migrated database, so they were left as
  is); the admin member-create form's name fields gained field-specific validation copy; HTTP-level tests
  were added for the `PageQuery` bounds
  (`limit` `@Max`/`@Positive`, `offset` `@Min`) and the kitty deposit/adjustment validation; and
  `frontend/package.json` now declares a Node `engines` floor. The Gradle daemon's `MaxMetaspaceSize` was
  also raised to 768m (the growing test suite exhausted 384m during the CI coverage aggregation pass).
- The Qodana static-analysis workflow now runs on every push (not only `main` and PRs), so its IntelliJ
  inspections catch idiomatic findings on the feature branch rather than only at merge. Its 8 backend
  findings were fixed (a redundant nullable return type, three redundant type arguments, a multi-dollar
  string, and three duplicated fragments DRYed into the shared `EventJsonMapper.writeEntityHeader` and
  `LedgerDataServiceImpl.deltaEffect` helpers). The frontend Qodana job, whose JS linter needs a paid Qodana
  Cloud token the project does not set, is marked `continue-on-error` so it never blocks CI (the eslint and
  stylelint `frontendLint` gate still covers the SPA). A commit-message convention was added to `CLAUDE.md`. The `EventSourcedMutator` event-write helper was renamed to `EventSourcedWriter` (the `Mutator` name collided with the project's mutation-testing vocabulary).

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
- The three ledger endpoints show their real `@RequestParam` defaults (`/ledger` and `/users/{id}/ledger`
  default to `limit=20`, `/kitty/ledger` to `limit=50`; the change-log default stays 5). `CLAUDE.md` now
  lists `GET /users/filter`, documents the `is_singleton` / `uq_coffee_prices_singleton` single-row guard,
  corrects the domain "no external dependencies" claim (it depends on Spring by design), marks the expense
  `weightGrams` required, and the `StartupDataInitializer` KDoc lists the real startup-task order.

### Security

- The insecure JWT-secret fallback is scoped to the dev profile only; a missing `JWT_SECRET` now fails fast
  in every other profile, and the prod compose runs the prod profile with a real secret (it previously ran
  the dev profile with the committed default).
- The runtime container runs as a dedicated non-root user and declares a `/actuator/health` healthcheck.

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
- Flyway migrations `V4`-`V7`: `coffee_prices`, `expenses` (with a split-sum CHECK), `payments`, and the
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

[0.6.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.6.0
[0.5.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.5.1
[0.5.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.5.0
[0.4.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.4.0
[0.3.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.3.1
[0.3.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.3.0
[0.2.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.2.0
[0.1.1]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.1
[0.1.0]: https://github.com/se-uhd/campus-coffee-consumption/releases/tag/v0.1.0
