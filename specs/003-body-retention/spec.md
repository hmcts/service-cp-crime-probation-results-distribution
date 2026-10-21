# Feature Specification: Stored-body retention — the daily `outbound_body` maintenance job

**Feature Branch**: `003-body-retention`  
**Created**: 2026-10-21  
**Status**: Draft  
**Input**: Design rev 3.6 (2026-10-20; decisions H1–H34) and H35 (2026-10-21) — H20, H28, H34 and
H35; rows A3-63 … A3-65, A3-98, A3-99, A3-105 and A3-115; design §4.1, §4.6, §4.7, §4.8, §8.1,
§8.4 ask 5, §10.1, §10.2, §10.3, Appendix A and Appendix B.2 — as restated in
`doc/design/DIGEST.md` §2 (the service runs one scheduled task), §8 (`outbound_body`, "Retention
job", "Sizing floor", "Data protection"), §12 (configuration keys), §13 (metrics and alerts) and
§16 (open items). Slice plan: "daily partition job (advisory lock, create ahead, detach/drop >
P6M, lock_timeout, metrics/alerts)", built on slice 002's V1.

## Context

Every attempt that serialises a body for the HMPPS POST stores the exact bytes it sent, in
`outbound_body` (H18, H20; row A3-63). The table is created by slice 002's Flyway `V1`: partitioned
by calendar month in UTC on `created_at` — one partition per month, named `outbound_body_YYYY_MM`,
plus `outbound_body_default` for a body whose month has no partition — with the migration month and
the next two created at migration time. The bodies are personal data, youth defendants included
(row A3-60); they average at least about 24 KB, so at least about 100 MB arrives each day (H28).

The design keeps them for `distribution.body-retention` (`P6M`) and removes them by whole month, so
a body is kept 6–7 months; after that, the attempt row's `body_digest`, `service_version`, status
and metadata are what remains of "what did we send?" (H20; row A3-65). Nothing else is ever purged:
`processed_request` and `outbound_message` are kept (DIGEST §8).

This slice is the job that makes that true, the service's one scheduled task (§4.1, §4.7). Once a
day, on one pod at a time, it makes sure the coming months have a partition, removes the months that
have passed the retention, deletes DEFAULT-partition rows written before the same cut-off, and says —
in logs, counters and a gauge — what it did and what it could not do. It is not a batch job, not
intake and not part of a delivery: it never reads the queue, never sends, never settles a message,
and never changes a request or an attempt row.

**Not a deviation-register event.** The legacy function app stores no body, so there is no legacy
behaviour to keep parity with, and nothing this job does is visible at the HMPPS boundary. No DV row
is added or changed (constitution Principle I).

## Clarifications

### Session 2026-10-21

An ambiguity scan of the slice against design rev 3.6 and the digest found the points below. Each is
answered from the design; where an answer is an inference rather than a statement of the design, it
says so and names the rows it rests on. Two points could not be answered from the design. One
blocked a requirement (FR-003-011, open question 1) until the design owner answered it the same day
with option A, recorded in the design as H35 (the last point below); the other has the design's
own default, which stands, and is carried as assumption AS-15 (open question 2). Both are set out,
with their options, in `checklists/requirements.md`.

- Q: Which clock decides "now", the current month and the cut-off? → A: The database clock, read in
  UTC. The scheduler's zone (UTC) decides only when a run starts. (§4.6 and DIGEST §8: "the database
  clock is the only time authority"; A3-64 (a).) Applied in FR-003-005.
- Q: The cut-off as the design writes it, `date_trunc('month', (now() AT TIME ZONE 'UTC') -
  retention)`, carries no time zone of its own. In which zone is it compared with a partition's
  bounds? → A: As a UTC instant, the first instant of a UTC month: the design computes the cut-off
  "in UTC" (A3-64 (a)). Compared in the database session's zone instead, it shifts by that zone's
  offset; checked against PostgreSQL 15, under a UK session zone a run on 21 October 2026 would keep
  March 2026 for a month too long. Applied in FR-003-005, FR-003-009 and SC-003-010.
- Q: Is a partition whose upper bound equals the cut-off expired? → A: Yes. A month goes when it
  "ends on or before" the cut-off (A3-64; DIGEST §8: "upper bound ≤"). A run in October 2026 with
  `P6M` has the cut-off 1 April 2026 00:00 UTC: March 2026 and earlier go; April to October stay.
  Applied in FR-003-009.
- Q: What does "6–7 months" mean for one body? → A: A body goes with its month. It is never removed
  before it is N months old (N being the retention in months), and with daily runs it is removed by
  the first run of the (N + 1)th month after its own: 7 months for a body written on a month's first
  day, 6 months and a day for one written on its last (H20; A3-64). Applied in SC-003-001 and
  SC-003-002.
- Q: Does "single runner" mean one run a day? → A: No: it means never two runs at once (A3-64). A
  run that takes the lock after another has finished — another pod whose trigger fired late, or the
  next day's run — finds nothing still due and changes nothing, because creation is "if absent" and
  removal follows the bounds (A3-64). Applied in FR-003-002 and FR-003-018.
- Q: Is a pod that does not get the lock failing, and what is a `lock`-step failure? → A: Not getting
  the lock is a skip: not a failure and not counted (A3-64; §10.1). A `lock` failure is an error
  while trying to take the lock, the store being unreachable for example. (Inferred: of the outcomes
  of trying to take the lock it is the only one that is neither the lock nor a skip; §10.1's step
  set.) Applied in FR-003-002 and FR-003-003.
- Q: Is the 5 s lock timeout a property? → A: No. §8.1 lists none, and a `distribution.*` property
  not in the catalogue does not exist (DIGEST §12); the timeout is a fixed 5 s (A3-64 (b), which
  calls it the default). The same holds for the two months created ahead and for the lock's key.
  Applied in FR-003-016 and FR-003-027.
- Q: Is a statement that fails retried within the run? → A: No: on the next scheduled run
  (A3-64 (b)). Applied in FR-003-017.
- Q: Does one failed statement end the run, or undo what the run had already done? → A: Neither.
  Each statement is attempted, counted under its step and retried on its own. (Inferred from
  A3-64 (b), "each statement … is retried on the next run", and §10.1, which counts failures by
  step.) Applied in FR-003-017.
- Q: Does the transaction-scoped lock put the whole run in one transaction? → A: No. The design
  fixes the lock's kind (`pg_try_advisory_xact_lock`, so the lock lasts as long as the transaction
  that took it) and that it covers the run (A3-64); since H35 the lock stays on a transaction of its
  own for the whole run, and each removal runs and commits in a transaction of its own (AS-13 (b)).
  For the run's other statements — the creations, the DEFAULT delete and the gauge's read — the
  design does not say whether they share the lock's transaction. Both arrangements meet FR-003-002
  and FR-003-017; they differ in how long a statement's locks are held, which the lock timeout does
  not bound (it bounds only the wait). That choice is the plan's, within the constraint AS-13
  states. Applied in FR-003-002, FR-003-011, FR-003-017 and SC-003-013.
