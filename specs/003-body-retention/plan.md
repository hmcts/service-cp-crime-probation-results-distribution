# Implementation Plan: Body Retention

**Branch**: `003-body-retention` | **Date**: 2026-10-22 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-body-retention/spec.md`

## Summary

A single daily scheduled job keeps `outbound_body` (partitioned by 002's V1 migration) supplied with
somewhere to put each new body and clear of bodies past their retention. Every run first makes sure
the current UTC month and the next two each have a partition (creating any that is missing, V1's
naming and bounds, refusing and logging `partition-overlap` rather than clobbering a same-ranged
partition under another name), then removes each whole partition whose month ends on or before the
cut-off (`distribution.body-retention` months before the current UTC month) and deletes stranded
`DEFAULT`-partition rows older than that same cut-off — `body_digest` and `service_version` stay on
the attempt row forever regardless (H20). It is not a batch job over the request pipeline, not intake,
and touches no leg, message or settlement — it is infrastructure maintenance on a table the pipeline
already writes. A single-runner advisory lock (`pg_try_advisory_xact_lock`) serialises concurrent
pods; each creation or removal is a plain `CREATE`/`DROP` in its own short transaction, never the
lock's; partition bounds are read from the PostgreSQL catalogue, never parsed from a name. Per the
2026-10-22 clarification (Option C+D), the job also publishes two signals independent of whether it
has ever run or is currently running: a continuously-updated gauge of `DEFAULT`-partition row count
(`probation_body_default_partition_rows`, satisfied today by `BodyMaintenanceMetrics` from slice 002
but re-scoped here to run on its own collection cycle, not only inside a job run) and a new gauge of
time elapsed since the job's last successful completion
(`probation_body_job_last_success_age_seconds`), alerted at ~2 days regardless of whether any row has
ever reached `DEFAULT` — so a job that stops running is visible even when the misrouting symptom
(FR-003-023) hasn't yet appeared.

## Technical Context

**Language/Version**: Java 25, Spring Boot 4.1 (same toolchain as 001/002; no new language feature
needed beyond what the repo already builds with)
**Primary Dependencies**: Spring's `@Scheduled` (cron, `zone = "UTC"` per design row A3-64/H35) run
through the existing `TaskScheduler`; `JdbcClient` and a raw `DataSource` connection for the advisory
lock (see Research, "Lock and transaction strategy"); Micrometer (`MeterRegistry`, already wired by
`config`) for the job's gauges and counters — no new external library
**Storage**: PostgreSQL 15 (production's version, row A3-105) — the existing `outbound_body` table and
its monthly + `DEFAULT` partitions from `V1__create_processed_log.sql`; this slice adds no new table,
only partition DDL executed at runtime and one Flyway migration if a maintenance-run audit column or
index proves necessary during implementation (see Research)
**Testing**: JUnit 5 + Mockito + AssertJ; Testcontainers (`PostgreSQLContainer`) for `BodyRetentionJobIT`
(AS-12) exercising real partition CREATE/DROP against a throwaway schema; no Service Bus or Redis
involvement — this feature has no queue, cache or gateway dependency
**Target Platform**: the existing AKS deployment of this service — no new pod, container or Helm
values; the job runs in-process on every replica, serialised by the advisory lock
**Project Type**: single Spring Boot service (extends the existing `service-cp-crime-probation-results-distribution`
codebase; no new module)
**Performance Goals**: one run per UTC day; each partition statement bounded by a 5 s `lock_timeout`
(H35); the whole run is expected to complete in low seconds (a handful of DDL statements), nowhere
near the per-leg 215 s send budget this feature doesn't touch
**Constraints**: must never hold an `ACCESS EXCLUSIVE` lock for longer than one statement needs (H35);
must never block the per-leg pipeline's writes to `outbound_body`/`outbound_message`; idempotent and
safe to run concurrently across replicas (only one proceeds, via the advisory lock); introduces at
most the two `distribution.*` properties FR-003-027 allows (the default-when-unreadable property is
unrelated — this slice's own property surface is the retention window and the maintenance cron); no
stored body, digest mismatch, or defendant PII ever appears in a log line or a metric label (DV-09,
row A3-60)
**Scale/Scope**: O(1) partition operations per run — create at most the few months not yet covered
(normally zero; one a month in steady state) and drop at most the few months now past retention
(normally zero or one); the `DEFAULT`-partition read and the last-success-age gauge are O(1)
catalogue/registry reads on their own collection cycle, independent of run count

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Applicability to this slice | Assessment |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | The legacy function app stores no body at all — there is no legacy behaviour to port, and nothing this job does is visible at the HMPPS boundary. Spec's own "Not a deviation-register event" framing (constitution Principle I) | **PASS** — not applicable in substance; no DV row added or changed |
| II. Test-Driven Development | Applies in full: every task in `tasks.md` must land its test before its implementation, with an observed RED run, same discipline as 001/002 | **PASS at task gate** — enforced by `tasks.md` generation and the TDD workflow rule, not by this plan directly |
| III. Message-Contract First | This job never reads `probation.requests`, never sends to HMPPS, and defines no message or body contract of its own — it is pure maintenance on a table the pipeline already owns | **PASS / N/A** — no contract introduced or changed |
| IV. Canonical JSON In, Typed Models Out | The job never touches a hearing payload or a `JsonNode`; everything it originates (the maintenance step, the removal cause, the run outcome) is a typed enum/record with a bounded value set, consistent with `BodyMaintenanceStep` already introduced in 002 | **PASS** |
| V. SOLID with Ports and Adapters | This job is **not** part of the per-leg pipeline and has no port to swap (`HearingPayloadSource`/`YouthFilterSource`/`HmppsSender`/`OutboundLog` are irrelevant here). Design §4.4 explicitly deferred where this job's package sits ("the first spec that needs the job places it") — this plan makes that call (see Project Structure) | **PASS** — the constructor-injection and single-responsibility spirit is kept even without reusing the pipeline's port set |
| VI. Explicit Failure | No exception is ever caught and ignored; a failed partition statement is logged at ERROR, counted (`probation_body_maintenance_failures_total{step}`), and retried on the next day's run, never silently skipped. The per-leg settlement and readiness rules this principle also states are N/A — this job settles no message and gates no readiness | **PASS** |
| VII. Privacy in Telemetry | Directly engaged: partition names, months, row counts and step/cause codes only — never a stored body or digest content — in any log line or metric label (DV-09, row A3-60); the spec's FR-003-024 already states this explicitly | **PASS** |
| VIII. Estate Conventions | Gradle, Flyway (if a migration proves needed), PMD/Checkstyle/JaCoCo gates, `uk.gov.hmcts.cp.probationresultsdistribution` package root, Conventional Commits, SLF4J/Logback JSON logging — all as established | **PASS** |
| IX. Scope — HMPPS Leg Only | This job is explicitly outside the per-leg pipeline: no youth-switch read, no VEP code, no second cutover lever. It operates on a table the HMPPS leg writes to, nothing more | **PASS / N/A** — no scope creep |

No violation requires an entry in Complexity Tracking.

**Post-Phase-1 re-check**: the design decisions in Research and Data Model — the new `maintenance/`
package, the direct `DataSource.getConnection()` use for the advisory lock, and catalogue-based bound
reading — introduce nothing that revisits this table. The direct-connection use is confined to
`maintenance/`, never `application/` (design_rules.md's JDBC-import prohibition names `application/`
specifically, for the per-leg pipeline's ports-only rule); `maintenance/` has no equivalent
prohibition because it has no ports to keep pure. No new violation found; Complexity Tracking stays
empty.

## Project Structure

### Documentation (this feature)

```text
specs/003-body-retention/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

