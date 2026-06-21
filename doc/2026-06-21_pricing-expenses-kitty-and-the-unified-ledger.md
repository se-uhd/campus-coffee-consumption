# Pricing, expenses, the kitty, balances, and the unified ledger

This record describes how the consumption tracker grew a small communal-fund accounting model: a price per
cup, member-recorded bean purchases, a shared kitty, per-member balances, and one unified ledger — all on
top of the existing event-sourcing architecture, with no change to the machinery that appends an event and
projects it.

## Overview

A member used to have only a coffee **count**, and an admin "reset" it to zero after payment. Now there is
a global **price per cup** (admin-set, event-sourced, with full history), members **record their own bean
purchases**, admins manage a communal **kitty** (members pay into it via admin-recorded settlements; admins
fund purchases partly or wholly from it), and every member has a **balance** read like a prepaid card.
Consumption, purchases, and settlements appear together in one **unified ledger** with a running balance.
"Reset" is gone. Paying is a settlement (real money), and an admin count override is just a correction.

## The money model

A member's balance is a prepaid-card figure: **negative means they owe the fund**, positive means the fund
owes them. Topping up raises it; drinking lowers it.

| Event | Member balance effect | Kitty effect |
|---|---|---|
| Consumption `+1` (at the price in effect then) | `−price` | — |
| Member undoes a recent `+1` within the grace period | `+(price of that +1)` | — |
| Member records own bean purchase €X | `+X` | — |
| Admin expense: €K from kitty + €V private (buyer B) | B: `+V` | `−K` |
| Admin settlement: member M pays €S (S > 0) | M: `+S` | `+S` |
| Admin kitty adjustment €A (float/correction, signed) | — | `+A` |

So `memberBalance = privateExpenses + settlements − coffeeCost` and
`kitty = settlements + adjustments − kittyExpenses`. All money is stored as integer **euro cents**; the
read side accumulates in `Long`. There is no floating-point arithmetic anywhere. Cents travel end to end
and are formatted to euros only in the UI.

## Three new event-sourced entities

`CoffeePrice`, `Expense`, and `Payment` are logged exactly like `CoffeeConsumption`: a domain model, a JPA
read-model entity, a relational `*DataServiceImpl`, and a `@Primary` event-sourced decorator that appends a
full-state event and projects it in one transaction.

- **`CoffeePrice`** is a single global row, created once and updated in place. Every change is a full-state
  event, so the append-only log *is* the price history. There is no fixed sentinel id and no special insert
  path. The first price is created through the normal `upsert` (null id) and updated thereafter.
- **`Expense`** flattens its buyer to a `buyerUserId` in the event body and splits its total into a private
  portion (credits the buyer) and a kitty portion (draws the kitty down). The split must sum to the total,
  validated in the domain service (a 400) and backed by a database CHECK.
- **`Payment`** flattens its user to a nullable `userId`: present is a settlement (credits the member and
  feeds the kitty); null is a pure kitty adjustment (the kitty only). Payments are never edited — a mistake
  is corrected with a compensating entry.

A new **`LoggedEntityType`** enum (data layer) is the `events.entity_type` discriminator. Each constant
carries the persisted label and its domain class. The `ReadModelProjector` dispatches a `when` over it, so
the compiler forces a projection branch for every logged type, so a new logged entity can never be
forgotten. The `events.entity_type` column stays an unconstrained varchar (the log must remain extensible).
The valid set is enforced in the application.

## Valuing each coffee at the price it was drunk at, keyed on `seq`

The balance must value each cup at the price in effect when it was consumed, even though the price rises
over time. The read side solves this as an "as-of" join over the log, keyed on the event **append order
(`seq`)**, never on a wall-clock timestamp. Two facts forced `seq`:

1. There are two independent `createdAt` clocks per write (the event row's and the body's), set by separate
   `now()` calls, so they are not comparable across events.
2. The price singleton is updated in place, so every price event after the first keeps the *first* insert's
   body `createdAt`. A timestamp-keyed price history would collapse to one instant.

So `priceAsOf(seq)` is the amount of the `CoffeePrice` event with the highest `seq ≤ that seq`. A consumption
`+1` is valued at `priceAsOf` its own seq; an admin count override is valued as a single lump at the override
event's seq price (a full-state event carries no per-cup history). Wall-clock time is used only for the
cancel grace-period guard, never for valuation.