- Q: How does a run tell that a month's partition is "absent"? → A: By name, as V1 does. V1 creates
  each month with `CREATE TABLE IF NOT EXISTS` under the month's name (Appendix B.2), and A3-64's
  "if absent" is read as that rule: a month is present when a table with V1's name for it exists.
  A partition the run creates takes V1's name and UTC bounds. "Bounds, never the name" is the drop
  rule's (A3-64 (a)) and is not extended to creation. A creation that another partition overlaps, in
  whole or in part, is refused by PostgreSQL and fails like any other statement. Applied in
  FR-003-006 and FR-003-007.
- Q: Does the job move DEFAULT rows into their month, or create partitions for past months? → A:
  Neither. It deletes DEFAULT rows older than the cut-off and leaves the rest; moving them is "a
  runbook step, alerted" (A3-64; §4.8). It creates only the current month and the next two. Applied
  in FR-003-014 and FR-003-015.
- Q: When is the DEFAULT-partition gauge read? → A: After the run's delete of DEFAULT rows older than
  the cut-off, so that it shows what remains (A3-64 (c): "the DEFAULT alert still fires while any
  remain"). Applied in FR-003-022.
- Q: Which of the job's outcomes page? → A: A non-empty DEFAULT partition, or any `create` or `drop`
  failure (§10.3; H34, "body-retention job failures"; A3-115). The alert rule itself is slice 006's.
  Applied in FR-003-023.
- Q: Which retention values are "whole months"? → A: A period with no days part, years counting as
  twelve months, totalling at least one month: `P1M`, `P6M`, `P1Y` and `P1Y6M` start; `P0M`, `P30D`,
  `P6M1D`, a negative period and a value that is not a period stop startup. (Inferred from §8.1:
  "whole months, ≥ `P1M`".) Applied in FR-003-025.
- Q: What is a "valid cron", and can the job be switched off through its schedule? → A: The
  six-field form of the default `0 15 2 * * *`: second, minute, hour, day of month, month, day of
  week. The scheduler's "disabled" value is not a cron expression, and the design gives the job no
  off switch, so it stops startup like any other invalid value. (§8.1, "a valid cron expression";
  the rest inferred.) Applied in FR-003-026.
- Q: Does pausing intake stop the job? → A: No. Pause and resume act on intake (§4.8 action 4); the
  job is not intake, so the retention is kept while intake is paused, including the long pause after
  a rollback (H23; row A3-117). (Inferred.) Applied in FR-003-004.
- Q: Are `processed_request` or `outbound_message` ever purged? → A: Never (DIGEST §8; row A3-65).
  Applied in FR-003-013.
- Q: Is the design's plain-`DROP` fallback for PostgreSQL 13 needed? → A: Not for the version: the
  servers run 15 (row A3-105). But the concurrent detach the design preferred is refused on this
  table for another reason, its DEFAULT partition (open question 1), so H35 makes the plain `DROP`
  the removal on every version. Applied in FR-003-011.
- Q: What is the "cause" on a failure's ERROR line? → A: The design requires a sanitised ERROR line
  for every failure (§10.2) and counts the job's failures by step only (§10.1); it names no causes.
  This spec closes the set, as a log field and never a metric label (AS-14). Applied in FR-003-017.
- Q: Where does the job sit in the package tree, and do its two properties bind environment
  variables? → A: Neither is established by the design (§4.4; §8.1 against Appendix A; DIGEST §16),
  and neither is decided here: both are carried as assumptions AS-1 and AS-2, for `/speckit-plan`.
- Q: How is an expired month removed, given that the design's `DETACH PARTITION … CONCURRENTLY`
  then `DROP` cannot run on this table (open question 1)? → A: Option A, decided by the design owner
  on 2026-10-21 as H35, which amends A3-64 (b): a plain `DROP` of the expired partition, each `DROP`
  in its own short transaction that commits at once, under the 5 s lock timeout, so that its
  `ACCESS EXCLUSIVE` lock on `outbound_body` is held only for that statement; the single-runner
  advisory lock stays on its own transaction for the whole run (AS-13 (b) for removals); a `DROP`
  that times out is retried on the next run. The DEFAULT partition, its alert and its recovery
  stay, and V1 is unchanged. Applied in FR-003-002, FR-003-011, FR-003-012, FR-003-016, FR-003-017,
  SC-003-013 and AS-13.

## User Scenarios & Testing *(mandatory)*

The people this slice serves are the **data owner**, who has been told that bodies are held about
six months and then removed (row A3-65); the **operations team**, who are paged when the job cannot
do its work and who investigate what was sent within the six-month window (§4.8); and the
**platform team**, who size the database (H28). The job has no caller: its journeys are what each of
them can rely on, day after day.

### User Story 1 - The coming months always have somewhere to put a body (Priority: P1)

Every day the job makes sure that the current UTC month and the next two each have a partition of
`outbound_body`, creating any that is missing, with the name and the bounds V1 uses. A body written
at any moment of those months lands in its own month's partition, and none lands in DEFAULT.

**Why this priority**: it is the first part of the job anyone depends on. V1 creates only the
migration month and the next two, so a body written in the fourth month has nowhere to go but the
DEFAULT partition unless the job has run — and a month whose bodies are in DEFAULT can no longer be
created without the recovery procedure of User Story 6 (A3-64).

**Independent Test**: on a PostgreSQL 15 store migrated by V1, remove the newest month's partition,
run the job once, and assert that the three months exist with the expected names and bounds, owned
by the service's role, and that the created counter rose by exactly one.

**Acceptance Scenarios**:

1. **Given** a store with partitions for October and November 2026 only, **When** a run at 02:15 UTC
   on 21 October 2026 completes, **Then** `outbound_body_2026_12` exists, bounded from 1 December
   2026 00:00 UTC (inclusive) to 1 January 2027 00:00 UTC (exclusive); its creation is logged; and
   `probation_body_partitions_created_total` has risen by exactly one.
2. **Given** the current month and the next two already have partitions, **When** a run completes,
   **Then** no partition is created, nothing is logged as created and the created counter does not
   move.
3. **Given** a run at 02:15 UTC on 30 November 2026, **When** it completes, **Then**
   `outbound_body_2027_01` exists, bounded by 1 January and 1 February 2027 00:00 UTC: the year rolls
   over.
4. **Given** a partition under another name already covers November 2026, and no table has V1's
   name for November, **When** a run in October 2026 completes, **Then** the creation of
   `outbound_body_2026_11` is refused because it would overlap that partition, is logged at ERROR
   with the cause `partition-overlap` and is counted `step="create"`; the existing partition is left
   as it is, and the run's other statements are still attempted.
5. **Given** any partition a run creates, **Then** it is owned by the service's own database role,
   the role that will later remove it.
6. **Given** the database session's time zone, or the pod's default time zone, is one with daylight
   saving (the UK's, for example), **When** a run completes, **Then** it creates exactly the
   partitions, with exactly the bounds, that a run in UTC creates.

---

### User Story 2 - Bodies past the retention are removed, and nothing else is (Priority: P1)

Every day the job removes each partition whose whole month ends on or before the cut-off — the first
instant of the UTC month N months before the current one, N being `distribution.body-retention` in
months — and deletes DEFAULT rows written before that cut-off. Attempt rows are untouched: after the
removal an attempt still carries its `body_digest`, `service_version`, status and metadata.

**Why this priority**: it is the commitment the data owner and the DPIA were given — bodies held
about six months, then removed (row A3-65) — and the reason the table is partitioned at all (H20:
"its size will grow"). Without it, personal data is held indefinitely and storage grows by at least
about 100 MB a day (H28).

**Independent Test**: create partitions for February, March and April 2026 holding bodies (with
their attempt rows), and DEFAULT rows written in January and in September 2026 (neither month has
a partition); run the job "on" 21 October 2026 with `P6M`; assert that February and March are
gone, April remains, the January DEFAULT rows are gone and the September ones remain, every request
and attempt row is unchanged, and the dropped counter rose by two.

**Acceptance Scenarios**:

1. **Given** `P6M` and partitions for February, March and April 2026 holding bodies, **When** a run
   at 02:15 UTC on 21 October 2026 completes, **Then** February and March 2026 are removed (March's
   upper bound, 1 April 2026 00:00 UTC, equals the cut-off); April 2026 is kept; each removal is
   logged and counted once on `probation_body_partitions_dropped_total`; and every
   `processed_request` and `outbound_message` row of the removed bodies is still present and
   unchanged, `body_digest` and `service_version` included.
2. **Given** the same store, **When** a run on 31 October 2026 completes, **Then** April 2026 is
   still kept; **and when** a run on 1 November 2026 completes, **Then** April 2026 is removed. A
   body written on 1 April 2026 was held 7 months, one written on 30 April 6 months and a day.
3. **Given** a partition named `outbound_body_2026_04` whose bounds in fact cover March 2026, **When**
   the run on 21 October 2026 completes, **Then** it is removed: the bounds decide, never the name.
4. **Given** no partition covers January or September 2026 and the DEFAULT partition holds bodies
   written on 15 January 2026 and on 10 September 2026, **When** the run on 21 October 2026
   completes, **Then** the January rows are deleted; the September rows remain, still in the
   DEFAULT partition, none of them moved; no partition has been created for September or for any
   other month before October 2026; and the DEFAULT partition itself is not removed.
5. **Given** the database session's time zone is the UK's, **When** the run on 21 October 2026
   completes, **Then** March 2026 is removed and April 2026 kept, exactly as in UTC.

---

### User Story 3 - One runner at a time, and maintenance never stalls delivery (Priority: P2)

Every pod carries the job (one image, one to two replicas) and every pod's trigger fires at the same
UTC time. Only the pod that takes the single-runner lock does any work; the others skip, quietly.
Each partition statement waits at most 5 s for its locks, so a body write that maintenance holds up
waits, at worst, until the run ends, never indefinitely; a removal commits on its own, so a write
it holds up waits only for that removal (H35). A statement that cannot proceed is counted and tried
again the next day.

**Why this priority**: with one replica the job works without it. With two, it is what stops both
pods racing to create the same month — a failure, and a page, every day — and what stops a
maintenance statement from queueing the delivery pipeline's writes behind it indefinitely
(A3-64 (b)).

**Independent Test**: start two instances against one store with their triggers at the same instant
and assert that one did all the partition work and the other skipped with no failure counted; then,
while another session holds a conflicting lock on the stored bodies, run the job and assert that the
blocked statement gave up within 5 s, was counted under its step, and was completed by the next run
once the lock had gone.

**Acceptance Scenarios**:

1. **Given** two instances whose triggers fire at the same instant, **When** both start a run,
   **Then** exactly one takes the lock and does the partition work; the other does no partition
   work, writes no ERROR or WARN line and counts no failure.
2. **Given** a run completed earlier the same day, **When** another instance's run takes the lock
   afterwards, **Then** it creates, removes and deletes nothing, and counts no failure.
3. **Given** another session holds a lock that conflicts with partition creation — an investigation
   query left open in a transaction on `outbound_body`, for example — **When** a run tries to create
   a month, **Then** the statement gives up after 5 s, is logged at ERROR with the cause
   `lock-timeout` and is counted `step="create"`; the run's other statements are still attempted;
   and the next day's run creates the month once the lock has gone.
4. **Given** a removal that cannot get its locks within 5 s, or that fails for any other reason,
   **When** the run ends, **Then** the expired partition is still attached to `outbound_body`, where
   the next run finds it and removes it; no expired body is left in a table outside `outbound_body`.
5. **Given** the store cannot be reached at the scheduled time, **When** a run tries to take the
   lock, **Then** it logs at ERROR with the cause `store-unavailable`, counts `step="lock"` and does
   nothing else; the next scheduled run tries again; and readiness, intake and settlement are
   whatever the store's own health makes them — the job changes none of them.
6. **Given** intake has been paused by an operator, **When** the scheduled time comes, **Then** the
   run takes place as usual.
7. **Given** a run that removes an expired month and has more to do after it, **When** that removal
   has completed, **Then** it is already committed and holds no lock while the run still holds the
   single-runner lock, so a body written to the current month at that moment waits, at most, for
   that removal, not for the rest of the run (H35).

---

### User Story 4 - Every maintenance failure and every stranded body is visible (Priority: P2)

Each creation and each removal is logged and counted. Each failure is logged at ERROR, with its
step, its month and its cause from a closed set, and counted under its step. After every run the
DEFAULT partition's row count is published, so a body that landed outside its month is seen. The
§10.3 alert "Body partition maintenance" reads these signals and pages when the DEFAULT partition
is not empty, or when a creation or a removal fails.

**Why this priority**: H34 puts body-retention failures among the genuine issues that page. A
creation that fails quietly means the next month's bodies land in DEFAULT; a removal that fails
quietly means personal data is held past the retention (§10.3).

**Independent Test**: drive a run that creates one month and removes two; then a run in which a
creation fails, one in which a removal fails, and one that leaves rows in DEFAULT; assert the
counters, the gauge, one ERROR line per failure, and that no output of the job carries any part of a
body.

**Acceptance Scenarios**:

1. **Given** a run that creates one partition and removes two, **Then**
   `probation_body_partitions_created_total` rises by one and
   `probation_body_partitions_dropped_total` by two, each with a log line naming its month, and no
   failure is counted.
2. **Given** a creation that fails, **Then** `probation_body_maintenance_failures_total{step="create"}`
   rises by one and one ERROR line names the step, the month and its cause (FR-003-017), and no line
   at INFO or above carries the database's own error text; **given** a removal, or the delete of
   DEFAULT rows older than the cut-off, that fails, **Then** the step is `drop` (assumption AS-7).
3. **Given** the DEFAULT partition holds 5 rows after a run's delete of rows older than the cut-off,
   **Then** `probation_body_default_partition_rows` reads 5; **and given** a later run after the
   rows have gone, **Then** it reads 0.
4. **Given** the alert condition of §10.3, **Then** it is met by a non-empty DEFAULT partition or by
   any `create` or `drop` failure, and never by a skipped run.
5. **Given** any run, successful or not, **Then** no log line, metric label or exception message it
   produces contains any part of a stored body.

---

### User Story 5 - The schedule and the retention are configuration, checked at startup (Priority: P2)

The run time and the retention are properties. The schedule is read in UTC, so it does not move with
the UK's clock changes. Both values are validated at startup, and a value that cannot mean what it
says stops the service, naming the property.

**Why this priority**: a retention that is not whole months, or a schedule that is not a cron
expression, would make the job remove too much, too little or nothing at all, and nobody would be
told.

**Independent Test**: start the application context with each value of a matrix of valid and
invalid settings and assert which start and which fail, with the property named; with the defaults,
assert the next trigger time.

**Acceptance Scenarios**:

1. **Given** the defaults, **When** the service starts, **Then** the job is scheduled daily at
   02:15:00 UTC with a retention of `P6M`.
2. **Given** `distribution.body-retention` set to `P0M`, `P30D`, `P6M1D`, `-P6M` or a value that is
   not an ISO-8601 period, **When** the service starts, **Then** startup fails naming
   `distribution.body-retention`; `P1M`, `P6M` and `P1Y` start.
3. **Given** `distribution.body-maintenance.cron` set to a malformed expression, a five-field
   expression or the scheduler's "disabled" value, **When** the service starts, **Then** startup
   fails naming `distribution.body-maintenance.cron`.
4. **Given** the pod's default time zone is the UK's, **When** the clocks change, **Then** the job
   still runs at 02:15 UTC, once a day.
5. **Given** the retention is shortened from `P6M` to `P3M` and the service restarted, **When** the
   next run completes, **Then** every month before the new cut-off has been removed, for good;
   lengthening the retention again brings nothing back.
6. **Given** the service's configuration, **When** it is read, **Then** the body-maintenance
   properties are `distribution.body-retention` and `distribution.body-maintenance.cron` only (the
   lock timeout, the months created ahead and the lock's key are not properties), and neither
   property is mapped to a named environment variable, Appendix A's proposed `BODY_RETENTION` and
   `BODY_MAINTENANCE_CRON` included.

---

### User Story 6 - A DEFAULT-partition spill can be recovered by a documented procedure (Priority: P3)

If bodies land in DEFAULT because their month was never created, the job cannot create that month
any more — PostgreSQL refuses a partition whose range has rows in DEFAULT — so every run fails that
creation and the alert stays on. The operations documentation for the alert gives the recovery the
design prescribes: detach the DEFAULT partition, create the missing month, move its rows into it,
and re-attach the DEFAULT partition. After it, the next run is clean.

**Why this priority**: it is needed only after something else has gone wrong — the job not running
for two months or more, or a creation failing for that long — and nothing is lost meanwhile: the
bodies are stored, in DEFAULT, and alerted.

**Independent Test**: put bodies for a month with no partition into DEFAULT; confirm that a run
counts a `create` failure for that month; apply the documented procedure; run again and assert no
failure, the gauge at 0, and every moved body in its month's partition, byte-identical and still
matching its attempt's `body_digest`.

**Acceptance Scenarios**:

1. **Given** bodies for a month that has no partition are in DEFAULT, **When** a run tries to create
   that month, **Then** the creation is refused, logged at ERROR with the cause `default-has-rows`,
   counted `step="create"` and alerted, and the bodies are still stored.
2. **Given** that state, **When** an operator follows the documented procedure, **Then** the next
   run completes with no failure, `probation_body_default_partition_rows` reads 0, and every moved
   body is in its month's partition, unchanged, and accepted by the digest check against its
   attempt's `body_digest`.
3. **Given** the operations documentation for the "Body partition maintenance" alert, **Then** it
   gives the procedure in its order, says what it means for sends in flight (assumption AS-9), and says
   that DEFAULT rows older than the cut-off are deleted by the next run whatever is done.

### Edge Cases

- **Any day of the month.** The cut-off depends only on the UTC month of the run: runs on 1 and on
  31 October 2026 have the same cut-off, 1 April 2026 (with `P6M`).
- **The turn of the year.** A run in November creates January of the next year; a run in January
  with `P6M` has the cut-off 1 July of the year before.
- **A bound exactly on the cut-off.** Expired, and removed (Clarifications).
- **A partition whose name and bounds disagree.** For removal the bounds decide and a name is never
  parsed (A3-64 (a)). For creation the name decides, as in V1: a month whose V1 name is taken counts
  as present whatever that table covers, so if it does not cover the month, the month's bodies land
  in DEFAULT, on the gauge and alerted.
- **Another partition already covers all or part of a month the job should create** (one made by
  hand under another name, for example). The creation is refused for the overlap, logged with the
  cause `partition-overlap`, counted `create` and alerted on every run until an operator resolves
  it; meanwhile the month's bodies are stored in the partition that covers them, or in DEFAULT.
- **A database session or a pod in another time zone.** The cut-off is a UTC instant. The expression
  the design writes yields a time with no zone; compared with the bounds in a session that is not
  UTC it shifts by the zone's offset and can keep a month for one month too long (checked against
  PostgreSQL 15). Outcomes are the same in every zone (SC-003-010).
- **The UK's clock changes.** The schedule is in UTC, so the run stays at 02:15 UTC: an hour earlier
  or later by the UK's clock, never twice in a day and never skipped.
- **Which months DEFAULT can hold.** A DEFAULT row can only belong to a month with no partition:
  PostgreSQL routes a body to its month's partition when there is one, refuses a direct write of it
  into DEFAULT, and refuses to create a month while DEFAULT holds rows of it. The test fixtures
  respect that.
- **DEFAULT holds rows of a month the job should create** — the current month, after the job has not
  run for two months or more. The creation is refused on every run, counted `create` (cause
  `default-has-rows`) and alerted; the bodies stay stored; the procedure of User Story 6 recovers.
- **DEFAULT holds rows of a past month.** The job never creates past months. The rows stay, on the
  gauge and alerted, until they pass the cut-off and are deleted, or until an operator moves them
  with the procedure.
- **No pod running at the scheduled time** — an environment scaled down at that hour, for example.
  There is no run that day. The months created ahead absorb at least two months of missed runs
  before a body lands in DEFAULT, and the schedule is a property, so such an environment can choose
  an hour at which the service runs. As designed, nothing in the service notices a job that never
  runs (AS-15; open question 2).
- **A valid schedule that never fires** (30 February, for example). It passes the startup check,
  which checks only validity (§8.1), and the job never runs, unnoticed by the service as designed
  (AS-15; open question 2).
- **Two triggers at the same instant; two triggers a little apart.** One runs and one skips; or both
  run, one after the other, and the second finds nothing to do.
- **A long transaction on `outbound_body`** (an investigation query, for example). A statement that
  needs a conflicting lock gives up after 5 s and is tried again the next day; delivery carries on.
  A removal that does get its lock holds it only while it runs: it commits at once (H35).
- **The store unreachable at the scheduled time.** A `lock` failure, counted, and nothing else that
  day.
- **Intake paused, including the long pause after a rollback.** The job runs and the retention is
  kept.
- **The retention shortened.** The next run removes the extra months, irreversibly. Lengthened:
  months already removed do not come back.
- **An in-place re-run while its old month is being removed.** A re-run deletes the attempt's earlier
  body and inserts the new one (row A3-63; slice 002). If the earlier body's partition is being
  removed at the same moment, whichever takes its locks first goes first: the re-run is never failed
  by the removal, the removal either completes or gives up after 5 s and is retried the next day,
  and the attempt ends with exactly one body, the new one, in the current month.
- **An investigation after the window.** The body is gone; `body_digest`, `service_version`, the
  status and the metadata remain (row A3-65). A resend re-reads the payload, so nothing in the
  service depends on a stored body (row A3-65).
- **The first deployment.** V1 has just created the migration month and the next two, so the first
  run normally has nothing to do; if it falls in a later month than the migration, it creates the
  one month still missing.

## Requirements *(mandatory)*

### Functional Requirements

**Schedule and the single runner**

- **FR-003-001**: The service MUST run exactly one scheduled task of its own, the daily
  `outbound_body` maintenance job, triggered by `distribution.body-maintenance.cron` (default
  `0 15 2 * * *`) read in UTC, whatever the pod's own time zone. It MUST NOT rely on a scheduler in
  the database (`pg_cron`), and it is not a batch job. *(H20; A3-64; §4.1; §4.7; §8.1)*
- **FR-003-002**: At most one run MUST be in progress at any time across all pods. A run MUST first
  take the transaction-scoped PostgreSQL advisory lock on a fixed key that nothing else in the
  service's database uses, and the transaction that took it MUST stay open until the run's last
  statement has finished, so that the lock is held for the whole run. Each removal runs in a
  transaction of its own, never in the lock's (FR-003-011; H35); whether the run's other statements
  share the lock's transaction or each run in a transaction of its own is the plan's (AS-13). A pod
  that does not get the lock MUST skip the run: no partition statement, no DEFAULT delete, no
  failure counted and no ERROR or WARN line. *(H20; H35; A3-64; §10.1)*
- **FR-003-003**: An error while trying to take the lock — as opposed to not getting it — MUST be
  logged at ERROR with its cause (FR-003-017) and counted `step="lock"`; that run MUST then do
  nothing else, and the next scheduled run tries again. *(A3-64; §10.1; constitution Principle VI)*
- **FR-003-004**: The job MUST run whether intake is running or paused by an operator, including the
  paused period after a rollback, and MUST NOT consume, settle, enqueue or send anything.
  *(DIGEST §2; §4.8 action 4; H23; A3-117)*
- **FR-003-005**: "Now", the current month and the cut-off MUST be taken from the database clock in
  UTC, and the cut-off MUST be compared with partition bounds as a UTC instant, so that a run's
  outcome does not depend on the database session's time zone or on the pod's default zone.
  *(§4.6; A3-64 (a))*

**The months ahead**

- **FR-003-006**: Each run MUST ensure that the current UTC month and the next two each have a
  partition of `outbound_body`, creating every one that is missing with the name and bounds slice
  002's V1 uses (002's FR-002-054): `outbound_body_YYYY_MM`, from the first instant of the UTC
  month (inclusive) to the first instant of the next (exclusive). *(H20; A3-63; A3-64;
  Appendix B.2; FR-002-054)*
- **FR-003-007**: A month MUST count as present when a table of its name (`outbound_body_YYYY_MM`)
  already exists, which is V1's own rule for creating a month only if no table of its name exists
  (002's FR-002-054), and a present month MUST be left as it is: nothing is created, logged as
  created or counted for it. A creation refused because another partition already covers all or
  part of the month MUST fail like any other statement, with the cause `partition-overlap`
  (FR-003-017). *(A3-64; Appendix B.2; FR-002-054)*
- **FR-003-008**: Partitions MUST be created by the service's own database role, the role that owns
  the database and runs its migrations, so that the role owns them and can later remove them; the job
  MUST need no other privilege. *(A3-64 (d); A3-105; §8.4 ask 5)*

**Removing expired months**

- **FR-003-009**: Each run MUST remove every partition of `outbound_body` whose upper bound is on or
  before the cut-off: the first instant of the UTC month `distribution.body-retention` months before
  the current UTC month. On a run in month M with a retention of N months, the months up to
  M − N − 1 are removed and the months M − N to M are kept. *(H20; A3-64; A3-64 (a))*
- **FR-003-010**: A partition's upper bound MUST be read from the database catalogue, never derived
  from its name. The DEFAULT partition MUST never be removed. *(A3-64 (a); A3-63)*
- **FR-003-011**: Each expired partition MUST be removed by a plain `DROP` of the partition, with no
  `DETACH` before it, under the lock timeout of FR-003-016. Each `DROP` MUST run in a transaction of
  its own that commits as soon as the `DROP` has run, so that the locks it takes on `outbound_body`
  and its DEFAULT partition are held only for that statement, not for the rest of the run; it MUST
  NOT run in the transaction that holds the single-runner lock, which stays open on its own for the
  whole run (FR-003-002; AS-13 (b)). A `DROP` that gives up on the lock timeout is retried on the
  next run, not within the run (FR-003-017). The concurrent detach the design first prescribed is
  not used: PostgreSQL refuses it while a DEFAULT partition exists, and V1 creates one, which stays
  (FR-003-010). *(H35; A3-64 (b) as amended by H35; A3-63; A3-105)*
- **FR-003-012**: A removal that fails MUST leave the expired partition attached to `outbound_body`,
  where the next run finds it and tries again. A failure at any point of a removal MUST NOT leave an
  expired month's bodies in a table outside `outbound_body`, where no later run would look for them;
  the plain `DROP` of FR-003-011 is one atomic statement, so it never does. *(A3-64 (b); H35)*
- **FR-003-013**: The job MUST NOT delete or change any row of `processed_request` or
  `outbound_message`; neither table is ever purged. After its month is removed, an attempt keeps its
  `body_digest`, `service_version`, status and metadata. *(H20; A3-63; A3-65; DIGEST §8)*

**DEFAULT-partition rows**

- **FR-003-014**: Each run MUST delete the rows of the DEFAULT partition whose `created_at` is before
  the cut-off of FR-003-009, and no other DEFAULT row. *(A3-64; A3-64 (c))*
- **FR-003-015**: The job MUST NOT move rows out of the DEFAULT partition, and MUST NOT create a
  partition for a month before the current one: recovering a spill is the documented procedure of
  FR-003-028. *(A3-64; §4.8)*

**Bounded waits, failures and idempotence**

- **FR-003-016**: Every partition statement — each creation and each removal — MUST run under a lock
  timeout of 5 s, a fixed value; a statement that cannot get its locks within it MUST fail rather
  than wait. *(A3-64 (b); H35; DIGEST §12)*
- **FR-003-017**: A statement that fails, for a lock timeout or for any other reason, MUST be logged
  at ERROR with its step, its month (for a creation or a removal) and its cause, and counted under
  its step (`create` for a creation; `drop` for a removal or the DEFAULT delete). The cause MUST be
  one of `lock-timeout` (the statement gave up waiting for its locks), `default-has-rows` (a
  creation refused because the DEFAULT partition holds rows of that month), `partition-overlap` (a
  creation refused because another partition covers all or part of the month), `store-unavailable`
  (the store could not be reached, or the connection was lost) or `other`; a `lock` failure
  (FR-003-003) carries one too. The database's own error text MUST NOT be logged at INFO or above.
  A failed statement MUST NOT be retried within the run: the next scheduled run retries it. It MUST
  NOT undo the statements the run has already completed, and the run MUST still attempt its
  remaining statements; a statement that shares the lock's transaction (AS-13; never a removal,
  FR-003-011) runs under its own savepoint, so that a failure undoes only that statement.
  *(A3-64 (b); H35; §10.1; §10.2; constitution Principles VI and VII; AS-14)*
- **FR-003-018**: A run MUST be idempotent. Run again — the same day, by another pod, or after a
  failed run — it MUST do only what is still due, and MUST NOT fail, or count anything, for work an
  earlier run has already done. *(A3-64)*
- **FR-003-019**: A run — completed, failed or skipped — MUST NOT change readiness or liveness,
  intake, or the settlement of any message. *(DIGEST §6 and §13: readiness is the store only;
  §10.1)*

**Signals**

- **FR-003-020**: Each partition the job creates MUST be logged with its month and counted once on
  `probation_body_partitions_created_total`; each partition it removes MUST be logged with its month
  and counted once on `probation_body_partitions_dropped_total`. Neither counter carries a label, and
  the partitions V1 creates are not counted. (The series are registered in slice 002's shared
  registry, FR-002-057; this slice only increments them.) *(A3-64; §10.1; FR-002-057)*
- **FR-003-021**: Failures MUST be counted on `probation_body_maintenance_failures_total`, labelled
  `step` ∈ {`lock`, `create`, `drop`} and nothing else. A skipped run is not a failure. (The series
  and its step label set, `BodyMaintenanceStep`, are registered in slice 002's shared core,
  FR-002-057 and FR-002-072; this slice only increments them.) *(A3-64; §10.1; FR-002-057;
  FR-002-072; constitution Principle VII)*
- **FR-003-022**: Each run that holds the lock MUST set `probation_body_default_partition_rows` to
  the number of rows left in the DEFAULT partition after its delete of rows older than the cut-off.
  *(A3-64; A3-64 (c); §10.1)*
- **FR-003-023**: The job's signals MUST let the §10.3 alert "Body partition maintenance" fire on a
  non-empty DEFAULT partition, or on any `create` or `drop` failure, and a skipped run MUST NOT be
  able to trigger it. Whether a `lock` failure pages is the alert rule's (assumption AS-8); the rule
  and its routing are slice 006's. *(§10.3; H34; A3-115)*
- **FR-003-024**: The job MUST NOT put any part of a stored body in a log line, at any level, in a
  metric label or in an exception message. Its log lines carry partition names, months, counts,
  steps and the causes of FR-003-017 only. *(H18; A3-60; DV-09; §10.2; constitution Principle VII)*

**Configuration**

- **FR-003-025**: `distribution.body-retention` (an ISO-8601 period, default `P6M`) MUST be whole
  months — no days part, years counting as twelve months — and at least one month; any other value
  MUST stop startup, naming the property. *(§8.1; A3-64; DIGEST §12)*
- **FR-003-026**: `distribution.body-maintenance.cron` (default `0 15 2 * * *`) MUST be a valid cron
  expression in the six-field form of its default (second, minute, hour, day of month, month, day of
  week); any other value, the scheduler's "disabled" value included, MUST stop startup, naming the
  property. *(§8.1; A3-64; DIGEST §12)*
- **FR-003-027**: This slice MUST NOT add a `distribution.*` property: the lock timeout (5 s), the
  months created ahead (the current one and the next two) and the lock's key are fixed. It MUST NOT
  map a named environment variable to either property while that binding is open in the design.
  *(§8.1; Appendix A; DIGEST §12, §16)*

**Recovering a DEFAULT spill**

- **FR-003-028**: The operations documentation for the "Body partition maintenance" alert MUST give
  the design's recovery for a non-empty DEFAULT partition — detach the DEFAULT partition, create the
  missing month with V1's name and bounds, move its rows into it, re-attach the DEFAULT partition —
  and a test MUST show that, after it, the next run completes with no failure, the DEFAULT gauge
  reads 0, and every moved body is unchanged and accepted against its attempt's `body_digest`.
  *(A3-64; §4.8)*

### Key Entities

- **Stored body**: one `outbound_body` row — the exact bytes POSTed for one attempt, with the time it
  was written (`created_at`, from the database clock). Personal data, youth defendants included.
  Belongs to one attempt row; kept until its month is removed.
- **Monthly partition**: the stored bodies of one calendar month in UTC, named
  `outbound_body_YYYY_MM` and bounded by the first instants of that month and of the next. Created
  ahead (by V1 or by the job), owned by the service's role, removed whole.
- **DEFAULT partition**: `outbound_body_default`, where a body goes when its month has no partition.
  Expected to be empty; never removed; its rows older than the cut-off are deleted; while it is not
  empty, the alert pages.
- **Retention**: `distribution.body-retention`, N whole months (default `P6M`).
- **Cut-off**: the first instant of the UTC month N months before the current UTC month — the line
  before which months are removed and DEFAULT rows deleted.
- **Maintenance schedule**: `distribution.body-maintenance.cron`, six fields, read in UTC.
- **Maintenance run**: one execution on one pod. Either skipped (no lock) or run (the lock held
  throughout); each of its statements succeeds or fails on its own.
- **Maintenance step**: `lock`, `create` or `drop` — the bounded label a failure is counted under.
  A failure's ERROR line also carries its **cause**, one of `lock-timeout`, `default-has-rows`,
  `partition-overlap`, `store-unavailable` or `other` (FR-003-017): a log field, never a metric
  label.
- **Leg attempt**: the `outbound_message` row a body belongs to. It keeps `body_digest`,
  `service_version`, its status and its metadata after its body is removed, and the job never
  changes it.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-003-001**: With the job running daily, no stored body is held more than N + 1 months and one
  day after it was written (N being the retention in months: 7 months and a day by default), shown
  by a test that walks a simulated calendar of at least 14 months, a year end included.
- **SC-003-002**: No stored body is removed before it is N months old: zero early removals over the
  same simulated calendar, with runs on the first, the last and a middle day of each month.
- **SC-003-003**: After every completed run, the current month and the next two are ready to take
  bodies (3 of 3), so that, with daily runs, no body written in normal operation is stored outside
  its own month.
- **SC-003-004**: 100% of the request and attempt records present before a run are present and
  unchanged after it, whatever the run removes.
- **SC-003-005**: With two instances triggered at the same instant, exactly one does maintenance
  work in each of at least 20 repetitions, and the other records no failure.
- **SC-003-006**: While another session holds the stored bodies in a way that conflicts with
  maintenance (an investigation left open, for example), each maintenance step that needs them gives
  up within 5 s (measured, with 1 s of tolerance), the run still finishes, and the next run, once the
  hold has ended, completes the work.
- **SC-003-007**: A second run on the same day creates nothing, removes nothing, deletes nothing and
  records no failure.
- **SC-003-008**: Every failed maintenance step produces exactly one ERROR line, carrying its cause,
  and one failure count under the right step — 100% of the failure cases in the test matrix — and a
  body stored outside its month is visible in the published DEFAULT count after the next run.
- **SC-003-009**: Zero log lines, metric labels or exception messages produced by the job contain any
  part of a stored body, and zero lines at INFO or above carry the database's own error text, checked
  by the repository's privacy tests extended to the job.
- **SC-003-010**: A run creates and removes exactly the same months whatever the database session's
  time zone and the pod's default zone, checked in UTC and in at least one zone with daylight saving,
  on dates either side of a clock change.
- **SC-003-011**: 100% of the invalid settings in the configuration test matrix stop startup with
  the property named, and the defaults and the valid examples start.
- **SC-003-012**: After any run, every retained month's storage is still readable as one figure,
  the size of that month's partition (the DEFAULT partition being empty in normal operation), so
  that after the first full month in production the H28 sizing floor (at least about 24 KB a body
  on average, 100 MB a day and 20 GB held) is replaced by the real figure (AS-11).
- **SC-003-013**: No send fails, and no send's outcome goes unrecorded, because of maintenance. A
  body written while a removal holds or waits for the stored bodies is stored as soon as that
  removal has completed or given up, within at most 5 s of lock wait plus the time the removal
  takes, because each removal is committed on its own (H35); one written while a creation holds or
  waits for them is stored by the end of that run at the latest, whichever arrangement the plan
  chooses for creations (AS-13).

## Assumptions

- **AS-1 — Where the job sits.** The design's package tree (§4.4) does not place the job, and the
  digest records that as not established (§16). This spec does not place it: `/speckit-plan` chooses
  and records the package, keeping constitution Principle V and every package's `package-info.java`
  true.
- **AS-2 — No environment variables.** Whether `distribution.body-retention` and
  `distribution.body-maintenance.cron` bind an environment variable is open: §8.1 gives none, and
  Appendix A proposes `BODY_RETENTION` and `BODY_MAINTENANCE_CRON`. The two properties are set through
  the service's configuration, as the scaffold has them, until the design decides (FR-003-027).
- **AS-3 — PostgreSQL 15** everywhere the service runs, and in its local and test stacks. Row A3-105
  confirms 15 for production and the test environments it read; the pre-production server's version
  was not read, and is assumed the same.
- **AS-4 — The service's role owns the database**, its schema, and so the partitions it creates
  (platform ask 5; row A3-105), so the job needs no grant beyond those V1 needs.
- **AS-5 — Every pod carries the job.** One image and one to two replicas to start (O-2): every pod's
  trigger fires, which is why the single-runner lock exists.
- **AS-6 — Daily runs.** The schedule fires once a day. Two months are created ahead, so at least two
  months of missed runs pass before a body lands in DEFAULT.
- **AS-7 — The DEFAULT delete counts as `drop`.** The step set is closed (`lock`, `create`, `drop`;
  §10.1). The delete of DEFAULT rows older than the cut-off is part of the purge, so its failure is
  counted `drop` and pages like a failed removal: bodies past the retention are being kept.
- **AS-8 — A `lock` failure is counted; whether it pages is the alert rule's.** §10.3's condition
  names creation and removal failures only, while H34 speaks of "body-retention job failures". A
  `lock` failure most likely means the store is unreachable, which the store's own signals show.
  Slice 006 settles the rule; this slice provides the count.
- **AS-9 — The recovery runs with intake paused.** While the DEFAULT partition is detached, a body for
  a month with no partition cannot be stored, so a send in flight would fail its outcome write after
  its POST and be run again — a possible duplicate at HMPPS. The documented procedure therefore
  starts by pausing intake (§4.8 action 4) and ends by resuming it. The design does not state this
  precondition; it is raised with the design owner. This slice builds neither the pause nor the
  resume runner: pause and resume intake are 004's runners (FR-004-042, FR-004-051). Until they
  exist, the runbook names the step, and the test of FR-003-028 turns intake off with the
  scaffold's intake switch, `distribution.consumer.enabled`.
- **AS-10 — Maintenance log lines carry no request fields.** They concern no request, so the rule that
  every processing line carries `requestId` and `hearingId` (constitution Principle VII) does not
  apply to them.
- **AS-11 — The sizing floor is an input, not a behaviour.** H28's floor sizes the database (platform
  ask 5). §10.1 has no partition-size metric, so the real figure is read by an operations query on
  the monthly partitions after go-live.
