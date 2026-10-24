# Research: Body Retention

Phase 0 output for `003-body-retention`. The spec's own Clarifications/Assumptions sections (AS-1
through AS-15, plus the 2026-10-22 session) already resolved almost every open question; this
document consolidates those into concrete technical decisions, and works through the two genuinely
unresolved implementation mechanics: holding the advisory lock across a long-running transaction
while individual statements commit independently, and reading a partition's bounds from the
PostgreSQL catalogue rather than its name.

## Package placement (AS-1)

**Decision**: a new package, `maintenance/`, sibling to `persistence/`.

**Rationale**: design §4.4 explicitly left this undecided — "the design's package tree places
neither the `outbound_body` writes nor the body-retention job... the first spec that needs the job
places it, keeping the dependency rules above." This is that spec. `persistence/`'s
`package-info.java` frames its responsibility around the per-leg write path (`processed_request`,
`outbound_message`, the store probe behind the idempotency guard) — none of which this job touches.
The job reads/writes `outbound_body`'s partition structure, not its rows via the pipeline's ports.
A dedicated package keeps that distinction visible in the source tree, matching Principle V's
single-responsibility intent even though this job has no port to swap.

**Alternatives considered**: placing it in `persistence/` alongside `OutboundMessageRepository` —
rejected because it would require rewriting that package's documented responsibility to admit a
second, unrelated concern (scheduled DDL maintenance vs. per-attempt row writes). Placing it in
`config/` as "just another scheduled bean" — rejected because the job has real logic (catalogue
reads, removal decisions, outcome classification) that belongs in its own unit-testable package with
plain mocks, not folded into the composition root.

## Lock and transaction strategy (AS-13)

**Decision**: the advisory lock is acquired and held on a **connection obtained directly from the
`DataSource`**, outside Spring's declarative transaction management, for the whole run; every
individual partition statement (a `DROP`, a `DELETE` of stranded `DEFAULT` rows, a `COUNT`) runs
through the existing `JdbcClient`/`TransactionTemplate` machinery already used by `persistence/`,
which transparently obtains a *different* pooled connection and commits independently.

