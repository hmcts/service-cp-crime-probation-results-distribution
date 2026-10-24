---

description: "Task list for body retention (slice 003)"
---

# Tasks: Body Retention

**Input**: Design documents from `specs/003-body-retention/`
**Prerequisites**: plan.md, spec.md, data-model.md, research.md, quickstart.md (no `contracts/` — this slice exposes no interface to any user or system; see plan.md's Project Structure)

**Tests**: MANDATORY (Constitution Principle II). Every user story's test tasks are ordered before the implementation tasks they guard. Each test task MUST land a compile-safe seam so its recorded red run is a failing assertion, never a compile error; the paired implementation task quotes the green run.

**Organization**: Tasks are grouped by user story (spec.md priorities P1–P3) so each story is independently implementable and testable. Package layout follows plan.md's Project Structure: a new `maintenance/` package (`BodyRetentionJob`, `DatabaseClock`, `SingleRunnerLock`, `PartitionCatalogue`, `PartitionBound`, `PartitionMaintenanceRepository`, `MaintenanceMetrics`, `MaintenanceRunOutcome`), under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/`, mirrored under `src/test/java/...`; one new enum, `domain/MaintenanceFailureCause.java`, alongside the existing `domain/BodyMaintenanceStep.java`.

## ✅ Blocking dependency — resolved 2026-10-22

**Resolved** by `git merge --no-ff 002-hmpps-distribution` (commit `0f3af86`), mirroring the same fix
used between `002-hmpps-distribution` and `001-parity-kernel`. `003-body-retention` now carries
slice 002's real classes (`BodyMaintenanceStep`, the V1 Flyway migration, `MetricRegistry`,
`ProcessedRequestRepository`, `OutboundMessageRepository`, the startup validator, the datasource),
confirmed present by `find` and a clean `./gradlew compileJava`. The one real merge conflict
(`CLAUDE.md`'s `SPECKIT` block, edited on both branches) was resolved by combining both narratives
rather than picking a side. Not pushed anywhere — local to this branch.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US6, mapped to spec.md's priorities
- Constructor injection only, `private final` fields, Java records for value types, no `@Autowired`, no wildcard imports, Flyway only (never Liquibase), `BodyMaintenanceStep` reused from `domain/` (never redefined) — per `.claude/rules/technical-rules.md`

---

## Phase 1: Setup

**Purpose**: Slice-specific scaffolding every story needs before any job logic lands.

- [x] T001 Create `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/package-info.java` stating the package's responsibility (the service's one scheduled task: ensure the coming months have a partition, remove expired ones, publish the two 2026-10-22 staleness signals) and its allowed dependencies (`domain/` for `BodyMaintenanceStep`/`MaintenanceFailureCause`; the shared `DataSource`/`JdbcClient`/`MeterRegistry` beans from `config/`; never `application/`, `pipeline/`, `adapter/*` or `inbound/`) per plan.md's Structure Decision and `design_rules.md`'s package-tree conventions
- [x] T002 [P] ~~Add `distribution.body-retention` ... to `application.yaml`~~ — **already satisfied**: 002's scaffold already placed both properties (lines ~194-199) with full rationale comments, no environment-variable binding (FR-003-027, AS-2). Verified present, no edit needed.
- [x] T003 [P] ~~Confirm the Testcontainers PostgreSQL dependency~~ — **already satisfied**: `testcontainers-postgresql`, `testcontainers-junit-jupiter` already in `build.gradle`. Verified present, no edit needed.

**Checkpoint**: scaffolding and configuration skeleton ready.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The primitives every user story composes — the database-clock reader, the catalogue reader, the single-runner lock and the partition-statement repository. No user story may start before this phase is green.

**⚠️ CRITICAL**: No task in this phase carries a story label — it blocks every story.

