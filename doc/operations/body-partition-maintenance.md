# Body partition maintenance

The owning team handles this alert. Slice 006 owns deployed alert rules, routing and dashboards.
This entry supplies the recovery procedure and series those rules consume.

| Series | Meaning | Alert input |
|---|---|---|
| `probation_body_partitions_created_total` | Successful monthly creations | Dashboard |
| `probation_body_partitions_dropped_total` | Successful expired-month drops | Dashboard |
| `probation_body_maintenance_failures_total{step}` | Failures; step is `lock`, `create` or `drop` only — a `state` or `read` failure (below) is logged and summarised but never counted here | Any new `create` or `drop` failure; DEFAULT-delete failures count as `drop` |
| `probation_body_default_partition_rows` | Row count, read from the database whenever metrics are collected, on every pod, lock or not, at most five minutes old (a cached read) | Greater than zero, or a reading of `-1` (a failed read), or no sample in the rule's window |
| `probation_body_partitions_ahead_months` | Consecutive whole months after the current one with a partition, read the same way (five-minute cache); the job never sets it | Fewer than two, or `-1`, or no sample |
| `probation_body_maintenance_last_success_seconds` | The epoch-second timestamp of the job's last no-failure completion, kept in the database (`body_maintenance_state`, H38) and read the same way (five-minute cache) | More than 48 hours old, or `-1`, or no sample |

All three gauges are read independently of the single-runner lock and of whether the job has ever
run on this pod (A3-118): every pod reports the same reading, a stopped job is seen once the
create-ahead buffer runs out, and a failed read reports `-1` rather than going stale. The
last-success timestamp survives a pod restart because it lives in the database, not in process
memory; a skipped run or a run with any failure leaves it unchanged. Final alert rule and
cross-pod aggregation belong to slice 006.

Each trigger logs one INFO start line; a pod that does not get the lock logs one INFO skip line and
nothing else; a run that holds the lock ends with one INFO summary line naming `created`, `dropped`,
`failed`, `monthsAhead`, `defaultRows` and `durationMs` — the two counts read fresh from the
catalogue and the database, never from the cached gauges above. A run logs a WARN when it starts
with fewer than two whole months ahead, and another when it ends with no failure and fewer than
three (H37).

A failure's ERROR line gives a bounded step (`lock`, `create`, `drop`, or `state`/`read` for a
failed `body_maintenance_state` update or fresh end-of-run read), month where relevant, cause
(`lock-timeout`, `default-has-rows`, `partition-overlap`, `store-unavailable` or `other`) and
`sqlState` (the five-character PostgreSQL code, or `unavailable`). Database error text and stored
bodies are excluded from every line, at every level. Inspect partition names, UTC bounds and counts
without selecting body content. A failed statement is retried by the next scheduled run. Readiness
continues to reflect the store.

## Recover a DEFAULT spill

1. Pause intake on **every replica**. Until slice 004 provides pause/resume runners, set
   `distribution.consumer.enabled=false` and roll out that configuration everywhere. Wait for all
   in-flight sends and their outcome writes to drain. A send while DEFAULT is detached can POST
   successfully then fail to store its outcome, allowing a duplicate at HMPPS on redelivery.
2. As the service's database owner, identify a missing month and its UTC bounds using timestamps
   and catalogue queries, without copying bodies to logs or tickets. Check for a wrongly named or
   overlapping partition first. The example below is October 2026; choose the affected month.
3. Run the following in one database session. The transaction holds the same advisory key as the
   job, so a scheduled run skips while recovery is active. Each lock wait is bounded at five seconds.
   If any statement fails, `ROLLBACK` the transaction and investigate; do not resume intake with a
   detached DEFAULT. With psql, enable `ON_ERROR_STOP` first.

```text
\set ON_ERROR_STOP on
\set partition outbound_body_2026_10
\set from '2026-10-01T00:00:00Z'
\set to '2026-11-01T00:00:00Z'
```

<!-- recovery-sql:start -->
```sql
BEGIN;
SET LOCAL lock_timeout = '5s';
SELECT pg_advisory_xact_lock(140750373257219);
ALTER TABLE outbound_body DETACH PARTITION outbound_body_default;
CREATE TABLE :"partition" PARTITION OF outbound_body
    FOR VALUES FROM (:'from') TO (:'to');
WITH moved AS (
    DELETE FROM outbound_body_default
    WHERE created_at >= TIMESTAMPTZ :'from' AND created_at < TIMESTAMPTZ :'to'
    RETURNING outbound_message_id, body, created_at
)
INSERT INTO outbound_body (outbound_message_id, body, created_at)
    SELECT outbound_message_id, body, created_at FROM moved;
ALTER TABLE outbound_body ATTACH PARTITION outbound_body_default DEFAULT;
COMMIT;
```
<!-- recovery-sql:end -->

The move goes through the parent table's digest trigger: bytes must still match the attempt's
`body_digest`. The detach, create, move and re-attach are atomic together. Repeat for other affected
months if necessary; rows outside the chosen range remain in DEFAULT.

4. Verify DEFAULT is attached and inspect counts. After the next scheduled completion, the DEFAULT
   gauge is zero when all spills have been recovered, and there are no new creation failures.
   DEFAULT rows **older than the cut-off** are deleted by the next run regardless of this procedure;
   recovery does not extend retention. Request/attempt rows, digest and service version are kept.
5. Resume intake everywhere after the verification and any rollback/cutover preconditions are met.
   Until slice 004's runners exist, restore the intake configuration and roll it out. No new
   on-demand maintenance action is exposed by this slice.

## Read storage size after the first production month

Read one figure per retained monthly partition to replace the initial sizing floor. This is an
operations query, not an additional service metric:

```sql
SELECT child.relname, pg_total_relation_size(child.oid) AS bytes,
       pg_get_expr(child.relpartbound, child.oid) AS bounds
FROM pg_inherits i JOIN pg_class child ON child.oid = i.inhrelid
WHERE i.inhparent = 'outbound_body'::regclass
ORDER BY child.relname;
```