**Rationale**: `pg_try_advisory_xact_lock` is transaction-scoped in PostgreSQL — it releases the
instant its own transaction commits or rolls back. The design requires the lock held for the whole
run (so a second pod's attempt fails fast) while each removal statement commits in its own short
transaction, so the `ACCESS EXCLUSIVE` lock a `DROP` takes is released the moment that one statement
finishes (H35) rather than held for the run's duration. A single JDBC `Connection` can only be in one
transaction at a time, so this is only achievable with **two connections in use concurrently within
one job execution**:

1. **Lock connection** — `BodyRetentionJob` calls `dataSource.getConnection()` directly (bypassing
   `JdbcClient`/Spring's transaction synchronization, which would bind the connection to the calling
   thread and conflict with wanting a second, independent connection on that same thread), sets
   `setAutoCommit(false)`, executes `SELECT pg_try_advisory_xact_lock(?)` with a fixed lock key
   constant in code (FR-003-027 forbids a `distribution.*` property for this — the key, like the
   5 s lock timeout and the "current month plus two" create-ahead window, is fixed, not configurable),
   and reads the boolean result.
   - `false` → another replica holds it; roll back immediately, log at DEBUG, exit without doing
     any work (not a failure, not counted as one — this is the designed serialisation, not an error).
   - `true` → proceed, keeping this connection and its transaction open (never committing) until
     every statement below has been attempted. On success, `connection.commit()` (releasing the
     lock) in a `finally`; on any uncaught exception, `connection.rollback()` (also releasing the
     lock), then rethrow — no run is left half-applied by the lock's own transaction either way.
2. **Statement connections** — every `PartitionMaintenanceRepository` method (drop a named
   partition, delete stranded `DEFAULT` rows older than the cut-off, count `DEFAULT` rows) runs
   through `TransactionTemplate`/`JdbcClient` exactly as `OutboundMessageRepository` already does,
   each call getting its own pooled connection and committing (or rolling back) independently of
   the lock connection and of each other. Each statement sets a `5 s` `lock_timeout` for itself
   (`SET LOCAL lock_timeout = '5s'`, scoped to that statement's own transaction) per H35; a
   statement that times out is logged, counted
   (`probation_body_maintenance_failures_total{step}`), and left for the next day's run — it does
   not fail the whole run or release the advisory lock early.

**Operational note**: holding one connection open for the run's duration (typically well under a
second, a handful of statements) removes it from the pool for that time; the existing pool size is
more than sufficient headroom for a once-a-day job and needs no change.

**Alternatives considered**: using `pg_advisory_lock`/`pg_advisory_unlock` (session-scoped, not
transaction-scoped) on a single connection, explicitly unlocking at the end — rejected because it
reintroduces the exact failure mode advisory locks are meant to avoid (a crash before the explicit
unlock leaves the lock held until the connection closes, which is less deterministic than a
transaction boundary the driver/connection-pool guarantees on return-to-pool); also rejected because
mixing session-level lock calls with Spring-managed transactional statements on the *same* logical
unit of work is exactly the ambiguity the two-connection split avoids. Running the whole job inside
one long transaction (lock plus every `DROP`) — rejected outright: it is precisely what H35 overturned,
since it would hold `ACCESS EXCLUSIVE` on each partition for the whole run instead of one statement.

## Reading partition bounds from the catalogue (FR-003-010)

**Decision**: query `pg_catalog.pg_class` joined to `pg_inherits` for the children of `outbound_body`,
reading each partition's bound expression via `pg_get_expr(c.relpartbound, c.oid)`:

```sql
SELECT child.relname,
       pg_get_expr(child.relpartbound, child.oid) AS bound_expr
FROM pg_inherits i
JOIN pg_class child ON child.oid = i.inhrelid
JOIN pg_class parent ON parent.oid = i.inhparent
WHERE parent.relname = 'outbound_body'
  AND child.relispartition;
```

`bound_expr` for a monthly partition is a string of the form
`FOR VALUES FROM ('2026-04-01 00:00:00+00') TO ('2026-05-01 00:00:00+00')`; for the `DEFAULT`
partition it is literally `DEFAULT`. The two ISO-ish timestamp literals are extracted with a small,
well-anchored regex (`FROM \('([^']+)'\) TO \('([^']+)'\)`) and parsed as `OffsetDateTime` — never a
partition's *name* is parsed for its bounds, satisfying FR-003-010 directly, and the `DEFAULT` string
is matched as a literal, not inferred from naming convention.

**Rationale**: PostgreSQL (through 15) exposes a partition's range bounds only as this
catalogue-function string — there is no separate typed system view returning bounds as structured
values. This is the same mechanism partition-management tooling in the Postgres ecosystem relies on;
parsing a fixed-format function output anchored by a regex is a normal, low-risk pattern here because
the format is controlled entirely by PostgreSQL itself (not user input), and `PartitionCatalogueTest`
pins the exact string shape this version of PostgreSQL 15 produces.

**Alternatives considered**: deriving the cut-off month purely from each partition's *name*
(`outbound_body_y2026m04`) — this is exactly what FR-003-010 forbids, because a partition's name is
an operator-visible label, not a guaranteed-accurate source of truth if one were ever created or
renamed by hand; reading `information_schema.check_constraints` — rejected, PostgreSQL's native
declarative partitioning does not express partition bounds as `information_schema` check constraints
in a directly queryable form; a third-party extension (`pg_partman`) — rejected, out of scope (no new
dependency, and the design names no such extension).

**Implementation finding (2026-10-22, during US3 implementation): the catalogue read needs its own
bounded wait too.** `PartitionCatalogue.partitions()` was originally a bare, untransacted
`SELECT ... FROM pg_inherits ...`, with no `lock_timeout`. Empirically (via a minimal raw-JDBC
reproduction, bypassing `JdbcClient`/`TransactionTemplate` entirely to isolate the mechanism), this
query genuinely blocks indefinitely when another session holds any conflicting lock on
`outbound_body` — including an `ACCESS EXCLUSIVE` hold, and, tested separately, it does *not* block
under a weaker `ACCESS SHARE` hold (compatible with another `ACCESS SHARE`). FR-003-016 only names
"each creation and each removal" as needing the 5 s `lock_timeout`, not the catalogue read — but
leaving it unbounded contradicts the design's own bounded-wait spirit (constitution Principle VI) and
is a real operational risk: User Story 6's DEFAULT-spill recovery procedure itself detaches/re-attaches
the DEFAULT partition, an operation that takes `ACCESS EXCLUSIVE` on the parent — so an operator running
that recovery while the scheduled job also fires could hang the job indefinitely, not just delay it by
5 s. Fixed by giving `PartitionCatalogue.partitions()` its own `SET LOCAL lock_timeout = '5s'` inside a
short transaction (a `TransactionTemplate`, mirroring `PartitionMaintenanceRepository`'s own
statements); a failure here now propagates as a bounded, ordinary `RuntimeException` that
`BodyRetentionJob.run()`'s existing rollback-and-rethrow path already handles correctly (the whole run
fails fast and the single-runner lock releases, rather than the job hanging and the lock never
releasing at all).

## Scheduling (AS-14, H35 row A3-64)

**Decision**: `@Scheduled(cron = "${distribution.body-maintenance.cron}", zone = "UTC")` — the
property name the spec's own Key Entities section already fixes (`distribution.body-maintenance.cron`,
six fields, read in UTC) — externalised as a validated `@ConfigurationProperties` value (startup
validator, per Estate Conventions) defaulting to a fixed early-UTC-morning time, e.g. `0 15 2 * * *`
(02:15 UTC, matching the spec's own acceptance-scenario examples). UTC is fixed in code (the `zone`
attribute), never itself a property — only the cron expression's time-of-day is configurable,
matching the pattern the design already set for `distribution.youth-switch.default-when-unreadable`
(a validated, defaulted property, not a free-for-all). The retention window is the separate property
`distribution.body-retention` (N whole months, default `P6M`, an `ISO-8601` period), validated at
startup to be a whole number of months.

