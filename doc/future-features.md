# Future feature ideas

A living backlog of ideas not yet built — deliberately **undated**, unlike the dated design records in this
folder (which capture decisions at a point in time). Add ideas freely; move one into a dated design record
when it is picked up and designed.

## Receipt-photo capture for expenses

When a member records a bean purchase, let them snap a photo of the receipt in the app and have the amount
(and ideally the weight and date) filled in automatically, so capturing an expense is a tap rather than
manual entry.

- **Frontend**: a camera/file input on the purchase form (the browser `capture` attribute on mobile);
  upload the image with the expense.
- **Backend**: an OCR step (a hosted vision/OCR API, or a small self-hosted model) that extracts the total,
  and optionally line items, then pre-fills the form for the member to confirm before saving. Keep the
  human-in-the-loop confirmation — OCR is a convenience, not the source of truth.
- **Storage**: decide whether to keep the image (object storage + a reference on the expense) or discard it
  after extraction. Keeping it helps audits but adds a storage and privacy concern.
- **Open questions**: which OCR provider; whether to store the image; how to handle multi-currency or
  non-coffee line items on a shared receipt.

## Settlement reconciliation / "who owes whom"

Today a positive kitty and a set of member balances are shown, but there is no guided settlement flow. A
future feature could suggest who should pay whom (or pay into the kitty) to bring everyone toward zero, and
let a member initiate their own settlement (currently admin-only).

## Full FIFO/LIFO per-cup costing

The balance values each cup at the price in effect when it was drunk, and an undo reverses the most recent
own increment at its original price. A richer model could track an explicit per-cup cost basis (FIFO or
LIFO) so that an admin count correction down, or an out-of-order undo, credits the "right" cup's price.
This is more machinery than the current group needs; revisit if pricing disputes ever arise.

## Notifications / reminders

Remind a member when their balance owed crosses a threshold, or nudge the group when the kitty runs low so
someone buys beans before the supply runs out.

## Per-member or per-bean pricing

The price is currently one global value per cup. A future variant could price by cup size, by bean type, or
offer a member discount — each would extend `CoffeePrice` (or add a price dimension) and the as-of valuation.

## Consumption insights

Simple charts from the event log: cups per day/week, spend over time, the kitty balance trend, top
contributors. The append-only log already holds everything needed; this is purely a read-side addition.
