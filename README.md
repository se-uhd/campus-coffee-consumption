# Campus Coffee Consumption

A coffee consumption tracker for [**SE@UHD**](https://se-uhd.de/), the Software Engineering Group at Heidelberg University. Each
user has a running coffee count, valued at a global admin-set **price per cup**, which feeds a
per-user **balance** and a communal **kitty**. A user bumps their own count by scanning a **QR (Quick Response) code on
the wall**: a secret per-user **capability link**. Scanning it opens a small mobile-first web app where
they add a coffee, **undo** a recent one within a grace period, **rate** the beans they just drank (1 to 5),
record their own bean purchases against a shared **bean catalog**, and see either their **balance** or their
**cup stats** (a per-user landing preference). Admins create and manage users, set the price, record
**typed** expenses (a *beans* purchase or an *other* group outlay) and kitty deposits, correct anyone's
count, and curate the bean catalog (rename and merge beans). Settling up records a **deposit** (real money
into the kitty); there is no reset. Every change is recorded in an append-only **event log**, from which a
**unified activity feed** (coffees, purchases, deposits, and ratings, the money entries with a running
balance) is read. Money is tracked in **euro cents**.

The app is a Spring Boot / Kotlin backend (hexagonal architecture, event sourcing persistence) with an
Angular 22 single-page application (SPA) frontend, derived from the CampusCoffee teaching project.

## How it works

- **Users** authenticate with their secret **capability token**. The token is encoded in their wall QR
  code as `https://<host>/login/{token}`; scanning it opens the SPA, which sends the token as the
  `X-Capability-Token` header on its API calls. The token never appears in an API URL path, only at the SPA
  entry point, so it stays out of API access logs. A user adds one coffee at a time, may **undo** the most
  recent one within a short grace period, may **rate** the beans they just drank (a score from 1 to 5, within
  the same window), records their own bean purchases (a catalog bean plus a weight), and sees either their
  balance or their cup stats (their choice), the current price, the kitty balance, and a unified activity feed
  of their coffees, purchases, deposits, and ratings.
- **Admins** authenticate with a **JSON Web Token (JWT)** held in an httpOnly, `SameSite=Strict` session cookie, minted from a
  username-and-password login (`POST /api/auth/token`, ~10-hour work-session token, no refresh flow). The
  credentials are encrypted in the browser (a compact JSON Web Encryption (JWE) payload under the backend's published RSA public key, with
  an `iat` so a captured ciphertext cannot be replayed) before they are sent, so the raw password never
  travels as plaintext, and the cookie keeps the token out of JavaScript's reach (a cross-site scripting (XSS) attack cannot steal it). An
  admin manages users (create, edit, deactivate, change
  role, view and rotate capability links, download any user's QR or all of them as a ZIP or printable PDF
  sheet), sets the global price, records **typed** expenses (a *beans* purchase against the catalog or an
  *other* group outlay) with a private/kitty split and kitty deposits and adjustments, corrects any user's
  count, curates the **bean catalog** (rename and merge beans), and reviews the kitty history, the bean
  ratings, and a per-user overview. There is no reset: settling up is a deposit, and a count change is a
  correction (optionally with a note).
- **SPA routing.** A user's capability link `/login/{token}` opens the user landing, with the user
  profile at `/login/{token}/profile` and the bean ratings at `/login/{token}/ratings`. The admin area is
  consolidated under `/admin/`: the login form at `/admin/login`, the landing/dashboard at `/admin`, the
  users, price, expenses, kitty, activity, and ratings pages at `/admin/users`, `/admin/price`,
  `/admin/expenses`, `/admin/kitty`, `/admin/activity`, and `/admin/ratings`, and the admin profile at
  `/admin/profile`. The root path redirects to `/admin`; any unknown route shows a not-found page.
- **Money is in euro cents and each cup is valued at the price when it was drunk.** A user's balance
  reads like a prepaid card (negative means they owe the fund), valuing each coffee at the price in effect
  when it was consumed.
- **The event log is the source of truth.** Each change (to a count, the price, an expense, a payment, a
  catalog bean, or a rating) appends one full-state event; the relational tables are a read model projected
  from the log, and the unified activity feed and balances are read straight from the event rows (each carries
  who made the change, when, and an optional note). See
  `doc/2026-06-21_pricing-expenses-kitty-and-the-unified-ledger.md`.

## Architecture

A multi-module Gradle project (Kotlin DSL) following a hexagonal (ports-and-adapters) architecture, with
layer boundaries enforced by ArchUnit:

- **domain**: domain models, port interfaces, and business logic (depends on Bean Validation and Spring, but not on the api, data, or application layers).
- **api**: REST controllers, data transfer objects (DTOs), MapStruct DTO mappers, the QR/capability URL helpers.
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
docker run -d --name campus-coffee-db -e POSTGRES_USER=postgres -e POSTGRES_PASSWORD=postgres -p ${DB_PORT:-5433}:5432 postgres:18-alpine
```

Run the backend in the `dev` profile (loads the seeded fixtures on first start):

```shell
gradle :application:bootRun --args='--spring.profiles.active=dev'
```

`bootRun` serves the full app (the bundled SPA plus the API) at `http://localhost:8081` (the first run builds
the Angular SPA); the API is under `/api`, with Swagger UI at `http://localhost:8081/api/swagger-ui.html`.
(The dev profile listens on `:8081` to avoid colliding with another app on the conventional `:8080`; override
with `SERVER_PORT`.)

