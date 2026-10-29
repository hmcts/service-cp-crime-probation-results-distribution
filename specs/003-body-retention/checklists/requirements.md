# Specification Quality Checklist: Stored-body retention — the daily `outbound_body` maintenance job

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the spec names what the design fixes
      for this job (A3-64; H35): the two properties, the six `probation_body_*` metrics and their
      `step` label, the one-row maintenance-state table (H38), V1's partition names and bounds, and the PostgreSQL mechanisms the design
      prescribes (a transaction-scoped advisory lock, a lock timeout, partition bounds read from the
      catalogue, a plain `DROP` of an expired partition in its own transaction). The design's SQL is
      quoted only where the design is cited (the Clarifications and open question 1), and the slice
      plan's own words in the Input line, never as a requirement. It chooses no class, package,
      library or framework: where the job sits is left to the plan (assumption AS-1), and so is
      whether the statements other than the removals share the lock's transaction (AS-13)
- [x] Focused on user value and business needs — the retention commitment given to the data owner
      and the DPIA, the investigation window for operations, and capacity for the platform
- [x] Written for non-technical stakeholders — the stories, edge cases and success criteria are in
      plain terms; the requirements keep the design's own vocabulary (partition, cut-off, lock), as
      this project's specs do for a service whose contracts and names the design fixes
- [x] All mandatory sections completed — plus Context, Clarifications, Dependencies, Out of Scope
      and Design traceability; the open questions, with their options, are in this checklist

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — **none remain**. The one there was, FR-003-011
      (open question 1: how an expired month is removed, since the design's concurrent detach then
      `DROP` cannot run on this table), was answered by the design owner on 2026-10-21 with option
      A, recorded in the design as H35 (amending A3-64 (b)): a plain `DROP` of the expired
      partition, each in its own short transaction under the 5 s lock timeout. FR-003-011 now
      states it.

      Open question 2 — how a job that never runs, or a DEFAULT reading left stale on the pod that
      skipped, is noticed — was never a marker, and was answered by the design owner on 2026-10-23
      (A3-118, with A3-119, H36 and H37): FR-003-022, FR-003-030 … FR-003-032 and AS-15 now state
      it. Both questions, with their options and answers, are at the end of this checklist.

      Twenty-two points were settled in the Clarifications session of 2026-10-21, each with its
      design reference: eighteen answered from the design (the inferred ones say so); one by the
      design owner's answer to open question 1 (H35); one answered as far as the design goes, which
      since H35 fixes the removals' transactions but not whether the run's other statements share
      the lock's (AS-13); and two the design does not establish, carried as assumptions (the
      failure causes, AS-14; the job's package and the properties' binding, AS-1 and AS-2). A further
      point was settled in the session of 2026-10-22: open question 2 itself, closing AS-15.
- [x] Requirements are testable and unambiguous — every requirement states an outcome a test can
      check, and a failure's cause is one of a closed set (FR-003-017; AS-14). FR-003-011 states
      the removal H35 decides — a plain `DROP`, committed in its own transaction while the lock's
      transaction stays open, under the lock timeout and retried on the next run — with its failure
      behaviour (FR-003-012, FR-003-016, FR-003-017); and the three body gauges are read at collection
      on every pod, with `-1` for a failed read (FR-003-022, FR-003-031, FR-003-032), as open
      question 2's answer (A3-118, H38) decides
- [x] Success criteria are measurable — each has a count, a duration, a percentage or a test matrix
      (SC-003-001 … SC-003-015); SC-003-013's bound is stated for a removal, which commits on its own
      (H35), and for a creation, whichever way the plan arranges it (AS-13)
- [x] Success criteria are technology-agnostic (no implementation details) — they speak of bodies,
      months, runs, failures and settings; "partition" appears only in SC-003-012, as the unit in
      which a month's storage is read
