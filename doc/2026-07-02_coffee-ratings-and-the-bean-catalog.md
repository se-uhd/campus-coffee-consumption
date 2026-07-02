# Coffee ratings, the bean catalog, and typed expenses (2026-07-02)

This record designs an optional coffee-rating feature on top of the existing consumption and expense model.
A user rates the beans currently being served right after they add a coffee, on a one-to-five bean scale.
Bean names come from bean-purchase expenses, so expenses grow a type (a bean purchase or some other
outlay) and an optional link to a named bean. A ratings view aggregates the ratings per bean, and admins
can tidy the catalog by renaming or merging bean names. The last section covers the one-time production
migration that gives every existing expense a type and a bean.

This is a planning record to be implemented later. The four decisions below were confirmed on 2026-07-02;
the rest of the document is written against them.

## Decisions (confirmed 2026-07-02)

1. **One vote per cup window; votes accumulate per bean.** A rating is a vote on a bean (value one to five).
   A user may cast many votes for the same bean over time, but **at most one vote per cancellable window**:
   the span from pressing the add-coffee button until the Undo control disappears (that cup's grace period).
   Within the window the user may set or change their single vote (bean and value); a second tap updates that
   vote rather than adding another. The `+1` is the moment of attention (the user is drinking the coffee),
   and each drink earns one vote. The ratings vote count is the total number of votes for a bean, not
   distinct raters.

   This is enforced **without storing any per-cup or event-store identity on the rating**, so the hexagonal
   boundary stays clean (no `events.id` or `seq` on the `CoffeeRating` model or in its body). The window is
   bounded by the current cancellable increment's `createdAt`, which the data layer already computes for the
   grace check: the service treats the user's most recent vote as the current window's vote when its
   `createdAt` is at or after that increment, and otherwise starts a new vote. Because both this check and
   the undo only ever concern the most recent increment (LIFO), no upper time bound is needed and the
   scoping is unambiguous. Undoing the cup within its window deletes that window's vote (the coffee was not
   ultimately consumed); votes from earlier cups are untouched. A concurrent double-submit within one window
   is serialized per user (reusing the existing `lockUser` advisory lock) so it cannot create two votes.

2. **The append-only log is not touched; projection is made backward compatible.** Legacy `Expense` event
   bodies stay byte-for-byte as they are. The deserializer defaults a missing `expenseType` to `BEANS` and a
   missing bean to "derive from the note", and beans become event-backed by appending `CoffeeBean` creation
   events at migration time. See **Production migration** for the rebuild-ordering wrinkle this creates and
   how it is handled. The rejected alternatives were rewriting the legacy bodies in place, and appending a
   correction event per legacy expense.

3. **Verify the production dataset first.** Inspect the live data (expense row count, note quality) as the
   first implementation step, then size the migration and its parity checks to it. The migration is designed
   to be safe either way; if there is little or no data yet, the parity harness in **Verification** can be
   trimmed.

4. **Rating-control expiry is server-authoritative, with no client timer.** The rating controls follow the
   existing Undo button exactly: they linger until the next server response, and a rating attempted past the
   grace period is rejected with a 409. This matches today's behavior and adds no new frontend mechanism
   (there is no grace-period countdown in the SPA today; `cancellable` only changes on a round-trip).

## Overview

Today a user adds a coffee and may undo it within a grace period; they also record their own bean purchases
as **expenses** (weight in grams, amount in cents, an optional note). There is no notion of a bean's
identity or quality. This feature adds:

- A **bean catalog**: named beans, derived from and linked to bean-purchase expenses.
- **Ratings**: a user rates a bean one to five, prompted right after they add a coffee.
- **Typed expenses**: an expense is either a bean purchase (`BEANS`, carrying a bean and a weight) or some
  other outlay (`OTHER`, no bean, no weight).
- A **ratings** view aggregating ratings per bean, with admin rename and merge.

Everything is built on the existing event-sourcing machinery (an append-only `events` log projected into
relational read tables) and reuses the established "logged entity" pattern rather than inventing new
persistence.

## User-facing behavior

