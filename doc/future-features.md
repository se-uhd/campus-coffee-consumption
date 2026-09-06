# Future feature ideas

A running backlog of ideas not yet built, deliberately **undated**, unlike the dated design records in this
folder (which capture decisions at a point in time). Add ideas freely; move one into a dated design record
when it is picked up and designed.

## Receipt-photo capture for expenses

When a member records a bean purchase, let them snap a photo of the receipt in the app and have the amount
(and ideally the weight and date) filled in automatically, so capturing an expense is a tap rather than
manual entry.

- On the frontend, add a camera/file input on the purchase form (the browser `capture` attribute on
  mobile) that uploads the image with the expense.
- On the backend, run an OCR step (a hosted vision/OCR API, or a small self-hosted model) that extracts the
  total, and optionally line items, then pre-fills the form for the member to confirm before saving. OCR
  only suggests the values; the member confirms them.
- For storage, decide whether to keep the image (object storage plus a reference on the expense) or discard
  it after extraction. Keeping it helps audits but adds a storage and privacy concern.
- Open questions: which OCR provider, whether to store the image, and how to handle multi-currency or
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
offer a member discount; each would extend `CoffeePrice` (or add a price dimension) and the as-of valuation.

## Consumption insights

Simple charts from the event log: cups per day/week, spend over time, the kitty balance trend, top
contributors. The append-only log already holds everything needed; this is purely a read-side addition.

## Self-hosted PostgreSQL on a virtual machine

Deferred by `adr/001-where-the-production-database-runs.md`. A container is not cheaper than the downsized
Cloud SQL instance, so it is only worth picking up if the bill does not drop as expected. The design, worked
out on 2026-09-05:

- The app stays on Cloud Run, because the `run.app` origin is printed in the wall QR codes.
- A `postgres:18-alpine` container on an e2-micro virtual machine (VM) running Debian 13 and Docker Community Edition,
  with a pd-balanced data disk bind-mounted at `/var/lib/postgresql`, the image's volume path since
  PostgreSQL 18 (a mount at the older `/var/lib/postgresql/data` is silently ignored).
- A static internal IP as `DB_HOST`, reached over Direct Virtual Private Cloud (VPC) egress through a
  dedicated `/26` subnet whose address range is the only source the VM firewall admits on port 5432 (ingress
  rules cannot match Cloud Run network tags).
- SSH only through Google's Identity-Aware Proxy (IAP), and a
  deny-all rule for everything else.
- Transport Layer Security (TLS) enforced in `pg_hba.conf` for the app role only. The superuser cannot log in
  over the network.
- Secrets fetched from Secret Manager onto tmpfs at boot, never on the persistent disk.
- Daily disk snapshots plus 6-hourly `pg_dump -Fc` to a bucket the VM can write but not delete, and a quarterly
  restore drill that replays the event log into the read tables.
- A restore that runs as the app role (otherwise later `ALTER TABLE` migrations fail on ownership), and a
  cutover with `ALTER ROLE campus_coffee_app NOLOGIN` on Cloud SQL as the write freeze, a version-18
  `pg_dump`, and a restore without `--clean` into the empty container.
- The option costs about $13 a month and gives up point-in-time recovery and managed patching. With the
  OpenTofu setup in place, the design would be a module in `infra/` rather than a shell script.