- [x] T004 [P] Create `domain/MaintenanceFailureCause.java` enum (`LOCK_TIMEOUT, DEFAULT_HAS_ROWS, PARTITION_OVERLAP, STORE_UNAVAILABLE, OTHER`) with a `fromValue(String)` factory that fails explicitly on an unknown value, alongside the existing `domain/BodyMaintenanceStep.java` (`LOCK, CREATE, DROP` — reused from slice 002, FR-002-072; **not** recreated here) (FR-003-017, AS-14)
- [x] T005 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/DatabaseClockIT.java` (Testcontainers Postgres 15) asserting `DatabaseClock.nowUtc()` returns the database server's own clock reading, converted to UTC, and that the result is identical whether the JDBC session's time zone is UTC or a DST zone (the UK's) — fails first (FR-003-005)
- [x] T006 Implement `maintenance/DatabaseClock.java`: `SELECT (now() AT TIME ZONE 'UTC')` through `JdbcClient`, returning an `OffsetDateTime` pinned to UTC regardless of session zone — makes T005 pass (FR-003-005)
- [x] T007 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/PartitionCatalogueIT.java` (Testcontainers Postgres) asserting `PartitionCatalogue.partitions()` reads each `outbound_body` child partition's name and bounds via `pg_get_expr(relpartbound, oid)` through `pg_inherits`, parses a `FOR VALUES FROM ('...') TO ('...')` expression into `OffsetDateTime` bounds, and represents the DEFAULT partition's literal `DEFAULT` bound as `PartitionBound.isDefault() == true` with no `from`/`to` to misuse — fails first (FR-003-010; research.md "Reading partition bounds from the catalogue")
- [x] T008 Implement `maintenance/PartitionBound.java` (record: `partitionName`, `from`, `to`, `isDefault()`) and `maintenance/PartitionCatalogue.java` against the SQL in research.md — makes T007 pass (FR-003-010)
- [x] T009 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/SingleRunnerLockIT.java` (Testcontainers Postgres) asserting: a connection that acquires `pg_try_advisory_xact_lock` on the fixed key keeps it held — visible to a concurrent attempt on the same key — across multiple subsequent statements run on *other* connections, until it `commit()`s or `rollback()`s; a second concurrent `tryAcquire()` on the same key returns `false` without blocking, and succeeds once the first connection releases — fails first (FR-003-002; research.md "Lock and transaction strategy")
- [x] T010 Implement `maintenance/SingleRunnerLock.java`: obtains a raw `Connection` directly from the `DataSource` (never through `JdbcClient`/Spring's declarative transaction management), sets `setAutoCommit(false)`, executes `SELECT pg_try_advisory_xact_lock(?)` with a fixed lock-key constant (not a property, FR-003-027), and exposes `tryAcquire(): boolean` / `release(boolean commit)` — makes T009 pass (FR-003-002, FR-003-027)
- [x] T011 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/PartitionMaintenanceRepositoryIT.java` (Testcontainers Postgres) covering, each under its own `SET LOCAL lock_timeout = '5s'`: `createPartition` (creates `outbound_body_YYYY_MM` with V1's name/bounds; a no-op when the name already exists; fails with `PARTITION_OVERLAP` when another table's range already covers the month; fails with `DEFAULT_HAS_ROWS` when the DEFAULT partition holds rows of that month); `dropPartition` (a plain `DROP`, committing in a transaction of its own, independent of any caller transaction); `deleteDefaultRowsBefore(cutoff)` (deletes only `outbound_body_default` rows with `created_at < cutoff`); `countDefaultRows()` (read-only, takes no lock) — fails first (FR-003-006 to FR-003-016, FR-003-031)
- [x] T012 Implement `maintenance/PartitionMaintenanceRepository.java` against `JdbcClient`/`TransactionTemplate` (never `SingleRunnerLock`'s own connection), each method setting its own 5s `lock_timeout` — makes T011 pass (FR-003-008, FR-003-011, FR-003-016)
- [x] T013 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/MaintenanceRunOutcomeTest.java` (plain unit) asserting the `MaintenanceRunOutcome`/`MaintenanceFailure` record shapes from data-model.md — fails first
- [x] T014 Implement `maintenance/MaintenanceRunOutcome.java` (record) and its nested `MaintenanceFailure` record — makes T013 pass

**Checkpoint**: the database-clock reader, catalogue reader, single-runner lock and partition-statement repository are green and independently tested. User story work can now begin.

---

## Phase 3: User Story 1 — The coming months always have somewhere to put a body (Priority: P1) 🎯 MVP

**Goal**: Every run ensures the current UTC month and the next two each have a partition, creating any that is missing with V1's name and bounds.

**Independent Test** (spec.md): on a store migrated by V1, remove the newest month's partition, run the job once, assert all three months exist with the expected names/bounds, owned by the service's role, and the created counter rose by exactly one.

### Tests for User Story 1 (MANDATORY — write and run these first) ⚠️

- [x] T015 [P] [US1] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/BodyRetentionJobTest.java` (plain mocks, no Spring context) asserting the create-ahead decision logic: given `DatabaseClock`'s UTC "now", the job asks `PartitionCatalogue` for the current month and the next two, calls `PartitionMaintenanceRepository.createPartition` only for the ones absent, and calls it for none when all three already exist — fails first (FR-003-006, FR-003-007)
- [x] T016 [P] [US1] Write `BodyRetentionJobIT` (Testcontainers Postgres) covering spec.md User Story 1's Acceptance Scenarios 1–6: a missing December partition is created with the exact V1 name/bounds and the created counter rises by one; a run with all three months present creates and counts nothing; a run at the November/December boundary rolls the year over correctly; a same-ranged partition under another name causes the creation to be refused, logged ERROR with cause `PARTITION_OVERLAP`, counted `step="create"`, with the run's other statements still attempted; every created partition is owned by the service's own database role; the outcome is identical under a UK (DST) session time zone and under UTC — fails first (SC-003-003, SC-003-010)

### Implementation for User Story 1

- [x] T017 [US1] Implement the create-ahead step of `maintenance/BodyRetentionJob.java`: read `DatabaseClock.nowUtc()`, compute the current UTC month and the next two, read `PartitionCatalogue.partitions()`, call `PartitionMaintenanceRepository.createPartition` for each absent month, log and count each creation on `probation_body_partitions_created_total`, and on a caught failure log ERROR with its cause (`MaintenanceFailureCause`) and count `BodyMaintenanceStep.CREATE` without stopping the run — makes T015, T016 pass (FR-003-005 to FR-003-008, FR-003-017, FR-003-018, FR-003-020)

**Checkpoint**: User Story 1 is independently functional and testable.

---

## Phase 4: User Story 2 — Bodies past the retention are removed, and nothing else is (Priority: P1)

**Goal**: Every run removes each whole partition past the retention cut-off and deletes stranded DEFAULT rows before that same cut-off, leaving every attempt row untouched.

**Independent Test** (spec.md): seed Feb/Mar/Apr 2026 partitions with bodies and attempt rows, plus DEFAULT rows for January and September 2026; run with `P6M` "on" 21 October 2026; assert Feb/Mar gone, April kept, January DEFAULT rows gone, September kept, every request/attempt row unchanged, dropped counter up by two.

### Tests for User Story 2 (MANDATORY — write and run these first) ⚠️

- [x] T018 [P] [US2] Extend `BodyRetentionJobTest` (unit, mocks) asserting the cut-off computation (`distribution.body-retention` whole months before the current UTC month, as a UTC instant, per `DatabaseClock`) and that `PartitionMaintenanceRepository.dropPartition` is called only for partitions whose catalogue-read upper bound is on or before that cut-off, and `deleteDefaultRowsBefore` is called with the identical cut-off — fails first (FR-003-005, FR-003-009, FR-003-014)
- [x] T019 [P] [US2] Write `BodyRetentionJobIT` covering spec.md User Story 2's Acceptance Scenarios 1–5: February and March 2026 removed, April kept (upper bound equals the cut-off), each removal logged and counted once on `probation_body_partitions_dropped_total`, every `processed_request`/`outbound_message` row of the removed bodies present and unchanged (`body_digest`/`service_version` included); day-boundary exactness (31 October keeps April, 1 November removes it); a partition named `outbound_body_2026_04` whose bounds actually cover March is removed on its bounds, never its name; DEFAULT rows before the cut-off deleted, rows after kept untouched in DEFAULT, no partition created retroactively, DEFAULT partition itself never removed; identical result under a UK session time zone — fails first (SC-003-001, SC-003-002, SC-003-004, SC-003-010)

### Implementation for User Story 2

- [x] T020 [US2] Implement the removal step of `maintenance/BodyRetentionJob.java`: compute the cut-off from `DatabaseClock.nowUtc()` and `distribution.body-retention`; for each catalogue-read partition whose upper bound is on or before it, call `PartitionMaintenanceRepository.dropPartition`, log and count on `probation_body_partitions_dropped_total`; then call `deleteDefaultRowsBefore(cutoff)`; never issue a statement against `processed_request`/`outbound_message` — makes T018, T019 pass (FR-003-009, FR-003-010, FR-003-011, FR-003-012, FR-003-013, FR-003-014, FR-003-015)

**Checkpoint**: User Stories 1 AND 2 together independently functional (spec.md's combined independent test).

---

## Phase 5: User Story 3 — One runner at a time, and maintenance never stalls delivery (Priority: P2)

**Goal**: Only the pod holding the single-runner lock does any work; every statement waits at most 5s for its locks; a removal commits on its own so delivery is never blocked for longer than one statement.

**Independent Test** (spec.md): two instances triggered at the same instant — exactly one does the work, the other skips with no failure counted; with a conflicting lock held elsewhere, the blocked statement gives up within 5s, is counted, and is completed by the next run.

### Tests for User Story 3 (MANDATORY — write and run these first) ⚠️

- [x] T021 [P] [US3] Write `BodyRetentionJobIT` concurrency scenario (SC-003-005, ≥20 repetitions): two instances triggered at the same instant — exactly one performs the partition work, the other does no partition statement, writes no ERROR/WARN line and counts no failure; a run that already completed that day does nothing on a later trigger, creates, removes and deletes nothing, and counts no failure (SC-003-007); and, with `distribution.consumer.enabled=false` (intake paused, including the long pause after a rollback), the run proceeds exactly as it would with intake running, creating and removing whatever is due — fails first (FR-003-002, FR-003-004, FR-003-018, SC-003-007)
- [x] T022 [P] [US3] Write `BodyRetentionJobIT` lock-timeout scenario: with another session holding a lock on `outbound_body` that conflicts with a creation, the statement gives up after 5s (±1s, SC-003-006), is logged ERROR with cause `LOCK_TIMEOUT`, counted `step="create"`, the run's other statements still attempted, and the month is created by the next run once the lock is gone — fails first (FR-003-016, FR-003-017)
- [x] T023 [P] [US3] Write `BodyRetentionJobIT` store-unavailable scenario: the store unreachable at the scheduled time logs ERROR with cause `STORE_UNAVAILABLE`, counts `step="lock"`, does nothing else that run, and leaves readiness, liveness, intake and settlement exactly as the store's own health already makes them — fails first (FR-003-003, FR-003-019)
- [x] T024 [P] [US3] Write `BodyRetentionJobIT` scenario: a removal that completes commits and releases its locks on `outbound_body` before the rest of the run finishes, so a concurrent write to the current month's partition is blocked only for that removal's own duration, never for the whole run (SC-003-013, H35) — fails first (FR-003-011, FR-003-012)

### Implementation for User Story 3

- [x] T025 [US3] Wire `SingleRunnerLock` into `BodyRetentionJob.run()`: `tryAcquire()` first; on `false`, log at DEBUG and return, touching no partition statement and counting nothing; on `true`, run the create-ahead step (T017) then the removal step (T020), then `release(true)` on normal completion or `release(false)` on any uncaught exception. `run()` MUST read no intake-related property or state — it has no code path that could skip, defer or alter its work because intake is paused — makes T021 pass (FR-003-002, FR-003-004, FR-003-018, SC-003-007)
- [x] T026 [US3] Add the `lock`-step failure path: a connectivity failure while acquiring the lock is caught, logged ERROR with its `MaintenanceFailureCause`, counted `BodyMaintenanceStep.LOCK`, and the run stops there without touching readiness, liveness, intake or settlement — makes T023 pass (FR-003-003, FR-003-019)
- [x] T027 [US3] Verify T022 and T024 pass against T010/T012's existing per-statement `lock_timeout` and independent-commit behaviour; add any missing ERROR-logging or counting this job-level wiring still needs to surface a statement's `LOCK_TIMEOUT` cause correctly — makes T022, T024 pass (FR-003-016, FR-003-017)

**Checkpoint**: User Stories 1–3 together independently functional.

---

## Phase 6: User Story 4 — Every maintenance failure and every stranded body is visible (Priority: P2)

**Goal**: Every creation/removal is logged and counted; every failure carries a bounded cause; the DEFAULT row count and the job's own staleness are both visible independently of whether a run is in progress or has ever happened (2026-10-22 clarification, Option C+D).

**Independent Test** (spec.md): drive a run that creates one month and removes two, then runs with a creation failure, a removal failure, and DEFAULT rows left behind; assert the counters, the gauge, one ERROR line per failure, and that no job output carries any part of a body; separately, prevent any run from completing and assert the last-success-age gauge keeps climbing and pages at the threshold without any row needing to reach DEFAULT.

### Tests for User Story 4 (MANDATORY — write and run these first) ⚠️

- [x] T028 [P] [US4] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/DefaultPartitionGaugeIT.java` (Testcontainers Postgres) asserting `probation_body_default_partition_rows` is read and set on the service's own independent metrics-collection cycle — not only after a run holding the lock — reflects the database's current count immediately after a row is inserted directly into `outbound_body_default` with no run in progress, and never takes the single-runner lock — fails first (FR-003-022, FR-003-031, SC-003-014)
- [x] T029 [P] [US4] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/LastSuccessAgeGaugeTest.java` (plain unit) asserting `probation_body_job_last_success_age_seconds` reports a sentinel-high value before any run has completed successfully since process start, drops to (effectively) zero immediately after a run reaches its final commit, and keeps climbing on its own collection cycle without requiring a further run — fails first (FR-003-032, SC-003-014)
- [x] T030 [P] [US4] Extend the repository's existing log/metric privacy-sweep pattern (reused from 002's `LogStatementSweepTest`) in `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/MaintenancePrivacyTest.java` asserting no log line, metric label or exception message this job produces contains any part of a stored body, and no line at INFO or above carries the database's own error text — fails first (FR-003-024, SC-003-009, DV-09)
- [x] T031 [P] [US4] Write `BodyRetentionJobIT` signals scenario: a run that creates one partition and removes two raises the created/dropped counters by exactly those amounts with one log line per partition naming its month and no failure counted; a creation failure counts `step="create"`, a removal or DEFAULT-delete failure counts `step="drop"` (AS-7), each producing exactly one ERROR line naming step, month and cause — fails first (FR-003-017, FR-003-020, FR-003-021, FR-003-023, SC-003-008)

### Implementation for User Story 4

- [x] T032 [US4] Implement `maintenance/MaintenanceMetrics.java` directly against the raw Micrometer `MeterRegistry` (never `application/OutcomeMetrics` — see research.md "Metrics ownership", 2026-10-22): three counters (`partitionCreated()`, `partitionDropped()`, `maintenanceFailure(BodyMaintenanceStep)`, labelled `step`) and two function-gauges computed live at scrape time — `probation_body_default_partition_rows` via `PartitionMaintenanceRepository::countDefaultRows`, `probation_body_job_last_success_age_seconds` via `Duration.between(lastSuccessRef.get(), Instant.now())` where `lastSuccessRef` is an `AtomicReference<Instant>` initialised to a far-past sentinel and set only by `recordSuccess(Instant)`. Duplicate the four pre-reserved name strings from `config.MetricRegistry.METRIC_NAMES` as this class's own constants plus the fifth new one, and add a unit test asserting the two catalogues agree (test code may depend on `config.MetricRegistry`; production code never does) — makes T028, T029 pass (FR-003-031, FR-003-032)
- [x] T033 [US4] Wire `BodyRetentionJob` to call `maintenanceMetrics.recordSuccess(Instant.now())` only on reaching its final commit (never merely on starting), and to call `PartitionMaintenanceRepository.countDefaultRows()` after the DEFAULT-delete step for the run's own log line (T032's function-gauge remains the metric's own live system of record, read independently at every scrape) — makes T031 pass (FR-003-022, FR-003-023)
- [x] T034 [US4] Run the privacy sweep of T030 against every log statement added by T017, T020, T025, T026 and fix any violation it finds (none expected) (FR-003-024)

**Checkpoint**: User Stories 1–4 together independently functional; the alert signals slice 006 needs are in place.

---

## Phase 7: User Story 5 — The schedule and the retention are configuration, checked at startup (Priority: P2)

**Goal**: `distribution.body-retention` and `distribution.body-maintenance.cron` are validated at startup; an invalid value stops the service naming the property; the schedule runs in UTC regardless of the pod's own zone.

**Independent Test** (spec.md): start the application context across a matrix of valid/invalid settings and assert which start and which fail, naming the property; with the defaults, assert the next trigger time.

### Tests for User Story 5 (MANDATORY — write and run these first) ⚠️

- [x] T035 [P] [US5] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/BodyRetentionPropertiesTest.java` (plain unit) covering FR-003-025/026's full matrix: `P1M`, `P6M`, `P1Y`, `P1Y6M` start; `P0M`, `P30D`, `P6M1D`, `-P6M` and a non-period value fail startup naming `distribution.body-retention`; the default `0 15 2 * * *` and other valid six-field crons start; a five-field expression, a malformed expression and the scheduler's "disabled" value fail startup naming `distribution.body-maintenance.cron` — fails first
- [x] T036 [P] [US5] Extend `BodyRetentionPropertiesTest` asserting exactly these two `distribution.*` properties exist for this slice (no lock-timeout, lock-key or create-ahead-window property), and neither is mapped to a named environment variable, Appendix A's proposed `BODY_RETENTION`/`BODY_MAINTENANCE_CRON` included — fails first (FR-003-027, AS-2)

### Implementation for User Story 5

- [x] T037 [US5] Implement the `@ConfigurationProperties` record(s) for `distribution.body-retention` (`Period`, validated whole-months, ≥ `P1M`) and `distribution.body-maintenance.cron` (validated six-field cron), extending 002's startup validator so an invalid value stops startup naming the offending property — makes T035, T036 pass (FR-003-025, FR-003-026, FR-003-027)
- [x] T038 [US5] Wire `@Scheduled(cron = "${distribution.body-maintenance.cron}", zone = "UTC")` on `BodyRetentionJob`'s trigger method, with UTC fixed in code, never itself a property — makes the schedule assertion of T035 observable end-to-end and holds under clock changes (FR-003-001, FR-003-026)

**Checkpoint**: User Stories 1–5 together independently functional.

---

## Phase 8: User Story 6 — A DEFAULT-partition spill can be recovered by a documented procedure (Priority: P3)

**Goal**: When bodies have landed in DEFAULT because their month was never created, every run's creation attempt for that month fails and pages until an operator follows the documented recovery; afterwards the next run is clean.

**Independent Test** (spec.md): put bodies for a month with no partition into DEFAULT, confirm a run counts a `create` failure for that month, apply the documented procedure, run again and assert no failure, the gauge at 0, and every moved body byte-identical and still matching its `body_digest`.

### Tests for User Story 6 (MANDATORY — write and run these first) ⚠️

- [x] T039 [P] [US6] Write `BodyRetentionJobIT` DEFAULT-spill scenario: bodies for a month with no partition sit in DEFAULT; a run's creation of that month is refused, logged ERROR with cause `DEFAULT_HAS_ROWS`, counted `step="create"`, the bodies remain stored, and `probation_body_default_partition_rows` stays non-zero (so the alert condition stays true) — fails first (FR-003-017; Edge Cases)
- [x] T040 [P] [US6] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/maintenance/BodyRetentionRecoveryIT.java`: after manually detaching the DEFAULT partition, creating the missing month with V1's name/bounds, moving its rows in and re-attaching DEFAULT, the next run completes with no failure, `probation_body_default_partition_rows` reads 0, and every moved body is unchanged and still accepted against its attempt's `body_digest` — fails first (FR-003-028)

### Implementation for User Story 6

- [x] T041 [US6] Write the "Body partition maintenance" alert's operations recovery documentation (in the location slice 006's alert documentation expects): detach the DEFAULT partition, create the missing month with V1's name/bounds, move its rows in, re-attach DEFAULT, in that order; state that the procedure runs with intake paused (AS-9) — using the scaffold's `distribution.consumer.enabled` switch until 004's pause/resume runners exist; state that DEFAULT rows older than the cut-off are still deleted by the next run regardless of the procedure — makes T040's documented-procedure assertion meaningful (FR-003-028)