- [x] All acceptance scenarios are defined — six stories, 37 Given/When/Then scenarios
- [x] Edge cases are identified — 20, including the cut-off on a bound, the turn of the year, a
      non-UTC database session (checked against PostgreSQL 15), clock changes, missed runs, a
      schedule that never fires, which months DEFAULT can hold at all, DEFAULT rows of a current or
      a past month, a partition overlapping a month to be created, and a re-run racing a removal
- [x] Scope is clearly bounded — Out of Scope and Dependencies separate this slice from 002 (V1 and
      the body write path) and from 006 (the alert rule, its routing and the dashboard)
- [x] Dependencies and assumptions identified — four slices, the external parties, and AS-1 …
      AS-15

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria — each of FR-003-001 … FR-003-028
      and FR-003-030 … FR-003-032 (FR-003-029 withdrawn)
      is exercised by a scenario of User Stories 1–6 — FR-003-030 … FR-003-032 by User Story 4
      scenarios 6–9: among them FR-003-007 by User Story 1
      scenarios 2 and 4 (a present month left alone; an overlapping creation refused and counted),
      FR-003-011 by User Story 3 scenarios 4 and 7 (a removal that gives up is retried; a removal
      commits on its own while the lock is held, H35), FR-003-015 by User Story 2 scenario 4 (no
      DEFAULT row moved, no past month created) and FR-003-027 by User Story 5 scenario 6 (no other
      property, no named environment variable). The Design traceability table ties each
      requirement to its design row
- [x] User scenarios cover primary flows — creation ahead, removal, single runner and bounded waits,
      signals, configuration, and recovery from a DEFAULT spill
- [x] Feature meets measurable outcomes defined in Success Criteria — every criterion is an outcome
      of this slice (SC-003-012 reads the storage of the months this slice keeps in their own
      partitions), and they hold under the answer to question 1 (option A, H35, which keeps the
      DEFAULT partition, so nothing is withdrawn) and under the answer to question 2 (A3-118, H37, H38)
- [x] No implementation details leak into specification — as the first item: only what the design
      fixes

## Notes

- Validation run 1 (2026-10-21): every item passes except the markers item, which stays open on the
  two design gaps above.
- Validation run 2 (2026-10-21, after review):
  - User Story 2's fixtures put DEFAULT rows only in months with no partition.
  - SC-003-013 and option A of open question 1 now say that the lock timeout bounds the wait for a
    lock, not how long it is held; AS-13 names the two arrangements of the run's statements.
  - FR-003-007 follows V1's own create-if-absent rule, by name, instead of an inferred bounds rule,
    and an overlapping creation fails as `partition-overlap`.
  - FR-003-017 closes the set of failure causes (AS-14).
  - Open question 1 cites the PostgreSQL manual, and option C states its consequence for the lock.
  - The second marker moved from FR-003-022 to the new FR-003-029.
  - The recovery's dependency on the pause and resume runners is stated (AS-9; Dependencies).
  - FR-003-015 and FR-003-027 have scenarios, and SC-003-012 is tied to this slice.
  - FR-003-009, FR-003-012 and FR-003-024 are reworded.

  Every item passes except the markers item, which stays open on FR-003-011 and FR-003-029.
- PostgreSQL facts the spec relies on. The two restrictions on a concurrent detach are stated in the
  PostgreSQL manual (ALTER TABLE, DETACH PARTITION). They were confirmed against a PostgreSQL 15
  instance on 2026-10-21, together with the following:
  - creating a month is refused while DEFAULT holds rows of that month;
  - a DEFAULT row cannot belong to a month that has a partition: a body for that month goes to the
    partition, and a direct write of it into DEFAULT is refused;
  - V1's create-if-absent skips a taken name whatever that table covers, and the creation is refused
    when another partition covers all or part of the month;
  - a plain `DROP`, and a plain `DETACH` then `DROP`, work with the DEFAULT partition in place and
    inside the transaction holding the advisory lock;
  - the error for a refused write quotes the failing row, which is why the database's own error text
    stays out of every line at INFO or above (AS-14);
  - the design's cut-off expression, compared with the bounds in a UK session zone, keeps a month
    for a month too long unless it is read as a UTC instant;
  - a plain `DROP` of a month takes `ACCESS EXCLUSIVE` on `outbound_body` and on its DEFAULT
    partition. Run on its own connection while the lock's transaction holds only the advisory lock,
    it completes at once; once that transaction has created a month, deleted DEFAULT rows or read
    the DEFAULT count, the `DROP` gives up after the 5 s lock timeout (checked on PostgreSQL 15.14
    the same day, for H35; AS-13).
