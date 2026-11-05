# Quickstart: Body Retention

How to exercise this slice locally once implemented. There is no HTTP endpoint and no message to
send — this is a scheduled job against the existing PostgreSQL store.

## Prerequisites

- `docker compose up -d` (starts Postgres; Service Bus emulator and Redis are not needed for this
  slice, but starting the whole stack is harmless)
- 002's Flyway migrations already applied (`./gradlew bootRun` runs them on startup, or apply via
  your usual Flyway task)

## Running the job on demand (outside its daily schedule)

The job is a plain Spring bean (`BodyRetentionJob`) triggered by `@Scheduled`; for local exercise,
either:

- temporarily set `distribution.body-maintenance.cron` to a near-future time and restart, or
- invoke `BodyRetentionJob.run()` directly from a `bootRun` debug session / a scratch `@Component`
  `CommandLineRunner` guarded by a local-only profile (not committed) — the job has no dependency on
  the message pipeline, so this requires no queue message and no users-groups/Redis stub.

## Verifying partition creation (User Story 1)

```sql
-- before: drop the newest ahead-of-need partition to simulate it missing
DROP TABLE outbound_body_2026_12;

-- run the job, then:
SELECT relname, pg_get_expr(relpartbound, oid)
FROM pg_class
WHERE relname LIKE 'outbound_body_2026\_%' ESCAPE '\';
-- expect outbound_body_2026_12 present again, bounded 2026-12-01 00:00 UTC to 2027-01-01 00:00 UTC
```

Check `probation_body_partitions_created_total` rose by exactly one on `/actuator/prometheus`.

## Verifying expired-partition removal (User Story 2)

```sql
-- seed a partition whose month is already past a 6-month retention, with a body and its attempt row
-- (use the existing test fixtures' insert pattern from OutboundMessageRepositoryIT)

-- run the job, then:
SELECT relname FROM pg_class WHERE relname = 'outbound_body_2026_02';
-- expect no row: the partition and every body in it are gone

SELECT body_digest, service_version FROM outbound_message WHERE id = :id;
-- expect the leg attempt row unchanged — body_digest and service_version still present
```

Check `probation_body_partitions_dropped_total` rose by one.

## Verifying single-runner serialisation (User Story 3)

Start two concurrent calls to `BodyRetentionJob.run()` (two threads, or two local instances against
the same database) and confirm via logs that exactly one proceeds (DEBUG "lock not acquired, skipping"
on the other) and that `probation_body_maintenance_failures_total` does not move for the skipped one —
a skip is not a failure.

## Verifying the two 2026-10-22 staleness signals (User Story 4, FR-003-031/032)

```text
# with no job ever run since process start:
curl localhost:8082/actuator/prometheus | grep probation_body_job_last_success_age_seconds
# expect a very large / sentinel value, not zero

# stop the scheduled trigger (or simply wait, in a test, past the cron) and confirm the age gauge
# climbs on its own collection cycle without a job run having occurred

curl localhost:8082/actuator/prometheus | grep probation_body_default_partition_rows
# insert a row directly into outbound_body_default, then re-scrape without running the job:
# expect the gauge to reflect the new count immediately, proving it is not frozen to the last run
```

## Running the full test suite

```bash
./gradlew test   # BodyRetentionJobTest (unit, mocks), PartitionCatalogueTest, BodyRetentionJobIT
                  # (Testcontainers Postgres) all run here — no separate integrationTest task
```

<!-- token-budget: compacted (level=medium) on 2026-10-22T18:33:26Z; original at quickstart.full.md -->