**Checkpoint**: all six user stories independently functional.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [x] T042 [P] Run `quickstart.md` end to end against a local Testcontainers/docker-compose stack and fix any drift between it and the actual class/property names — **verified, no drift**: `BodyRetentionJob.run()`, both `distribution.body-retention`/`distribution.body-maintenance.cron` properties and all five metric names match quickstart.md exactly; the quickstart's scenarios are exercised by the existing Testcontainers IT suite (`BodyRetentionJobIT`, `DefaultPartitionGaugeIT`, `LastSuccessAgeGaugeTest`, `BodyRetentionRecoveryIT`)
- [x] T043 [P] Add this slice's five metrics (`probation_body_partitions_created_total`, `probation_body_partitions_dropped_total`, `probation_body_maintenance_failures_total{step}`, `probation_body_default_partition_rows`, `probation_body_job_last_success_age_seconds`) to the repository's monitoring documentation, if one exists — **already satisfied**: `doc/operations/body-partition-maintenance.md` (T041) documents all five in its series table; no other monitoring doc exists in the repo
- [x] T044 Run `./gradlew build` (full suite + Checkstyle + PMD + JaCoCo ≥88% line / ≥85% branch) and fix any gate failure — green: fixed 6 Checkstyle import-order/indentation warnings and 7 PMD findings (constant-name, literal-in-if, duplicate-literal, two unnecessary `@SuppressWarnings`, object-in-loop, lambda-to-method-reference) surfaced across T001–T041's work; full suite, Checkstyle, PMD and the JaCoCo coverage gate all pass
- [x] T045 Update `doc/DEVIATIONS.md` only if implementation surfaces a genuine registrable difference from the design — none is expected for this slice (spec.md: "Not a deviation-register event") — **confirmed**: no registrable difference surfaced; `doc/DEVIATIONS.md` unchanged, next free id stays DV-27

