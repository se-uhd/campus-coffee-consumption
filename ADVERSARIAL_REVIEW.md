# CampusCoffeeConsumption: Adversarial Review Synthesis

## Executive Summary

This is a carefully engineered codebase. The hexagonal architecture is real and enforced, the event-sourcing machinery is consistent, the money model is integer-cents end to end, and the test discipline (system, acceptance, architecture, mutation, dual-sided e2e coverage) is well above average for a project of this size. Most of what follows is hardening, documentation accuracy, and edge-case coverage rather than broken core behavior.

The serious problems cluster in one place: **production deployment configuration is broken in ways that would surface only after the first real deploy.** Three separate defects each independently make a fresh prod deployment unusable or unsafe, and they compound. Fix these first:

1. **Prod inherits the deterministic id seeds (42/100) with no startup reset, so primary-key collisions begin on the first entity created after any restart or redeploy.** A persistent prod database cannot reliably create members after its first restart.
2. **The prod deploy never sets `CAMPUS_COFFEE_APP_BASE_URL`,** so every wall QR code and capability link encodes `http://localhost:8080/login/<secret-token>`: unusable links, and the central member onboarding path is dead.
3. **`compose.prod.yaml` overrides the documented Cloud SQL datasource with a hardcoded `postgres/postgres` plain-JDBC sidecar that declares no volume,** so the deploy silently talks to an ephemeral database with default credentials, and the entire money ledger is destroyed on every restart. The Secret-Manager password and Cloud SQL connector are dead config in this path.

A fourth deploy gap (no `BOOTSTRAP_ADMIN_*` wired into the tooling) leaves a fresh prod instance with no admin account at all. Taken together, the documented one-command deploy produces an app that has no admin, points members at dead links, talks to a throwaway database with a guessable password, and starts colliding on ids after the first restart. None of these are exploitable by an outsider, but they make the production story non-functional as shipped.

The second cluster worth attention is **one genuine data-integrity defect in the live application:** renaming a member's login name silently disables their undo and can misvalue their balance, because the ledger walk keys ownership on the mutable login string rather than the immutable user id.

Notably well done: the event-first projection with a single transaction per write, the `LoggedEntityType` enum that makes the projector exhaustive over logged types, the kitty-overdraw advisory lock (correctly placed before the read in every mutating path), the seq-keyed as-of valuation, the deliberate in-memory keeping of capability tokens in the SPA, the security split (capability tokens always `ROLE_USER`), and the breadth of the system/acceptance suites. The codebase's own docs and KDoc are mostly accurate; the inaccuracies found are specific and listed below.

## Severity Counts

| Severity | Count |
| --- | --- |
| Critical | 0 |
| High | 4 |
| Medium | 16 |
| Low | 47 |
| Nit | 30 |
| **Total** | **97** |

(115 raw findings merged to 97 distinct issues after deduplication; the em-dash findings and several deploy/database findings were reported by multiple reviewers and are merged below.)

---

## High

### Deployment configuration (fix before any production deploy)

#### H1. Prod inherits deterministic id seeds (42/100) with no reset, causing primary-key collisions on restart
**Severity: High** · `application/src/main/resources/application.yaml:35-37` (prod block 84-112 does not override); `data/.../SeededUuidGenerator.kt:18-25`

The prod profile does not override `campus-coffee.id.entity-seed` / `event-seed`, so it inherits the numeric defaults 42 and 100. A numeric seed selects the deterministic `SeededUuidGenerator`, whose `Random(seed)` restarts from the seed on every process start. In prod, `fixtures.reset-on-startup` is false, so prior rows persist. After any container restart or redeploy, the generator re-issues the same first UUIDs, which now collide with existing rows. Dev escapes this only because `reset-on-startup` clears the database first; prod has no such reset.

**Impact:** The first user/event/expense/payment created after a prod redeploy is assigned a UUID that already exists, so the INSERT fails loudly with a `DuplicationException` / PK constraint violation. The app is up but cannot reliably create new entities after its first restart. (Note: the failure is loud, not silent corruption; the DB constraint prevents a bad-reference write. The bootstrap admin specifically is a no-op when an admin already exists, so it is the first member/expense/payment that collides.)

**Fix:** Set `campus-coffee.id.entity-seed: random` and `event-seed: random` in the prod profile block (or pass `CAMPUS_COFFEE_ID_ENTITY_SEED=random` / `CAMPUS_COFFEE_ID_EVENT_SEED=random` in `compose.prod.yaml`). Deterministic seeds are only safe with `reset-on-startup`, which prod deliberately disables.

#### H2. Prod sets no `CAMPUS_COFFEE_APP_BASE_URL`, so capability URLs and QR codes default to `http://localhost:8080`
**Severity: High** · `application/src/main/resources/application.yaml:31-34`; `api/.../AppProperties.kt:14-16`

`campus-coffee.app.base-url` defaults to `http://localhost:8080` and is overridable only via `CAMPUS_COFFEE_APP_BASE_URL`, which is set nowhere in the prod deploy (`compose.prod.yaml`, `deploy.env.example`, `deploy-cloudrun.sh`). The prod profile block does not set it either. `CapabilityUrlFactory` builds every member's capability URL as `"$baseUrl/login/{token}"`, and the QR codes encode it.

