# Where should the production PostgreSQL database run?

## Decision Metadata

### Status

Accepted

### Date

2026-09-06

### Involved

* Sebastian Baltes (decision)
* Claude Code (analysis)

## Context

The app runs on Google Cloud Run and scales to zero, so it costs almost nothing when idle. Its database is a
managed Cloud SQL for PostgreSQL 18 instance, tier `db-g1-small` (a shared core, 1.7 GB of memory), zonal,
10 GB SSD, with automated backups and point-in-time recovery (PITR). Cloud SQL bills 24 hours a day whatever
the traffic, about $30 to $33 a month at list prices for the region, which makes it the dominant line on the
bill for a coffee counter used by one research group. The question was whether to run PostgreSQL as a Docker
container instead, and how to keep the data safe in that setup.

Two constraints shape the answer. The capability URLs printed as QR (Quick Response) codes on the wall carry
the app's `run.app` origin, so the app itself must stay on Cloud Run. And the schema uses only core
PostgreSQL (jsonb, expression and partial indexes, identity columns, advisory locks), so any PostgreSQL 18
would do.

## Considered Options

* Keep `db-g1-small`. No work, no risk. About $30 to $33 a month.
* Downsize Cloud SQL to `db-f1-micro` (a shared core, 0.6 GB of memory). One `gcloud sql instances
  patch` with a restart of a few minutes (how the downsize was done on 2026-09-06, before the OpenTofu
  definition took ownership of the instance). Keeps managed backups, PITR, and patching. About $11 to $13 a
  month. The default connection limit drops from 50 to 25, so Cloud Run must be capped at 2 instances (5
  pooled connections each, and two revisions overlap during a deployment). No service level agreement,
  which was already true of `db-g1-small`.
* PostgreSQL 18 in a Docker container on an e2-micro virtual machine (VM), app on Cloud Run. Data on a
  persistent disk bind-mounted into the container, daily disk snapshots plus 6-hourly `pg_dump` backups to a
  bucket, reached from Cloud Run over Direct Virtual Private Cloud (VPC) egress at a static internal IP,
  Transport Layer Security (TLS) enforced for the app role. About $13 a month (the VM, an external IP for outbound traffic, two disks).
  Costs a data migration and ownership of backups, patching, and recovery, and loses PITR (the recovery point
  becomes up to 6 hours).
* Everything in Docker on one e2-small VM. About $18 a month plus a domain and a TLS certificate.
  Changes the origin, which invalidates every printed QR code. Rejected on that alone.
* A container on a university host. Free hosting, but Cloud Run would then reach the database over the
  public internet. Not evaluated, because no host was named.

## Decision(s)

Downsize to `db-f1-micro` now and cap Cloud Run at 2 instances. Watch one full billing month. The container
option is not cheaper than the downsized Cloud SQL and gives up managed backups and PITR, so it stays a
fallback for the case that the bill does not drop as expected. Its design is summarized in
`doc/future-features.md`.

## Consequences

* The database bill should fall by about two thirds. Verify it in Billing > Reports, grouped by SKU (stock
  keeping unit, Google's name for a billing line item).
* Cloud Run's instance cap is 2 (it was 4), declared as `max_instances` in `infra/variables.tf`. The connection
  pool of 5 per instance stays.
* Nothing changes in the application. Managed backups and PITR are kept.
* One restart of a few minutes during the tier change. The connection pool reconnects on its own.
* Rollback is setting `tier = "db-g1-small"` in `infra/sql.tf`, raising `max_instances` in
  `infra/variables.tf` with it, and deploying. The definition owns the instance since
  `002-declarative-cloud-setup.md`, so a `gcloud sql instances patch` would be reverted by the next apply.
* If the container route is picked up later, a new ADR supersedes this one.
