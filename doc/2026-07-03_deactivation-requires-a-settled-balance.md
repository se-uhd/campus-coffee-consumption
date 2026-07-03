# Deactivating a user requires a settled balance (2026-07-03)

This note records a guard that stops an admin from deactivating a user who still **owes the coffee fund**.

A user's **balance** is a prepaid-card figure where **negative means they owe the fund** (a coffee `+1`
lowers it by the price, and a deposit or a private bean purchase raises it). Until now an admin could flip a
user inactive from the `/admin/users` table at any time, with no check on that balance. A user deactivated
(or one who leaves) while their balance is negative strands an unpaid debt. They can no longer add coffees
or be chased through the app, and their negative balance just sits there.

We already refuse to **hard-delete** a user who has any financial history (a non-zero count, or any expense
or deposit). The delete returns `409` and the UI says "deactivate them instead". This change adds the
missing sibling guard on the other exit, refusing a deactivation while the user still owes money so the debt
is settled first. Settling up is what it already is elsewhere in the app, where an admin records a
**deposit** into the kitty (`POST /api/kitty/deposit`), which credits the user and raises their balance to
zero.

## Decisions

- **Hard block, enforced in the domain (not just the UI).** The guard lives in `UserServiceImpl.update`,
  the single place every user update flows through, so the API cannot be talked around by a direct call.
  It mirrors the existing last-active-admin guard, which already blocks a deactivation there with a `409`.
  A frontend-only warning would leave the invariant unenforced.
- **Debt only (`balance < 0`).** The guard fires only when the user actually owes the fund. A user at
  exactly zero deactivates freely, and so does one with a **positive** balance (the fund owes *them*). That
  is not a debt to collect, and blocking it would trap money the other way.
- **Only on a genuine active to inactive transition.** Reactivating a user, or an admin re-saving an
  already inactive user's profile, is never blocked. So a user who owes money can still be reactivated, and
  an admin can still edit a deactivated debtor.
- **The escape hatch is a deposit.** There is no debt write-off operation. The admin settles the balance
  with a deposit and then deactivates. A future "deactivate anyway, write off the debt" override could be
  added if the group ever needs it, but it is deliberately out of scope here.

## Backend

- **`UserServiceImpl`** gains the existing `BalanceDataService` port as a constructor dependency and a small
  private guard `requireBalanceSettledForDeactivation(userId)`. In `update`, right after the last-active-admin
  check, it runs the guard only when `existing.active != false && newActive == false` (the real
  active to inactive transition). The guard reads `balanceDataService.userBalanceCents(userId)` and throws
  `ConflictException` (which already maps to `409`) when it is negative.
- **`userBalanceCents` is the maintained `user_balance` projection**, the same source the admin overview
  (`GET /api/users/overview`) trusts, so the guard and the number the admin sees in the table cannot
  disagree. That projection is recomputed inside every balance-moving write's transaction (including a
  coffee `+1`), so by the time an admin deactivates, it reflects the committed balance.
- **No new exception, DTO, endpoint, or migration.** `ConflictException` is reused (the same type the
  last-active-admin guard throws), and the euro amount is kept out of the domain message, following the
  cents-only rule (the UI formats the euros).

## Frontend

The `/admin/users` table already carries each row's `balanceCents` (merged from the overview) and already
flags a negative balance in red, so the SPA can catch this before the round-trip.

- **`AdminUsersComponent.toggleActive`** now receives the whole `UserRow` (not just the `UserDto`). When the
  admin is deactivating a row whose `balanceCents < 0`, it opens the shared `ConfirmDialogComponent` titled
  "Settle the balance first", naming the owed amount (via `formatEuros`) and offering "Go to deposit", which
  routes to `/admin/kitty`. It does **not** call the update in that case.
- **The slide-toggle is reverted explicitly.** The toggle binds one-way (`[checked]="row.active"`) and has
  already flipped visually by the time the handler runs, so both the guard branch and a failed update put the
  switch back through the change event (`toggle.source.checked = row.active`). A `reload()` cannot do this,
  because the table reuses the row DOM under `trackBy`, so the unchanged `row.active` never re-fires the
  `[checked]` binding.
- **Belt and suspenders:** if the balance changed between the overview load and the click and the backend
  still returns a `409`, the catch reverts the switch and surfaces the backend's own reason via
  `errorWithServerReason`. That reads correctly for either `409` the toggle can hit (a stale-cache debt or the
  last-active-admin guard), so neither is mislabeled, and it falls back to a generic message for any other
  failure.

## Tests

- **`UserServiceTest`** (domain, mocked ports). Deactivating a user whose `userBalanceCents` is negative
  throws `ConflictException` and never calls `upsert`. The transition condition is pinned by the allowed
  cases, so a zero or positive balance deactivates, reactivating a debtor is allowed, and re-saving an
  already inactive debtor never even consults the balance.
- **`UserAdminSystemTests`** (full stack over Testcontainers). Booking one coffee at the seeded 50-cent
  price drives a user to `-50`, and `PUT /api/users/{id}` with `active=false` then returns `409`. Recording
  a matching 50-cent deposit clears the balance and the same deactivation then returns `200`. The existing
  pristine-user deactivation test (a zero balance) still returns `200`.
- **Playwright e2e** covers the admin path. Toggling a debtor shows the "Settle the balance first" dialog and
  the switch snaps back instead of deactivating. After a deposit clears the balance, the same switch
  deactivates the user.
