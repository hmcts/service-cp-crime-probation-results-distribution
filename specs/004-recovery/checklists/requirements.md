# Specification Quality Checklist: Operator recovery — replay, resend-leg and the operator actions

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — the spec names the vocabulary the
      design fixes as contract: statuses, reasons, `note` forms, `audit_note` events, metric names
      and labels, the one property (`distribution.resend.max-ids-per-run`), message and application
      properties, the request-id recipe, the design's own runner form and its proposed test names.
      It also names, on purpose, the design's own component and vocabulary names where the design's
      rule is stated through them — `RequestIdRecipe` (proposed by the design; FR-004-055 and the
      submit-hearing rules), `ConsumerLifecycleController` and `distribution.consumer.enabled` inside
      FR-004-052's confirmed pause contract, `NoteForms` in Dependencies, and the proposed test and runner names — and the deployment
      terms the design fixes: Redis as the payload cache (H2), and the image and the pod in which
      the runners run (§4.4 Exposure row). It keeps as behaviour the guarantees that read like
      mechanism, because they are the design's: replay admission in one statement (A2-62) and an
      in-place re-run in one update (A2-39), so that each is atomic; the database clock as the only
      time authority (§4.6); the request-before-leg order that fences replay against resend
      (A2-65); and one transaction for an attempt's outcome, stored body and recovery (A2-49, H20).
      No language, framework, library or query text is named
- [x] Focused on user value and business needs — operators get a failed send to HMPPS without
      re-sending what was delivered, stop and resume intake safely around a rollback, and repair a
      share that was never admitted; a deliberate duplicate is recorded as deliberate
- [x] Written for non-technical stakeholders — the operator overview, user stories and success
      criteria explain recovery and rollback in plain language. Functional requirements retain
      the fixed vocabulary needed for precise acceptance tests.
- [x] All mandatory sections completed — plus Context, Clarifications, Dependencies, Out of Scope and
      Design traceability, which the slice brief asks for

## Requirement Completeness

- [x] No clarification markers remain — all three decisions were confirmed on 2026-10-26:
      FR-004-024 option B (no automatic resend reclaim), FR-004-049 option A (guarded CLI runner),
      and FR-004-052 option A (persisted pause shared by every replica and honoured on restart).
- [x] Requirements are testable and unambiguous. Where the
      design is silent the spec takes a stated default instead of leaving room: the replay action's
      codes for a missing or in-flight request (Assumption 2), the whole-run codes (3), the unforced
      resend's reason (4), the 200-character bound on operator and reason (5), the pause re-check
      before each claim and the pause winning over the store's recovery (8), the listings' bound
      (10), an unexpected `origin` (17), the purge's cut-off (18), submit-hearing's refusal and run
      id (19), and submit-hearing while intake is paused (20)
- [x] Success criteria are measurable — counts, 100% and zero outcomes over the test matrix;
      SC-004-012 is a pass/fail walk-through with the owning team
- [x] Success criteria are technology-agnostic — they use the design's statuses and metric names
      and the SIT environment of go-live gate row 21, and name no technology; SC-004-013 refers to
      the repository's build and quality gates without naming a tool
- [x] All acceptance scenarios are defined — eight stories with 13, 9, 18, 6, 6, 4, 7 and 7
      Given/When/Then scenarios
- [x] Edge cases are identified — 22, including two replays of one request, a replay deferred over
      a resend, replays and submissions enqueued while intake is paused, a request stuck
      `RECEIVED`/`RETRYING` after a final-delivery hand-back, a message never admitted, the crash
      windows of replay and resend, a store failure mid-run, `sharedTime` renderings, a
      submit-hearing after a re-share, and messages arriving around a purge
- [x] Scope is clearly bounded — Out of Scope and Dependencies separate this slice from 001, 002,
      003, 005 and 006; the optional flag check is the one §4.8 action no slice builds, by the design
- [x] Dependencies and assumptions identified — 20 assumptions; slice and external dependencies; the
      design's proposed pinning tests mapped to requirements

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria — all 60 do. FR-004-024
      requires unchanged rows and zero POSTs on expired resend refusal; FR-004-049 requires
      pause-guarded CLI execution; FR-004-052 requires shared persistence, startup read before
      consumption and reconciliation by the next successful store probe. These are specification
      checks, not claims that every implementation or smoke test is complete.
