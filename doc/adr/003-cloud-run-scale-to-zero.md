# Should the Cloud Run service scale to zero?

## Decision Metadata

### Status

Accepted

### Date

2026-09-23

### Involved

* Sebastian Baltes (decision)
* Claude Code (analysis)

## Context

When `001-where-the-production-database-runs.md` was written, the app scaled to zero: from 24 August to
5 September an instance ran in 1% to 7.5% of a day's minutes. On 2026-09-06 the OpenTofu setup from
`002-declarative-cloud-setup.md` added an uptime check that requests `/actuator/health` every 5 minutes from
all six checker locations (none are selected, so all are used), which is 6 requests every 5 minutes, each
taking about 10 ms. Cloud Run keeps an idle instance for up to 15 minutes after its last request, so since
7 September one instance has run in every minute (apart from two single-minute gaps), although
`min_instance_count` is still 0.

Before that, the first scan after a quiet period waited for a cold start. Spring Boot needs 18 to 29 seconds
to start (23 seconds on average over the 56 starts in the logs, which reach back to 24 August). From 24 August
to 6 September, 42 user requests took longer than 5 seconds, up to 33 seconds, 1 to 9 on most days. There has
been none since.

The service uses request-based billing (`cpu_idle = true` in `infra/run.tf`). With a minimum of 0 instances,
an instance that is not processing a request is not billed. `europe-west3` is a Tier 2 region in Cloud Run's
price list.

## Considered Options

* Restore scale-to-zero by removing the uptime check (an empty `ALERT_EMAIL`). A sparser check does not do
  it: the sparsest allowed (three locations every 15 minutes) still sends a request every 5 minutes on
  average. Saves nothing measurable, brings the cold starts back, and loses the alert when the app or the
  database is down.
* Keep `min_instance_count = 0` and let the uptime check keep one instance warm (the state since
  2026-09-06). Only the probes are billed: about 52,000 requests a month, inside the 2 million free requests,
  and even counting each 10 ms probe as 100 ms, about 5,200 billed seconds of 1 vCPU (virtual CPU) and 1 GiB,
  under $0.20 a month at list price. The warm instance is a side effect of the monitoring, not a guarantee:
  Cloud Run may still stop or replace an idle instance.
* Set `min_instance_count = 1`. A warm instance that no longer depends on the monitoring. A minimum instance
  is billed while idle, at $0.0000035 per vCPU-second and per GiB-second in `europe-west3`, about $18 a month
  for 1 vCPU and 1 GiB, which is more than the whole database costs (about $11 to $13 a month, see
  `001-where-the-production-database-runs.md`).

## Decision(s)

The service no longer scales to zero. `min_instance_count` stays 0, and the uptime check keeps one instance
warm. This costs nothing measurable, and nobody waits 20 to 30 seconds for the first scan. A minimum instance
would buy the same effect for about $18 a month.

This does not change the decision in `001-where-the-production-database-runs.md`: the database tier and the
cap of 2 instances stay.

## Consequences

* One instance runs around the clock. Cloud Run's instance chart labels it active or idle for each one-minute
  sample, depending on whether a request arrived during that minute. It is the same instance either way.
* The uptime check now also keeps the app fast. An empty `ALERT_EMAIL` removes the check and brings the cold
  starts back.
* Cloud Run still replaces the instance now and then (four times from 10 to 19 September). Each time it
  started the new instance next to the old one and moved the requests over once the new one was up, so the
  chart showed 2 instances for 2 to 7 minutes and no request waited for a start.
* If cold starts come back anyway (user requests slower than 5 seconds in the request logs), setting
  `min_instance_count = 1` is the fallback, at about $18 a month.