- After a user presses the add-coffee button, the landing shows, alongside the existing Undo button:
  - a bean selector (defaulting to the most recently purchased bean), and
  - five bean-shaped rating buttons (one to five).
- Rating is optional.
- While the just-added cup is still cancellable, the user can set or change the rating for the selected bean.
- The rating controls appear under the same condition as the Undo button (the summary's `cancellable`
  flag), and expire the same way (decision 4: on the next server response; a late rating gets a 409).
- An admin adding a coffee to a user does **not** get a rating prompt. The prompt is a self-service concern,
  and an admin step is not an owner increment, so it never sets `cancellable` for the user anyway.
- Ratings are visible to users and admins. Admins get an edit mode to rename or merge bean names.

**Undoing a cup deletes that window's vote** (decision 1): the coffee was not consumed. Votes cast for the
same bean on earlier cups are untouched. So `CoffeeConsumptionService.cancel`, after the successful
decrement, clears the vote whose `createdAt` falls in the reversed cup's window (a `CoffeeRatingService`
call passing the reversed increment's `createdAt`, a domain timestamp, so nothing leaks).

## Domain model

New models in `domain/.../model/`:

- **`CoffeeBean`**: `id`, `createdAt`, `updatedAt`, `name`, `active`, and (for merges) a nullable
  `mergedIntoId` canonical pointer. A bean with a non-null `mergedIntoId` is a tombstone that resolves to
  its target; the target is `active`.
- **`CoffeeRating`**: `id`, `createdAt`, `updatedAt`, `user`, `bean`, `value` (one to five). Many rows per
  `(user, bean)` are allowed (one per cup window); no cup reference is stored (the current window's vote is
  resolved by `createdAt`, see decision 1).
- **`CoffeeBeanRatings`**: a read-only projection per bean: the bean, `averageValue`, `voteCount`,
  `latestRatingAt`, `latestPurchaseAt`. Not event-sourced (it is derived, like `UserSummary`).
- **`CoffeeRatingPrompt`**: a read-only projection returned in the summary and add-coffee responses:
  `canRate` (mirrors `cancellable`), the selectable beans, the default bean id, and the user's current
  `value` for the default bean (so the UI can prefill). Not event-sourced.
- **`ExpenseType`** enum: `BEANS`, `OTHER`. A cross-layer enum such as `Role` and `SummaryPanel`.

New API ports in `domain/.../ports/api/`:

