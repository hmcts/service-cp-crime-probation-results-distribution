# Data Model: Body Retention

Phase 1 output for `003-body-retention`. This slice adds **no new table and no new column** — the
schema is 002's `V1__create_processed_log.sql`, unchanged. What follows are the entities this slice
reasons about (several are rows/structures that already exist; a few are types this slice originates
in code, never persisted as new tables).

## Entities already owned by 002 (read/acted on, never redefined)

### Stored body (`outbound_body` row)

| Field | Type | Notes |
|---|---|---|
| `outbound_message_id` | bigint (FK) | part of the composite PK with `created_at` |
| `created_at` | timestamptz (UTC) | the partition key; from the database clock, never the client's |
| body bytes | `text` | the exact UTF-8 bytes POSTed; this slice never reads, logs or reasons about its content — only its existence and its row's `created_at` |

This slice's write surface on this table is exactly two SQL shapes: `DROP TABLE <partition>` (removes
every row of a whole partition at once, atomically, as part of the partition's own catalogue entry)
and `DELETE FROM outbound_body_default WHERE created_at < :cutoff` (the only row-level DML this slice
performs, and only ever against the `DEFAULT` partition). It never `UPDATE`s a row and never touches a
named monthly partition's rows individually.

### Leg attempt (`outbound_message` row)

Untouched by this slice. `body_digest` and `service_version` remain on the row forever, regardless of
whether its `outbound_body` row has been removed (H20; User Story 2, Acceptance Scenario 1). This
slice's `PartitionMaintenanceRepository` never issues a statement against `outbound_message`.

### Monthly partition (`outbound_body_YYYY_MM`)

A PostgreSQL native range partition of `outbound_body`, bounded `[first instant of month, first
instant of next month)` in UTC. Not a Java type this slice persists — represented transiently as the
`PartitionBound` record below, read fresh from the catalogue on every run.

### DEFAULT partition (`outbound_body_default`)

The catch-all partition V1 creates. Never removed by this slice (User Story 2, Acceptance Scenario 4);
only its rows older than the cut-off are deleted, by `created_at`, never by inferring a partition it
"should" belong to.

## Types this slice originates (in-memory / log and metric fields only — no new table)

### `PartitionBound` (record)

```java
record PartitionBound(String partitionName, OffsetDateTime from, OffsetDateTime to) {}
```

Produced by `PartitionCatalogue` from `pg_get_expr(relpartbound, oid)` (see research.md, "Reading
partition bounds from the catalogue"). `from`/`to` are `null` for the `DEFAULT` partition (its bound
expression is the literal string `DEFAULT`, not a range) — `PartitionCatalogue` represents this as a
dedicated `isDefault()` predicate on the record rather than a pair of null timestamps callers must
remember to check first.

### `MaintenanceStep` (enum)

The bounded label a success or a failure is counted and logged under — the spec's own closed set
(Key Entities: "Maintenance step"):

```java
enum MaintenanceStep { LOCK, CREATE, DROP }
```

### `MaintenanceFailureCause` (enum)

The bounded, closed set from FR-003-017 — a log field on a failure's ERROR line, **never** a metric
label (the spec is explicit that only `step` is a label; `cause` stays in the log line only, to keep
the metric's cardinality bounded and avoid a label that could ever carry anything data-shaped):

```java
enum MaintenanceFailureCause { LOCK_TIMEOUT, DEFAULT_HAS_ROWS, PARTITION_OVERLAP, STORE_UNAVAILABLE, OTHER }
```

### `MaintenanceRunOutcome` (record)

Not persisted — the return value `BodyRetentionJob` hands to its own logging/metrics code at the end
of a run, and the shape `BodyRetentionJobTest`'s assertions are written against:

```java
record MaintenanceRunOutcome(
    boolean lockAcquired,
    List<String> partitionsCreated,
    List<String> partitionsDropped,
    long defaultRowsDeleted,
    List<MaintenanceFailure> failures) {}

record MaintenanceFailure(MaintenanceStep step, MaintenanceFailureCause cause, String detail) {}
```

A run where `lockAcquired == false` is the normal "another pod has it" case (User Story 3) — logged at
DEBUG, not a `MaintenanceFailure`, and not counted on the failures metric.

## State transitions

None in the per-request sense (processing-state-machine rules in `design_rules.md` govern
`processed_request`/`outbound_message`, which this slice never writes). The only "state" this slice
owns is:

- A partition's existence: **absent → present** (via `CREATE TABLE ... PARTITION OF`, for the
  current month and the next two) and **present → absent** (via `DROP TABLE`, once its month is on or
  before the cut-off). Both transitions are one-way and idempotent to re-attempt (a `CREATE` that
  finds the partition already present is simply not attempted that day; a `DROP` of an
  already-dropped partition cannot be re-attempted because the catalogue read that drives each run
  never lists it again).
- A `DEFAULT` row's existence: **present → absent**, once its `created_at` is before the cut-off, via
  `DELETE`.
- `lastSuccessTimestamp` (in-memory, process-lifetime only, feeding
  `probation_body_job_last_success_age_seconds`): **unset → set to now** on every run that reaches its
  final commit without an uncaught exception, even if individual statements inside it failed and were
  counted (a run with per-statement failures still "succeeded" at the run level for this gauge's
  purpose, since the lock was held and every statement was genuinely attempted — only a run that
  throws before reaching its commit, or a pod that never runs the job at all, leaves this gauge aging).

## Validation rules

- `distribution.body-retention` MUST parse as an ISO-8601 period expressing a whole number of months
  (FR-003-025); a non-whole-month value or an unparseable one stops startup, naming the property.
- `distribution.body-maintenance.cron` MUST be a valid six-field cron expression (FR-003-026); any
  other value, including the scheduler's "disabled" sentinel, stops startup, naming the property.
- No partition `CREATE` or `DROP` statement is attempted without the advisory lock held by the
  attempting connection's own transaction (enforced structurally — `PartitionMaintenanceRepository`
  is only ever invoked from inside `BodyRetentionJob`'s lock-held branch, never from the independent
  metrics-collection cycle, which only ever calls the read-only `countDefaultPartitionRows()`).
