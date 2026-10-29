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

## The two 2026-10-22 gauges (FR-003-031, FR-003-032)

**Decision**: both gauges are registered once, at startup, directly against the `MeterRegistry` (the
`Gauge.builder(...).register(registry)` pattern `BodyMaintenanceMetrics` already uses from slice 002)
and read a plain `AtomicLong`/`AtomicReference` updated on an **independent** `@Scheduled` collection
cycle — not inside `BodyRetentionJob`'s run — so both gauges hold a sensible value whether or not the
retention job has ever executed, is mid-run, or holds the lock:

- `probation_body_default_partition_rows` — the collection cycle runs
  `PartitionMaintenanceRepository.countDefaultPartitionRows()` (a plain read-only `COUNT(*)`, no
  lock, no row mutation, satisfying FR-003-031's explicit "MUST NOT take the single-runner lock").
- `probation_body_job_last_success_age_seconds` — computed as
  `Duration.between(lastSuccessTimestamp, now)` where `lastSuccessTimestamp` is an in-memory value
  set only on `BodyRetentionJob`'s own successful-commit path (step 1's `connection.commit()`
  returning normally) and read by the independent collection cycle; before any successful run since
  process start this is `null` and the gauge reports a sentinel the alert rule treats as "stale"
  (age effectively infinite), so a pod that has *never* completed a run pages immediately rather than
  reporting a misleadingly fresh age of zero.

**Rationale**: this is exactly the C+D combination the 2026-10-22 clarification settled on — C (the
existing per-run `DEFAULT`-row metric, re-scoped to its own cycle so it stays live even when the job
never runs) plus D (a dedicated staleness gauge independent of the misrouting symptom). Decoupling
both gauges from the job's run loop is what makes them answer "has the job stopped running at all?"
rather than only "did the job's last run see a problem?".

**Alternatives considered**: updating both gauges only inside `BodyRetentionJob.run()` — rejected,
this is the exact gap the clarification identified: a job that never fires again never updates either
gauge, so both would freeze at their last good value instead of climbing, defeating the purpose of a
staleness alert. A single combined gauge — rejected, the two failure modes (rows stuck in `DEFAULT`
vs. the job not running at all) are independent and the spec requires both signals to page on their
own terms (FR-003-023 vs. FR-003-032).

## Outstanding NEEDS CLARIFICATION

None. Every Technical Context field above is resolved from the spec's own Clarifications/Assumptions,
the design (§4.5-§4.7, H35, row A3-64), and the two implementation-mechanics decisions worked through
above.