For frontend development, run the Angular dev server (it proxies `/api` to the backend on `:8081`, so the
browser still sees a single origin and no Cross-Origin Resource Sharing (CORS) is needed):

```shell
cd frontend && npm start
```

`gradle build` packages the same self-contained app (the SPA plus the API) into a single jar for deployment.

## Test fixtures (dev)

The `dev` profile seeds one admin and four users with deterministic capability tokens (so demos are
repeatable), each with a coffee consumption at zero. The credentials live in
`domain/src/main/kotlin/de/seuhd/campuscoffee/domain/tests/TestFixtures.kt`:

| Login           | Role  | Capability token (user login)                  |
|-----------------|-------|------------------------------------------------|
| `jane_doe`      | ADMIN | `Rh7tK2pXmQ9vL4nB8cD1eF6gH3jZ0sW5yAuToN2kEac`  |
| `maxmustermann` | USER  | `Pq3wE9rT5yU1iO7pA2sD8fG4hJ6kL0zXcVbN3mM1nBqe` |
| `student2023`   | USER  | `Zx1cV7bN3mA9sD5fG2hJ8kL4qW0eR6tYuIoP1lK7jHzx` |
| `lisa_lee`      | USER  | `Lk8jH4gF6dS2aP0oI9uY7tR3eW1qZ5xCvBnM2mN8bVlk` |
| `olivia_lee`    | USER  | `Ty6rE2wQ8aS4dF0gH6jK1lZ3xC9vB5nMqWeR7tY1uIty` |

A user's capability link is `http://localhost:8081/login/<token>`, and users authenticate **only** with
that link (they have no password). The admin `jane_doe` is different: she logs in with a **password** at
`POST /api/auth/token` (the JWT login), **not** with the capability token in the table above. The fixture
admin password is **`aaaMbnPdFYDqkOpS3fVA2xyz`** (also in `TestFixtures.kt`). The dev `DevController` (`GET/PUT/DELETE /api/dev/data`) reports the counts, reloads the
fixtures (reassigning the same seeded ids), or clears the data.