**Impact:** Every wall QR code and every `/profile` capability link in prod points at `http://localhost:8080/login/<secret-token>`: unusable off the server, breaking the primary member-onboarding path. There is no override and no startup guard. (The secondary "token travels over plain HTTP" framing is overstated for the default value specifically: `localhost` is the scanning phone's own loopback, so the link simply fails to open rather than leaking the token to the server over HTTP. The dominant impact is dead links.)

**Fix:** Set `base-url` to the https Cloud Run URL in the prod deploy, or fail fast at startup if `base-url` is still `localhost`/`http` while the prod profile is active.

#### H3. `compose.prod.yaml` overrides the Cloud SQL datasource with a hardcoded `postgres/postgres` sidecar that has no volume
**Severity: High** · `compose.prod.yaml:32-36, 38-52`; `application/src/main/resources/application.yaml:89-95`
*(Merges two reviewer findings: the Cloud SQL override, and the no-volume ephemeral storage.)*

The prod profile in `application.yaml` configures Cloud SQL via the socket factory and a Secret-Manager password. But `compose.prod.yaml` (used by `scripts/deploy-cloudrun.sh`) sets `SPRING_DATASOURCE_URL` to a plain `jdbc:postgresql://${DB_HOST:-localhost}:5432/postgres` with `SPRING_DATASOURCE_PASSWORD=postgres` as environment variables. Spring's relaxed binding gives env vars highest precedence, so these override the prod-profile YAML. The documented Cloud SQL socket factory, the `DB_PASSWORD` Secret-Manager indirection, and the TLS+IAM the socket factory provides are all silently bypassed. The README and the build dependency (`postgres-socket-factory`) both describe the managed Cloud SQL deployment that this one-command deploy does not use. Separately, the db sidecar declares no `volumes:`, so its data lives in the container writable layer.

**Impact:** The documented deploy ships a prod app talking to an ephemeral sidecar Postgres with well-known credentials `postgres/postgres`, not the managed, backed-up Cloud SQL instance the docs promise. The two prod configs disagree about which database prod uses, and the env override wins. The entire append-only event log (the money ledger and audit trail) is lost on every Cloud Run revision replacement, scale-in, or redeploy. This is a silent data-loss footgun for an event-sourced money system.

**Fix:** Decide on one prod database story. If Cloud SQL is intended (as the README and prod YAML state), drop the `SPRING_DATASOURCE_*` overrides and the sidecar from `compose.prod.yaml`, attach the instance with `--add-cloudsql-instances`, and supply `DB_PASSWORD` from a secret. A Compose named volume is not a valid fix for Cloud Run (the sidecar filesystem is ephemeral there regardless).

### Event sourcing

#### H4. Renaming a member's login name corrupts undo and can misvalue their balance
**Severity: High** · `data/.../LedgerDataServiceImpl.kt:187, :111`

The ledger walk decides whether a consumption step is an owner self-scan (pushed onto the increment-price stack, valued and undoable) versus an admin override (valued as a lump) by string-comparing the event's frozen `created_by` login against the member's **current** login name (`actorOf(event) == ownerLogin`). `created_by` is set at append time, but the login name is mutable: `UserServiceImpl.update()` maps the whole DTO including `loginName` and upserts with no guard against a login change (contrast `rotateCapabilityToken`, which is guarded; and `capabilityToken`, which the mapper pins). The correct events are still retrieved (the query keys on the immutable `body->>'userId'`), but the in-memory comparison misclassifies them.

**Impact:** After an admin renames a member: (1) `lastCancellableIncrement()` finds no owner increments on the stack, so the member can no longer undo a recent coffee even within the grace window, and the summary `cancellable` flag goes false; this is the always-triggered breakage. (2) In histories that mix undos and overrides, the stack-trimming no longer corresponds to real owner increments, so an undo can credit a wrong/missing price and the euro balance diverges. (A pure run of self-scans yields the same balance either way, so the balance corruption is conditional, but the undo loss is unconditional.) No test exercises a login rename with history.

**Fix:** Do not key ownership on the mutable login string. Either forbid changing `loginName` after creation in `UserServiceImpl.update` (mirroring the `capabilityToken` pin), or classify owner-vs-admin steps by the immutable user id (already in the event body and equal to the ledger's `userId`). Add a system test that renames a member with history and asserts the balance and `cancellable` flag are unchanged.

---

## Medium

### Security

#### M1. Insecure default JWT secret in the common config; the shipped compose runs the dev profile with no `JWT_SECRET`
**Severity: Medium** · `application/src/main/resources/application.yaml:40-41`

The shared (profile-independent) config sets `secret: ${JWT_SECRET:dev-only-insecure-jwt-secret-change-me-in-production}`. The prod profile re-declares it without a fallback, but any run that is not the prod profile (no profile, or dev) inherits this committed, publicly-known signing secret. `JwtProperties` only enforces length ≥ 32 bytes, which the 52-byte fallback passes. The same HMAC key both signs and verifies, so anyone with the default secret can forge `ROLE_ADMIN` tokens. The shipped `compose.yaml` hardcodes `SPRING_PROFILES_ACTIVE=dev` with no `JWT_SECRET` and is the documented `gcloud beta run compose up` / docker-compose path, so the documented container deployment runs with the publicly-known secret by default.

**Fix:** Move the insecure fallback into the dev profile document only; leave the common/default secret unset so a missing `JWT_SECRET` fails fast everywhere except dev. Set `SPRING_PROFILES_ACTIVE=prod` plus a real `JWT_SECRET` in the deployment compose.

#### M2. Capability URL HTTPS requirement is documented but never enforced; prod compose ships a plain-HTTP localhost default
**Severity: Medium** · `api/.../AppProperties.kt:14-16`; `application/src/main/resources/application.yaml:34`; `compose.prod.yaml:32-34`

The secret capability token is embedded in the QR URL. The KDoc states the base "must be https in production so the token never travels over plain HTTP," but nothing enforces it. `AppProperties` does no scheme validation (contrast `JwtProperties`, which does `require(secret.size >= 32)`). The prod profile never sets `base-url`, and `compose.prod.yaml` never sets `CAMPUS_COFFEE_APP_BASE_URL`. A misconfigured or default deployment embeds a bearer token in a plain-HTTP URL on printed QR codes with no startup guardrail. (Related to H2; this is the missing-enforcement half.)

**Fix:** Add an init-block check (or a prod-profile startup validation) that rejects a non-https `base-url` under prod, mirroring `JwtProperties`. Set an explicit https `base-url` in the prod deploy.

#### M3. Capability tokens stored in plaintext in the immutable event log, including old/rotated ones
**Severity: Medium** · `data/.../EventJsonMapper.kt:44-66`

A `User` event body is serialized field-for-field with only the raw `password` dropped via `UserSecretsMixin`. Every user event (create, edit, role change, deactivation, and token rotation) writes the member's current `capabilityToken` in cleartext into the append-only `events.body` jsonb, kept forever. After an admin rotates a token, the old token persists in a prior event row indefinitely. The capability token is a bearer credential, yet unlike the password (bcrypt-hashed) it is stored unhashed.

**Impact:** Any DB-read exposure (backup leak, read replica, SQLi elsewhere, the replay path) reveals live member bearer credentials; rotation does not erase the compromised old secret from the log. Note: the live token is also stored unhashed in the `users` read-model row (it must be looked up by header), so this is a secondary defense-in-depth weakness, and the log-specific harms are retained old tokens plus many duplicate copies.

**Fix:** Store only a hash/HMAC of the token in both the event body and the read-model row, and compare against it on lookup, so the log never holds a usable plaintext bearer credential.

#### M4. Hardcoded admin password and deterministic capability tokens ship in the production jar
**Severity: Medium** · `domain/src/main/kotlin/.../tests/TestFixtures.kt:44-93`; `compose.prod.yaml:4-5`

`TestFixtures` lives in `domain/src/main` (the production source set) and hardcodes a real admin password (`"aaaMbnPdFYDqkOpS3fVA"`) and five deterministic capability tokens, compiled into `application.jar` and recoverable from the artifact. They load only when `fixtures.load-on-startup` is true (off in prod by config). Worse, `compose.prod.yaml`'s header comment claims "The prod profile loads the fixture data on startup," which is false; acting on that comment would seed a known admin password and known member tokens into a real database.

**Fix:** Move the credential/token constants out of the prod artifact (generate random secrets at seed time, or keep seed values in a dev/test-only source set), and fix the `compose.prod.yaml` comment.

#### M5. Runtime container runs as root with no dedicated non-root user
**Severity: Medium** · `Dockerfile:16-21`

The runtime image defines no `USER` directive, so the JVM and entrypoint run as root inside the container, and no compose/orchestration file corrects it. A non-root runtime user is baseline container hardening; running as root widens the blast radius of any RCE and is flagged by every image scanner and the CIS Docker Benchmark. (The Cloud Run gVisor sandbox mitigates the host-escalation narrative, so this is defense-in-depth / scanner compliance, not an exploitable high.)

**Fix:** Add a non-root user in the runtime stage and switch to it (`addgroup -S app && adduser -S -G app app`, `chown`, then `USER app` before the `ENTRYPOINT`). The jar needs no write access to `/opt/app`.

### Deployment / documentation

#### M6. Prod deploy sets no `BOOTSTRAP_ADMIN_*`, so a fresh prod deployment has no admin
**Severity: Medium** · `compose.prod.yaml:32-36`; `scripts/deploy-cloudrun.sh`; `application/src/main/resources/application.yaml:101-104`

In prod, fixtures are off (no seeded admin) and the only way to create the first admin is `BootstrapAdminLoader`, a no-op unless `BOOTSTRAP_ADMIN_LOGIN` and `BOOTSTRAP_ADMIN_PASSWORD` are set. Neither `compose.prod.yaml`, `deploy-cloudrun.sh`, nor `deploy.env.example` provides them (the YAML defaults both to empty). A deploy via the documented one-command flow comes up with zero admins: no one can log in, so no members, prices, expenses, or settlements can ever be created. (The README prose does instruct injecting these from Secret Manager, so the gap is that the automated tooling and the prose disagree.)

**Fix:** Add `BOOTSTRAP_ADMIN_LOGIN`/`BOOTSTRAP_ADMIN_PASSWORD` to `deploy.env.example` and to the deploy script's env generation (ideally a generated password printed once), and document them as required for the first deploy.

#### M7. `compose.prod.yaml` header comment falsely claims the prod profile loads fixture data
**Severity: Medium** · `compose.prod.yaml:3-5`
*(Surfaced by multiple findings; the comment also contains an em-dash, see N-block.)*

The comment states the prod profile loads fixture data on startup. The prod profile sets `fixtures.load-on-startup: false` ("prod has real members, not fixtures"), and `FixtureStartupLoader` is `@ConditionalOnProperty(havingValue="true")`, so it is not even instantiated in prod. Combined with M6, this hides the fact that a fresh prod deploy is empty and adminless.

**Fix:** Correct the comment: prod loads no fixtures; the first admin comes from the bootstrap-admin properties, which must be supplied.

### Accounting and the kitty

#### M8. Expense correction (UPDATE) ledger row pairs a delta amount with the absolute new split
**Severity: Medium** · `data/.../LedgerDataServiceImpl.kt:218-251` (member walk), `:350-381` (kitty walk); `splitPortions` at `:32-37`

For a `ChangeType.UPDATE` expense, the balance effect stored in `amountCents` is computed as a delta (new private minus old private), but `splitPortions(event)` reads the full **new** `privateAmountCents`/`kittyAmountCents` from the body. So a corrected split expense renders one ledger row whose `amountCents` is the incremental change while its displayed split shows the absolute new values; the two cannot be reconciled by a reader (`amountCents != privateAmountCents` on an UPDATE row). The running balance stays correct (it uses the delta); the per-entry breakdown is misleading. Only the admin member-ledger and kitty-ledger views expose this (the member-serving path nulls the split). The system test asserts only the resulting running balance, never the per-entry split, so the inconsistency is untested.

**Fix:** Make `amountCents` and the displayed split agree on an UPDATE row: either emit the row as the absolute new split plus a reversal of the prior row, or set the split portions to the same delta (or null) on an UPDATE.

#### M9. Every balance/summary read replays the full event stream; the kitty walk replays all payments+expenses, with no snapshot
**Severity: Medium** · `data/.../LedgerDataServiceImpl.kt:79, :88`
*(Merges the no-snapshot finding with the missing `(entity_type, seq)` index finding, which share the same hot path.)*

Balances are never projected; they are recomputed by replaying streams on every read. `memberLedger()` loads and folds the member's entire consumption+expense+payment history per call (no SQL `LIMIT`; paging is in-memory). `kittyLedger()` loads all `Payment` and all `Expense` events via `findByEntityTypeOrderBySeqAsc` and re-sorts them in memory. These run on the hottest paths: `memberSummary` (every wall-QR scan) walks the member stream twice plus the kitty once; `allBalances` (the admin overview) walks each member's full stream; and the kitty-overdraw guard recomputes `kittyBalanceCents()` on every money write. Compounding this, there is no `(entity_type, seq)` composite index (only single-column `idx_events_entity_type` and `idx_events_seq`), so the type-filtered, seq-ordered scan cannot be served from one index, and the overdraw check runs this unindexed scan-and-sort inside the global advisory lock that serializes all money writes.

**Impact:** Read cost grows linearly with the append-only log (which never shrinks). For a single coffee club the absolute numbers stay small for years, so this is steady degradation rather than an acute risk, but the serialized critical section lengthens over time. (Two prose corrections from the reviewers: `allBalances` does not walk the kitty, and `memberSummary` walks the member stream twice, not the kitty twice.)

**Fix:** Add `CREATE INDEX idx_events_type_seq ON events (entity_type, seq)`. Project a maintained per-user balance and a single kitty-balance row in the same projection transaction (keeping the log rebuildable), or add a bounded `SUM` for the overdraw check, so reads and the lock-held check are O(1). Order the kitty stream in SQL (a `UNION ... ORDER BY seq`) instead of concatenating and sorting in memory.

### Consumption

#### M10. No end-to-end test exercises two genuinely concurrent self-scans
**Severity: Medium** · `application/src/test/.../ConsumptionSystemTests.kt`; `data/src/test/.../CrudDataServiceOptimisticLockTest.kt`

CLAUDE.md states the system tests cover concurrent self-scans returning 409. The only optimistic-lock tests mock the relational delegate or unit-test the projector's exception mapping; no test drives two real concurrent `applyDelta(+1)` calls against PostgreSQL through the `@Primary` event-sourced path so the `@Version` conflict actually fires in the projection. The load-count → append → project → version-check sequence the SPA retry contract depends on is unverified end to end. (The exception mapping itself is unit-tested at both layers, so the residual risk is a regression in the projection wiring / version preservation.)

**Fix:** Add a system test firing two concurrent `POST /api/consumption` for the same member, asserting exactly one 200 and one 409, with the final count = 1.

### Frontend correctness

#### M11. SPA does not retry a concurrent self-scan 409 as documented; it errors and reloads
**Severity: Medium** · `frontend/src/app/pages/coffee-landing/coffee-landing.component.ts:294-307`; `frontend/src/app/services/summary.service.ts:28-30`
*(Merges with the tests-dimension finding on the same contract.)*

CLAUDE.md and the docs (5 locations) state "the SPA retries" on a 409 `ConcurrentUpdateException`. No retry logic exists anywhere in the frontend. `addCoffee()` catches any error, shows "Could not record that coffee. Reloading.", and reloads. The documented self-healing behavior is absent. (Impact correction: two *different* members tapping a shared wall QR do not collide, since each has a distinct one-per-user consumption row; the 409 arises only from the *same* member's concurrent writes, e.g. two tabs or a double-tap. And on the 409 the reload fetches the authoritative summary, so the count reconciles correctly and only the tap is dropped, re-tappable. So this is a documentation/contract mismatch plus a rare recoverable dropped tap, not a high-impact loss.)

**Fix:** Either implement the documented retry (re-issue `addCoffee()` on a 409 in the catch), or correct CLAUDE.md and the three docs to say the SPA shows an error and reloads.

#### M12. Admin count-correction form pre-fills a stale total after using +/-, silently rolling back cups
**Severity: Medium** · `frontend/src/app/pages/admin-landing/admin-landing.component.ts:442-468, 379-393, 252/391`

`newTotal` (the absolute-correction form value) is set only from the server in `loadConsumption()`. The +/- handler `change()` updates `this.consumption.total` but never `this.newTotal`, and the Edit button merely toggles `editMode` without re-reading the total. So after an admin taps +1 a few times, opens Edit, and clicks Set without retyping, the absolute correction writes the pre-+/- total, reverting every intervening cup and re-valuing the balance via the lump correction. (Mitigations: the stale value is rendered in the input, so it is wrong-but-visible, and the result is a reversible logged event, not irrecoverable corruption.)

**Fix:** Seed `newTotal` from the current total when Edit opens (replace the inline `editMode = !editMode` toggle with a method that sets `this.newTotal = this.consumption.total`), and/or set `this.newTotal = updated.total` in the success branches of `change()` and `override()`.

### Documentation

#### M13. Docs claim the event `note` column records kitty-adjustment reasons, but it is only set for count corrections
**Severity: Medium** · `CLAUDE.md:78-81`; `README.md:162`; `doc/2026-06-21_...md`

CLAUDE.md and README state the `events.note` metadata column records an admin's reason for both a count correction and a kitty adjustment via `ChangeNoteContext`. In reality `runWithNote` is called only in `CoffeeConsumptionServiceImpl` (the absolute count correction). A settlement's or kitty adjustment's note is written into the `Payment` event **body** (`PaymentEventSerializer`), never the metadata column. The documented `SELECT ... note FROM events` query returns null for every settlement/kitty adjustment, so a reader inspecting the log would wrongly conclude data is missing. CLAUDE.md also says "the services set" (plural) when only one does.

**Fix:** Correct CLAUDE.md and README: the metadata `note` column records only the reason for an absolute count correction; a settlement, kitty adjustment, or expense note lives in that entity's event body and read-model row.

### API documentation

#### M14. Hand-written endpoints document only 200, omitting all error responses
**Severity: Medium** · generated `frontend/src-gen/api-docs.json`; all controllers except the `@CrudOperation`-annotated User methods

Only `UserController`'s `@CrudOperation`-annotated methods get rich response docs (400/401/403/404/409). Every hand-written controller method (the entire money/consumption/summary/ledger/expense/payment surface, plus auth and dev) documents only its inferred success code, even though these return 400 (validation), 401, 403 (non-admin or deactivated member), 404, and 409 (kitty overdraw, concurrent self-scan, undo with nothing to undo). Swagger UI and generated clients have no documented error contract for the bulk of the API. Runtime behavior is correct; this is documentation only.

**Fix:** Extend the `@CrudOperation`/customizer mechanism (or add a shared `OperationCustomizer`) to inject the standard 400/401/403/404/409 error responses for the non-CRUD controllers.

### Tests

#### M15. The expense-correction kitty-overdraw guard (a 409 branch) has zero test coverage
**Severity: Medium** · `domain/.../ExpenseServiceImpl.kt:108-111`

`ExpenseServiceImpl.update` guards a correction that would overdraw the kitty with the differential formula `kittyBalanceCents() + existing.kittyAmountCents - kittyAmountCents < 0`. No test exercises this branch: the update tests only succeed or fail on buyer change, and the system tests correct expenses downward where the kitty stays positive. This is the trickiest of the three overdraw checks (it nets old vs new), and an off-by-sign error there would ship undetected, allowing a correction to silently overdraw the kitty.

**Fix:** Add an `ExpenseServiceTest` case where a correction raises the kitty portion beyond the available balance and asserts `ConflictException`, plus a system test PUTting such a correction and asserting 409.

#### M16. The `@Max` money/weight fat-finger caps (a 400 guardrail) are never tested
**Severity: Medium** · `api/.../MoneyBounds.kt`; all money request DTOs

`MAX_MONEY_CENTS` (100,000 EUR) and `MAX_WEIGHT_GRAMS` (1,000,000 g) are applied as `@Max` on five DTOs and documented to reject absurd amounts with a 400, but no test submits an over-cap amount or weight (the only "exceeds" tests are the distinct kitty-overdraw rule). The Long-accumulation boundary is also untested. A regression dropping or mis-sizing the `@Max` (wrong constant, wrong DTO) would pass CI silently.

**Fix:** Add a system test POSTing an expense/settlement/price above `MAX_MONEY_CENTS` (and a weight above `MAX_WEIGHT_GRAMS`) asserting 400.

#### M17. The kitty advisory lock (the only TOCTOU-overdraw defense) is mocked but never verified as invoked
**Severity: Medium** · `domain/src/test/.../PaymentServiceTest.kt`, `ExpenseServiceTest.kt`

`adjustKitty` and `record`/`update` call `kittyLock.lockForUpdate()` before the overdraw check, and this serialization is the entire defense against concurrent draws overdrawing the fund. Both unit tests construct the service with a mocked `KittyLock` but never assert it is acquired, and never assert it is taken before the balance read. If a refactor deleted or reordered the lock acquisition (reintroducing the race), every unit test would stay green. (Tests-dimension gap; production code is currently correct.)

**Fix:** Add assertions that `lockForUpdate()` is invoked, and use Mockito `InOrder` to assert it precedes the `kittyLedger()` read, in `adjustKitty`, `record`, and `update`. Optionally add a data-layer integration test running two real concurrent kitty draws against Testcontainers Postgres, asserting exactly one succeeds.

---

## Low

### Security

#### L1. No brute-force protection on `POST /api/auth/token`
`api/.../AuthController.kt:51-80` · The admin login endpoint has no rate limiting, lockout, or backoff. bcrypt slows each attempt and credentials do not enumerate, but an unthrottled online password-guessing attack against full-privilege admin accounts is possible. **Fix:** add per-IP / per-account rate limiting or temporary lockout on the token endpoint.

#### L2. Admin JWT in localStorage with a 10h TTL and no revocation; `GET /users` returns every member's capability token to that session
`frontend/src/app/services/auth.service.ts:24-39`; `api/.../AuthController.kt:91`; `UserController.kt:68-73` · The admin JWT is JS-readable (XSS-exfiltratable), valid up to 10h with no refresh and no server-side revocation. The same session can `GET /users`, which returns every member's `capabilityUrl` in one response, so a stolen admin token yields every member's bearer credential. Contingent on a separate XSS bug. **Fix:** consider a shorter TTL with refresh, a `jti` denylist, or an httpOnly-cookie strategy; at minimum document the residual risk.

#### L3. `compose.prod.yaml` commits a weak default database password (`postgres/postgres`)
`compose.prod.yaml:36-47` · The prod compose hardcodes a guessable DB credential inline, unlike `JWT_SECRET` (sourced from gitignored `deploy.env`). Largely subsumed by H3; in the documented Cloud Run path the prod profile ignores these vars and uses Cloud SQL, and the sidecar port is not internet-published, so the exposure is real only for a manual `docker compose -f compose.prod.yaml up`. **Fix:** source the DB password from `deploy.env` / Secret Manager and drop the public `5432` mapping.

#### L4. `campus-coffee.app.base-url` has no prod-side fail-fast; an http base in prod is silently accepted
`api/.../AppProperties.kt:13-16` · `AppProperties` accepts any `baseUrl` with no validation, including the localhost default, even under the prod profile (contrast `JwtProperties`'s fail-fast). The capability link (not every API request) would be emitted over http. Defense-in-depth gap. **Fix:** add a prod-active https validation, mirroring `JwtProperties`.

#### L5. robots.txt disallows the old `/coffee/` path, not the real `/login/` capability entry point
`frontend/public/robots.txt` · The comment claims to keep crawlers out of the capability-URL entry point per the W3C guidance, but it disallows `/coffee/` while the live route is `/login/{token}`, so the rule matches nothing. The actual leak protection (the `no-referrer` meta in `index.html`) is in place, robots.txt is not a security control, and a "fixed" `Disallow: /login/` would advertise the path prefix. **Fix:** remove the stale rule and its inaccurate comment (preferred), or re-point it.

#### L6. No Gradle wrapper means no distribution checksum verification
`settings.gradle.kts`; `mise.toml:3` · The project pins `gradle = '9.5'` via mise but has no wrapper with `distributionSha256Sum`, so there is no repo-level integrity check on the Gradle distribution. mise verifies its own downloads, so the residual supply-chain risk is minor. **Fix:** document that mise verifies the download, or pin the exact patch version so 9.5 cannot float.

### Event sourcing and accounting

#### L7. An admin count correction values reversed cups at the correction-time price, not the prices actually charged
`data/.../LedgerDataServiceImpl.kt:204-214` · A correction-down (or an admin single −1) pops the increment-price stack correctly but credits the lump at `priceAsOf(override seq)`, ignoring the per-increment prices actually debited. If the price changed between consumption and correction, the member is over- or under-credited. This is the **documented intended** rule (lump at override-time price), gated behind an admin correcting down AND an intervening price change, so it is a design/documentation item, not a bug. **Fix (if exact reconciliation is wanted):** credit the popped increment prices summed LIFO, reserving the current-price lump only for an increase; at minimum document that a correction may not net to zero.

#### L8. A single coffee event with no preceding price 500s the whole admin overview / any member ledger read
`data/.../LedgerDataServiceImpl.kt:59-66`; `domain/.../AccountingServiceImpl.kt:84-95` · `priceAsOf` throws `IllegalStateException` (unmapped → 500) if any increment has no price event at or before its seq, and `allBalances` has no per-user isolation, so one bad stream 500s the entire overview. The invariant holds today only via StartupTask ordering; reachable only by a hand-edited log, a misordered import, or a future fixture. **Fix:** treat a missing as-of price as a defined fallback (as the undo path already does at line 201), and/or isolate per-user failures in `allBalances`.

#### L9. `setTotal` docstring claims it is not version-guarded, but the `@Version` entity guards every write
`domain/.../CoffeeConsumptionServiceImpl.kt:86-93`; `CoffeeConsumptionService.kt:64-72` · The override is documented as deliberately last-writer-wins, but its only write path is the versioned read-model projection, so a concurrent self-scan in the read-to-flush window yields a 409 (`ConcurrentUpdateException`), not a silent overwrite. Documentation/contract mismatch; runtime behavior is safe. **Fix:** make the docstring accurate (the override may 409 on a concurrent self-scan; add `@throws ConcurrentUpdateException`), or, if last-writer-wins is truly intended, catch and re-apply inside `setTotal`.

#### L10. `cancel()` reads the candidate and the count in separate unsynchronized queries before the guarded write
`domain/.../CoffeeConsumptionServiceImpl.kt:121-133` · The grace/candidate decision is taken against an unsynchronized read; only the final upsert is `@Version`-guarded. Not a lost-update bug (the `count<=0` recheck plus `@Version` prevent double-undo), but the gate and the credit re-derive the LIFO result independently and must stay rule-identical, which is fragile to a future edit changing one stack rule. **Fix:** document the invariant, or pass the chosen candidate's seq/price into the write so the ledger credit derives from the cancel event rather than an independent re-walk.

#### L11. Consumption-history query keys on the class `simpleName`, defeating the `LoggedEntityType` label decoupling
`data/.../ConsumptionHistoryDataServiceImpl.kt:52` · The change-log query filters on `CoffeeConsumption::class.simpleName`, but events are stored with `LoggedEntityType.COFFEE_CONSUMPTION.label`. Equal today only by coincidence; a class rename would silently empty the change log with no compile or runtime error, contradicting the `LoggedEntityType` "decoupled from the constant name" design. The `EventRepository` KDoc also wrongly calls `entity_type` "the domain class's simple name." Latent refactoring-fragility. **Fix:** use `LoggedEntityType.COFFEE_CONSUMPTION.label` and fix the KDoc.

#### L12. seq-keyed as-of valuation can be wrong because IDENTITY seq is assigned at INSERT, not at commit
`data/.../LedgerDataServiceImpl.kt:59`; `V3__create_events_table.sql:12` · Price changes and +1 consumptions are not serialized by any lock, and seq is assigned at INSERT inside an uncommitted transaction. Two concurrent unlocked transactions can acquire seq in one order and commit in the opposite order, so a cup can be attributed to a price not actually in effect, off by one price delta. Bounded and transient; cannot drive the kitty negative (that path is separately locked). The design doc's "Two facts forced seq" section does not address insert-vs-commit ordering. **Fix:** serialize price-vs-consumption through the same lock, assign seq at commit, or at minimum document that seq reflects INSERT order and price changes are assumed not to interleave with scans.

#### L13. `events.seq` has no UNIQUE constraint or unique index, only a plain btree
`V3__create_events_table.sql:12, 31` · seq is the authoritative replay/valuation key but is a plain (non-unique) index; an IDENTITY column creates no implicit unique index. Uniqueness/monotonicity is enforced only by the generator, not the schema. `GENERATED ALWAYS` rejects plain manual inserts, so the residual risk requires `OVERRIDING SYSTEM VALUE`, a restore that resets the identity, or a future ALTER. **Fix:** make it a `UNIQUE (seq)` constraint/index that doubles as the ordering index.

### Architecture

#### L14. Event-store `seq` leaks from persistence into the domain model and the public REST API
`domain/.../LedgerEntry.kt:51`; `domain/.../CancellableIncrement.kt:12`; `api/.../LedgerEntryDto.kt:19` · A DB BIGSERIAL row-order appears in two domain read models and the public `LedgerEntryDto` JSON, contradicting the stated persistence-agnostic design and `LedgerEntry`'s own "carries no identifier" KDoc. Not dead at the API boundary: the SPA depends on `LedgerEntryDto.seq` as a stable key for de-dup and `track`. Only `CancellableIncrement.seq` is genuinely dead. **Fix:** remove `CancellableIncrement.seq`; the API-side `seq` is a debatable tradeoff with a working consumer (changing it is a breaking API change).

#### L15. Domain service catches a Spring persistence exception, bypassing the port abstraction
`domain/.../CoffeePriceServiceImpl.kt:12,50,61` · The price service catches the raw `org.springframework.dao.DataIntegrityViolationException` to handle the `uq_coffee_prices_singleton` race, instead of mapping it to a domain exception in the adapter as the codebase does everywhere else. It is the only `spring.dao` persistence type in the domain. The race is handled correctly. **Fix:** map the singleton violation to a domain exception in the data adapter, or add an idempotent `ensureSingleton`/`setOrCreate` to the port.

#### L16. ArchUnit layer definition omits the application module's `web` and `configuration` packages
`application/src/test/.../ArchitectureTests.kt:20-41` · The bare `"de.seuhd.campuscoffee"` matches only the root package, so `web..` (`SpaForwardingController`) and `configuration..` (`CorsConfig`, properties) belong to no layer and are not covered by `mayNotBeAccessedByAnyLayer()`; `ensureAllClassesAreContainedInArchitecture()` is not called. CLAUDE.md claims the rule covers the whole module. The Gradle module direction is the real guardrail today, so this is a latent backstop gap. **Fix:** add sub-package patterns (`web..`, `configuration..`) and `ensureAllClassesAreContainedInArchitecture()`.

#### L17. Typed `ConsumptionProperties` is dead; the domain reads the grace period via a raw `@Value`, duplicating the default
`domain/.../CoffeeConsumptionServiceImpl.kt:43`; `api/.../ConsumptionProperties.kt` · `ConsumptionProperties` is never injected; the domain binds the key with a raw `@Value("...:5m")`, restating the `5m` default that also lives in the properties class and `application.yaml`. Two drift-prone defaults and a misleading config class that is not the binding point. **Fix:** inject a domain-level config object and delete the raw `@Value`, or delete `ConsumptionProperties`; do not keep both.

#### L18. `EventSourcedMutator.create` is dead generic machinery, never called
`data/.../EventSourcedMutator.kt:60-70` · `create` exists "for a port that has no read-by-id method," but every port extends `CrudDataService` (which has `getById`), and all five decorators use `upsert`. The scenario does not exist. **Fix:** delete `create` (YAGNI), or, if intended, document which port needs it and add a test.

#### L19. Domain documented as "no external dependencies except validation" but depends on Spring
`domain/build.gradle.kts:11-12`; `CLAUDE.md:30` · The domain declares `spring.tx` and uses `@Service`/`@Component`/`@Value`/`@Transactional`/slf4j/`org.springframework.dao`, so it is Spring-coupled, not framework-agnostic. Documentation accuracy only. **Fix:** correct CLAUDE.md to say the domain depends on Spring by design, or move the annotations to the wiring layer.

### API contract / documentation

#### L20. PUT `/profile` requires `loginName` (and other fields) that the server then drops
`api/.../ProfileController.kt:54-72`; `api/.../UserDto.kt:29-43` · The profile edit reuses the full `UserDto` with `@NotNull`/`@Pattern` on `loginName`, but the handler overwrites id/loginName and nulls password/role/active. A client omitting `loginName` gets a 400 for no semantic reason (the bundled SPA round-trips the full DTO, so it never trips this). **Fix:** introduce a dedicated `ProfileUpdateDto` with only `firstName`/`lastName`/`emailAddress`.

#### L21. Expense `weightGrams` is required by the DTO but documented as optional
`api/.../MemberExpenseDto.kt:14-17`; `AdminExpenseDto.kt:14-17`; `CLAUDE.md:523,543` · Both DTOs mark `weightGrams` `@NotNull`, so omitting it yields a 400, but CLAUDE.md writes `{ amountCents, weightGrams?, note? }` (optional) and README has it correct (required). A non-integer JSON value is rejected (Jackson → 400), not truncated. **Fix:** drop the `?` from `weightGrams` in CLAUDE.md (the doc, not the code, is wrong), or make the field genuinely optional.

#### L22. OpenAPI documents 200 for three endpoints that actually return 201 Created
`api/.../PaymentController.kt:49,64`; `AdminExpenseController.kt:74` · `POST /payments/settlement`, `POST /payments/adjustment`, and `POST /users/{userId}/expenses` return 201 at runtime, but springdoc infers 200 because no `@ResponseStatus(CREATED)` is present. The in-repo SPA is unaffected; the contract is wrong for external/strict clients. **Fix:** add `@ResponseStatus(HttpStatus.CREATED)` to each, which makes the status both declarative and documented.

#### L23. QR PNG / ZIP endpoints advertise `*/*` instead of `image/png` and `application/zip`
`api/.../UserController.kt:175-189`; `ProfileController.kt:77-78` · The three QR/ZIP endpoints return binary at runtime but the spec documents the 200 content as `*/*`. Spec accuracy only; the SPA uses hand-written blob calls. **Fix:** add `produces = [MediaType.IMAGE_PNG_VALUE]` / `"application/zip"` to the `@GetMapping`s.

#### L24. `ConsumptionDeltaDto` KDoc names the wrong (member) endpoint
`api/.../ConsumptionDeltaDto.kt:5-9`; `ConsumptionController.kt:39-44` · The KDoc says the DTO is the body of `POST /api/consumption`, but that member endpoint takes no body and hardcodes +1; the DTO is used only by the admin `POST /users/{userId}/consumption`. Source-KDoc only (not the published spec). **Fix:** reference only the admin endpoint.

#### L25. CLAUDE.md ledger endpoints documented with `?limit=5` but real defaults are 20 and 50
`CLAUDE.md` / `README.md:130,141,149`; `SummaryController.kt:69`, `AdminAccountingController.kt:57`, `KittyController.kt:44` · The docs uniformly write `?limit=5` for the three ledger endpoints, but the `@RequestParam` defaults are 20 (member/admin ledger) and 50 (kitty). Only the consumption change-log defaults to 5. Misleads a reader inferring the default. **Fix:** use the real defaults in the examples or drop the explicit `=5`.

#### L26. PUT `/profile` and other gaps in CLAUDE.md's endpoint matrix and migration descriptions
*(Cluster of documentation accuracy items, all Low.)*
- **`GET /users/filter` omitted from CLAUDE.md** (`UserController.kt:134`; documented in README) — the endpoint exists but the matrix does not list it.
- **CLAUDE.md V4 migration description omits the `is_singleton` guard column and its unique constraint** (`CLAUDE.md:415`; `V4__create_coffee_prices_table.sql`) — the DB-level single-row guard is dropped from the column list (it is documented in the design doc).
- **V3 migration comments are stale** (`V3__create_events_table.sql:15,26`) — `entity_type` lists only `(User, CoffeeConsumption)` of five types, and the `note` comment mentions a non-existent "reset."

**Fix:** update each to match the code and the canonical design doc.

#### L27. `GET /users` and `/users/overview` return the full member set with no paging
`api/.../UserController.kt:68-73`; `AdminAccountingController.kt:38-42` · Both list endpoints are uncapped while every other read is paged, and `/overview` walks the event log per member (O(N) fan-out). Admin-only; for a single small group the cost is minor. **Fix:** add bounded `limit`/`offset` paging, or document why these are intentionally unpaged.

### Database

#### L28. Member-ledger and stream queries have no LIMIT/OFFSET; all paging is in memory
`data/.../EventRepository.kt:54-65, :44`; `domain/.../AccountingServiceImpl.kt:113` · `findMemberLedger` and `findByEntityTypeOrderBySeqAsc` return the whole stream; paging is applied in-domain. The running-balance walk inherently requires the full stream, so this is a correctness requirement, not a missable optimization. The actionable residue is the undocumented asymmetry with the bounded `findHistory` plus the repeated full-stream re-walks. **Fix:** document the intentional in-domain paging; optionally snapshot the running balance.

### Build / CI

#### L29. `.dockerignore` omits `frontend/node_modules` (423 MB), `frontend/dist`, `.angular`, and coverage dirs
`.dockerignore`; `Dockerfile:11` (`COPY . .`) · None of these host-generated dirs are excluded, so every `docker build` uploads ~432 MB of unnecessary context (almost all `node_modules`). The Gradle build regenerates `dist`, so output stays correct; the harm is context bloat. **Fix:** add `**/node_modules`, `frontend/dist`, `frontend/.angular`, `frontend/coverage`, `frontend/coverage-e2e`, `frontend/playwright-report`, `frontend/test-results`.

#### L30. Frontend Vitest, Knip, and Prettier `format:check` are not run by any CI gate
`.github/workflows/build.yml`; `application/build.gradle.kts:102-126` · `gradle check` triggers only `frontendLint` (ESLint + Stylelint). No CI step runs `npm test`, `npm run knip`, or `npm run format:check`, despite CLAUDE.md/CHANGELOG presenting them as wired-in gates. Practical blast radius is small (advisory tools; the Vitest suite is ~1% of the app). **Fix:** wire them into `frontendLint` / a check-bound task, or correct the docs to say they are advisory.

#### L31. Heavy e2e job runs on every branch push and again on PRs to main
`.github/workflows/build.yml:3-8,56` · The unguarded `e2e` job runs on every feature-branch push and duplicates on same-repo PRs to main (no concurrency dedup), contrary to the deliberately narrower Qodana trigger scope. Wasted CI minutes; no functional impact. **Fix:** scope the e2e job like Qodana (PRs to main + pushes to main) and add a concurrency group to cancel superseded runs.

#### L32. `run-e2e-coverage.sh`: under `set -e`, the e2e exit-code capture and `exit $E2E_STATUS` are dead code
`scripts/run-e2e-coverage.sh:24,114-115,124` · The standalone e2e command aborts the script under `set -e` before the status is captured, so `E2E_STATUS` is only ever 0 and the final `exit` always exits 0 (CI still goes red via `set -e`). Misleading dead code. **Fix:** guard the e2e (`if ( ... ); then E2E_STATUS=0; else E2E_STATUS=$?; fi`).

#### L33. `compose.prod.yaml` overrides the prod Cloud SQL datasource (duplicate of H3)
*(This issue was reported under both `build-tooling` and `config-deploy`; see H3 for the merged write-up. Listed here only to note the dedup.)*

### Frontend correctness

#### L34. Forms and the +1 FAB rely only on a synchronous busy flag, so a fast double-tap can double-submit
`frontend/.../coffee-landing.component.ts:294-307,326-355`; `admin-kitty.component.ts:256-299` · `busy` is set synchronously with no early-return; under zone-coalesced change detection the `[disabled]` attribute applies on a later tick, so two same-tick taps can both fire a POST. The +1 path is server-protected by `@Version` (one 201 + one 409, no dup), but the settlement/adjustment/expense POSTs have no client idempotency token and no backend dedup, so a double-tap can create two payments/expenses. **Fix:** add an entry guard `if (this.busy) return;` at the top of each handler, or a client request id the backend dedupes.

#### L35. A 401 on a token-less admin request is not caught (no redirect)
`frontend/.../auth.interceptor.ts:42-64` · The admin-branch `catchError` that clears the JWT and redirects on a 401 is nested inside `if (jwt)`. If the JWT was cleared in another tab and an open admin page then fires a request, it goes out token-less, 401s, and the page stays put (at best a snackbar). Edge case; recoverable by refresh. **Fix:** move the 401 `catchError` so it also applies to the credential-free admin request.

#### L36. Negative euro amounts pass inline validation; rejection only on submit
`frontend/.../admin-price.component.ts`, `coffee-landing.component.ts`, `admin-kitty.component.ts` · `parseEurosToCents` treats `-5` as a valid parse, so the per-field error getters return null and the Save button stays enabled for a negative price/expense/deposit; the handler's `< 0` check fires a generic snackbar. (Correction to the original finding: rejection is purely client-side and immediate, before any network call, not "after a round-trip.") **Fix:** extend the per-field getter to flag `cents < 0` inline so the button disables.

#### L37. Offset-paged ledger can display stale running balances after a concurrent prepend
`frontend/.../ledger.ts:26-30`; `coffee-landing.component.ts:269-283`; `admin-landing.component.ts:402-410` · With `offset = ledger.length`, a concurrently-prepended newer entry is missed, and each row keeps the running balance from fetch time; admin-landing derives the member balance from `ledger[0].runningBalanceCents`, so it can lag. Inherent to offset paging; self-heals on the next local action or reload. **Fix:** document the limitation, or page by a seq cursor and refresh the summary after Load more.

### Frontend design

#### L38. Browser tab title is the internal camelCase `CampusCoffeeConsumption` on every route
`frontend/src/index.html:5`; `app.routes.ts` · No `LOCALE_ID`/Title strategy and no per-route `title`, so every page shows the internal class name. A title exists (WCAG 2.4.2 technically met), so the issue is the leak of the internal jargon name and the lack of per-page differentiation. **Fix:** set a human title in `index.html` and a `title:` on each route.

#### L39. Ledger labels the undo action "Canceled transaction" while the button says "Undo last cup"
`frontend/.../ledger-list.component.ts:209` · The same event is named two different ways, and "transaction" is jargon foreign to the member's coffee/cup vocabulary. **Fix:** use matching plain wording, e.g. "Coffee undone." (The cited file path in the original was slightly off: the file is under `pages/`, not `components/`.)

#### L40. Mathematical sigma (Σ) used as a user-facing label for the running cup total
`frontend/.../ledger-list.component.ts:79` · `· Σ {{ entry.count }} cups` uses a math glyph in the primary member history view; screen readers announce it as the Greek letter. **Fix:** replace with a plain word, e.g. `· total {{ entry.count }} cups`.

#### L41. Dates and money rendered in en-US format for a German university audience
`frontend/.../money.ts:1`; `app.config.ts` · No `LOCALE_ID`, so dates default to en-US (`6/21/26`), and money is hardcoded to `Intl.NumberFormat('en-US')` (`1,234.56 €`). The euro input accepts a comma decimal but output never uses one, so a German user types `4,20` and sees `4.20 €`. Cosmetic; math stays in cents. **Fix:** register `de-DE` (`registerLocaleData` + `LOCALE_ID`), or document the deliberate English choice and at least align the money output with the comma-input affordance.

#### L42. No `prefers-reduced-motion` handling; Material animations always on
`frontend/src/app/app.config.ts`; `styles.scss` · `provideAnimationsAsync()` is unconditional and `styles.scss` has no reduced-motion media query, so an OS reduced-motion preference is ignored. WCAG 2.3.3 (AAA). Material's animations are short, so real impact is modest. **Fix:** add a global `@media (prefers-reduced-motion: reduce)` block zeroing animation/transition durations.

#### L43. Unknown URLs silently redirect to `/admin` instead of a not-found page
`frontend/.../app.routes.ts:61` · The catch-all `{ path: '**', redirectTo: 'admin' }` sends unknown URLs to the admin sign-in with no honest 404 state. (Correction: a mistyped capability link `/login/<wrong-token>` matches the `login/:token` route and already shows an in-page error with Retry, so the "member fat-fingers their link" scenario is handled; only truly unmatched paths funnel to admin.) **Fix:** add a lightweight not-found component for unmatched paths.

---

## Nit

### Code and config cleanups

- **N1.** `ConsumptionDeltaDto.delta` has no `@Min`/`@Max` bound while the override total is bounded (`api/.../ConsumptionDeltaDto.kt:11-14`); the ±1 rule is enforced only in the domain. Admin-only; always a clean 400. Add `@Min(-1) @Max(1)` for OpenAPI visibility (the domain's exact-±1 check must stay).
- **N2.** Change-log delta treats a DELETE event as count 0 (`data/.../ConsumptionHistoryDataServiceImpl.kt:32-49`); could surface a spurious negative delta, but no CoffeeConsumption DELETE event is ever written (user hard-delete appends only a User DELETE plus cascade), so this is unreachable defensive code. Optionally filter DELETE/no-count events from the history query.
- **N3.** Event-row `createdAt` and the projected read-model `createdAt`/`updatedAt` come from two separate `now()` calls (`EventStore.kt:118`; `EventSourcedMutator.kt:45`); sub-millisecond, documented design (the doc explains the two clocks are why valuation uses seq). No action needed; the "document it" remedy is already satisfied.
- **N4.** `EventsToDataRunner.run()` self-invokes `rebuildFromLog()`, so the inner `@Transactional` is inert (`EventsToDataRunner.kt:38`); the rebuild is transactional via the outer annotation. Drop the inner `@Transactional`.
- **N5.** `OnCreate` validation group is wired but governs zero constraints (`api/.../OnCreate.kt`; `UserController.kt:90`); `@Validated(Default, OnCreate)` is equivalent to plain `@Valid` (the create-only admin-password rule is enforced in the domain). Attach a real constraint to the group or delete it.
- **N6.** `ValidationException` couples to `jakarta.validation` via an admitted-unused constructor (`domain/.../ValidationException.kt:3,9-14`); the `Set<ConstraintViolation<*>>` is never populated or read. Remove the unused constructor and field.
- **N7.** `CrudServiceImpl.upsert` does a redundant existence read that the mutator repeats, up to three `getById`s per update (`CrudServiceImpl.kt:46-64`; `EventSourcedMutator.kt:52-57`); cold User-CRUD path, indexed PK lookups. Drop the pre-read (the mutator's `getById` already throws `NotFoundException`).
- **N8.** Per-row money columns are 32-bit `int` while sums accumulate in `Long`; cap ~21.4M EUR per row, fine for a coffee kitty, and an oversized JSON value is a clean Jackson 400, not silent overflow. The int/Long intent is already in code comments; optionally add a schema comment.
- **N9.** `SET TIME ZONE 'UTC'` at the top of every migration is a session-scoped no-op (no `timestamptz` columns, no tz-dependent literals). UTC discipline lives in the app's `@PrePersist`/`@PreUpdate`. Remove or note it is decorative.
- **N10.** Bean-purchase weight accepts non-integer grams client-side (`coffee-landing.component.ts`, `admin-expenses.component.ts`); a decimal passes the `< 0` check but the backend rejects it as a 400 (no truncation). Add `step="1"` and a `Number.isInteger` guard, mirroring `newTotalError`.
- **N11.** Member vs admin expense creation return different status and body shapes (200 + `MemberSummaryDto` vs 201 + `ExpenseDto`); intentional (different operations/audiences), already documented in controller KDoc. The deliberate divergence is fine; nothing to change beyond awareness.
- **N12.** `logging.file.name` is active in prod, writing a rolling log to the ephemeral container filesystem (`application.yaml:19-21`; the FILE appender in `logback-spring.xml` has no profile guard); bounded by Spring's default rolling limits, never shipped anywhere. Scope it to dev or remove it (Cloud Run captures stdout).
- **N13.** `spring.jpa.open-in-view: true` (anti-pattern) holds a DB connection for the whole request (`application.yaml:6-7`); all lazy access already happens inside `@Transactional` methods and there are no lazy associations, so flipping to false is safe. Set `open-in-view: false`.
- **N14.** No graceful shutdown configured (`application.yaml`); Cloud Run SIGTERM can cut in-flight requests. (Corrections: the money ledger is NOT at risk because the event-append+projection is one transaction PostgreSQL rolls back on a dropped connection, and the JaCoCo e2e flush uses the agent's JVM shutdown hook, independent of Spring graceful shutdown.) Add `server.shutdown: graceful` plus a small `timeout-per-shutdown-phase` as robustness hygiene.
- **N15.** `logback-spring.xml` declares two `<root>` elements instead of one with both appenders (`:7-13`); functionally correct (the root logger is a singleton), but a maintenance trap. Merge into a single `<root>`.
- **N16.** `StartupDataInitializer` KDoc describes a relational-to-event import task that no longer exists (`:16-18`) and omits three of the five real tasks. Update the KDoc to list the real orders (rebuild 100, fixtures 200, price 250, dev demo 260, bootstrap admin 300).
- **N17.** `deploy.env.example` documents a macOS-only `sed -i ''` that fails on GNU sed/Linux (`:2`); the actual deploy script uses the portable `openssl rand` + `printf` form. Use a portable approach in the doc.
- **N18.** Runtime image has no `HEALTHCHECK` (`Dockerfile:16-21`); a hard startup crash exits the JVM (so `restart: on-failure` reacts), but a started-but-degraded app reports running. Add a `wget`/`curl` healthcheck against `/actuator/health`.
- **N19.** `postgres:18-alpine` in the compose files is tracked by no updater (`dependabot.yml:48-53` parses the Dockerfile only); the same tag also appears in CI and Testcontainers. Add a `docker-compose` Dependabot ecosystem entry or document the hand-bump.
- **N20.** Snackbar action label differs between success ("OK") and error ("Dismiss") (`notification.service.ts:17`); microcopy inconsistency on the one shared feedback control. Use one label.
- **N21.** Mobile-first wall-QR app lacks a `theme-color` meta and a web manifest (`frontend/src/index.html`); the brand red does not extend to mobile browser chrome, and there is no add-to-home-screen support. Add `<meta name="theme-color" content="#c61826">` and a minimal manifest.

### Tests

- **N22.** Two security tests claim an exact HTTP status in their name but assert `isIn(401, 403)` (`AuthorizationSystemTests.kt:147,169`); a 401↔403 flip would not fail, violating the naming standard. Pin the single expected status.
- **N23.** No system test asserts any error-response body, only the numeric status (all of `tests/system/`); the 401 body IS pinned by `JsonAuthenticationEntryPointTest`, so the unpinned parts are the human-readable 409 conflict message and the 400 field message. Assert the body shape/message for representative 409/400 cases. (App uses a custom `ErrorResponse`, not Spring `ProblemDetail`.)
- **N24.** The deactivated-member 403 matrix omits undo and own-expense at the system level (`AuthorizationSystemTests.kt:70-93`); covered by domain unit tests but not the full request path. Add system tests for `POST /consumption/cancel` and `POST /expenses` returning 403 for a deactivated member.
- **N25.** The `priceAsOf` "no price in effect" error path is unreachable in tests and the guarding bootstrap order is not pinned (`LedgerDataServiceImpl.kt:59-66`; `StartupDataInitializerTest.kt`). Add a test pinning the price-seeder-before-consumption-seeder order and a ledger test asserting the documented error.
- **N26.** Cucumber action steps discard the response status (`CucumberAccountingSteps.kt:32-77`), so an intermediate-step regression surfaces only at the final balance assertion with a misleading message. Assert each action step's 2xx before proceeding.
- **N27.** Frontend unit suite covers 0/15 components and 2/13 services with a token 1% floor (`frontend/src/app/**/*.spec.ts`); a deliberate, documented choice with the e2e suite as the real coverage source (~70%). Add unit specs for the highest-logic units and raise the floor as the suite grows.
- **N28.** The concurrent self-scan 409 / "SPA retries" path is tested nowhere and the SPA does not retry (duplicate of M10/M11; see those for the merged write-up).
- **N29.** The event-replay equivalence test omits the price-change/undo/override paths (`EventsToDataRebuildSystemTest.kt:103-137`); but those valuations are read from the untouched log, not the rebuilt tables, so the gap is actually that the test never replays an UPDATE-event sequence through the projector. Extend it to replay a multi-event UPDATE sequence (undo/override/price-singleton update).
- **N30.** Documented acceptance feature files for user administration and authorization do not exist (`tests/acceptance/`); only `consumption.feature` and `accounting.feature` exist, so 2 of 4 documented BDD flows are absent (covered by system tests). Add the feature files or correct CLAUDE.md.
- **N31.** Link-rotation system test asserts `isNotNull()` redundantly and never verifies the new token authenticates (`UserAdminSystemTests.kt:188-189`); only the old-token-401 (negative half) is covered. Drop the redundant assertion and add a 200 assertion for a request bearing the rotated token.

### Prose: em-dashes and slop (maintainer's #1 stated pet peeve)

These are merged from many reviewer findings spanning backend KDoc/comments, frontend comments and one UI string, build/CI/script comments, docs, and a stale `compose.prod.yaml` comment. The maintainer flags em-dashes (U+2014) as the top AI-slop tell.

- **N32. One user-facing em-dash in a snackbar** (`frontend/.../admin-users.component.ts:541`): `'This member has financial history — deactivate them instead.'` is shown to admins at runtime (fires on a 409 delete-conflict). The only em-dash in live UI copy. **Fix:** `'This member has financial history. Deactivate them instead.'`
- **N33. Em-dashes throughout source comments/KDoc**: ~129 lines across 68 source files (backend ~17 in `api/`, plus `domain`/`data`/`application`; frontend 45 across 14 files; build/CI/scripts in `run-e2e-coverage.sh`, `qodana.yml`, `dependabot.yml`, etc.). **Fix:** a repo-wide sweep replacing prose em-dashes with commas, colons, parentheses, or sentence breaks; consider a detekt/CI grep guard.
- **N34. Stale/slop prose in docs**:
  - The `0.3.0` changelog claims an "AI-slop prose cleanup ... across the docs, UI copy, and KDoc," but the KDoc em-dashes (and the UI-copy one) were not removed, so the entry overstates its scope and is vague (`CHANGELOG.md:149`).
  - `doc/2026-06-20_...md` describes the removed "reset" and free "−1" as current behavior with no superseded banner (lines 24-34, 58-59, 78-80), while a sibling doc has such a banner and points to it. Add a superseded banner or edit the stale passages.
  - SCSS comment uses figurative "need room to breathe" (`styles.scss:138`); rewrite plainly.
  - `[Unreleased]` changelog header has no matching link reference (`CHANGELOG.md:8`); either add one in the repo's existing tag-URL style or remove the empty header.
  - The em-dash placeholders in the money-model table (`doc/2026-06-21_...md:24-29`) are a table-cell convention (the amounts correctly use the proper minus sign `−`), acceptable; inventory only.

---

## Notes on confidence

Almost all findings were verified at high confidence against the source. Items flagged by reviewers as **partial** (needs-maintainer-judgment) and reframed above: N2 (DELETE-event delta is unreachable in practice), L36 (rejection is client-side, not after a round-trip), L43 (the fat-fingered-link scenario is already handled; only truly unmatched paths funnel to admin), N29 (the replay test cannot catch valuation-replay bugs by design; the real gap is UPDATE-event replay), and N14 (no ledger-corruption or coverage-flush risk). H1's worst case is a loud insert failure, not silent corruption. None of these reframings change the recommended fixes materially; they sharpen the impact statements.