- **AS-12 — Test names.** The design proposes `BodyRetentionJobIT` for this job (Appendix B.2); the
  name is kept unless the plan says otherwise.
- **AS-13 — The lock's transaction and the statements' own.** The single-runner lock is
  transaction-scoped and held for the whole run (FR-003-002), while each statement succeeds or fails
  on its own (FR-003-017). Two arrangements meet both:
  - (a) a statement shares the lock's transaction, under its own savepoint. Every lock it takes on
    `outbound_body` is then held until the run commits;
  - (b) the lock's transaction stays open on a connection of its own while the statement runs and
    commits in its own transaction on another. The statement's locks are then released when it
    commits.

  For removals the design decides (b): each `DROP` commits in its own short transaction while the
  lock's transaction stays open on its own for the whole run (H35; FR-003-011). It does not say how
  the run's other statements — the creations, the DEFAULT delete and the gauge's read — stand, so
  the plan chooses (a) or (b) for them, within one constraint: while a removal runs, the lock's
  transaction must hold no lock on `outbound_body` or on its DEFAULT partition. A `DROP` of a month
  takes `ACCESS EXCLUSIVE` on both, so a creation, a DEFAULT delete or a DEFAULT count run earlier
  in the lock's transaction makes every removal of that run give up after 5 s (checked against
  PostgreSQL 15 on 2026-10-21); under (a), those statements would therefore run after the
  removals. The lock timeout bounds the wait for a lock, not how long it is held, so SC-003-013 is
  stated to hold whichever arrangement the plan chooses for creations.
