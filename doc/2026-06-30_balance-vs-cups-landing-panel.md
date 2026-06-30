# A profile setting to swap the balance panel for a cup-stats panel (2026-06-30)

This note records a per-user display preference on the landing. The second card under the big cup count
currently shows money: the user's **personal balance** and the communal **kitty**
(`BalanceSummaryComponent`, fed by `GET /api/summary`). Some users would rather not see the prepaid-card debt
figure and prefer a friendlier, same-sized panel of pure coffee stats (most immediate first):

- *Today*: cups since local midnight
- *This week*: cups since the most recent Monday 00:00
- *Since `<date of first cup>`*: the running total

The setting lets a user choose, per account, which of the two panels renders. The big count card (cups +
price + add/undo) is unchanged in both modes; only the second card swaps.

## Decisions

- **Default = Balance.** Existing and newly created users keep today's behavior. The cup-stats view is opt-in.
- **Time windows use a calendar time zone, ISO week.** "Today" is since local midnight; "this week" since
  Monday 00:00 in `campus-coffee.summary.time-zone` (a `ZoneId`, default `Europe/Berlin`). The activity
  timestamps are UTC and are compared against these boundaries converted to UTC.
- **The preference is per user, settable by that user or an admin.** The toggle is on the shared profile, so a
  user sets it on their own profile and an admin sets it for any user (the admin profile's dropdown picks
  whom). It is the subject user's setting, and the landing honors it both for the user's own view and for an
  admin viewing that user (an admin who wants to see balances picks a user on `BALANCE` or reads the
  `/admin/users` overview).
- **The backend never branches on the preference.** `GET /api/summary` always returns both the balance fields
  and the cup-stat fields. The persisted `summaryPanel` is included only to tell the SPA which panel to render
  by default. The cup numbers are derived from the same `fullActivity` walk that already computes the balance,
  so they cost no extra query. The choice is server-persisted (it follows the user across devices), not a
  client-only toggle.

## Backend

- **`SummaryPanel` enum** (`domain/.../model/SummaryPanel.kt`): `BALANCE`, `CUPS`, a cross-layer enum like
  `Role`.
- **`User.summaryPanel: SummaryPanel?`** (nullable, the same accept-or-keep convention as `role`/`active`):
  omitted on an update keeps the stored value, and `UserServiceImpl.upsert` defaults it to `BALANCE` when a
  user is created.
  The field carries through event sourcing automatically: `User` is serialized field-for-field by Jackson and
  reconstructed generically by `ReadModelProjector`, so it needs no `EventJsonMapper`/`ReadModelProjector` edit.
- **Migration `V9__add_summary_panel_to_users.sql`**: `ALTER TABLE users ADD COLUMN summary_panel varchar(16)
  DEFAULT 'BALANCE';`. **Nullable on purpose**: the `ADD COLUMN ... DEFAULT` backfills existing rows to
  `BALANCE`, and `upsert` coalesces the value it persists to a non-null one, but an events-to-data rebuild
  replays pre-V9 `User` events whose body lacks the field (reconstructing it to `null`). A `NOT NULL` column
  would make that rebuild fail; a nullable column whose value defaults to `BALANCE` when read keeps it robust.
  This mirrors how `role`/`active` are nullable in the domain and resolved at the boundary.
- **Cup stats in `AccountingServiceImpl.userSummary`**: an injected `java.time.Clock` (deterministic under
  test) and `SummaryProperties` (the time zone) give the local day/week boundaries (converted to UTC). From the
  `fullActivity` walk it takes the `CONSUMPTION` / `CONSUMPTION_CANCEL` entries: `firstCupAt` is the first such
  entry with `delta > 0`; `cupsToday` / `cupsThisWeek` sum `delta` over entries at or after the cutoff, clamped
  at 0 (an admin down-correction never shows a negative figure). "Cups since the first" is the existing `count`.
  `summaryPanel` is `user.summaryPanel ?: BALANCE` (it falls back to `BALANCE` when the stored value is null).
  `UserSummary` carries the four new fields; `UserSummaryDto` mirrors them (auto-mapped). The `Clock` bean is
  provided by `ClockConfiguration`.
- **Profile update**: `ProfileUpdateDto` gains a required `summaryPanel` (the SPA always sends the current
  choice); `ProfileController.update` copies it onto the user. `UserDto` gains a nullable `summaryPanel` that
  round-trips on the profile read and the admin edit, so the admin profile sends it the same way.
- **Config**: `campus-coffee.summary.time-zone: Europe/Berlin` in `application.yaml` (`SummaryProperties`).

## Frontend

- **`BalanceSummaryComponent`** gains a `panel` input (`BALANCE` / `CUPS`) plus the cup-stat inputs
  (`firstCupAt`, `cupsThisWeek`, `cupsToday`). `showBalance` stays the loaded/ready gate; inside it the
  template branches: the `CUPS` panel reuses the same `mat-card.card` / `.row` markup so it matches the
  balance card's size, with a "No cups yet" empty state when there is no first cup. The "Since" date uses the
  `utcDate` pipe then `date: 'd MMMM y'` (an explicit pattern: the app registers no `LOCALE_ID`, so this gives
  an unambiguous day-month-year form rather than the US `mediumDate`).
- **`CoffeeLandingComponent`** passes `[panel]` from the summary (the subject user's choice, honored in both
  the user and admin modes) plus the three cup-stat inputs.
- **`ProfileComponent`** is the one shared component for both the user profile (`/profile`) and the admin
  profile (`/admin/profile`); the only admin addition is the user-selection dropdown at the top (the admin
  count tools, approved earlier, are the other admin-only block). The panel control is not one of those admin
  branches: the read-only "Your details" view shows a **disabled** Balance / Cups `mat-button-toggle-group`
  (a grayed mirror of the edit control, showing the current choice), and the edit form (the pencil) shows the
  same group enabled, in **both** user and admin modes. So a user sets their own panel and an admin sets it
  for the selected user. Editing is saved by the existing `save()`, which carries `summaryPanel` on both paths
  (the self `PUT /api/profile` and the admin `PUT /api/users/{id}`; the admin body sends the `id` so it
  matches the path). Both copies of the group share a `cc-panel-toggle` width rule (a fixed group width split
  into two equal halves) so the pair stays compact and balanced rather than stretching across the card.

## Tests

`AccountingServiceTest` (a fixed clock + zone) covers `firstCupAt`, `cupsToday`/`cupsThisWeek` across the
day/week boundaries, the no-cups case, the same-day undo netting to zero, the admin down-correction clamp, and
that `summaryPanel` passes through and falls back to `BALANCE`. `UserServiceTest` checks that a created user
defaults to `BALANCE` and that a later update keeps the stored value or sets a new one. A system test does
`PUT /api/profile` with `CUPS` then asserts `GET /api/summary` returns `CUPS`
plus the cup stats. Playwright e2e covers both audiences: a user toggles the panel on their own profile and
the landing swaps, and an admin sets a user's panel from `/admin/profile` and that user's admin-viewed landing
swaps. A separate e2e deactivates a user from the admin users table, guarding the admin update path that
`PUT /api/users/{id}` requires the body id on (the same path the admin panel save uses).