On top of these fixtures, the `dev` profile runs a dev-only `DevDemoDataLoader` that adds about nine more
users and an initial kitty float, and also enriches **every existing fixture user** (the admin `jane_doe`
included) with varied consumption, bean-purchase, and deposit history, so the users list paginates and the
activity and history views are not empty on a fresh start. Two users are deliberately left **empty** to demo
the empty state: a freshly created active user `new_user` (no history at all) and the inactive demo user
`hannes_schulz`. It is `@Profile("dev")`, so the tests still see exactly the five-user fixture set above.
The `dev` profile also reseeds on every startup (`campus-coffee.fixtures.reset-on-startup`), returning to
this deterministic state on each restart.

## REST API

All paths are under `/api`. JSON only. See Swagger for the full contract.

**User (auth: `X-Capability-Token`):**

- `GET  /summary`: the user landing in one call (current count, balance, the current price, the kitty balance, the landing-panel preference with its cup stats, the rating prompt, and the first page of the unified activity feed).
- `POST /consumption` (no body): add one coffee.
- `POST /consumption/cancel`: undo the most recent coffee within the grace period (nothing to undo, or past the grace period, returns 409 Conflict).
- `PUT  /consumption/rating` `{ "beanId": "…", "value": 1..5 }`: rate the bean of the most recent coffee within the grace window (a repeat updates the one vote).
- `GET  /activity?limit=20&offset=0`: own unified activity feed (coffees, purchases, deposits, ratings) with a running balance.
- `POST /expenses` `{ "expenseType": "BEANS" | "OTHER", "beanName"?: "…", "weightGrams"?: N, "amountCents": N, "note"?: "…" }`: record an own expense (booked 100% to the user). A `BEANS` expense names a catalog bean and a weight; an `OTHER` expense has neither.
- `GET  /profile`, `PUT /profile`: view and edit own name, email, and landing-panel preference (the response includes the capability URL).
- `GET  /profile/qr.png`: own QR code (high-resolution PNG).
- `GET  /beans`, `GET /beans/ratings`: the selectable beans and the per-bean ratings table (shared with admins; any authenticated user).

**Admin (auth: JWT, `ROLE_ADMIN`):**