- **AS-14 — The failure causes are this spec's.** The design requires a sanitised ERROR line for
  every failure (§10.2; constitution Principle VI) and counts the job's failures by step only
  (§10.1); it names no causes. FR-003-017 closes the set so that "sanitised" can be tested. The
  cause is a log field, not a metric label: `step` stays the failure counter's only label. The
  database's own error text stays out of every line at INFO or above because PostgreSQL's error
  detail can quote a failing row, a body included; FR-003-024 keeps any part of a body out of every
  line at every level.
- **AS-15 — A job that does not run is not noticed by the service, as designed.** Every signal the
  design defines for the job comes from a run (A3-64; §10.1). So a job that never runs — no pod up
  at the scheduled time, a valid schedule that never fires, a fault in the scheduler — counts no
  failure and changes no gauge; and with two replicas, only the pod that takes the lock sets the
  DEFAULT gauge (FR-003-022), while the pod that skips keeps its last reading. This slice builds
  what the design specifies and adds no signal: the design's default stands, so nothing is blocked.
  Whether the reading should be kept current independently of the lock, and whether an overdue run
  should be signalled, is raised with the design owner as open question 2, with its options, in
  `checklists/requirements.md`. Until it is answered, slice 006's alert 9 reads the gauge as
  designed.

## Dependencies