- [x] User scenarios cover primary flows — service replay, dead-letter resubmission, resend-leg with
      its `sharedTime` check and recovery, the dead-letter runbook, the forced resend, the read-only
      listings, intake pause and resume with purge-queue, and submit-hearing
- [x] Feature meets measurable outcomes defined in Success Criteria — each story's independent test
      exercises at least one success criterion; SC-004-011 is SIT evidence gathered with slice 006
- [x] No implementation details leak into specification — as the first item

## Notes

- **Validation pass, 2026-10-21.** Found and fixed in the spec before this checklist was filled: the
  story 2 independent test reused one request for all three DV-26 cases (the first admission makes
  the request non-`FAILED`), now three seeded requests; an in-place re-run that no longer serialises
  a body deletes the earlier body row rather than "replacing" it; the exhausted id after a replayed
  run is the id of the delivery that exhausted it; the query API's `sharedTime` rendering was stated
  as fact and is now left as the design leaves it; FR-004-008 names the attempt-1 states the replay
  column re-runs in place; the run summary counts `not-run`; a runner's ban on intake no longer
  contradicts purge-queue's removal of messages; a scenario was added for the request-claim fencing
  of replayed runs (FR-004-013); traceability rows were added for H25, Q19 and A2-50.
- **Scope extended to match the sibling slices.** The first draft left §4.8 actions 4–6 unowned.
  Slice 002's out-of-scope list assigns "operator pause and resume, submit-hearing, purge-queue and
  the runners" to this slice, and slice 006's rollback inputs expect this slice's runners and its
  pinned `RequestIdRecipe` vector, so stories 7 and 8 and FR-004-051 … FR-004-058 were added. The
  request listing and the `dlq-completed` listing are here because the resend and dead-letter
  procedures use them and slice 005 assumes the list runner exists. The optional flag check
  (action 7) is built by no slice.
- **Clarify-style pass.** 32 ambiguities resolved from the DIGEST and the design, each recorded as a
  Clarifications bullet with its reference. Three genuine gaps carried as markers. Design items
  deliberately not decided and carried as out of scope: A3-115 (counting crash re-runs during a
  replay's re-run or a resend reclaim; slice 002 hands the replay case here), Q28 (whether
  `payload-changed` and `rolled-back` page), O-1 (HTTP exposure of the other actions), Q20 (the
  vector is re-taken when it lands) and the environment-variable binding of
  `distribution.resend.max-ids-per-run`.
- **The task brief listed "status/date filters, bounded count" for resend-leg.** The design withdrew
  that selector (H16, A3-56), and the design wins: resend-leg takes an explicit list capped by the
  property, and status, reason and `updated_at` filtering lives in the separate selection step.
- **Alignment with slice 005's draft.** The replay outcomes (`enqueued`, `no-op`), the reuse of
  `unknown-request` and `not-eligible` for replay, the whole-run codes (`intake-paused`,
  `too-many-request-ids`, `missing-argument`, `unreadable-argument`, `argument-too-long`,
  `store-unavailable`, `broker-unavailable`), the 200-character bound on operator and reason and the
  distinct-ID cap follow 005's draft, so that a runner and the API answer alike; `intake-not-paused`
  follows the same style. None of these codes is the design's. The cross-spec pass confirmed the
  names: the set is fixed in this spec (Key Entities; Assumption 3), and 005 renders the same set
  and mirrors it in its OpenAPI enums.