- `GET /users`, `POST /users`, `GET/PUT/DELETE /users/{id}`, `GET /users/me`, `GET /users/filter?login_name=…`, `GET /users/overview` (the per-user overview now renders in the user-management page, `/admin/users`).
- `DELETE /users/{id}`: refused (409) if the user has financial history; deactivate instead.
- `GET /users/{id}/link`, `POST /users/{id}/link/rotate`, `GET /users/{id}/qr.png`.
- `GET /users/qr.zip`: a streamed ZIP of every active user's QR code (one `<loginName>.png` per user).
- `GET /users/qr.pdf`: a printable PDF grid of every active user's QR code, each labeled by login name.
- `GET  /users/{id}/summary?limit=10&offset=0`: the per-user landing summary (the admin analogue of `/summary`).
- `GET  /users/{id}/consumption?limit=5&offset=0`, `GET /users/{id}/activity?limit=20&offset=0`.
- `GET /users/activity?limit=20&offset=0`: the whole-installation global activity feed (every user's coffees, purchases, and deposits, the kitty adjustments, and price changes), newest first, each row carrying the subject user, the actor, and the user and kitty running balances. Renders in the admin **Activity** page (`/admin/activity`).
- `GET /users/activity.csv`: the same global feed as a streamed comma-separated values (CSV) download of the full dataset, with a UTF-8 byte order mark (BOM), ISO-8601 UTC timestamps, and raw integer euro cents.
- `POST /users/{id}/consumption` `{ "delta": 1 | -1 }`; `POST /users/{id}/consumption/cancel`: undo the user's most recent coffee within the grace period.
- `PUT  /users/{id}/consumption` `{ "total": N, "note": "…" }`: absolute count correction (`note` optional); `PUT /users/{id}/consumption/rating` `{ "beanId", "value" }`: rate the user's current cup on their behalf.
- `GET/POST/PUT/DELETE /users/{id}/expenses`: list, record, correct, or delete a user's typed expense (`expenseType` `BEANS`/`OTHER`, an optional `beanName` and `weightGrams`) with a private/kitty split (the buyer cannot be changed on a correction).
- `PUT /beans/{id}` `{ "name" }`: rename a catalog bean; `POST /beans/{id}/merge` `{ "targetBeanId" }`: merge a bean into a canonical target (there is no create endpoint; beans are created by name from the expense and rating paths).
- `GET /price`: read the current global price (admin-only; users receive it through their landing summary).
- `PUT /price` `{ "amountCents": N }`, `GET /price/history?limit=50&offset=0`: set the global price, or view a page of its history (newest first).
- `POST /kitty/deposit` `{ "userId", "amountCents", "note"? }`: a user pays money into the kitty.
- `POST /kitty/adjustment` `{ "amountCents", "note"? }`: a pure kitty adjustment (an initial float or a correction).
- `GET /kitty/history?limit=50&offset=0`: the kitty history with the running kitty balance.

**Auth:** `GET /auth/public-key` returns the backend's RSA public key as a JSON Web Key (JWK); `POST /auth/token`
`{ "encryptedPayload": "<compact JWE of { loginName, password, iat }>" }` sets the JWT in an httpOnly
`SameSite=Strict` cookie and also returns `{ "token": "<jwt>" }` for header-based API clients;
`POST /auth/logout` clears the cookie. The SPA encrypts the credentials with the published key
(`RSA-OAEP-256` + `A256GCM`) so the raw password never travels as plaintext (defense in depth on top of TLS);
a malformed, undecryptable, or stale (replayed) payload returns 400.

Money is in integer euro cents throughout. The HTTP method carries the semantics: `GET` reads, user
`POST /consumption` adds one coffee (and `/cancel` undoes a recent one), and admin `PUT` sets an absolute
total. Consumption has no reset, no `−1`, and no `DELETE`. Settling up records a deposit; a count change
records a correction; both stay in the append-only log.

## Inspecting the event log

Every change (to a count, the price, an expense, or a payment) is one row in the append-only `events`
table. The `created_by` column records the actor's login (a user, an admin, or `"SYSTEM"` for the
fixtures), and `note` records the note for that change: an admin's reason for an absolute count correction,
or a deposit, kitty-adjustment, or expense note:

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

## Production deployment (Cloud Run + Cloud SQL, defined with OpenTofu)

The whole app ships as **one Cloud Run image**: the Angular SPA is bundled into the backend's `static/`
resources, so the browser loads the app and calls `/api` under one origin (no CORS in prod).

The prod profile connects to a **Cloud SQL for PostgreSQL 18** instance (tier `db-f1-micro`, see
`doc/adr/001-where-the-production-database-runs.md`) through the **Cloud SQL Java connector**, which does
Transport Layer Security (TLS) and Identity and Access Management (IAM) auth itself, as the least-privilege
role `campus_coffee_app` (provisioned once, see the notes below).
Fixtures are off in prod. A **bootstrap admin** is created on first startup from
`campus-coffee.bootstrap-admin.*` when no admin exists.

**The cloud setup is defined declaratively in `infra/`** with OpenTofu (`doc/adr/002-declarative-cloud-setup.md`):
the enabled APIs, the image repository, a dedicated runtime service account with exactly the two roles the
app needs (`roles/cloudsql.client`, and `roles/secretmanager.secretAccessor` on its five secrets), the Secret
Manager secret containers, the Cloud SQL instance (with deletion protection), the Cloud Run service, and an
optional uptime check with an email alert. The state file `infra/terraform.tfstate` is committed to git,
encrypted with `STATE_PASSPHRASE` from `deploy.prod.env` (`infra/encryption.tf`). It holds no secret, only
the deploy identifiers, which is why it is encrypted. `infra/imports.tf` re-adopts the resources if the
state or the passphrase is ever lost (all but the uptime check, its channel, and its alert, whose ids are
server-generated, so the plan creates a new check, channel, and alert, and the previous three are deleted by hand).