- **Slice 002-hmpps-distribution** (must land first). Flyway `V1`: `outbound_body` partitioned by
  month in UTC on `created_at`, `outbound_body_default`, the three months created at migration, the
  digest trigger and the foreign key to `outbound_message`; and `outbound_message` with `body_digest`
  and `service_version`. Also the body write path (the insert after the outcome UPDATE, the
  replacement on an in-place re-run), the datasource, the startup property validator and the metric
  registry this slice adds to: the `probation_body_*` series and the `BodyMaintenanceStep` label set
  are registered in 002's shared core (FR-002-057, FR-002-072), and this slice only increments
  them. The names, bounds and create-if-absent rule of the monthly partitions are V1's (002's
  FR-002-054). Open question 1 (FR-003-011) is answered, before V1 is written: option A (H35,
  2026-10-21), a plain `DROP` of each expired month, so V1 is unchanged and keeps
  `outbound_body_default` (A3-63; 002's FR-002-054 and Assumptions).
- **Slice 006-production-readiness.** The §10.3 alert rule "Body partition maintenance", its routing
  and owner, and the dashboard; the alert links this slice's recovery procedure. How the rule combines
  the pods' readings depends on open question 2 (AS-15); until it is answered, the rule reads the
  gauge as designed. Its SIT → STE replay reads `outbound_body` well within the retention (H21).
- **Slices 004-recovery and 005-operations-api.** No build dependency. Investigations of what was
  sent read `outbound_body` within the window this slice enforces (§4.8, the dead-letter runbook).
  The documented recovery of FR-003-028 uses pause-intake and resume-intake (§4.8 action 4; AS-9),
  which this slice does not build: pause and resume intake are 004's runners (FR-004-042,
  FR-004-051). Until they exist, the runbook names the step, and the test of FR-003-028 turns
  intake off with the scaffold's intake switch.
