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
      FR-004-052's open question, which names candidate mechanisms only to frame the choice it asks
      for, `NoteForms` in Dependencies, and the proposed test and runner names — and the deployment
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
- [ ] Written for non-technical stakeholders — **partly, by design of the brief**. The Context, the
      user stories and the success criteria are in operator terms; the functional requirements name
      the design's frozen vocabulary, as the slice brief requires for a message-driven service with
      fixed contracts. Accepted; not a blocker for planning
- [x] All mandatory sections completed — plus Context, Clarifications, Dependencies, Out of Scope and
      Design traceability, which the slice brief asks for

## Requirement Completeness

- [ ] No clarification markers remain — **three remain** in the spec, the maximum, each a genuine
      design gap:
      FR-004-024 — an expired `PENDING` resend row is reclaimed in place by §4.4 claim contract (c),
      the §4.5 resend column and A2-72, but refused `resend-in-progress` "live or expired" by A3-56
      and the §4.4 Selects row (DIGEST §16 lists it as blocking this slice);
      FR-004-049 — the rollback's close-in-flight transition (§9.4 step 4) is specified and pinned,
      but no action or runner executes it (DIGEST §16);
      FR-004-052 — the design makes the operator pause a front for a per-pod, in-memory control, yet
      requires it to hold for the whole rolled-back period, so how it reaches every replica and
      survives a restart is undecided (A2-52; O-2).
      Everything else was resolved from the design (32 Clarifications bullets) or carried as a
      documented assumption or an explicit out-of-scope item. The three questions and their
      options are at the end of this checklist, in the form every slice uses
- [x] Requirements are testable and unambiguous — except the three marked requirements. Where the
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

- [ ] All functional requirements have clear acceptance criteria — 57 of 60 do; FR-004-024,
      FR-004-049 and FR-004-052 cannot until their questions are answered (FR-004-052 is testable
      within one pod today: US7 scenarios 1–3). FR-004-049a, the close-in-flight statement, is
      tested without its executor (US7 scenario 7; `RollbackFencingIT`), and FR-004-050a by US3
      scenarios 9 and 12
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

### Question 1: What does resend-leg do with an expired `PENDING` resend row? (FR-004-024)

**Context**: FR-004-024: "When the leg's latest row is a `PENDING` resend row whose lease has
expired (its runner died or overran), resend-leg MUST [NEEDS CLARIFICATION …]". Claim contract (c),
the §4.5 resend column and A2-72 reclaim the row in place — owner, token and expiry rewritten, the
attempt number kept, then run — and A2-72's procedure for a deferred replay ("run resend-leg, then
replay") depends on it. A3-56 and the §4.4 Selects row refuse it `resend-in-progress`, "live or
expired" (DIGEST §16 lists the conflict as blocking this slice).

**What we need to know**: whether an expired `PENDING` resend row is reclaimed or refused.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | Reclaim in place, as claim contract (c), the §4.5 resend column and A2-72 say | A dead runner's row is finished by the next resend, and A2-72's "run resend-leg, then replay" works. A reclaim may send again a body the dead runner had already sent: a genuine duplicate at HMPPS that the design does not count (A3-115) |
| B | Refuse `resend-in-progress`, live or expired, as A3-56 and the §4.4 Selects row say | No duplicate from a reclaim. But nothing closes the row except rollback step 4's statement (FR-004-049a), whose executor is open (FR-004-049), and every replay of the request is deferred meanwhile (FR-004-003): the request stays stuck |
| C | Reclaim in place as in A, and count each reclaim as a possible duplicate, for example on `probation_crash_reruns_total` | As A, and the possible duplicate is visible. The metric's definition in §10.1, and so the meaning of the crash re-run alert, widen: a design change |
| Custom | Provide your own answer | Say what happens to the row, and whether a reclaim is counted |

**Your choice**: _for the owning team (H31), with the design owner_

### Question 2: Who runs rollback step 4's close-in-flight statement? (FR-004-049)

**Context**: FR-004-049: "The rollback's close-in-flight transition … MUST be executable by an
operator through [NEEDS CLARIFICATION …]". The statement is specified (FR-004-049a) and pinned
(`RollbackFencingIT`), but no §4.8 action or runner executes it (§9.4 step 4; DIGEST §16). Slice 005
keeps it off HTTP (FR-005-002), and slice 006's rollback inputs record the gap until a path is
placed, describing no statement to run by hand (FR-006-040).

**What we need to know**: which supported path runs the statement in a rollback.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | A command-line runner this slice delivers, refused unless intake is paused by an operator | The pinned statement gets a tested executor, run like the other rollback runners. It is an operator action §4.8 does not list: a design addition. It stays off HTTP (O-1) |
| B | An action another slice owns, for example on the operations API | Attribution and audit as for the other API calls. It needs O-1 decided and a constitution amendment, because 005 keeps it off HTTP today |
| C | A step of the rollback runbook, run outside the service against the store by the security-cleared operations team | No new action in the service. The runbook carries its own copy of the statement, which the service's tests do not pin, so two definitions must be kept in step; 006's inputs describe it only once this is the answer |
| Custom | Provide your own answer | Name the path, who runs it, and what refuses it outside a rollback |

**Your choice**: _for the owning team (H31), with the design owner_

### Question 3: How does an operator pause reach every replica and survive a restart? (FR-004-052)

**Context**: FR-004-052: "An operator pause MUST stay in force until an operator resumes intake … and
it MUST hold on every replica and across a pod restart, by [NEEDS CLARIFICATION …]". The design makes
pause "the ops front for `ConsumerLifecycleController`", which holds its state per pod and in
memory, yet requires intake to stay paused for the whole rolled-back period (A2-52), with one or two
replicas (O-2). A runner runs in the pod as a process of its own.

**What we need to know**: the mechanism that makes one pause hold on every replica and across a
restart.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | A pause recorded in the store, which every pod reads before it takes deliveries | Holds on every replica and across restarts, and the runner's pause reaches every pod. It needs a table or row beyond V1's three tables and one view: a V2 migration and a design addition |
| B | The deployment's intake switch (`distribution.consumer.enabled`) set off for the rolled-back period | No schema change, and it survives restarts. But it is a deployment change per environment, not an operator runner: the pause and resume actions become advisory or give way to the deployment step, and the `cause="operator"` read-back must follow the switch |
| C | A pause applied to each pod, with a restart accepted as resuming intake | The simplest. But a pod restart during the rolled-back period resumes intake while the legacy is sending again: a "both", and duplicates at HMPPS, against A2-52, unless every restart is watched for and the pause re-applied |
| Custom | Provide your own answer | Name the mechanism, and how the pause is read back |

**Your choice**: _for the owning team (H31), with the design owner_