**Rationale**: row A3-64/H35 states the scheduler runs with `zone = "UTC"` explicitly; fixing the
zone in code (not as a property) prevents an environment-specific misconfiguration from silently
shifting the cut-off comparison's day boundary.

## Metrics ownership: a new `maintenance/MaintenanceMetrics`, not `OutcomeMetrics` (research update, 2026-10-22)

**Decision**: `maintenance/` owns all five of this slice's own metrics directly against the raw
Micrometer `MeterRegistry` bean, in a new `maintenance/MaintenanceMetrics.java` — it does **not**
extend `application/OutcomeMetrics`/`config/MetricRegistry`, even though 002's `MetricRegistry`
already reserves four of the five metric names in its `METRIC_NAMES` catalogue
(`probation_body_partitions_created_total`, `probation_body_partitions_dropped_total`,
`probation_body_maintenance_failures_total`, `probation_body_default_partition_rows`) as forward
declarations for this slice. `MaintenanceMetrics` duplicates those four name strings as its own
private constants (identical values), plus a fifth new one,
`probation_body_job_last_success_age_seconds` (not pre-reserved — it postdates 002, born from the
2026-10-22 clarification). A unit test asserts `MaintenanceMetrics`'s five name constants are exactly
`config.MetricRegistry.METRIC_NAMES`'s four body-prefixed entries plus the fifth, so the two catalogues
can never silently drift; test code may depend on `config.MetricRegistry` for this assertion even
though production code in `maintenance/` never does.

**Rationale**: `OutcomeMetrics`'s own javadoc states it is "declared here, in `application`, by the
side that uses it" — the per-leg pipeline specifically — and its one implementation,
`config.MetricRegistry`, does not yet build an active `Counter`/`Gauge` for any of the four reserved
`body_*` names; they are name-only placeholders in the catalogue. Importing `OutcomeMetrics` into
`maintenance/` to use it would both violate this slice's own stated rule (`maintenance/` never imports
`application/`) and stretch a port documented as belonging to an unrelated pipeline. Micrometer
de-duplicates meters by name+tags regardless of which Java class registers them, so there is no
technical conflict in `MaintenanceMetrics` being the sole registrar/incrementer for these five names
while `config.MetricRegistry.METRIC_NAMES` remains the complete, documentation-level catalogue of
every series the service emits.

**Decision — both 2026-10-22 gauges are function-based, not state-polled (FR-003-031, FR-003-032)**:
rather than a separate `@Scheduled` collection cycle reading into an `AtomicLong` on its own cadence,
both gauges use Micrometer's function-gauge form — `Gauge.builder(name, holder, fn)` — where `fn` runs
*live, at scrape time* (the exact pattern 002 already uses for `probation_oldest_non_terminal_age`):

- `probation_body_default_partition_rows`: `Gauge.builder(NAME, partitionMaintenanceRepository,
  PartitionMaintenanceRepository::countDefaultRows)` — a plain read-only `COUNT(*)` run fresh on every
  Prometheus scrape, never taking the single-runner lock, satisfying FR-003-031's "read ... on the
  service's normal metrics-collection cycle" literally: Micrometer's own scrape *is* that cycle, so no
  bespoke poller is needed.
- `probation_body_job_last_success_age_seconds`: `Gauge.builder(NAME, lastSuccessRef, ref ->
  Duration.between(ref.get(), Instant.now()).toSeconds())`, where `lastSuccessRef` is an
  `AtomicReference<Instant>` `BodyRetentionJob` sets only on reaching its final commit. Initialised to
  a far-past sentinel (not `null`, to avoid a `NullPointerException` at the first scrape before any run
  has ever completed), so a pod that has never completed a run reads a very large age immediately,
  never a misleadingly fresh zero.

**Rationale**: this still delivers exactly the C+D combination the 2026-10-22 clarification settled
on, with less code — neither gauge freezes when the job stops running, because neither is driven by
the job at all; both are computed from scratch on every read. The only thing `BodyRetentionJob`
writes is the single `AtomicReference<Instant>` on success; everything else is pull-based.

**Alternatives considered**: a dedicated `@Scheduled` collector class polling into `AtomicLong` fields
— rejected as unnecessary complexity once "the service's normal metrics-collection cycle" is read as
Micrometer's own scrape rather than a bespoke polling loop; extending `OutcomeMetrics`/`MetricRegistry`
— rejected per the dependency-direction reasoning above; a single combined gauge — still rejected, the
two failure modes (rows stuck in `DEFAULT` vs. the job not running at all) remain independent and the
spec requires both signals to page on their own terms (FR-003-023 vs. FR-003-032).

## Outstanding NEEDS CLARIFICATION

None. Every Technical Context field above is resolved from the spec's own Clarifications/Assumptions,
the design (§4.5-§4.7, H35, row A3-64), and the two implementation-mechanics decisions worked through
above.

<!-- token-budget: compacted (level=medium) on 2026-10-22T18:33:26Z; original at research.full.md -->
