# One landing component for users and admins, and admin parity for a selected user (2026-06-30)

This note records the unification of the landing page. Previously the user landing (`/login/:token`) and the
admin landing (`/admin`) were two separate Angular components that had drifted apart: the user landing could
add a coffee, undo a recent one within the grace period, and record a simple bean purchase, while the admin
landing (a different component) could step a count by `+1`/`-1` and set an absolute total, but had no undo and
no expense form. An admin viewing a user therefore could not do, in place, what that user could do for
themselves.

The goal: **one configurable landing component** serving both audiences, where the only differences in admin
mode are the user-selection dropdown (as the first card) and the admin-only count tools. This mirrors the
pattern the profile page already uses, where a single `ProfileComponent` serves both `/login/:token/profile`
and `/admin/profile`, differing only by the dropdown.

## The frontend: one dual-mode component

`CoffeeLandingComponent` (`frontend/src/app/pages/coffee-landing/coffee-landing.component.ts`) now serves both
routes; the separate `admin-landing` component is removed and `/admin` points at this one. The mode is read
from the route exactly as the profile does: a capability token in the path means user mode, its absence (the
`/admin` route) means admin mode.

Both modes render the same blocks: the count with a `+1` hero, the price per cup, the subject's balance, the
read-only kitty balance, an "undo last cup" affordance when the most recent coffee is still cancellable, a
simple bean-purchase form, and the unified activity feed. Admin mode adds, and only adds:

- the user-selection dropdown as the first card (the selected user is URL state via the `?user=` query param
  and the shared `AdminSelectionService`, so Back/Forward traverse the selection, identical to the profile and
  the other admin pages); and
- the admin-only count tools the admin keeps: a single `-1` step and an absolute count correction (a new
  total with an optional note).

Both modes are driven by **one** `UserSummaryDto`: the user's own `GET /summary`, or, in admin mode, the new
per-user `GET /users/{id}/summary`. So the displayed money is always the server's authoritative figure (in
admin mode the balance is now read from the summary rather than re-derived from the newest activity row), and
only the count moves optimistically before each action's response reconciles it. The user-mode behavior is
unchanged, including the bounded retry on a concurrent-update `409` when the same user scans from several
devices at once.

## The backend: per-user parity endpoints

Two endpoints were added so an admin can do, for a selected user, what a user does for themselves:

- **`GET /api/users/{id}/summary`** (`AdminAccountingController`): the admin-by-id analogue of the
  self-service `GET /summary`. `AccountingService.userSummary` was already "readable by the user themselves or
  an admin", so this only needed a thin controller method. It passes `includeKittyPortion = true` (a new flag
  on `userSummary`, defaulting to the user-serving `false`) so the summary's first activity page keeps the
  kitty-funded portion of a split purchase, matching the kitty-inclusive admin per-user activity feed the
  landing pages with. The running balance is the private-only balance regardless; the flag governs only the
  displayed split detail.
- **`POST /api/users/{id}/consumption/cancel`** (`AdminConsumptionController`): an admin undoes a user's most
  recent coffee on their behalf. This required relaxing the domain `CoffeeConsumptionService.cancel`.

A simple admin expense reuses the existing `POST /api/users/{id}/expenses` with a full-private split
(`privateAmountCents = amountCents`, `kittyAmountCents = 0`), so the whole amount credits the user, exactly as
a user's own purchase does. The Expenses page remains where an admin records a kitty-funded split or corrects
a purchase.

## Relaxing `cancel`, and the money-model nuance of an admin undo

`cancel` was owner-only ("an admin uses `setTotal` instead"). It is now **self-or-admin** (reusing
`requireMaySelfMutate`, so a deactivated non-admin stays read-only), and the increment to undo is resolved by
the **owner's** login (`userDataService.getById(userId).loginName`), not the acting user's, so an admin undo
reverts the user's own cup and not the admin's. This matches how `cancellableIncrement` (the flag behind the
undo affordance) already resolves the owner.

There is one deliberate asymmetry, rooted in how the event reducer values a count change. The reducer
(`EventReducer`) classifies a coffee event as a true undo (`CONSUMPTION_CANCEL`, popping the increment's exact
stacked price) only when the event's actor is the owner (`actorOf(event) == subjectLogin(subject)`). The
attribution of an event follows the authenticated actor:

- An **owner** undo is recorded as the user, so the reducer credits it at exactly the price the cup was
  charged at; undoing nets to zero. Unchanged.
- An **admin** undo is recorded as the admin, so the reducer values it the same way as the admin `-1` step: a
  lump at the as-of price, dropping one of the owner's outstanding increments from the price stack. Within the
  short grace window the as-of price equals the increment's price, so the credited amount matches in practice;
  the only observable differences from an owner undo are that, if the price changed within those minutes, the
  credit is the current price rather than the original, and the activity row reads as a count change by the
  admin rather than as the user's "undo". Both are correct for an admin acting on the user's behalf, and the
  behavior is identical to the admin's existing `-1` step (which is why the admin undo and the `-1` step are
  intentionally redundant: the undo is the user-facing affordance, the `-1` the admin tool).

Keeping the audit trail accurate (the admin who acted is recorded as the actor) was preferred over forcing the
"exact original price" crediting by misattributing the event to the user.

## What did not change

- The user landing's behavior and the user-facing endpoints are unchanged.
- Prod is unaffected; no schema change (the read model is unchanged, and the new endpoints project from the
  existing event log and balance projections).
- The kitty-inclusive admin per-user activity feed (`GET /users/{id}/activity`) is unchanged; the new summary
  endpoint simply matches its view.

## Tests

`CoffeeConsumptionServiceTest` covers the relaxed `cancel` (an admin within the grace period decrements the
user's count, the candidate resolved by the owner's login; a non-owner non-admin is still forbidden). The
Playwright `admin.spec.ts` covers the admin landing showing a selected user the parity view (their own
cancellable coffee and its undo affordance) and recording an expense for them; the full e2e suite confirms
the user landing is unchanged under the shared component.
