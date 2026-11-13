# Alert catalogue

The human-readable mirror of `config.AlertCatalogue` (slice 006, FR-006-014 to FR-006-022). The
complete list of paging alerts this service asks the platform to deploy — no other alert is
requested, 005's proposed operations-failure counter included. This repository's input to the
ticket DevOps create the Azure Monitor rules from by hand (H36); the rules' own logic lives on
that ticket, not here. Routing is email to the owning team (the Resulting Assistant team), through
an action group, for every alert today.

Every series an alert below reads that this service itself emits is checked, by
`AlertCatalogueCoverageIT`, against the real names the service's `MetricRegistry` and
`MaintenanceMetrics` publish — a renamed or removed series fails that test. Three series are
external, named with their owner rather than claimed as this service's own: alert 1's Azure
Monitor `DeadletteredMessages` metric, alert 10's results-owned `probation_flag_read_failures_total`,
and alert 11's legacy function app telemetry.

## Paging alerts (11)

| # | Alert | Series | Condition | Threshold | Rule type | Runbook entry | Lapses |
|---|---|---|---|---|---|---|---|
| 1 | DLQ not empty | Azure Monitor `DeadletteredMessages` (external) | > 0 | fixed | metric | The dead-letter runbook (004): resend `leg-exhausted`/`exhausted`; never `validation`/`collision`; remove a replayed-and-completed original and any re-dead-lettered copy; remove `exhausted` duplicate-copy noise | — |
| 2 | Leg parked too long | `probation_oldest_parked_age` | > 30 min | fixed | log-search | Waiting for broker redelivery; on the final delivery the leg is `FAILED`, dead-lettered `leg-exhausted`; the dead-letter runbook applies | — |
| 3 | Silent drop | `probation_oldest_non_terminal_age` | > 30 min | fixed | log-search | The oldest non-terminal request: the silent-drop detector | — |
| 4 | Leg failed | `probation_leg_outcomes_total{status="FAILED", reason=~"gateway-transient\|payload-unavailable\|rules-exception\|payload-changed\|rolled-back"}`, every origin | any count | fixed | log-search | A `FAILED(gateway-transient)` after a 5xx/408/read-timeout is outcome-unknown: confirm with HMPPS before any re-send (FR-006-017) | — |
| 5 | Youth switch unreadable | `probation_youth_switch_unreadable_total{cause, defaulted}` | not zero for > 5 min | fixed | log-search | Each counted read is a hearing sent on an assumed switch value; re-check the default against the live toggle (FR-006-033) | — |
| 6 | Gateway refusing | `probation_leg_outcomes_total{reason="receiver-rejected", http=~"401\|4xx-other"}` | > 3 in an hour | fixed (H41) | log-search | The gateway is refusing the service: its credentials (401), its key or a policy (403) or its route (404). Each is terminal at once and the request `COMPLETED`; once fixed, the affected legs are `FAILED` and resend-eligible | — |
| 7 | Send re-run after a crash | `probation_crash_reruns_total` | any increase | fixed | log-search | A possible duplicate at HMPPS: record the hearing for HMPPS | — |
| 8 | Case-hearing 400 spike | `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}`, daily increase | > 2× the environment's normal daily level | proposal | log-search | The daily count itself is counted, not paged (FR-006-022) | — |
| 9 | Body partition maintenance | `probation_body_default_partition_rows`, `probation_body_partitions_ahead_months`, `probation_body_maintenance_last_success_seconds`, `probation_body_maintenance_failures_total{step}` — see [body-partition-maintenance.md](body-partition-maintenance.md) | DEFAULT not empty; any `create`/`drop` failure; fewer than 2 consecutive months ahead (H37); more than 48h since the last success (H38); any pod `-1`; or no sample | proposal | log-search | 003's own runbook (linked above) | — |
| 10 | Results switch unreadable | results' `probation_flag_read_failures_total` (external) | any increase | fixed | log-search | After cutover, each failed read is a share on neither path; reconcile and repair with `submit-hearing` while the `EXT_` payload is still in Redis | — |
| 11 | Legacy function app active after cutover | the legacy function app's own telemetry (external): HMPPS send lines, orchestration-start trace | any count > 0 after cutover | proposal | log-search | A "both": check the legacy subscription's filter and set it back to `TOGGLED-OFF` | decommissioning |

## Counted, never paged (5)

| Class | Series |
|---|---|
| Application-only 400s | `probation_leg_outcomes_total{reason="receiver-rejected", shape="application-only"}` |
| Case-hearing 400s | `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` |
| 500s recovered by a later delivery | `probation_gateway_recovered_total{after="5xx-redelivery"}` |
| 429s recovered by an in-run retry | `probation_gateway_recovered_total{after="429-in-run"}` |
| 2xx answers other than 200 | `probation_gateway_non200_success_total{status}` |

Only the case-hearing 400 spike (alert 8) pages; the other four classes are dashboard/
reconciliation counts only (FR-006-022).

## P36 (FR-006-021)

Recorded as open until the alert rules above, their routing, an owner and a runbook are deployed —
a design is not enough. Whether counting 400s instead of paging on them satisfies the legacy
alert-on-failure ticket is a question for that ticket's owner, not answered here.

## Both-paths check (alert 11, User Story 6)

Alert 11's query definition and `DailyReconciliationQuery`'s own legacy-silence check read the
identical telemetry-line definition — one definition, not two that can drift. Both end together
with the subscription and the function app's own alerts at decommissioning (FR-006-020,
FR-006-027).