**Secrets are authored in `deploy.prod.env` and synced into Google Secret Manager.** The gitignored
`deploy.prod.env` is the local source of truth for the five secrets (`JWT_SECRET`, `LOGIN_PRIVATE_KEY_PEM`,
`TOTP_ENCRYPTION_KEY`, `DB_PASSWORD`, `BOOTSTRAP_ADMIN_PASSWORD`), the state passphrase, and the non-secret
config. `scripts/deploy.sh` reads it, syncs each secret into Secret Manager (creating it, or adding a new
version when the value changes), builds and pushes the image with Cloud Build, and runs `tofu apply`, which
binds the secrets onto the service by name and version number and passes the non-secret config as
environment variables. No secret value passes through OpenTofu or its state. See
`doc/2026-06-25_secret-manager-for-deployment-secrets.md`.

Deploy:

```shell
scripts/deploy.sh          # prints the plan and asks before applying; --yes skips the question
git add infra/terraform.tfstate infra/terraform.tfstate.backup infra/.terraform.lock.hcl && git commit   # after every apply
```

Deploy notes:
- One-time: `gcloud auth login`, then copy `deploy.env.example` to `deploy.prod.env` and fill it in
  (`gcloud` and `opentofu` come from `mise.toml`, and the project comes from `GCP_PROJECT` there). OpenTofu
  authenticates with the gcloud login's access token, so no application-default credentials are needed.
- `CAMPUS_COFFEE_APP_BASE_URL` is the deployed https origin (used to build the capability URLs). On a fresh
  project deploy once with a placeholder, read the URL from the deploy's `Service URL` line, put it into
  `deploy.prod.env`, and deploy again.
- Cloud Run is capped at 2 instances (`max_instances` in `infra/variables.tf`) because `db-f1-micro` allows
  25 connections and the app holds 5 per instance across two revisions during a deployment. Raise the cap
  only together with the database tier.