No `contracts/` directory: this feature exposes no interface to a user or another system (constitution
Principle III is N/A here — see Constitution Check row III). Its only "contract" is with the schema
002 already owns (`outbound_body`'s partitioning and the `body_digest`/`service_version` columns),
which `doc/API_CONTRACTS.md` and 002's own `data-model.md` already document; this slice adds no new
one. Per the command's own Phase 1 guidance ("Skip if project is purely internal"), `contracts/` is
skipped for this slice.

### Source Code (repository root)

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── maintenance/                          # NEW — this slice's package (design §4.4 deferred this
│   │                                        choice to "the first spec that needs the job"; see
│   │                                        Research "Package placement" for the rationale)
│   ├── BodyRetentionJob.java              # @Scheduled entry point: acquires the advisory lock,
│   │                                        orchestrates the run, never touches SQL directly
│   ├── DatabaseClock.java                 # SELECT (now() AT TIME ZONE 'UTC') — "now" per FR-003-005,
│   │                                        never the JVM's or the session's own clock
│   ├── SingleRunnerLock.java              # the lock primitive: a raw DataSource connection held
│   │                                        open across the whole run for pg_try_advisory_xact_lock,
│   │                                        separate from every statement's own transaction
│   ├── PartitionCatalogue.java            # reads outbound_body's partitions and their bounds from
│   │                                        pg_catalog (never parses a partition name); its own 5s
│   │                                        lock_timeout in a short transaction, same as every
│   │                                        other statement (research.md, 2026-10-22 finding)
│   ├── PartitionBound.java                # record: partition name + bounds, or DEFAULT's own marker
│   ├── PartitionMaintenanceRepository.java # the short, separately-transacted DDL/DML statements:
│   │                                        CREATE a missing month's partition, DROP an expired
│   │                                        one, DELETE stranded DEFAULT rows, COUNT DEFAULT rows
│   ├── MaintenanceMetrics.java             # owns all five of this slice's own metrics directly
│   │                                        against the raw Micrometer MeterRegistry — NOT through
│   │                                        application/OutcomeMetrics (see research.md "Metrics
│   │                                        ownership", 2026-10-22 update). Three counters
│   │                                        (created/dropped/failures) incremented by
│   │                                        BodyRetentionJob; two function-gauges (DEFAULT rows,
│   │                                        last-success age) computed live at Micrometer scrape
│   │                                        time — no separate @Scheduled collector needed
│   ├── MaintenanceRunOutcome.java          # record: per-step results, for logging/metrics only —
│   │                                        no table rows (the design's "three tables and one view"
│   │                                        in persistence/ is unchanged by this slice)
│   └── package-info.java
├── domain/
│   └── MaintenanceFailureCause.java        # NEW enum (FR-003-017, AS-14) — alongside the existing
│                                             BodyMaintenanceStep.java, reused from slice 002, never
│                                             redefined here
├── config/
│   └── (existing) — wires the @Scheduled trigger's cron/zone property, the two new properties
│                     (distribution.body-retention, distribution.body-maintenance.cron — already
│                     present in application.yaml from 002's forward-looking scaffold, not yet bound
│                     into DistributionProperties/StartupValidator) and the startup validation that
│                     rejects an invalid value; no new port/adapter selection needed
└── persistence/
    └── (existing, unchanged) — package-info's existing reference to "the daily body-retention job"
                                 now resolves to maintenance.BodyRetentionJob; no code there changes

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── maintenance/
│   ├── BodyRetentionJobTest.java               # unit, plain mocks, no Spring context (Principle V)
│   ├── BodyRetentionJobIT.java                 # Testcontainers Postgres — AS-12
│   ├── BodyRetentionRecoveryIT.java            # the User Story 6 DEFAULT-spill recovery procedure
│   ├── DatabaseClockIT.java                    # Testcontainers Postgres
│   ├── SingleRunnerLockIT.java                 # Testcontainers Postgres
│   ├── PartitionCatalogueIT.java               # Testcontainers Postgres
│   ├── PartitionMaintenanceRepositoryIT.java   # Testcontainers Postgres
│   ├── MaintenanceRunOutcomeTest.java          # unit
│   ├── DefaultPartitionGaugeIT.java            # Testcontainers Postgres
│   ├── LastSuccessAgeGaugeTest.java            # unit
│   └── MaintenancePrivacyTest.java             # reused pattern from 002's LogStatementSweepTest
└── config/
    └── BodyRetentionPropertiesTest.java        # unit — the retention/cron validation matrix
```

**Structure Decision**: a new top-level package, `maintenance/`, sibling to `persistence/`,
`application/`, `domain/`, `adapter/` and `config/`. Rejected: putting this in `persistence/` —
that package's `package-info.java` frames itself around the per-leg write path (`processed_request`,
`outbound_message`, the idempotency guard's store probe) and reusing it here would blur a
responsibility the design never assigned there. A new package keeps the one scheduled task the
service runs fully separate from the message-driven core, matching Constitution Principle V's
single-responsibility intent even though this job has no port to implement. `persistence/package-info.java`'s
one outdated line ("Whole months are dropped by the daily body-retention job") is left factually
correct as-is; it names the job's existence, not its package, so no edit is needed there.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No violations — table intentionally omitted.

<!-- token-budget: compacted (level=medium) on 2026-10-22T18:33:26Z; original at plan.full.md -->
