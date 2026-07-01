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
  *(Superseded by the 2026-07-01 update below: the toggle is now a single live switch in the read-only details
  that saves on its own, and the pencil's edit mode covers only name/email.)*

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

## Update (2026-07-01): the landing-panel toggle is a live switch

The panel control moved out of the pencil's edit mode. Instead of the two-copy design above (a disabled
read-only mirror plus an enabled copy inside the edit form, saved by `save()`), the read-only "Your details"
view carries a single **live** Balance / Cups switch, labeled **"Show"**, as a row in the details grid so its
label lines up with the field labels and its toggle starts at the value column. The pencil's "Edit your
details" mode is limited to the actual user details (first name, last name, email) and no longer carries the
panel toggle. The switch is hidden while editing. This matches what the pencil's tooltip already implied and
removes the awkwardness of opening the name/email editor just to change which card the landing shows.

Flipping the switch **saves on its own**: `onPanelChange` calls the same `PUT /api/profile` (user) or
`PUT /api/users/{id}` (admin) through a shared `persistProfile(profile, fields)` helper, then confirms with a
message naming what the landing now shows ("Now showing coffee stats on the landing page." / "Now showing the
balance on the landing page."). These details make it safe:

- **It re-sends the shown profile's name/email unchanged with the new panel.** The switch appears only in the
  read-only view, so those fields are the last-saved values, and the flip only moves the panel.
- **The in-flight save is pinned to the profile object captured at entry.** The admin's user-select dropdown
  stays enabled, so a switch mid-request would otherwise let one user's stale response repaint another's
  view. Both the success and the failure branch bail unless `this.profile` is still that captured object,
  mirroring the id-guards the sibling `CoffeeLandingComponent` already enforces.
- **A flip and a name/email save cannot overlap.** The switch is hidden while editing, so they sit on separate
  screens, and they also share the `busy` flag, which blocks a name/email save from starting while a flip is
  still in flight (both issue a full-profile PUT, so overlapping them could clobber values).

The toggle binds one-way with `[ngModel]` + `(ngModelChange)` (not `[value]` + `(change)`): because
`MatButtonToggleGroup` is a `ControlValueAccessor`, an `[ngModel]`-controlled group repaints its selection
when the on-failure revert writes the previous value back, which a plain `[value]` group would not. The
accessible name is `aria-label="Show landing panel"` so it contains the visible word "Show" (WCAG 2.5.3).
Backend, DTOs, and the persisted `summaryPanel` are unchanged. Only the frontend interaction changes.