---

## Phase 10: Rework for H36–H38 / A3-118 / A3-119 (2026-10-24)

**Why**: `origin/main` carried the spec forward, past T001–T045's implementation, to the design
owner's 2026-10-23 answer to open question 2 (A3-118, A3-119) and three new decisions (H36 alert
delivery, H37 three months ahead, H38 the last-success state table), superseding the 2026-10-22
"Option C+D" gauge design T028–T034 built against. This phase brings the code in line with the
merged spec: no new user story, a correction of Phase 3's and Phase 6's prior work.

- [x] T046 Add Flyway `V2__create_body_maintenance_state.sql`: the one-row `body_maintenance_state`
  table (`last_success_at timestamptz NOT NULL`), seeded with the migration time; V1 unchanged (H38)
- [x] T047 Implement `maintenance/BodyMaintenanceStateRepository.java`: an independent
  `lastSuccessAt()` read (for the gauge) and `recordSuccessOnConnection(Connection)`, which MUST run
  on the single-runner lock's own connection under a savepoint — not `PartitionMaintenanceRepository`'s
  REQUIRES_NEW pattern — so a run's success commits together with the lock that computed it
  (FR-003-032); `BodyMaintenanceStateRepositoryTest` (unit, savepoint/rollback) and
  `BodyMaintenanceStateRepositoryIT` (Testcontainers) pin both paths