- Before `/speckit-plan`: open question 1 is answered (option A, H35, 2026-10-21), so the tasks for
  FR-003-011 can be written, and slice 002 writes V1 unchanged (002's FR-002-054). Open question 2 is
  also answered (option C and D combined, slice 003 clarification, 2026-10-22; FR-003-031,
  FR-003-032), so AS-15 is closed rather than carried. The plan also records where the job sits
  (AS-1) and whether the creations, the DEFAULT delete and the gauge's read share the lock's
  transaction, within the constraint AS-13 states. The whole slice can be planned now.
- Raised with the design owner alongside the two questions, as assumptions rather than markers: the
  recovery procedure runs with intake paused (AS-9); a failed delete of DEFAULT rows is counted
  `drop` (AS-7); whether a `lock` failure pages is the alert rule's (AS-8); the failure causes are
  this spec's (AS-14).
- Cross-slice (closed in the cross-spec pass): the pause and resume runners that the recovery
  procedure uses (§4.8 action 4) are 004's (FR-004-042, FR-004-051), as AS-9 and Dependencies now
  say.
- Validation run 3 (2026-10-21, cross-spec pass). The cross-slice findings were checked against
  the DIGEST and the design, then applied:
  - FR-003-029 is withdrawn. Its marker spent a clarification where the design's default stands
    (every signal comes from a run, A3-64; §10.1), which DIGEST §16 classes as "a default stands, so
    nothing is blocked". The point is carried as assumption AS-15 and open question 2, for the
    design owner, and FR-003-022 stands as designed. One marker remains (FR-003-011).
  - The open questions moved from `spec.md` into this checklist, the form every slice now uses.
  - FR-003-006 and FR-003-007 cite 002's FR-002-054 for V1's names, bounds and create-if-absent
    rule; FR-003-020, FR-003-021 and Dependencies cite 002's FR-002-057 and FR-002-072 for the
    series and the step label set this slice only increments.
  - Dependencies states the V1 ordering of open question 1: it is answered before V1 is written,
    and a late answer is a V2 migration.
  - AS-9 and Dependencies name 004's pause and resume runners (FR-004-042, FR-004-051).
  - The Key Entity "Attempt row" is renamed "Leg attempt", as slices 002 and 004 name it.

  Every item passes except the markers item, which stays open on FR-003-011.
- Validation run 4 (2026-10-21, open question 1 answered). The design owner chose option A,
  recorded in the design as H35 (amending A3-64 (b)), and the spec was updated to match:
  - FR-003-011's marker is replaced by the decided rule: a plain `DROP` of each expired partition,
    in its own short transaction that commits at once, under the 5 s lock timeout, never in the
    lock's transaction, and retried on the next run; the DEFAULT partition stays and V1 is
    unchanged. A Clarifications point records the answer.
  - AS-13 is resolved for removals, as its arrangement (b). The design says nothing of the other
    statements, so their arrangement stays the plan's, within a constraint checked against
    PostgreSQL 15: while a removal runs, the lock's transaction holds no lock on `outbound_body` or
    its DEFAULT partition.
  - FR-003-002, FR-003-012, FR-003-016, FR-003-017, SC-003-013, User Story 3 (a new scenario 7),
    the edge case of a long transaction, Dependencies and the traceability table (a new H35 row;
    A3-64 (b) and A3-105 reworded) follow it.
  - Slice 002's FR-002-054, Assumptions, Dependencies and traceability table, and its checklist,
    now say that the question is answered and that V1 keeps the DEFAULT partition.

  Every item passes; no marker remains.
- Validation run 5 (2026-10-23, open question 2 answered; A3-118, A3-119, H36, H37). The spec was
  updated to match:
  - FR-003-022 reads the DEFAULT row count at collection on every pod; new FR-003-031 (months ahead,
    read at collection) and FR-003-032 (last success, per pod), registered by this slice; FR-003-023
    adds the two alert conditions; AS-15 is rewritten as answered.
  - FR-003-017 adds `sqlState`; FR-003-020 adds the start, skip and summary lines; FR-003-024 widens
    the allowed fields; new FR-003-030, the two months-ahead WARNs (start below two, end below
    three). FR-003-029 stays withdrawn and is not reused.
  - H37: three months kept ahead — FR-003-006, FR-003-027, SC-003-003, User Story 1 (independent
    test and scenarios 1–3), User Story 6, the edge cases and AS-6. V1 (002's FR-002-054) is
    unchanged; the job's first run adds the third month.
  - User Story 4 scenarios 6–9, SC-003-014, SC-003-015, a Clarifications session, the Input line,
    Dependencies and the traceability table (rows A3-118, A3-119, H36, H37) follow.
  - An independent review then found that a per-pod last-success value is lost on a restart and has
    none at first deployment: H38 keeps it in a one-row table created and seeded by this slice's
    Flyway `V2` (FR-003-032; V1 unchanged; slice 002 has no `V2`). Also from that review: a failed
    gauge read reports `-1` and is not cached; the WARNs and the summary read the catalogue fresh,
    not the metrics cache; the database's error text is never logged at any level and `sqlState` is
    `unavailable` when the driver gives none; "months ahead" is consecutive months everywhere;
    SC-003-015 states the cache, export and evaluation delays.

  Every item passes; no marker remains.
- This spec was written directly in `specs/003-body-retention/`. `.specify/feature.json` does not
  point at it; a developer starting the feature branches `003-body-retention` from `main` (or sets
  `feature.json`) before running `/speckit-plan`.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.

## Open questions

Two points could not be answered from the design, and both went to the design owner. Question 1
blocked FR-003-011; the design owner answered it on 2026-10-21 with option A, recorded in the
design as H35, and FR-003-011 now states it. Option C, which would have removed the DEFAULT
partition and changed V1 in slice 002 (002's FR-002-054), was not chosen: V1 is unchanged.
Question 2 was never a marker; the design owner answered it on 2026-10-23 (A3-118, H37, H38), and
FR-003-022, FR-003-023, FR-003-031, FR-003-032 and AS-15 now state it.

### Question 1: How is an expired month removed? (FR-003-011; answered, H35)

**Context**: Before H35, A3-64 (b) and DIGEST §8 removed an expired partition with `DETACH PARTITION
… CONCURRENTLY`, then `DROP`, preferring the concurrent detach because it does not take the
strongest lock on `outbound_body`. It cannot run on this table. The PostgreSQL manual (ALTER TABLE, DETACH
PARTITION) states that `CONCURRENTLY` "cannot be run in a transaction block and is not allowed if the
partitioned table contains a default partition", and a check against PostgreSQL 15 on 2026-10-21
confirmed both:

- PostgreSQL refuses a concurrent detach while the partitioned table has a DEFAULT partition
  ("cannot detach partitions concurrently when a default partition exists"), and V1 creates
  `outbound_body_default` (row A3-63; Appendix B.2; 002's FR-002-054);
- a concurrent detach cannot run inside a transaction block, so it cannot share the transaction that
  holds the transaction-scoped single-runner lock (A3-64).

A plain `DROP` of the partition, and a plain `DETACH` followed by `DROP`, both work with the DEFAULT
partition in place and inside the lock's transaction.

**What we need to know**: which removal the design intends.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | A plain `DROP` of the expired partition, under the 5 s lock timeout: the design's own fallback for PostgreSQL 13 (A3-64 (b)) | One statement, atomic, so FR-003-012 holds by construction. It takes the strongest lock on `outbound_body` about once a month, as the creation of a new month already does. The lock timeout bounds the wait for that lock, not how long it is held: until the transaction that ran the statement commits, which is the end of the run if the run's statements share the lock's transaction (AS-13). V1 unchanged |
| B | A plain `DETACH PARTITION`, then `DROP`, in one transaction, under the lock timeout | The same locks as A, held as long, in two statements; atomic only inside one transaction (FR-003-012). V1 unchanged |
| C | No DEFAULT partition in V1 (slice 002), so the concurrent detach becomes possible, run outside the lock's transaction | A removal no longer queues the pipeline's writes. But a body for a month with no partition is then refused, so its send's outcome write fails after the POST (a possible duplicate at HMPPS); the DEFAULT gauge, its alert, the DEFAULT delete and the recovery procedure go; a detach interrupted between its two phases is left pending and needs its own completion step; and the single-runner lock must be held outside the detach's transactions. Either its transaction stays open on a connection of its own (AS-13 (b)), or the lock becomes session-scoped, a departure from A3-64's transaction-scoped lock: released by the run itself, and a run that fails to release it blocks every later run until its connection closes. V1 changes in slice 002; after V1 is applied, only by a V2 migration |
| Custom | Another removal | Stated by the design owner |

**Your choice**: A (H35, 2026-10-21) — each `DROP` in its own short transaction that commits at
once, under the 5 s lock timeout; the single-runner advisory lock stays on its own transaction for
the whole run (AS-13 (b) for removals); a timed-out `DROP` is retried on the next run; the DEFAULT
partition, its alert and its recovery stay; V1 unchanged. Applied in FR-003-011.

### Question 2: How is a job that does not run, or a stale reading, noticed? (AS-15; answered, 2026-10-22)

**Context**: every signal of the job comes from a run — "each run … gauges the DEFAULT partition's
row count, and counts failures by step" (A3-64; §10.1). Two consequences follow, and the design
addresses neither:

- a job that never runs — no pod up at the scheduled time, a valid schedule that never fires, a fault
  in the scheduler — counts no failure and changes no gauge, while bodies are held past the retention
  and, once the months created ahead have run out (two months or more), new bodies land in DEFAULT
  unseen;
- with two replicas, only the pod that takes the lock sets its gauge; the other keeps its last
  reading, so a spill already recovered can stay alerted, or a new one be masked, depending on how
  the alert rule (slice 006) combines the pods.

The design's default (option A) was not blocking — the slice could be planned while this was open —
but it is not what was chosen: the design owner chose option C and D combined (see below).

**What we need to know**: whether the DEFAULT reading should be kept current independently of the
lock, and whether a run that is overdue should be signalled.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | As designed: the pod that runs sets the gauge, and the alert rule reads the most recent reading | No change. A job that never runs is not noticed by the service |
| B | Every pod reads the DEFAULT row count at each scheduled time, lock or not: a read, with no partition work | The pods agree. A job that never runs is still not noticed |
| C | The DEFAULT row count is read whenever metrics are collected, independently of the job | The pods agree, and a spill is seen even if the job never runs, but only once the months ahead have run out: two months or more in which bodies are held past the retention |
| D | B or C, plus a new signal of when the last run completed, alerted when it is more than about two days old | Closes the gap. A new metric and a new alert: a change to design §10.1 and §10.3 |
| Custom | Another mechanism | Stated by the design owner |

**Your choice**: Custom, close to D (A3-118, 2026-10-23) — the months ahead (a new gauge) and the
DEFAULT row count are read from the database whenever the metrics are collected, on every pod, lock
or not (cached 5 minutes), and so is the end of the last run with no failure, kept in a one-row
table created by this slice's Flyway `V2` (H38); a failed read reports `-1`; the alert also fires on too few months ahead (fewer than
two, H37, with three kept ahead) or more than 48 hours without a successful run. A3-119 adds the run
log lines; H36 says how the alerts are delivered.
