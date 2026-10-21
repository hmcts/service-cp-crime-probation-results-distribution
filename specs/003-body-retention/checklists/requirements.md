# Specification Quality Checklist: Stored-body retention — the daily `outbound_body` maintenance job

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the spec names what the design fixes
      for this job (A3-64): the two properties, the four `probation_body_*` metrics and their `step`
      label, V1's partition names and bounds, and the PostgreSQL mechanisms the design prescribes
      (a transaction-scoped advisory lock, a lock timeout, partition bounds read from the catalogue).
      The design's SQL is quoted only where the design is cited (the Clarifications, the marker of
      FR-003-011 and open question 1), and the slice plan's own words in the Input line, never as a
      requirement. It chooses no class, package, library
      or framework: where the job sits is left to the plan (assumption AS-1), and so is whether the
      run's statements share the lock's transaction (AS-13)
- [x] Focused on user value and business needs — the retention commitment given to the data owner
      and the DPIA, the investigation window for operations, and capacity for the platform
- [x] Written for non-technical stakeholders — the stories, edge cases and success criteria are in
      plain terms; the requirements keep the design's own vocabulary (partition, cut-off, lock), as
      this project's specs do for a service whose contracts and names the design fixes
- [x] All mandatory sections completed — plus Context, Clarifications, Dependencies, Out of Scope
      and Design traceability; the open questions, with their options, are in this checklist

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain — **one remains**, within the limit of three, a
      genuine design gap for the design owner:
      - FR-003-011 (open question 1): how an expired month is removed. The design's
        `DETACH PARTITION … CONCURRENTLY` then `DROP` cannot run on this table: the PostgreSQL
        manual states that a concurrent detach cannot run in a transaction block and is not allowed
        while the partitioned table has a default partition, and V1 creates one.

      Open question 2 — how a job that never runs, or a DEFAULT reading left stale on the pod that
      skipped, is noticed — is not a marker: the design's default stands (every signal comes from a
      run, A3-64; §10.1), so nothing is blocked. It is carried as assumption AS-15, and the gauge
      itself is a requirement as designed (FR-003-022). Both questions, with their options, are at
      the end of this checklist.

      Twenty-one points were settled in the Clarifications session of 2026-10-21, each with its
      design reference: eighteen answered from the design (the inferred ones say so); one answered
      as far as the design goes, which fixes the lock's kind but not whether the run's statements
      share its transaction (AS-13); and two the design does not establish, carried as assumptions
      (the failure causes, AS-14; the job's package and the properties' binding, AS-1 and AS-2)
- [x] Requirements are testable and unambiguous — every requirement states an outcome a test can
      check, and a failure's cause is one of a closed set (FR-003-017; AS-14). The marked
      requirement also states what must hold whatever the answer: the removal's lock timeout and
      its failure behaviour (FR-003-012, FR-003-016, FR-003-017); and the gauge's value after a run
      is stated as designed (FR-003-022), whatever open question 2's answer
- [x] Success criteria are measurable — each has a count, a duration, a percentage or a test matrix
      (SC-003-001 … SC-003-013); SC-003-013's bound holds whichever way the run's statements stand to
      the lock's transaction (AS-13)
- [x] Success criteria are technology-agnostic (no implementation details) — they speak of bodies,
      months, runs, failures and settings; "partition" appears only in SC-003-012, as the unit in
      which a month's storage is read
- [x] All acceptance scenarios are defined — six stories, 31 Given/When/Then scenarios
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
      is exercised by a scenario of User Stories 1–6: among them FR-003-007 by User Story 1
      scenarios 2 and 4 (a present month left alone; an overlapping creation refused and counted),
      FR-003-015 by User Story 2 scenario 4 (no DEFAULT row moved, no past month created) and
      FR-003-027 by User Story 5 scenario 6 (no other property, no named environment variable).
      The Design traceability table ties each requirement to its design row
- [x] User scenarios cover primary flows — creation ahead, removal, single runner and bounded waits,
      signals, configuration, and recovery from a DEFAULT spill
- [x] Feature meets measurable outcomes defined in Success Criteria — every criterion is an outcome
      of this slice (SC-003-012 reads the storage of the months this slice keeps in their own
      partitions), and they hold under every option of the two open questions, except that option C
      of question 1 (no DEFAULT partition) would withdraw the DEFAULT parts of SC-003-008 and
      SC-003-012 along with FR-003-014, FR-003-015, FR-003-022, FR-003-028 and the DEFAULT half of
      FR-003-023
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
    for a month too long unless it is read as a UTC instant.
- Before `/speckit-plan`: open question 1 needs the design owner's answer before the tasks for
  FR-003-011 are written, and before slice 002 writes V1, because option C would change V1 (002's
  FR-002-054). Open question 2 blocks nothing: the design's default stands (AS-15). The plan also
  records where the job sits (AS-1) and whether the run's statements share the lock's transaction
  (AS-13). The rest of the slice can be planned now.
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
- This spec was written directly in `specs/003-body-retention/`. `.specify/feature.json` does not
  point at it; a developer starting the feature branches `003-body-retention` from `main` (or sets
  `feature.json`) before running `/speckit-plan`.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`.

## Open questions

Two points cannot be answered from the design, and both are for the design owner. Question 1 blocks
FR-003-011 and is marked there. Question 2 is not a marker: the design's default stands, and it is
carried as assumption AS-15. The rest of the slice can be planned while they are open, and the
outcomes FR-003-012, FR-003-016, FR-003-017, FR-003-022 and FR-003-023 state hold under every option
below, with one exception: option C of question 1 removes the DEFAULT partition, which would
withdraw FR-003-014, FR-003-015, FR-003-022, FR-003-028 and the DEFAULT half of FR-003-023, and
change V1 in slice 002 (002's FR-002-054). Question 1 is therefore answered before V1 is written.

### Question 1: How is an expired month removed? (FR-003-011)

**Context**: A3-64 (b) and DIGEST §8 remove an expired partition with `DETACH PARTITION …
CONCURRENTLY`, then `DROP`, preferring the concurrent detach because it does not take the strongest
lock on `outbound_body`. It cannot run on this table. The PostgreSQL manual (ALTER TABLE, DETACH
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

**Your choice**: _for the design owner_

### Question 2: How is a job that does not run, or a stale reading, noticed? (AS-15; not a marker)

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

The design's default (option A) stands until the design owner answers, so nothing in this slice is
blocked (AS-15).

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

**Your choice**: _for the design owner_