- **Slice 001-parity-kernel.** None.
- **External.** Platform ask 5: the service's own database, owned by its role, on PostgreSQL ≥ 13 for
  V1's trigger (15 confirmed, row A3-105). The data owner and the DPIA, informed that bodies are held
  about six months and then removed (row A3-65; a note, not a gate). The design owner, for open
  question 2 (AS-15); open question 1 is answered (H35; FR-003-011).

## Out of Scope

- `outbound_body` itself and `V1` — the partitioned table, its DEFAULT partition, the three initial
  months, the digest trigger and the foreign key — and the write path that inserts and replaces
  bodies: slice 002.
- The alert rule, its routing and owner, and the dashboard: slice 006.
- Purging `processed_request` or `outbound_message`: never done (DIGEST §8).
- Moving DEFAULT rows automatically, and creating partitions for past months: the documented
  procedure's (FR-003-028).
- A scheduler in the database (`pg_cron`) (A3-64).
- A metric or report of partition sizes: none in §10.1 (assumption AS-11).
- A run at startup, a catch-up of missed runs, or an operator action that runs the job on demand:
  none is in the design (§4.8 lists no such action).
- Binding environment variables to the two properties: open in the design (DIGEST §16).
- Column encryption and database access control (H18; the platform).
- Informing the data owner and updating the DPIA note: a note, not a gate, outside this repository
  (row A3-65).