- [x] T048 Add `SingleRunnerLock.heldConnection()`, exposing the lock's own connection for T047's
  statement only (documented exception to "never share this connection")
- [x] T049 Change the create-ahead window from the current month + two to the current month + three
  (H37) in `BodyRetentionJob`; add `PartitionCatalogue.monthsAhead(OffsetDateTime)` (instance, one
  catalogue read) and its pure static form over an already-read partition list, shared by the job's
  start-of-run check and `MaintenanceMetrics`' independent gauge
- [x] T050 Add `sqlState` to `PartitionMaintenanceException` (extracted from the SQLException found in
  the cause chain, or `unavailable`), and carry it on every ERROR line (`BodyRetentionJob`,
  `BodyMaintenanceStateRepository`) per A3-119; extend `MaintenanceSignalsTest` to assert it
- [x] T051 Add the A3-119 log lines to `BodyRetentionJob`: one INFO start line on every trigger, one
  INFO skip line when the lock is not acquired, one INFO summary line (`created`, `dropped`,
  `failed`, `monthsAhead`, `defaultRows`, `durationMs`) on a run that held it; a start WARN below two
  whole months ahead and an end WARN below three with no failure, both reading the catalogue fresh,
  never the metrics cache (H37); a `state`/`read` failure (T047's update, or a fresh end-of-run read)
  is logged and summarised but never counted on `probation_body_maintenance_failures_total`
  (FR-003-021's step set stays closed) — pinned by the new `BodyRetentionJobSignalLinesTest`
- [x] T052 Rework `MaintenanceMetrics`: remove the old in-process `recordSuccess`/last-success-age
  gauge; add `CachedGaugeRead` (a five-minute-TTL cache over a database read, `-1` and no caching on
  failure — a short TTL is injectable package-private for tests) and two new gauges,
  `probation_body_partitions_ahead_months` and `probation_body_maintenance_last_success_seconds`
  (epoch seconds, read from `BodyMaintenanceStateRepository`); `probation_body_default_partition_rows`
  moves onto the same cached-read path. `MaintenanceMetrics.METRIC_NAMES` grows from five to six.
  Renamed/rewritten `MaintenanceCachedGaugesTest` (was `LastSuccessAgeGaugeTest`) and rewritten
  `DefaultPartitionGaugeIT` pin the cache window and the `-1` sentinel
- [x] T053 Wire the new `BodyMaintenanceStateRepository` bean through `BodyRetentionConfiguration`
  into both `MaintenanceMetrics` and `BodyRetentionJob`; add it to the `@MockitoBean` lists in
  `ServiceBusReadinessIT` and `TestProfileContextTest` alongside the other maintenance beans
- [x] T054 Update every test call site for the widened `BodyRetentionJob`/`MaintenanceMetrics`
  constructors (`BodyRetentionJobTest`, `BodyRetentionJobLogLineTest`, `MaintenanceSignalsTest`,
  `MaintenancePrivacyTest`, `MaintenanceTestStore` and its users); fix the window-dependent fixtures
  this uncovered (`BodyRetentionCalendarIT`'s DST test now creates the fourth month ahead, not the
  third)
- [x] T055 Update `doc/operations/body-partition-maintenance.md`'s series table and signal
  description for A3-118/A3-119/H36–H38 (the runbook's recovery SQL itself is unchanged: V1's schema
  and the advisory lock key are untouched by this rework); update `data-model.md` and `plan.md` for
  the new `body_maintenance_state` table, the three-months-ahead window and the six-metric set
- [x] T056 Run `./gradlew build` (full suite + Checkstyle + PMD + JaCoCo) and fix any gate failure —
  green: fixed a Mockito strict-stubbing gap in two new tests (missing `lenient()` on a
  single-month `createPartition` stub, now one of four months tried under the widened window), a
  `MaintenanceSignalsTest` fixture that left two months missing instead of one under the same
  widened window, and 12 PMD findings (an unused local, an unnecessary `@SuppressWarnings`, missing
  `/* default */` comments on three package-private members, four unnecessary numeric casts
  superseded by method references, a field/method name collision on `PartitionMaintenanceException`
  — renamed the field, not the public `sqlState()` accessor — the same collision on
  `SingleRunnerLock` — renamed the method to `heldConnection()` — an `OnlyOneReturn` finding on the
  new `sqlState`-extraction loop, two mocked-resource `CloseResource` findings in the new repository
  unit test, two unused-variable findings in the rewritten gauge IT, and one field declared after a
  method in `MaintenanceTestStore`)

### Review loop — 2026-10-24 (Phase 10)

A code-reviewer / qa / spec-validator pass was run against Phase 10's uncommitted working tree.
spec-validator: **COMPLIANT** (0 HIGH, 0 MEDIUM; one LOW naming nit on an existing test, not this
phase's code). code-reviewer: **PASS** (0 HIGH, 2 MEDIUM, 1 LOW), all three fixed:

1. `package-info.java` still described the two-months-ahead window and cited the superseded
   2026-10-22 session; updated to the three-months-ahead window (H37) and H36–H38/A3-118/A3-119.
2. The `state`/`read` failure branch of `recordSuccessAndSummarise` (a failed fresh end-of-run read,
   or a failed `body_maintenance_state` update) had no test. Added three cases to
   `BodyRetentionJobSignalLinesTest`: a failed months-ahead read, a failed DEFAULT-rows read, and a
   failed state update — each asserting the `step=state`/`step=read` ERROR line with its `sqlState`,
   the inflated summary `failed=` count, and that `metrics.maintenanceFailure(...)` is never reached
   (`verifyNoInteractions(metrics)`).
3. An inline fully-qualified `java.sql.SQLException` in `PartitionMaintenanceException` replaced
   with a proper import, for consistency with the sibling `BodyMaintenanceStateRepository.java`.

qa: **FAIL**, on coverage gaps only (all existing tests green, no logic defect found). Closed:

- `SingleRunnerLock.heldConnection()` was untested: added a unit case for the `IllegalStateException`
  before acquisition and a case confirming it returns the exact connection the lock acquired on.
- The real end-to-end wiring (`BodyRetentionJob` → `lock.heldConnection()` →
  `BodyMaintenanceStateRepository.recordSuccessOnConnection`) was never exercised against real
  PostgreSQL — only the repository's own connection-management, and the job's call-or-not decision
  under mocks. Added `BodyRetentionJobIT.a_successful_run_advances_the_real_last_success_state`,
  asserting `body_maintenance_state.last_success_at` genuinely advances after a real successful run.
- `PartitionCatalogue.monthsAhead`'s "stops at the first gap" semantics had no gap-shaped fixture —
  every existing case was either zero or full contiguous coverage. Added
  `PartitionCatalogueMonthsAheadTest` (six cases: zero, full, a gap immediately after some coverage,
  a gap further out, a partial-overlap exclusion, and DEFAULT-partition exclusion). This surfaced two
  wrong expectations in the first draft of that new test, not a production defect: the method already
  counted a single leading run of consecutive coverage correctly.
- qa also flagged that Phase 10 (T046–T056) carries no recorded TDD red-run evidence, unlike
  T001–T045, and that nothing in this phase is committed yet so there is no commit history to audit
  either way. **Not closed**: this is a process gap for the repository owner to decide on (accept as
  a documented exception, as T001–T027 were on 2026-10-24, or require another form of evidence)
  rather than one this session can resolve unilaterally by asserting an exception on the owner's
  behalf.

`./gradlew build` green after every fix above, including the full `maintenance` suite, Checkstyle,
PMD and the JaCoCo coverage gate.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately once the blocking dependency above is resolved
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3–8)**: All depend on Foundational phase completion
  - US1 and US2 are the P1 pair (spec.md: both "it is the commitment..." priority); implement in order since US2's removal step and US1's creation step both live in `BodyRetentionJob` and are naturally sequenced by the task list, but each has its own independent test
  - US3–US5 (P2) build on US1+US2's `BodyRetentionJob` skeleton; US6 (P3) builds on all of the above
- **Polish (Phase 9)**: Depends on all six user stories being complete

### User Story Dependencies

- **US1 (P1)**: Can start after Foundational — no dependency on other stories
- **US2 (P1)**: Can start after Foundational; shares `BodyRetentionJob.java` with US1 but is independently testable (its own Acceptance Scenarios, its own independent test in spec.md)
- **US3 (P2)**: Needs US1+US2's create/remove steps to exist as something to wrap in the lock
- **US4 (P2)**: Needs US1–US3's log/count call sites to extend with the two independent gauges
- **US5 (P2)**: Independent of US1–US4's job logic (it validates configuration and wires the trigger), but `BodyRetentionJob`'s trigger method (T038) needs the job itself to exist
- **US6 (P3)**: Needs US1's `PARTITION_OVERLAP`/`DEFAULT_HAS_ROWS` failure paths (T017) and US4's gauge (T032) to assert against

### Within Each User Story

- Tests MUST be written, run, and observed to FAIL before the implementation task they guard
- `BodyRetentionJobTest` (plain mocks) before `BodyRetentionJobIT` (Testcontainers) within a story, where both exist, since the unit test pins decision logic cheaply before the slower integration test proves real database behaviour
- Story complete before moving to the next priority, though US1/US2 implementation tasks (T017, T020) land in the same file and are naturally done together

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- Within Foundational: T004/T005/T007/T009/T011/T013 (the test-writing tasks) can run in parallel with each other, each gated only on its own paired implementation task
- Within a user story, all [P]-marked test tasks can run in parallel
- US4's four test tasks (T028–T031) are independent of each other and can run in parallel
- US5 can be worked in parallel with US3/US4 once Foundational is done, since it touches `config/` rather than `maintenance/`'s job logic

---

## Parallel Example: Foundational Phase

```bash
# Launch the Foundational phase's test-writing tasks together (each gated on its own implementation task):
Task: "DatabaseClockIT in src/test/java/.../maintenance/DatabaseClockIT.java"
Task: "PartitionCatalogueIT in src/test/java/.../maintenance/PartitionCatalogueIT.java"
Task: "SingleRunnerLockIT in src/test/java/.../maintenance/SingleRunnerLockIT.java"
Task: "PartitionMaintenanceRepositoryIT in src/test/java/.../maintenance/PartitionMaintenanceRepositoryIT.java"
Task: "MaintenanceRunOutcomeTest in src/test/java/.../maintenance/MaintenanceRunOutcomeTest.java"
```

## Parallel Example: User Story 4

```bash
# Launch all four US4 test tasks together (they precede T032-T034):
Task: "DefaultPartitionGaugeIT in src/test/java/.../maintenance/DefaultPartitionGaugeIT.java"
Task: "LastSuccessAgeGaugeTest in src/test/java/.../maintenance/LastSuccessAgeGaugeTest.java"
Task: "MaintenancePrivacyTest in src/test/java/.../maintenance/MaintenancePrivacyTest.java"
Task: "BodyRetentionJobIT signals scenario (extends the existing IT class)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Resolve the blocking dependency (merge or rebase onto 002's actual code)
2. Complete Phase 1: Setup
3. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
4. Complete Phase 3: User Story 1
5. **STOP and VALIDATE**: run User Story 1's independent test
6. Deploy/demo if ready — note that without US2, bodies accumulate without being removed, so US1-only is a demo increment, not a production-ready one (the retention commitment to the data owner, row A3-65, needs US2 too)

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. US1 → test independently (creation-ahead works)
3. US2 → test independently → together, US1+US2 satisfy the data owner's retention commitment (row A3-65) — the natural first deployable increment
4. US3 → test independently → safe with 2 replicas
5. US4 → test independently → alertable (slice 006 can now build its rule)
6. US5 → test independently → configuration is validated, not just defaulted
7. US6 → test independently → the DEFAULT-spill runbook is provable end to end

### Parallel Team Strategy

With multiple developers, after Foundational is done:

- Developer A: US1 then US2 (same file, `BodyRetentionJob.java`, sequenced)
- Developer B: US5 (a different file, `config/`, fully independent of A's work)
- Once A finishes US1+US2: Developer A continues to US3, Developer B picks up US4, a third developer takes US6 once US1's failure paths and US4's gauge exist

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- `BodyMaintenanceStep` is reused from slice 002's `domain/` package — never redefined here (FR-003-021, FR-002-072)
- Verify tests fail — for the right reason — before implementing, and record the red run in the task's commit or PR narrative, per this repo's TDD reconstruction convention
- Commit after each task or logical group
- Stop at any checkpoint to validate a story independently
- Avoid: vague tasks, same-file conflicts without a sequencing note, cross-story dependencies that break independence

<!-- token-budget: compacted (level=medium) on 2026-10-22T18:42:16Z; original at tasks.full.md -->


## Implementation evidence — 2026-10-23

Baseline: T002–T027 were already checked and most maintenance sources/tests were untracked at
session start. Their historical test-first order cannot be established from this branch's history;
this session does not claim retrospective RED evidence. Baseline maintenance selection reproduced
`BodyRetentionJobTest.records_drop_metrics`' Mockito strict-stubbing fixture error, then a similar
creation fixture error; both were corrected by explicitly stubbing the successful calls too.

Observed RED before the corresponding production changes:

- US4: `./gradlew test --tests '*MaintenancePrivacyTest' --tests '*BodyRetentionJobTest.classifies_a_lock_acquisition_failure' --tests '*BodyRetentionJobTest.records_success_only_on_completion' -Dtest.noFailFast=true`:
  4 tests, 4 failures — missing LOCK counter, missing post-delete count, raw cause retained, connection
  loss classified OTHER. Compilation succeeded. Production was then changed to meet these assertions.
- Review regressions: `./gradlew test --tests '*SingleRunnerLockTest' --tests '*MaintenancePrivacyTest' --tests '*PartitionCatalogueIT.other_schema*' --tests '*PartitionCatalogueIT.unsupported_bound*' --tests '*BodyRetentionJobIT.standalone*' -Dtest.noFailFast=true`:
  8 tests, 6 expected assertion failures — ignored rollback/close failures, raw exception text escaping,
  unrelated schema children included, unsupported bounds treated as DEFAULT, existing standalone table
  counted as created. Production fixes followed this run.

One early integration attempt could not connect to Testcontainers Ryuk; the subsequent invocation
started PostgreSQL successfully. That infrastructure error is not counted as a TDD RED result.

US4 GREEN: `./gradlew test --tests '*maintenance.*' --tests '*LogStatementSweepTest' -Dtest.noFailFast=true` passed on 2026-10-23. Includes live PostgreSQL gauges while the advisory lock is held, the simulated three-day age threshold with no DEFAULT rows, and exact ERROR/counter/privacy assertions for all four failure paths. T028–T034 complete.

US5 RED: `./gradlew test --tests '*BodyRetentionPropertiesTest' -Dtest.noFailFast=true`: 19 tests, 18 assertion failures (missing scheduled job for valid values; invalid values wrongly allowed). Empty configuration seam only; no binding/validation/scheduler implementation existed at that run. T037–T038 implemented afterward.

US5 GREEN: `./gradlew test --tests '*BodyRetentionPropertiesTest' --tests '*StartupValidationTest' -Dtest.noFailFast=true` passed (31 tests). Valid/invalid periods and six-field crons, disabled and macro cron rejection, registered UTC trigger across DST, unchanged property surface, and intake-independent composition are covered. T035–T038 complete.

US6 RED: `./gradlew test --tests '*BodyRetentionRecoveryIT'`: the spill was refused and counted as expected, then the assertion failed because `doc/operations/body-partition-maintenance.md` did not exist. The executable recovery procedure was written afterward.

Transaction regression RED: `./gradlew test --tests '*PartitionMaintenanceRepositoryIT.drop_should_commit*'`: assertion failed because the dropped partition reappeared after the outer transaction rolled back. Repository statements now use an independent REQUIRES_NEW template.

Gauge privacy RED: `./gradlew test --tests '*MaintenancePrivacyTest.gauge_read_failure*'`: expected a bounded maintenance exception, received raw `CannotGetJdbcConnectionException` containing the synthetic marker. The independent count now classifies and sanitises read failures before Micrometer can log them.

US6 and extended acceptance GREEN: maintenance + BodyRetentionPropertiesTest selection passed after fixing the isolated-schema test URL (append with `&` when JDBC URL already has parameters; no public-schema fallback). Recovery test executes the SQL directly from the runbook and verifies identical bytes, digest acceptance, zero DEFAULT rows and unchanged attempt records. Calendar, deterministic overlap and independent-commit tests pass. T039–T041 complete.

Final failure-path RED evidence:

- Commit telemetry + quoted identifier tests: 2 tests, 2 assertion failures. Commit failure escaped
  unsanitised/uncounted; quoted catalogue identifier prevented the expired partition being dropped.
- Transaction boundary / blocked count selection: 9 tests, 9 assertion failures. Begin/commit failures
  bypassed classification; metrics COUNT exceeded its seven-second test deadline. Added typed
  transaction classification and a separately committed five-second lock timeout for COUNT.
- The database-free context fixture failed because maintenance now correctly requires store beans.
  Only those tests substitute mock maintenance collaborators; production wiring remains unconditional
  and independent of intake. No test profile or production disable property was introduced.
- Asia/Kolkata catalogue-instant comparison passed before any parser change: the suggested offset
  issue was not reproduced, so no speculative production change was made.

## Review loop — 2026-10-24

A code-reviewer / qa / spec-validator pass was run against the uncommitted working tree.
code-reviewer: **PASS** (0 HIGH, 0 MEDIUM, 4 LOW advisory). spec-validator: **COMPLIANT** (0 HIGH,
0 MEDIUM, 1 LOW — a deliberate, tested design choice, not a defect). qa: **FAIL**, for two reasons:

1. A genuine defect: `BodyRetentionJob.run()`'s catch around `lock.tryAcquire()` hardcoded
   `MaintenanceFailureCause.STORE_UNAVAILABLE` instead of reusing the class's own `boundedCause()`
   classifier used on every other failure path, so a `LOCK_TIMEOUT` or `OTHER` lock-acquisition
   failure would have been misreported. Fixed: the catch now calls `boundedCause(e)`;
   `BodyRetentionJobTest` gained `reports_the_lock_acquisition_failures_real_cause` and
   `falls_back_to_other_for_an_unclassified_lock_acquisition_failure` pinning the real classification
   against a mocked `PartitionMaintenanceException`/raw `RuntimeException` respectively. Full
   `./gradlew build` green afterward.
2. A TDD-evidence gap: T001–T027 (Setup, Foundational, User Story 1, User Story 2, and User Story
   3's initial lock-wiring) have no retrospective red-run evidence, and since none of this slice is
   committed yet there is no commit history to supply it another way.

**Decision (2026-10-24, repo owner)**: accepted as a documented historical exception, on the same
basis as `7beb44a`'s T065–T104 exception for `002-hmpps-distribution`. T001–T027's working-tree
state is not reconstructed task-by-task. This is accepted on the strength of the independent
evidence above — a PASS code review, a COMPLIANT spec-validator pass, the one genuine defect this
loop found and fixed with real observed red/green runs, and a green `jacocoTestReport
check`/`build` — not on trust that the uncommitted foundation was written test-first. The
constitution's Principle II (TDD, non-negotiable) governs all new work on this slice from this
point forward without exception; this entry records why this one historical gap is closed without
reconstruction rather than asserting the principle was followed when T001–T027 were originally
written.