- **Boundary with 002.** 002 lands the copied guard with an interim rule for `FAILED` requests
  (002's FR-002-008) and the settlement statements; this slice replaces the interim rule with
  replay admission and its recorded departures (DV-26 predicate, appended `audit_note`,
  `replayed_at`, `REPLAY_DEFERRED`, the route, submit-hearing's admission audit) and adds the
  operator pause on 002's intake control.
- **Review pass, 2026-10-21.** Ten review findings were checked against the DIGEST and the design,
  and all ten were applied:
  - FR-004-041 no longer contradicts FR-004-057. It lists every place an operator's name or a
    reason is written: resend attempt rows; the `audit_note` lines of a forced resend and of a
    submit-hearing admission; and the submit-hearing command's application properties.
  - FR-004-009's "never re-run in place" is scoped to replayed runs (A2-59). It no longer decides
    FR-004-024's open question.
  - FR-004-047 keeps only the dead-letter and resend rules of §4.8. When intake resumes after a
    rollback, and the purge of messages enqueued while paused, are now handed to 006's rollback
    inputs in Dependencies.
  - FR-004-001 states the DV-26 predicate as necessary, not sufficient, so it no longer conflicts
    with FR-004-003.
  - FR-004-033 no longer has a resend write `rolled-back`.
  - FR-004-023 and story 3 scenario 16 now follow the §4.5 claim states.
  - FR-004-020's input rule now covers every action that takes arguments. Story 8 scenario 7 tests
    it.
  - The A3-115 out-of-scope item and FR-004-008 now name the expired `PENDING` replay-created
    re-run.
  - The invalid-`sharedTime` edge case now cites 002's format check (FR-002-003).
  - This checklist's first item now says what the spec names.

  Three adjustments were made on the way:
  - The roll-forward and paused-queue rules are cited to 006's FR-006-043 and FR-006-044, not
    FR-006-041, which holds the repair rules.
  - The A2-52 traceability row keeps FR-004-052, whose open question is that row's rule.
  - The note above also admits Redis, `RequestIdRecipe` and the database clock.
- **Cross-spec pass, 2026-10-21.** The cross-slice findings were checked against the DIGEST and the
  design, then applied:
  - FR-004-001 now says that it replaces 002's interim rule FR-002-008 and removes its test; the
    DV-26 traceability row cites FR-002-008.
  - The new FR-004-049a states the rules of rollback step 4's close-in-flight statement (DIGEST §7;
    §9.4 step 4; A2-43, A2-51, A2-66, A2-67), which no spec stated before: one transaction per row,
    the `prev=` note, the columns that change, the request outcomes and the fencing of late
    runners. `RollbackFencingIT` pins it in its three cases (late queue runner, late resend runner,
    late leg-outcome UPDATE). FR-004-049 keeps its marker for the executor only. US7 scenario 7
    tests the statement without an executor. 002 and 006 cite FR-004-049a.
  - The new FR-004-050a pins the resend halves of DV-07 and DV-17, which 001's Dependencies hand to
    this slice; US3 scenario 9 now includes Redis unreachable.
  - The distinct-ID reading of the cap is this slice's own, shared with 005's assumption 12: the
    design states both rules without fixing their order (Clarifications; Assumption 6).
  - Assumption 3 no longer defers the code names: the set is fixed here, and 005 renders it.
  - FR-004-042 names the runner option `--allow-changed-payload`, lining up with the design's
    `allow-changed-payload` and 005's `allowChangedPayload`.
  - The three open questions now carry option tables, below.
- **Public-repository hygiene.** No personal names, hosts, URLs, resource names, environment values
  or payload content in the spec; checked by search.

### Question 1: Expired `PENDING` resend row (FR-004-024) — resolved

**Decision (2026-10-26)**: option B. Refuse `resend-in-progress` for a live or expired lease;
never reclaim automatically. Expiry changes no row and causes no POST. Operator recovery uses
FR-004-049/FR-004-049a; replay remains refused or deferred until the row is closed.

**Acceptance coverage**: US3 scenario 16 and T042 (`ResendByRequestIdsIT`) must cover an expired
lease, unchanged attempt and claim, and zero POSTs. T042 remains incomplete until implemented.

### Question 2: Rollback close-in-flight executor (FR-004-049) — resolved

**Decision (2026-10-26)**: option A. This service owns the `rollback-close-in-flight` CLI runner.
It refuses `intake-not-paused` without changing rows unless intake is paused by an operator.
It calls the application action implementing FR-004-049a and remains off HTTP.

**Acceptance coverage**: US7's independent test, `RollbackCloseInFlightActionTest`,
`RollbackCloseInFlightRunnerTest` and `RollbackFencingIT` cover the guard, result and statement.
T037 now supplies production CLI registration and `PackagedIntakeCommandsIT` for pause/resume and
close-in-flight. T037 remains open because successful broker-native cutoff purge is not integrated.

### Question 3: Persistent operator pause (FR-004-052) — resolved

**Decision (2026-10-26)**: option A. Persist the pause in `intake_control`; every pod reads it
before starting consumption and reconciles on the store-probe interval (default 10 seconds).
Read failure suspends consumption. Only operator resume clears the pause; restart and database
recovery do not. Existing pods honour changes by their next successful reconciliation.

**Acceptance coverage**: US7 scenarios 1–3, `IntakeControlReconciliationIT` and the lifecycle
controller tests must establish shared state, startup ordering, restart persistence and independent
database/operator holds. Completing the checklist does not mark implementation tasks complete.