## Design traceability

| Design id | What it fixes | Requirements and criteria |
|-----------|---------------|---------------------------|
| H20 | Bodies in `outbound_body`, monthly partitions in UTC; a daily in-service job, single runner by advisory lock; purged after `distribution.body-retention` (`P6M`, 6–7 months in effect) | FR-003-001, FR-003-002, FR-003-006, FR-003-009, FR-003-013; SC-003-001, SC-003-002 |
| H18 (protection, kept by H20) | Bodies are personal data and are never logged | FR-003-024; SC-003-009 |
| H23, A3-117 | Intake stays paused after a rollback until a full new cutover | FR-003-004 |
| H28 | The sizing floor: at least ~24 KB a body on average, ~100 MB a day, ~20 GB held; the real figure from partition sizes after go-live | SC-003-012; AS-11 |
| H34 | Body-retention failures page; known issues are counted | FR-003-023 |
| H35 | An expired month removed by a plain `DROP`, each in its own short transaction under the 5 s lock timeout; the advisory lock on its own transaction for the whole run; a timed-out `DROP` retried on the next run; the DEFAULT partition, its alert and its recovery kept; V1 unchanged (answers open question 1; amends A3-64 (b)) | FR-003-002, FR-003-011, FR-003-012, FR-003-016, FR-003-017; SC-003-013; AS-13 |
| A3-60 | Bodies never logged and never in a metric | FR-003-024; SC-003-009 |
| A3-63 | Monthly partitions on UTC bounds, the DEFAULT partition, V1's three months; an in-place re-run replaces the body; "digest ⇒ body" is not a constraint | FR-003-006, FR-003-010, FR-003-013; Edge cases |
| A3-64 | The job: schedule, single runner, creation ahead "if absent", removal, counts, the DEFAULT alert and the recovery step; every signal comes from a run | FR-003-001, FR-003-002, FR-003-003, FR-003-006, FR-003-007, FR-003-009, FR-003-014, FR-003-015, FR-003-018, FR-003-020, FR-003-021, FR-003-022, FR-003-025, FR-003-026, FR-003-028; AS-13, AS-15 |
| A3-64 (a) | The cut-off in UTC; bounds read from the catalogue, never the name (the drop rule); the scheduler's zone UTC | FR-003-005, FR-003-009, FR-003-010; SC-003-010 |
| A3-64 (b) | Lock timeout 5 s; a failed statement retried on the next run; removal by a plain `DROP` in its own short transaction, as amended by H35 (the concurrent detach then drop it first prescribed is refused while the DEFAULT partition exists) | FR-003-011, FR-003-012, FR-003-016, FR-003-017; SC-003-006, SC-003-013; AS-13 |
| A3-64 (c) | DEFAULT rows older than the cut-off deleted; the alert fires while any remain | FR-003-014, FR-003-022 |
| A3-64 (d) | Partitions created, and so owned, by the service's role | FR-003-008 |
| A3-65 | After the purge, digest, version, status and metadata remain; investigations within the window | FR-003-013; Edge cases |
| A3-98, A3-99 | The sizing floor's figures; no further measurement | SC-003-012; AS-11 |
| A3-105 | PostgreSQL 15; the database owned by the service's role; the PostgreSQL 13 fallback not needed for the version (H35 makes the plain `DROP` the removal on every version) | FR-003-008, FR-003-011; AS-3, AS-4 |
| A3-115 | The alert set, "body partition maintenance" kept | FR-003-023 |
| §4.1, §4.7 | One image, no batch job, one in-service scheduled task | FR-003-001 |
| §4.6, Appendix B.2 | The database clock is the time authority; V1's partition names and bounds, and its creation of a month only if its name is absent (002's FR-002-054) | FR-003-005, FR-003-006, FR-003-007 |
| §4.8 | Pause and resume act on intake; the six-month window for investigations; the DEFAULT recovery | FR-003-004, FR-003-015, FR-003-028 |
| §8.1 | `distribution.body-retention` and `distribution.body-maintenance.cron`, with their startup checks | FR-003-001, FR-003-025, FR-003-026, FR-003-027; SC-003-011 |
| §8.4 ask 5 | The database owned by the service's role | FR-003-008; AS-4 |
| §10.1 | The four `probation_body_*` metrics; a skipped run is not a failure | FR-003-002, FR-003-003, FR-003-017, FR-003-020, FR-003-021, FR-003-022; AS-15 |
| §10.2 | Every failure path emits a sanitised ERROR line and a metric; the stored body is never logged | FR-003-017, FR-003-024; SC-003-008, SC-003-009; AS-14 |
| §10.3 | The "Body partition maintenance" alert condition | FR-003-023; AS-15 |
| Appendix A | Environment variable names proposed, not bound | FR-003-027; AS-2 |
| DV-09 | No payload or defendant data in logs | FR-003-024 |
| DIGEST §6, §13 | Readiness is the store only | FR-003-019 |
| DIGEST §12 | No `distribution.*` property outside the catalogue | FR-003-016, FR-003-027 |
| DIGEST §16 | The job's package and the properties' environment binding not established | AS-1, AS-2 |