- The image build runs as the dedicated `campus-coffee-build` service account (`infra/iam.tf`: log writer
  on the project, writer on the image repository, object user on the project's Cloud Build bucket). The
  default compute service account, which the build ran as before with `roles/editor`, and the legacy Cloud
  Build account, which held the builder role, need no role any more. Their bindings were removed by hand on
  2026-09-06. Project IAM is additive, so the definition does not remove roles it does not declare: review
  the project's members now and then with `gcloud projects get-iam-policy <project>
  --flatten='bindings[].members' --format='value(bindings.members,bindings.role)'`.
- The service is pinned to the image digest and to the secrets' version numbers, so a rebuild and a secret
  rotation both create a revision. `scripts/deploy.sh --plan` is the drift check without a build.
- Do not change the service, the instance, the secrets' IAM, or the identities by hand or with
  `gcloud run deploy`. The next `tofu plan` shows the drift and the next apply reverts it. Secret versions
  are the exception. Writing them is the script's job.

After deploying, verify `/actuator/health`, the admin login, and a capability URL scan against the deployed
origin.

### The database role

The instance's `postgres` user is its administrator, and the app runs as the least-privilege role
`campus_coffee_app`. Provision it once, in this order, so the password is recorded before it is used:

1. Generate the password with `scripts/generate-password.sh` and put it into `deploy.prod.env` as
   `DB_PASSWORD`, replacing the placeholder line rather than adding a second one: the deploy script reads
   the first occurrence of a key and refuses to run when a key appears twice.
2. Create the role as `postgres` through the Cloud SQL Auth Proxy:
   `psql "host=127.0.0.1 port=5432 user=postgres dbname=postgres" -v ON_ERROR_STOP=1 -v app_password="$(grep '^DB_PASSWORD=' deploy.prod.env | cut -d= -f2-)" -f scripts/sql/create-app-role.sql`.
3. If Flyway ever ran as `postgres` (it did until 2026-09-06), transfer the tables and sequences it created
   to the app role. Otherwise a later `ALTER TABLE` migration fails with "must be owner". The transfer is
   `GRANT campus_coffee_app TO postgres;` then `ALTER TABLE public.<table> OWNER TO campus_coffee_app` for
   each table and `ALTER SEQUENCE` for each sequence still owned by `postgres` (a `DO` block over
   `pg_tables` and `pg_sequences` does it in one go, and the `public` schema keeps its owner). Verify with
   `SELECT tablename, tableowner FROM pg_tables WHERE schemaname = 'public'`.
4. Set `DB_USERNAME=campus_coffee_app` in `deploy.prod.env` and deploy. Keep the `postgres` password as
   `DB_SUPERUSER_PASSWORD`, used by hand as the administrator and never bound to the app.

### Rotating a secret

Change the value in `deploy.prod.env` and run `scripts/deploy.sh`. The sync adds a Secret Manager version,
the apply pins the service to it, and the new revision serves it. A version added by hand with
`gcloud secrets versions add` is not what the service reads: the next deploy pins whatever
`deploy.prod.env` holds. After a successful apply the script disables the superseded versions, so a
compromised app process cannot read historical values. Re-enable one with
`gcloud secrets versions enable <version> --secret=<name>` if traffic ever has to go back to an older
revision.

`DB_PASSWORD` is the exception, because the database has to change with it. Do it in one window:

1. Put the new password (from `scripts/generate-password.sh`) into `deploy.prod.env`.
2. Run `scripts/deploy.sh` **without** `--yes` and let it stop at the plan prompt. The image is built and
   the new secret version exists at that point, but nothing is deployed yet.
3. In another shell, through the Cloud SQL Auth Proxy as `postgres`, run
   `ALTER ROLE campus_coffee_app PASSWORD '<new password>'` (the instance's password policy in
   `infra/sql.tf` applies). Connections that are already open keep working.
4. Confirm the apply. The window in which the old revision cannot open new connections is seconds.

### Recovering a lost state or passphrase

The committed state is unreadable without `STATE_PASSPHRASE`, and `infra/imports.tf` exists for exactly
this case:

1. `git rm infra/terraform.tfstate infra/terraform.tfstate.backup && git commit`.
2. `scripts/deploy.sh --plan`. The import blocks re-adopt every resource, so the plan should show 28
   imports, nothing destroyed or replaced, and only three additions: the uptime check, its notification
   channel, and its alert policy, whose ids are server-generated and so cannot be imported by name. It also
   shows six in-place changes, which are expected: `deletion_protection` on the five secrets and
   `deletion_policy` on the image repository are client-side settings that the cloud API does not store, so
   a fresh import reads them as unset and the apply puts the guards back.
3. `scripts/deploy.sh`, then delete the three orphaned monitoring resources in the console (the ones not in
   the new state), and commit the new state.

### A fresh project

The definition adopts an existing setup: a plan fails when a resource named by an import block does not
exist. To stand up a new project, remove every import block in `infra/imports.tf` except the ones for the
enabled APIs and the Secret Manager secrets (that is: the Cloud SQL instance, the Cloud Run service and its
public invoker binding, the image repository, both service accounts, and all of their IAM members), and
before the first deploy:

1. Enable the APIs and create the Cloud SQL instance and its database.
2. Create the Cloud Build staging bucket: `gcloud storage buckets create gs://<project>_cloudbuild
   --location=US`. It is not part of the definition on purpose: Cloud Build creates and shares it across the
   whole project as a US multi-region bucket, so declaring it with this deployment's region would replace it
   and destroy the build logs.
3. Run `scripts/deploy.sh`, provision the database role as above, and put the import blocks back.

## License

See [LICENSE](LICENSE).