- **`CoffeeBeanService`**: list selectable beans, the ratings, and the admin rename and merge operations.
- **`CoffeeRatingService`**: `rateCurrentBean(userId, beanId, value, actingUser)` (upserts the current
  window's vote, allowed only while the user has a cancellable increment within grace), `promptFor(userId,
  actingUser)` (the read for the prompt), and `clearVoteInWindow(userId, windowStart)` (called by
  `cancel`).

New data ports in `domain/.../ports/data/`:

- **`CoffeeBeanDataService`** (extends `CrudDataService`), adding a lookup by normalized name.
- **`CoffeeRatingDataService`** (extends `CrudDataService`), adding the per-user upsert and the rating
  aggregation read.

`Expense` (in `domain/.../model/Expense.kt`) gains:

- `expenseType: ExpenseType?` (nullable in the domain, resolved to `BEANS` when absent, the same
  accept-or-keep convention as `role`/`active`/`summaryPanel`).
- `bean: CoffeeBean?` (a bean purchase links a bean; an `OTHER` expense does not).
- `weightGrams` becomes nullable (`Int?`), required only for `BEANS`.

**Validation** moves into `ExpenseServiceImpl` (a cross-field rule, like the existing split check, not plain
annotations, because the requirement depends on `expenseType`):

- `BEANS` requires a `bean` and a positive `weightGrams`.
- `OTHER` requires no `bean` and no `weightGrams`.
- The existing split rule (`privateAmountCents + kittyAmountCents == amountCents`) and the non-negative
  checks are unchanged; `validateAmounts` is reworked so `weightGrams` is validated only when present. A
  user's own purchase is still booked 100 percent private (`recordOwn`).

### On `CancellableIncrement` and the architecture boundary

`domain/.../model/CancellableIncrement.kt` carries exactly `createdAt` and `priceCents`, and its doc comment
states deliberately that it "carries no persistence identifier (the event-store append position stays in the
data layer)." The original plan proposed extending it with "the source consumption event id"; that would
break this contract and the project's rule against persistence concepts (an event `seq` or id) in the domain
and public API. **This design does not touch `CancellableIncrement`.** All "which cup?" resolution stays in
the data layer, exactly as the undo already works: the domain calls `cancel(userId)`, and
`ActivityDataServiceImpl.lastCancellableIncrement` re-walks the user's consumption events LIFO to find the
increment. The rating path reuses the same walk when it needs the current increment; the domain and the
user-facing responses never carry an event id.

## Data and event sourcing

Two new logged entities, each following the established "adding a new entity" checklist:

- Add `COFFEE_BEAN` and `COFFEE_RATING` constants to `LoggedEntityType` (data layer). The projector's `when`
  is exhaustive over this enum, so the compiler forces the new branches.
- Read-model entities `CoffeeBeanEntity` and `CoffeeRatingEntity`, repositories, MapStruct entity mappers,
  relational `*DataServiceImpl`s (each with a `BEAN_NAME` companion constant), and `@Primary`
  `EventSourced*DataService` decorators that inject the relational bean by `@param:Qualifier(<Impl>.BEAN_NAME)`
  and reuse `EventSourcedWriter.upsert`, mirroring `EventSourcedExpenseDataService`.
- `EventJsonMapper`: a serializer per new type. `CoffeeRating` flattens its `user` to `userId` and its `bean`
  to `beanId` in the body (mirroring how an expense flattens its buyer). `CoffeeBean` is a small record and
  can serialize field-for-field (with `mergedIntoId` nullable).
- `ReadModelProjector`: insert, update, and delete branches for both types. The rating branch resolves
  `userId` and `beanId` against the already-projected read models (`requireUser`, and a new `requireBean`).
- Read-model `@ManyToOne` associations are mapped as **foreign-key references by id**, not deep nested
  copies. A rating (or a corrected expense) can switch which bean it points at, and MapStruct's default
  nested `updateEntity` would call the bean mapper on the *currently referenced* bean row and rewrite it
  (renaming the old bean onto the new one, which the partial unique index then rejects as a duplicate).
  A small `EntityReferenceResolver` (used by the consumption, expense, payment, and rating entity mappers)
  maps each association's id to a loaded reference via `EntityManager.find`, so a write only sets the key and
  never mutates the referenced parent. `fromEntity` still maps associations deeply for reads.
- `ConstraintMapping` and the projector's `DUPLICATION_RULES`: a bean name is **unique among active beans**,
  so `CoffeeBean` declares that unique constraint and gets a `DUPLICATION_RULES` entry (unlike `Expense`,
  which declares `emptySet()`). Enforce uniqueness on active beans only (a partial unique index
  `WHERE merged_into_id IS NULL`), so a merged tombstone does not block reusing a freed name.
- `Expense` serialization and projection are extended for `expenseType` and `beanId`. `weightGrams` becomes
  nullable in the `ExpenseEventPayload` and is written with `writePOJO` (which tolerates null) rather than
  `writeNumber`. The projector resolves `beanId` to a bean (see the migration for legacy bodies).

**Balance and activity are unaffected.** The money projections (`BalanceDataServiceImpl`) and the activity
feeds (`ActivityDataServiceImpl`, `EventReducer`) already filter by entity type, so `CoffeeBean` and
`CoffeeRating` events are ignored for free: they carry no money and touch no user or kitty balance. Confirm
the admin global activity feed (`/api/users/activity`) also excludes them (bean and rating churn is not an
audit-worthy money event); if an audit trail of renames and merges is wanted later, add it deliberately.

### Migrations

Current highest migration is `V9`. New files (plain data-definition language, no comments, append-only):

- `V10__create_coffee_beans_table.sql`: `coffee_beans` (uuid PK, timestamps, `name`, `active`,
  `merged_into_id` nullable self-FK, `version`), plus the partial unique index on `name` where
  `merged_into_id IS NULL`.
- `V11__add_type_and_bean_to_expenses.sql`: add `expense_type varchar(16) NOT NULL DEFAULT 'BEANS'` (the
  default backfills every existing row to `BEANS` on migrate), a nullable `bean_id uuid` FK to
  `coffee_beans` (default RESTRICT, like the buyer FK), and drop the `NOT NULL` on `weight_grams`. The
  column is now nullable, but a `ck_expenses_beans_weight` CHECK ties it to the type (mirroring the domain
  rule and the split CHECK): a `BEANS` row must have a weight, an `OTHER` row must have none. The existing
  `weight_grams >= 0` check still holds for a present weight.
- `V12__create_coffee_ratings_table.sql`: `coffee_ratings` (uuid PK, timestamps, `user_id` FK to `users`,
  `bean_id` FK to `coffee_beans`, `value smallint CHECK (value BETWEEN 1 AND 5)`, `version`), with an index
  on `(user_id, created_at)` to resolve the current window's vote. There is no unique constraint (votes
  accumulate), so `CoffeeRating` declares `emptySet()` constraints like `Expense` and adds no
  `DUPLICATION_RULES` entry; the one-vote-per-window rule is enforced in the service under `lockUser`.

Order matters: beans exist (`V10`) before the expense FK (`V11`) and the rating FK (`V12`).

## API surface

New endpoints, named in the UI's vocabulary and grouped under the resource the UI shows them on:

- **User self-service** (`X-Capability-Token`):
  - `GET /api/summary` and `POST /api/consumption`: extend `UserSummaryDto` with a `ratingPrompt`
    (`CoffeeRatingPrompt`). The backend always includes it; the SPA renders the rating controls when
    `canRate` is true.
  - `PUT /api/consumption/rating` `{ beanId, value }`: set or update the rating for the current cup's
    selected bean. Allowed only while the user has a cancellable increment and within the grace period
    (server-checked; a late call is a 409, matching Undo). Returns the refreshed `UserSummaryDto`.
  - `GET /api/beans`: the selectable (active) beans for the dropdown.
  - `GET /api/beans/ratings`: the ratings (also readable by users).
  - `POST /api/expenses`: the own-purchase body gains `expenseType`; for `BEANS` it carries a bean choice
    (an existing `beanId` or a new `beanName`) and a `weightGrams`; for `OTHER` it omits both. The server
    resolves or creates the bean and books the expense 100 percent private, as today.
- **Admin** (JWT, `ROLE_ADMIN`):
  - `GET /api/beans/ratings` edit affordances are backed by `PUT /api/beans/{id}` (rename) and
    `POST /api/beans/{id}/merge` `{ targetBeanId }` (merge into a canonical bean).
  - `POST/PUT /api/users/{id}/expenses`: gain the same `expenseType`/bean/weight fields with the explicit
    private and kitty split preserved.

A merged bean keeps its ratings and expenses; reads resolve a tombstone through its `mergedIntoId` to the
canonical bean, and the ratings aggregate under the canonical bean. Rename is a single bean update event;
merge is a single update event on the merged bean (setting `mergedIntoId` and `active = false`). This keeps
merge cheap in the event log (one event, not a repoint of every referencing row).

The ratings are read as a **read-model aggregation** over `coffee_ratings` joined to `coffee_beans` (average
and count of `value`, newest rating time) and `expenses` (newest `BEANS` purchase time per bean), resolving
tombstones to canonical. This is a bounded SQL query, unlike the activity feeds that must walk the log, so it
needs no log replay.

All request and response DTOs are **OpenAPI-generated** (`frontend/src/app/api/model/`, re-exported through
`frontend/src/app/models.ts`). New fields come from the backend spec: implement the backend, then
`gradle :application:refreshOpenApiSpec` to recapture `frontend/src-gen/api-docs.json` and regenerate the
DTOs. Do not hand-edit the generated files; the drift gate (`DevSystemTests`) fails the build if the
committed spec falls behind the live one.

## Frontend

Routes today are `/login/:token` and `/login/:token/profile` for users (the landing is token-scoped, not a
bare `/`), and `/admin`, `/admin/users`, `/admin/price`, `/admin/expenses`, `/admin/kitty`,
`/admin/activity`, `/admin/profile` for admins. The original plan's `/` and `/profile` do not exist.

- **Rating controls on the landing** (`CoffeeLandingComponent`, used for both the user `/login/:token` view
  and the admin `/admin` view). The rating block renders in user mode, gated on `summary().ratingPrompt.canRate`
  (which mirrors `cancellable`), next to the existing Undo button in the `[extra]` slot of
  `cc-balance-summary`. It has a bean `mat-select` (defaulting to `ratingPrompt.defaultBeanId`) and five
  bean-icon buttons prefilled from `ratingPrompt.value`. Choosing a rating calls a new `SummaryService`
  method (`PUT /api/consumption/rating`) and adopts the refreshed summary, the same pattern as
  `addCoffee`/`undo`. No client timer (decision 4).
- **Expense form** (embedded in `CoffeeLandingComponent` for own purchases; `AdminExpensesComponent` for the
  admin split). Add a `BEANS`/`OTHER` toggle. For `BEANS`: a bean autocomplete (`matAutocomplete` over
  `GET /api/beans`) that accepts an existing name or a new one, plus the weight field. For `OTHER`: hide the
  bean and weight fields. The admin split fields are unchanged.
- **Ratings page** (titled *Ratings*, matching the other single-word page titles; the route, endpoint,
  `CoffeeBeanRatingsDto`, and `BeanService.ratings()` all use the `ratings` name too): routed at
  `/admin/ratings` (admin, `adminGuard`) and the token-scoped `/login/:token/ratings` (user), one dual-mode
  component backed by a new `BeanService` (`GET /api/beans/ratings`, and for admins the rename and merge
  writes). It is a paginated
  `mat-table` (matching the users and activity tables) with a column each for the bean name (truncated, full
  name in a tooltip), the average rating (a fixed-width column of full/half/empty bean icons, rounded to the
  nearest half, plus the number), and the vote/recency metadata (vote count, latest rating, latest purchase).
  The table is sortable by name, rating, or vote count and defaults to rating, best first. Pagination is
  client-side over the full list the endpoint returns. In admin edit mode a trailing actions column adds an
  inline rename and a merge-into-target control (the editors replace the name cell for that row). A
  `leaderboard` nav link reaches it in both modes.
- New Angular services follow the `*Service` naming (`BeanService`; the rating write is `SummaryService.rateCoffee`).
- **The auth interceptor learns the one dual-audience path.** `/api/beans` is read by users (capability
  token) and written by admins (JWT cookie). The interceptor attaches the capability token only to the user
  **GET** reads of `/api/beans`; the admin writes (PUT/POST) fall through to the cookie, so an admin write is
  never misattributed to a lingering capability token.

## Production migration

The migration gives every existing expense a type and a bean, and makes historical events replay under the
new schema. Its exact rigor depends on decision 3 (how much real data exists); it is designed to be safe
regardless, and the confirmed approach (decision 2) does not rewrite the log.

**Status:** the schema (`V10`/`V11`/`V12`), the backward-compatible projection (a legacy `Expense` body with
no `expenseType`/`beanId` projects as `BEANS` with no bean), and the events-to-data rebuild (which now clears
and replays the `coffee_beans`/`coffee_ratings` tables in the correct order) are **implemented and tested**.
`V11` adds `expense_type NOT NULL DEFAULT 'BEANS'`, so every existing expense row is already typed `BEANS` on
migrate. What remains is the optional **backfill of a bean per legacy expense** from its note (steps 3 to 5),
a one-off prod-ops step; until it runs, legacy expenses read as `BEANS` with no linked bean, which is safe
and shows a blank bean name.

**Production reality (decision 3, inspected 2026-07-02).** Production sits at `V9` and is tiny: 7 users, one
price, three payments, and a single bean-purchase expense ("Heidelberger Partnerschaftskaffee", 1449 cents,
500 g, fully private), whose event body is the legacy shape (no `expenseType`/`beanId`). So the schema
migration is very low risk, and the note-to-bean backfill would create exactly one bean. The deploy is
rehearsed by `ExpenseTypeMigrationTest` (data module), which drives Flyway to `V9`, seeds a row and log event
shaped exactly like that production state, applies `V10`/`V11`/`V12`, and asserts the expense is typed `BEANS`
with no bean and its columns intact, `weight_grams` becomes nullable but the `ck_expenses_beans_weight` CHECK
still requires a weight for a `BEANS` row (and forbids one for `OTHER`), the new tables exist, and the
append-only log is untouched (the legacy body still carries no `expenseType`/`beanId`).

Steps (backward-compatible projection):

1. **Inspect the live data first** (decision 3): expense count and note quality. This sizes the rest.
2. **Schema** (`V10`, `V11`, `V12`) as above. `V11` makes `weight_grams` nullable and adds the nullable
   `bean_id` and `expense_type`.
3. **Derive beans from notes.** For each existing expense, normalize its note (trim, collapse whitespace,
   case-fold for matching) to a bean name; a blank note maps to a single fallback bean (for example
   "Unspecified"). Preserve the original note on the expense.
4. **Create one `CoffeeBean` per distinct normalized name**, as event-backed rows: append `CoffeeBean`
   creation events (`created_by = "SYSTEM"`) so the beans exist in both the log and the read model.
5. **Backfill the expense read rows**: set `expense_type = 'BEANS'` and `bean_id` to the derived bean for
   every existing expense (a data-only update to the read table; the legacy event bodies are left untouched
   under the recommended approach).
6. **Make projection backward compatible** so a full events-to-data rebuild still works: the `Expense`
   deserializer defaults a missing `expenseType` to `BEANS`, and the projector, for a legacy expense body
   with no `beanId`, derives the bean from the (normalized) note and resolves it against the read model.

**The rebuild-ordering wrinkle** (only for the deferred legacy backfill). The events-to-data rebuild replays
strictly in `seq` order (`EventsToDataRunner`, keyset batches). For **new** data this is already correct and
tested: recording a bean purchase creates the bean in the same transaction *before* the expense, so the bean
event has the lower `seq` and projects first. The wrinkle appears only if the legacy backfill (steps 3 to 5)
introduces a `CoffeeBean` event with a **higher** `seq` than the legacy expense that references it. When that
backfill is done, give beans priority in the rebuild: a first pass that projects all `COFFEE_BEAN` events,
then the normal seq-ordered pass for everything else (a legacy expense that resolves its bean by note then
finds the derived bean already present). This keeps the read model a pure projection of the log.

## Verification

- On a production clone (decision 3): capture every user balance and the kitty balance **before** the
  migration, run it, and assert they are **unchanged after** (beans and ratings add no money, so balances
  must be identical). This guards the expense changes.
- Run a full events-to-data rebuild on the clone and assert it reproduces the same beans, expenses,
  balances, and ratings (the log-plus-projection round-trip).
- Automated tests:
  - Expense-type validation: `BEANS` requires bean and weight; `OTHER` forbids both; the split and
    non-negative rules still hold.
  - Bean create, rename, and merge (uniqueness on active names; a tombstone resolves to canonical; a freed
    name is reusable).
  - Rating only while cancellable (a 409 past the grace period, matching Undo), and the prompt prefill.
  - A second rating within the same window updates the one vote (no duplicate); a new cup's window starts a
    new vote; undoing the cup deletes that window's vote while votes from earlier cups remain (decision 1).
  - Legacy expense-event replay through the backward-compatible projector, including the bean-first rebuild.
  - Frontend: the rating flow (add a coffee, rate, see it reflected), the expense `BEANS`/`OTHER` toggle and
    bean autocomplete, and the ratings edit mode (rename and merge).

## What this deliberately does not do

- It does not put an event `seq` or id into `CancellableIncrement`, the domain models, or any user-facing
  response.
- It does not rewrite the append-only log (decision 2); the log stays immutable and beans are added as new
  events.
- It does not add per-bean or per-cup **pricing**; the global price is unchanged. Ratings are qualitative
  only and never touch a balance or the kitty.
- It does not build a background job to "commit" ratings; a rating is valid the moment it is written, and the
  grace period is enforced server-side at write time.
- It does not serialize bean creation across concurrent purchases. If two purchases name the identical
  brand-new bean in the same instant, one insert loses the unique-name index and gets a clean, retryable 409
  (the whole write rolls back atomically, leaving no orphaned event or row); a retry then resolves to the
  now-existing bean. Resolving the race in code would require committing the bean's creation event in a
  separate transaction from the expense that names it, which would break the one-transaction-per-request
  event-sourcing model for a collision that needs two people to first-record the same new name within a
  millisecond. So the race is accepted; `CoffeeBeanServiceImpl.resolveOrCreate` documents it.

## Implementation status (2026-07-02)

The feature is implemented on `feature/coffee-ratings-bean-catalog` against the decisions above, and the full
`gradle build` is green (compile, all backend tests, ktlint, detekt, the coverage gate, and the frontend
lint/build), as is the frontend build, lint, Prettier, and knip.

- **Backend, complete and tested.** The bean catalog (`CoffeeBean`), ratings (`CoffeeRating`), typed
  expenses, and ratings, all wired through the existing event-sourcing machinery. New domain unit tests
  (`CoffeeBeanServiceTest`, `CoffeeRatingServiceTest`, and the reworked `ExpenseServiceTest`) and a
  `RatingSystemTests` HTTP integration suite cover the flows, alongside the unchanged existing suites.
- **Frontend, complete.** The rating control on the landing, the `BEANS`/`OTHER` expense toggle with bean
  autocomplete (landing and admin), the dual-mode ratings page and its routes, and the interceptor's
  dual-audience handling of `/api/beans`.
- **Reviewed.** Two adversarial reviews (backend and frontend) and an AI-slop pass ran over the diff. The
  backend review found no high or medium defects and two low concurrency items: the rating write now takes
  the per-user lock **before** deriving the cup window (so a concurrent undo cannot leave a vote for an
  undone cup), and the bean-creation race is the accepted, documented limitation above. The frontend review
  found and fixed two real bugs: the interceptor never sent the capability token to `/api/beans` (so user
  bean reads 401'd), and the landing loaded beans before registering the token.
- **Post-review refinements.** A later pass found and fixed one more real defect: re-rating a cup with a
  *different* bean rewrote the previously referenced bean's row rather than repointing the key (the read-model
  entity mappers deep-updated the `@ManyToOne` parent), which the bean-name unique index then rejected. The
  fix maps read-model associations as foreign-key references (see the Data section), with a new
  `RatingSystemTests` case for the bean switch. The ratings table also gained column sorting (name, rating,
  votes) and half-bean icons for fractional averages, and the rejected-rating toast now surfaces the server's
  specific reason. Finally, the feature was unified under the name *ratings*: the page title is the single
  word *Ratings*, and the route (`/ratings`), endpoint (`/api/beans/ratings`), DTO (`CoffeeBeanRatingsDto`),
  and service method (`ratings()`) were renamed from `ranking` to match.
- **Local dev and migration rehearsal.** The dev demo data now seeds a spread of ratings across the bean
  catalog (`DevDemoDataLoader.seedRatings`, ten votes over five beans, seeded straight through the rating
  data service since demo cups are recorded as the `SYSTEM` actor and so have no cancellable window), so the
  ratings page comes up populated on a fresh dev start. `ExpenseTypeMigrationTest` rehearses the production
  deploy against a `V9` production-shaped database (see the migration Status above).
- **Deferred to prod-ops.** The legacy-expense note-to-bean backfill (steps 3 to 5 above). Production holds a
  single legacy expense, so this creates one bean; until it runs, that expense reads as `BEANS` with no
  linked bean.

## Assumptions

- Existing expenses are bean purchases with the best available bean name in their note.
- Bean names are unique among active canonical beans.
- Merging preserves all ratings and expenses by resolving merged beans through the canonical target.