## Undo instead of a free −1

A member can no longer freely decrement. Instead they can **undo** their most recent un-cancelled own
coffee within a grace period (`campus-coffee.consumption.cancel-grace-period`, default 5 minutes). An undo
is recorded by the owner (so the event is attributed to the member) and the read side credits it at the
**exact price of the increment it reverses**, found by walking the member's own increments LIFO. This makes
undo net to zero and keeps count 0 ⇒ balance €0, and it removes the timing exploit a free −1-at-a-new-price
would have allowed. An admin still corrects any count with the absolute override (`setTotal`), valued as a
lump at the override-time price; that never touches the kitty.

## Read side: one walk over the log

There is no ledger table. `LedgerDataService` (implemented in the event-sourcing package) walks the log:

- A member's **unified ledger** is one ascending pass over their three streams — consumption, the expenses
  they bought, and the settlements they paid — keyed on the owning user id in each body (`userId` for
  consumptions and payments, `buyerUserId` for expenses), ordered by `seq`. Each entry contributes a signed
  effect and carries the running balance. Only the **private** portion of an expense affects the member's
  balance, so an admin split never leaks the kitty portion into the member's view. The member balance is the
  last entry's running balance; the API pages it newest-first.
- The **kitty ledger** is the same idea over the global payment and expense-kitty streams. The kitty balance
  is its last running value. Members see only the kitty *balance* (in their summary). The detailed kitty
  ledger is admin-only, since the movements reveal who settled and contributed.

New owner-key expression indexes (`events((body->>'userId'))` and `events((body->>'buyerUserId'))`) keep
those scans efficient.

## Deleting members preserves financial history

`expenses` and `payments` reference `users` with `RESTRICT` (not `CASCADE`), and the user service refuses to
hard-delete a member with any financial footprint — a non-zero count, or any expense or settlement — with a
409 (an admin deactivates them instead). The `coffee_consumptions` FK stays `CASCADE` because every user
always has a (often zero) consumption row, so a RESTRICT there would make no user deletable; the financial
history is preserved by the service rule, and the FK arrangement is just a backstop.

## Bootstrap

At bootstrap, user counts start at 0, the price is seeded (a `CoffeePriceStartupLoader`, idempotent), and
the kitty may hold an initial float (an admin adjustment). Because counts start at 0 and every real coffee
is added after the price is seeded, no cup is ever consumed before a price exists, so the as-of lookup
always resolves and the "no prior price" branch is a defensive error that should never fire.

## Implementation notes

A few decisions and fixes emerged while building and reviewing this, beyond the design above:

- **Ledger amounts are `Long`.** A per-entry effect and the running balance are 64-bit, so a large admin
  count override (count × price) cannot overflow.
- **An expense's buyer cannot be changed on a correction.** The member ledger keys on the buyer, so
  reassigning an expense would leave the old buyer credited and double-credit the new one. A correction
  keeps the buyer; to move an expense, delete it and record a new one. Admins find an expense to correct or
  delete via `GET /api/users/{id}/expenses`.
- **An admin count override that lowers the count trims the member's undo stack**, so a member cannot undo a
  coffee an admin removed or added, and a later undo still credits the correct price. The summary's
  `cancellable` flag is also gated on a non-zero count.
- **The `coffee_prices` table has a single-row database guard** (a sentinel column with a unique
  constraint), so even a racing double-seed across instances cannot create a second, competing price.
- **Three event-store fixes** surfaced in testing: the event `seq` is read back after insert (Hibernate
  `@Generated`), so a read in the same transaction sees it; a user delete removes the dependent consumption
  read row before the user row; and a `DELETE` event carries the owner key (`buyerUserId`/`userId`) so the
  member ledger still matches and reverses a deleted expense or settlement.

## What this deliberately did not do

- No reset-to-zero workflow (settlement and override replace it).
- No floating-point money.
- No re-pricing of a member's prior cups when the price changes (only new cups get the new price; an undo or
  an admin override is the only thing that changes a prior cup's contribution, and each is valued by its own
  rule above).

Future refinements (e.g. full FIFO/LIFO per-cup costing, settlement reconciliation, receipt-photo capture)
are tracked in [`future-features.md`](future-features.md).
