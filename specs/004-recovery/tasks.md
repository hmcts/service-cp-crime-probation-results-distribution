---
description: "Task list for 004-recovery: operator recovery — replay, resend-leg and the operator actions"
---

# Tasks: Operator recovery — replay, resend-leg and the operator actions

**Input**: Design documents from `/specs/004-recovery/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/cli-runners.md,
contracts/broker-properties.md, quickstart.md

**Tests**: MANDATORY (constitution Principle II). Every test task is ordered before the
implementation task it guards; its commit records the failing assertion (not a compile error)
before the paired implementation task's commit records the green run. Exempt only: pure mechanical
refactors, formatting, comment-only edits.

**Dependency note**: this slice is implemented against slice 002's merged pipeline. Every task below
assumes `IdempotencyGuard`, `DistributionRunner`, the three tables/one view, and the existing ports
(`HearingPayloadSource`, `YouthFilterSource`, `HmppsSender`, `OutboundLog`) already exist on `main`
at the time this branch is rebased/merged. No task in this file creates a new port.

**Confirmed decision** (2026-10-26): FR-004-024 refuses `resend-in-progress` for live or expired
resend leases, without automatic reclaim. FR-004-049 confirms this service owns the guarded
`rollback-close-in-flight` CLI runner, with no HTTP exposure.

**Confirmed pause decision** (2026-10-26): FR-004-052 uses the persisted `intake_control` row,
read before consumption starts and reconciled on the store-probe interval. Earlier task history
labelled this provisional; the user has now confirmed it.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US8, mapped to spec.md's eight user stories
- File paths are under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/` and
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/` unless stated otherwise

---

## Phase 1: Setup

**Purpose**: the one new package this slice adds, and nothing else — no new dependency, no new
container (plan.md "Technical Context").

- [X] T001 Create the `cli` package (new driving adapter, symmetric with `inbound`/`api`) with
      `cli/package-info.java` stating its responsibility (parses one action's arguments, calls one
      `application` service, prints its bounded outcome) and dependency direction (depends on
      `application` and `domain` only; nothing depends on it) — see plan.md "Project Structure"
- [X] T002 [P] Add the CLI dispatch entry point `cli/RunnerDispatcher.java`: reads `args[0]` as the
      action name, routes to the matching runner bean, and — when an action is present — calls
      `SpringApplication.exit(...)` after the action completes without starting the ASB listener or
      the body-maintenance scheduler (research.md Decision 6; FR-004-045). No action argument leaves
      `Application.java`'s existing startup behaviour untouched
- [X] T003 [P] Document the six runners and two listings as this slice's external interface:
      confirm `specs/004-recovery/contracts/cli-runners.md` and
      `specs/004-recovery/contracts/broker-properties.md` are current (already written in Phase 1 of
      `/speckit.plan`; this task is the implementation-time re-check, not new authoring)

**Checkpoint**: `cli` package compiles empty; dispatch mechanism exists; no behaviour yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: shared schema, vocabulary and property-validation changes every later story needs.
**⚠️ CRITICAL**: no user-story phase starts before this one is complete.

- [X] T004 Add the confirmed `intake_control` single-row table migration
      `src/main/resources/db/migration/V{next}__add_intake_control.sql` exactly as specified in
      data-model.md (columns `id`, `paused_by_operator`, `paused_at`, `updated_at`; CHECK
      `id = 1`; seed row) — **(confirmed 2026-10-26, FR-004-052)**. `{next}` is the first unused Flyway
      version once this branch is rebased onto 002's (and, if landed first, 003's) migrations —
      never renumber an already-applied migration. Landed as `V3__add_intake_control.sql` (V1, V2
      already taken by slices 002/003 on this branch).
- [X] T005 [P] Add `SchemaVocabularyIT` assertions (extend slice 002's existing test, do not
      duplicate it) confirming `intake_control` has exactly one row and the CHECK holds —
      `src/test/java/.../persistence/SchemaVocabularyIT.java`.
      RED (before V3 existed): `org.postgresql.util.PSQLException: ERROR: relation
      "intake_control" does not exist` in `seeds_exactly_one_row`.
      GREEN (after V3 landed): both `IntakeControlSingleRow` cases pass —
      `SchemaVocabularyIT > intake_control (slice 004, provisional FR-004-052) > seeds exactly one
      row PASSED`, `... > the single-row CHECK refuses a second row PASSED`.
- [X] T006 [P] Add closed value-set enums in `domain/`: `WholeRunRefusal` (`INTAKE_PAUSED`,
      `TOO_MANY_REQUEST_IDS`, `MISSING_ARGUMENT`, `UNREADABLE_ARGUMENT`, `ARGUMENT_TOO_LONG`,
      `STORE_UNAVAILABLE`, `BROKER_UNAVAILABLE`, `INTAKE_NOT_PAUSED`) and `RunnerOutcome` (`DONE`,
      `REFUSED`, `FAILED`) — one file per enum under `domain/`. **`ReplayRoute`
      (`SERVICE`/`SUPPORT`/`COPY`) and the per-ID resend refusal set already exist on
      `002-hmpps-distribution` as `ReplayRoute` and `ResendRefusal`, built against that branch's
      shared `WireValued`/`Vocabularies` helper — discovered only once this branch was merged onto
      it; do not redefine them here. `WholeRunRefusal` should be refactored onto that same
      `WireValued`/`Vocabularies` pattern for consistency once this lands for real.**
- [X] T007 [P] `PropertiesValidatorTest` case: `distribution.resend.max-ids-per-run` must be ≥ 1 at
      startup, refusal names the property — `src/test/java/.../config/PropertiesValidatorTest.java`
      (extend the existing class; this is the one named case this slice adds, FR-004-019).
      **Implemented as `refuses_a_resend_cap_below_one` in the existing
      `config/StartupValidationTest.java`**, this branch's actual properties-validator test class
      (`PropertiesValidatorTest` is the design's proposed name; `StartupValidationTest` is what
      002 already built and the one slice-002 test this case joins).
      RED: `java.lang.AssertionError: Expecting code to raise a throwable.` (no `Resend` cap
      validation existed yet).
      GREEN: `the startup configuration validator > refuses a resend cap below 1, naming the
      property (slice 004, FR-004-019) PASSED`.
- [X] T008 Bind and validate `distribution.resend.max-ids-per-run` (default 100) in the
      `distribution.*` `@ConfigurationProperties` class — `config/DistributionProperties.java` (or
      the equivalent existing properties record) — makes T007 pass. Added `DistributionProperties
      .Resend(maxIdsPerRun)` and `StartupValidator.validateResend`; all `new DistributionProperties
      (...)` call sites updated (`ServiceBusConsumerConfigIT`, `OperationsConfigDefaultsTest`,
      `YouthSwitchDefaultConfigTest`, `StartupValidationTest`).
- [X] T009 [P] `IntakeControlReconciliationIT` (Testcontainers Postgres): a pod that starts with the
      row `paused_by_operator = true` reconciles to paused before taking its first delivery; a pod
      that restarts mid-pause stays paused after reconciliation —
      `src/test/java/.../inbound/IntakeControlReconciliationIT.java` — write first, confirm it fails
      for the right reason, before T010 starts.
      RED: both cases failed `Expecting value to be true but was false` on
      `controller.pausedByOperator()` (the constructor seam — `IntakeControlRepository` injected
      but not yet read by `probe()`).
      GREEN (after T010): both `IntakeControlReconciliationIT` cases pass, and the existing
      `ConsumerLifecycleControllerTest`/`DatabaseOutageIT` suites stay green (the `database`-cause
      gauge lookups were narrowed to `.tag("cause","database")` once a second, `operator`-cause
      gauge of the same name was added; `ClosedLabelSetIT` updated to allow the new `operator`
      label value).
- [X] T010 Extend `ConsumerLifecycleController` (`inbound/`) with a `paused_by_operator` read path
      against the `intake_control` row: on startup and every `distribution.store.probe-interval`,
      reconcile the in-memory pause flag and `probation_intake_suspended{cause="operator"}` to the
      persisted row, making T009 pass — **(confirmed 2026-10-26, FR-004-052)** —
      `inbound/ConsumerLifecycleController.java`
- [X] T010a [P] `ReplayAdmissionPredicateTest` (plain unit test, no Spring context): the DV-26
      predicate decides admit-as-replay vs dead-letter-`exhausted` for a `FAILED` request's delivery
      — `messageId ≠ exhausted_message_id`; `exhausted_message_id IS NULL`; or
      `messageId = exhausted_message_id` at broker delivery count 1 — otherwise dead-lettered
      `exhausted` with the request row unchanged (FR-004-001); the exhausted-identity test runs
      before the `PENDING`-resend-row deferral check, so a copy under the exhausted id whose first
      delivery is not admitted is dead-lettered `exhausted` on redelivery, never given a second
      chance (FR-004-002); a `PENDING` resend row on the leg, live or expired, causes a
      `REPLAY_DEFERRED` abandon decided under the request-row lock before the replay statement runs,
      with `probation_replay_deferred_total` incremented and the request left `FAILED` with
      `replayed_at` NULL, dead-lettering `exhausted` instead on the final permitted delivery
      (FR-004-003) — write first, confirm it fails for the right reason, before T011/T012 —
      `src/test/java/.../application/ReplayAdmissionPredicateTest.java`. **Implemented as the
      `ReplayAdmission` `@Nested` class inside the existing `IdempotencyGuardTest.java`, not a
      separate file** — the predicate lives inside `IdempotencyGuard`, so its test joins that
      class's existing style rather than introducing a parallel file for the same production class;
      all cases above are covered there and pass.
      RED not reproducible: the predicate (FR-004-001/002/003) landed with T011/T012 (both [X])
      before this test was pinned, so it is a characterization test of existing production code, not
      a test-first guard. GREEN: `the idempotency guard > admitting a FAILED request's delivery
      (DV-26) > ...` — all 8 `ReplayAdmission` cases PASSED.
- [X] T011 Replace slice 002's interim replay-admission rule (FR-002-008) with the DV-26 predicate in
      the guard's admission statement (FR-004-001, FR-004-002): a `FAILED` request's delivery is
      admitted only if `messageId ≠ exhausted_message_id`, or `exhausted_message_id IS NULL`, or
      (`messageId = exhausted_message_id` and broker delivery count = 1); otherwise dead-lettered
      `exhausted`, request row unchanged; the exhausted-identity test runs before the
      `PENDING`-resend-row deferral check of T012 — `inbound/` (the guard's admission SQL/statement
      class) and `application/` (the predicate itself, unit-testable without Spring) — satisfies
      T010a (FR-004-001, FR-004-002)
- [X] T012 Add the `REPLAY_DEFERRED` abandon path (FR-004-003): when the leg's latest row is a
      `PENDING` resend row (live or expired), abandon with reason `REPLAY_DEFERRED` under the
      request-row lock, before the replay statement runs; dead-letter `exhausted` instead on the
      final permitted delivery; increment `probation_replay_deferred_total` — same classes as T011
      — satisfies T010a (FR-004-003)

**Checkpoint**: the guard's replay-admission predicate and the persisted pause mechanism exist and
are unit/IT-tested in isolation. No story below re-implements either.

---

## Phase 3: User Story 1 - Replay a `FAILED` request from its stored command (Priority: P1) 🎯 MVP

**Goal**: an operator (or the replay runner) can put a `FAILED` request's stored command back on the
queue under a fresh `messageId`, and the replayed run re-runs only what failed or is still waiting.

**Independent Test**: seed `FAILED(leg-exhausted)` with a `FAILED(gateway-transient)` HMPPS row;
run the replay runner; gateway stub answers 200; observe one enqueued message (body = stored
command byte-for-byte, fresh `messageId`, `origin=REPLAY`), one POST, the row re-run in place to
`SENT`, request `COMPLETED(recovered)`.

### Tests for User Story 1 (write first, confirm red, then implement) ⚠️

- [X] T013 [P] [US1] `RequestReplayStatementTest`: the admission statement moves
      `FAILED → RECEIVED`, clears `failure_reason`/`exhausted_message_id`, sets `replayed_at`,
      appends `REPLAYED_AFTER_FAILURE prior=<reason>` to `audit_note`, takes the request claim — one
      statement, condition = T011's predicate (FR-004-001, FR-004-004) —
      `src/test/java/.../application/RequestReplayStatementTest.java`. **Not written as its own
      file**; the statement's effects (status, `replayed_at`, `audit_note`'s two lines, a fresh
      claim) are exercised end-to-end by `RequestDedupeIT`'s
      `failed_request_copy_at_first_delivery_is_admitted_as_replay` (a real Postgres + Service Bus
      emulator IT), which passes. A dedicated statement-level unit/IT test is still worth adding.
      **Now added** as `application/RequestReplayStatementTest.java` (Testcontainers Postgres via the
      public `SchemaTestSupport`), calling `JdbcProcessedRequestRepository.admitReplay` directly.
      RED not reproducible: `admitReplay` (FR-004-004) landed with T011/T017 (both [X]) before this
      statement-level test was pinned, so it is a characterization test, not a test-first guard.
      GREEN: `the replay admission statement > moves FAILED to RECEIVED, clears the failure columns,
      sets replayed_at, appends the two audit lines and takes a fresh claim PASSED` (and the route
      and non-FAILED cases).
- [X] T014 [P] [US1] `LegRerunTest`: an in-place re-run of an attempt-1 `FAILED`/`PARKED` row writes
      one UPDATE (no `PENDING` first), increments `attempts`, rewrites `note` to
      `prev=<STATUS>/<reason>` + `rerun-by=<messageId>`, replaces the stored body in the same
      transaction (FR-004-008); a `FAILED` row with attempt ≥ 2 gets a new appended `replay` row,
      never an in-place re-run (FR-004-009); a redelivery whose `note` already names the message in
      `rerun-by` is skipped, a `PARKED` row is re-run on every delivery (FR-004-010) —
      `src/test/java/.../application/LegRerunTest.java`. **Partially covered, not written as its own
      file**: the in-place attempt-1 `FAILED` re-run (FR-004-008's `writeReplayOutcome`) is proven by
      `RequestDedupeIT` above (`note` = `prev=FAILED/gateway-transient;rerun-by=<messageId>`,
      `SENT`). **Not implemented or tested**: FR-004-009's append-a-new-row path for attempt ≥ 2, and
      FR-004-010's skip-if-already-rerun-by-this-message exclusion (the SQL guard is written into
      `writeReplayOutcome`'s WHERE clause but has no test proving it skips on a second delivery).
      **Now written** as `application/LegRerunTest.java` (Testcontainers Postgres via
      `SchemaTestSupport`), exercising all four cases through `OutboundLogAdapter.recordReplay`.
      RED (before T018's append path): the append case failed —
      `appends a new replay row over a FAILED attempt-2 resend row ... FAILED: Expected size: 3 but
      was: 2` (the old in-place path wrongly re-ran attempt 1 to `SENT` instead of appending
      attempt 3). The other three cases (in-place attempt-1, the rerun-by skip, the PARKED re-run)
      were green from slice-002's existing `writeReplayOutcome`.
      GREEN (after T018): all four `LegRerunTest` cases PASSED.
- [X] T015 [P] [US1] `RequestRecoveryTransitionTest` (US1 half): a terminal leg after a replay
      completes the request with `completion_reason = recovered` whenever `audit_note` contains
      `REPLAYED_AFTER_FAILURE`, including when the first replayed run parks and a later redelivery
      settles it (FR-004-012); every leg-outcome write of a replayed run is guarded by the request
      claim's owner/token — a mismatch is a stale-runner rejection, counted, run does not settle
      (FR-004-013; this is RollbackFencingIT's "late queue or replay runner" case, pinned here as its
      own assertion so US1 is independently testable without the rollback runner existing yet) —
      `src/test/java/.../application/RequestRecoveryTransitionTest.java`. **Partially covered**:
      `completion_reason = recovered` is proven by `RequestDedupeIT` above.
      **FR-004-013's stale-runner-rejection case for a replayed run is now pinned**, per this
      task's own forward reference: `RollbackFencingIT$LateRunnerFencing
      .late_replay_runner_write_is_fenced` (and `.late_queue_runner_write_is_fenced` for the queue
      half) seed a request, run T034's close-in-flight statement to clear its request claim, then
      call the existing, unchanged `OutboundMessageRepository.writeOutcome`/`writeReplayOutcome`
      with the pre-close claim and assert 0 rows — added as part of T030, landed (002-hmpps-
      distribution, "feat(004-recovery): pin the rollback close-in-flight statement").
      **Now tested** (so T015 is closed): the park-then-later-redelivery-settles case, and the two
      other recovery cases, are pinned in `application/RequestRecoveryTransitionTest.java`
      (Testcontainers Postgres via `SchemaTestSupport`), driving
      `admitReplay → markRetrying → reclaim → markCompleted` and asserting
      `completion_reason = recovered` with `replayed_at` cleared, plus a stale-claim completion that
      changes nothing. RED not reproducible: the recovered decision keys on `audit_note LIKE
      '%REPLAYED_AFTER_FAILURE%'` in `markCompleted`, which landed with T017/T020 ([X]) before this
      test; it is a characterization test. The leg-write fencing of FR-004-013 remains pinned in
      `RollbackFencingIT$LateRunnerFencing`. GREEN: all three `RequestRecoveryTransitionTest` cases
      PASSED.
- [X] T016 [P] [US1] `ReplayActionTest`: `replay --request-id` decides under the request-row lock —
      no row → `unknown-request`; `COMPLETED` → `no-op`, nothing enqueued; `RECEIVED`/`RETRYING` →
      `not-eligible`; leg's latest row a `PENDING` resend row (live or expired) → `resend-in-progress`
      (confirmed 2026-10-26, FR-004-024); otherwise enqueue `processed_request.command` byte-for-byte under a
      fresh `messageId` (never equal to `RESULTS:{requestId}` or any earlier replay message's id),
      `correlationId = hearingId`, `origin = REPLAY`; the action itself writes nothing to the store;
      **and proceeds unchanged while `intake_control.paused_by_operator = true`** — replay is
      available at any time and is never refused `intake-paused` (FR-004-014–017, FR-004-016) —
      `src/test/java/.../application/ReplayActionTest.java`

### Implementation for User Story 1

- [X] T017 [US1] Implement the replay statement's SQL/JdbcClient call in `persistence/` behind the
      request store port, satisfying T013 — `persistence/ProcessedRequestRepository.java` (or
      equivalent)
- [X] T018 [US1] Implement the in-place re-run and replay-row-append write paths in `persistence/`
      behind `OutboundLog`, satisfying T014 — `persistence/OutboundMessageRepository.java`.
      **In-place re-run done** (`OutboundMessageRepository.writeReplayOutcome`, `OutboundLog`
      additive `recordReplay` method, `DistributionRunner`'s additive 5-arg `run(...)` overload —
      none of this touched the existing `record`/`run`/`writeOutcome` signatures, so no existing
      test needed updating beyond mechanical call-site fixes for the unrelated `admit()` signature
      change). **Now also done**: the replay-row-append path for a `FAILED` row at attempt ≥ 2
      (FR-004-009). `writeReplayOutcome` now reads the leg's latest attempt first and dispatches —
      `appendReplayRowOrSkip` when that row is `FAILED` at attempt ≥ 2 (INSERT a new `replay` row at
      `max(attempt)+1`, no leg claim, `note = rerun-by=<messageId>`, guarded by the request claim via
      `WHERE EXISTS`, body stored via `replaceBody`; a redelivery whose latest row already names the
      message is skipped), otherwise the in-place path (`inPlaceReplayOutcome`). GREEN:
      `LegRerunTest` all cases; `OutboundLogAdapterIT` and `RequestDedupeIT` still green.
      **Gate round 1 remediation**: `inPlaceReplayOutcome` no longer hardcodes `attempt = 1`. It
      re-runs the leg's **actual latest attempt** (read once by `latestLegRow`), so an appended
      `replay` row that itself ends `PARKED`/`PENDING` at attempt ≥ 2 is now re-run in place on
      redelivery instead of silently overwriting the stale attempt-1 row (FR-004-008). A `FAILED` row
      at attempt ≥ 2 is still appended over (FR-004-009); an empty leg history fences to 0 rows. Pinned
      by `LegRerunTest.reruns_an_appended_parked_replay_row_in_place_on_redelivery` (RED before the
      fix: attempt 1 was overwritten to `SENT`; GREEN after) and the append-path per-message skip by
      `LegRerunTest.skips_an_appended_row_already_rerun_by_this_message`. The RED test landed in its
      own commit before the production fix.
      **Gate round 2 remediation**: the in-place-vs-append dispatch now reads `attempt_origin` too.
      A `PARKED`/`PENDING` **resend-origin** row at attempt ≥ 2 is guarded by its own leg claim, not
      the request claim, so the replay path MUST NOT rewrite it in place — it now fences to 0 rows
      instead of being re-run under the request claim (FR-004-009; design_rules.md). Only attempt-1
      rows (any re-runnable state) or `replay`-origin rows go to the in-place path; a `FAILED` row at
      attempt ≥ 2 is still appended over. Pinned by
      `LegRerunTest.leaves_a_parked_resend_row_untouched_on_replay` (RED before the fix:
      `recordReplay` returned `true`, the resend row was re-run to `SENT`; GREEN after). The RED test
      landed in its own commit before the production fix.
      **Accepted TDD-process deviation (gate round 3, qa, not re-remediated)**: commit `93be609`
      lands `LegRerunTest`'s FR-004-009 append-over-attempt-≥2 assertion together with the
      `OutboundMessageRepository`/`OutboundLog` production code implementing it, so that one commit
      does not itself show a red-before-green split (unlike the two later pairs above, which do:
      `fb1529d`→`05a569d` and `6354ef4`→`b03f129`). The RED run for the append case was recorded and
      observed (`Expected size: 3 but was: 2`) before the fix landed; only the commit boundary is
      coarser than the convention asks for. Left as-is rather than rewriting already-landed history
      that ten further commits now sit on top of; a genuine functional defect was not found here by
      either of the other two round-3 reviewers (code-reviewer PASS, spec-validator COMPLIANT).
- [X] T019 [US1] Implement `application/ReplayAction.java`: one `requestId`, checks per T016, returns
      a bounded outcome (`enqueued`/`no-op`/refusal) or fails (`store-unavailable`,
      `broker-unavailable`) with nothing changed; never throws past its outcome
- [X] T020 [US1] Wire the replayed run's settlement consequences into the existing
      `DistributionRunner`/settlement statement: `replayed_at`-gated eligibility column
      (FR-004-007), `runOrigin = replay` labelling (FR-004-011), recovery on terminal leg
      (FR-004-012), claim-guarded writes (FR-004-013) — `application/DistributionRunner.java`
- [X] T021 [US1] [P] Implement `cli/ReplayRunner.java`: parses `--request-id`, validates per
      FR-004-020 (missing → `missing-argument`, not a UUID → `unreadable-argument`, before any
      store/cache/broker read), calls `ReplayAction`, prints the bounded outcome and exit code per
      `contracts/cli-runners.md` §1. The class and its own test (`ReplayRunnerTest`) are done and
      green; **not yet wired into `RunnerDispatcher`/`Application.main` or into a Spring
      `@Configuration`** (no `ReplayEnqueuer` Azure adapter exists yet either — `ReplayAction` has no
      production implementation of that port wired up). The runner cannot actually be invoked from
      the built jar until that composition-root wiring lands.
- [X] T022 [US1] [P] `OutcomeMetricsTest` (US1 half): every replay outcome increments
      `probation_leg_outcomes_total{origin="replay", forced="false"}` and
      `probation_replays_total{route}`; logs carry `runOrigin`, `route`, status, reason, HTTP status,
      body digest (FR-004-011, FR-004-040) — `src/test/java/.../application/OutcomeMetricsTest.java`.
      **Now written** (Mockito + `SimpleMeterRegistry` + a Logback `ListAppender`, the
      `LegOutcomeLogLineTest` pattern): a replay run through `DistributionRunner` asserts
      `leg_outcomes_total{origin=replay, forced=false, status=SENT}` = 1 and a log line carrying
      `runOrigin=REPLAY` with the bounded fields (no body), and `MetricRegistry.replays(route)`
      asserts `probation_replays_total{route}` per route.
      RED not reproducible: `MetricRegistry.replays(ReplayRoute)`, `legOutcome(... REPLAY ...)` and
      the replay log line all landed before this session (T020/T025, [X]); this is a characterization
      test. GREEN: all three `OutcomeMetricsTest` cases PASSED.
- [X] T023 [US1] Run `quickstart.md` steps 1–2 locally against the Service Bus emulator; confirm the
      Independent Test scenario end to end.
      **Not run against the built jar this pass** — quickstart step 2 is `java -jar build/libs/app.jar
      replay --request-id <id>`, which needs the composition-root wiring this phase deliberately left
      open (T021/T037): `ReplayRunner` is built and unit-tested but not registered in
      `RunnerDispatcher`/`Application.main`, and no production `ReplayEnqueuer` Azure adapter exists,
      so the packaged jar cannot take the `replay` action yet. The step-2 Independent Test scenario
      itself — seed `FAILED(leg-exhausted)` with a `FAILED(gateway-transient)` attempt-1 HMPPS row,
      deliver a copy under the exhausted `messageId` at first delivery, gateway stub answers 200,
      observe one POST, the attempt-1 row re-run in place to `SENT` (`note =
      prev=FAILED/gateway-transient;rerun-by=<messageId>`), and the request `COMPLETED(recovered)` —
      is exercised end to end by the automated IT
      `RequestDedupeIT.failed_request_copy_at_first_delivery_is_admitted_as_replay` (real Service Bus
      emulator + Postgres + WireMock gateway), which is green. Left open for the pass that wires the
      composition root; flagged in open_points.

**Checkpoint**: User Story 1 is fully functional and independently testable — the MVP.

---

## Phase 4: User Story 2 - A DLQ resubmission is admitted as a replay (Priority: P1)

**Goal**: a dead-letter resubmission (same or new `messageId`) is admitted as a replay exactly once,
re-dead-lettered `exhausted` above delivery count 1 under the same id, and the route is recorded.

**Independent Test**: three `FAILED(leg-exhausted)` requests, each exhausted by its own `messageId`
X; deliver a copy under X at count 1 (admitted, route `copy`), a delivery of X at count 2
(dead-lettered `exhausted`, unchanged), a copy under a new id (admitted, route `copy`).

### Tests for User Story 2 (write first, confirm red, then implement) ⚠️

- [X] T024 [P] [US2] `DlqResubmissionReplayIT` (Testcontainers Service Bus emulator): the three-case
      matrix above, plus a copy carrying `origin=SUPPORT` under X at count 1 → route `support`; a
      dead-lettered copy of a service-replay message (keeps `origin=REPLAY`) → route `service`; a
      producer republish (same X, count 1) → route `copy`, no `SENT`/`NOT_APPLICABLE` leg re-sent; a
      `COMPLETED` request meeting any copy → no-op; a `FAILED(rolled-back)` request (NULL
      `exhausted_message_id`) admitted at any count (FR-004-001, FR-004-002, FR-004-005, FR-004-006,
      FR-004-048, FR-004-050; DV-26) — `src/test/java/.../inbound/DlqResubmissionReplayIT.java`
      Red→green evidence: `/private/tmp/recovery-us2-red.xml` captured assertion failures for the
      actual replay message identity and terminal-success re-send; final green: 23 broker cases and
      `./gradlew build` (873 tests, 0 failures/errors/skips; PMD and Checkstyle clean).

### Implementation for User Story 2

- [X] T025 [US2] Implement route derivation at admission (`service`/`support`/`copy` from
      `origin`/absence) as an audit-only label: append to `audit_note`, log field, increment
      `probation_replays_total{route}` — no eligibility/settlement/sending rule reads `origin`
      itself (FR-004-005) — `application/` (alongside T011's predicate class)
- [X] T026 [US2] Confirm (and extend if needed) the inherited redelivery/final-delivery
      `exhausted`/`collision`/`validation` dead-letter paths still hold under T011's replaced
      predicate (FR-004-001's parenthetical: "every delivery of a `FAILED` request is judged by this
      predicate from this slice on") — `inbound/`
- [X] T027 [US2] Seed the `doc/DEVIATIONS.md` DV-26 row (create the file if slice 002 has not yet;
      constitution Principle I) naming `DlqResubmissionReplayIT` as its pinning test (FR-004-050) —
      `doc/DEVIATIONS.md` (existing DV-26 row confirmed; the named pinning test now passes).

**Checkpoint**: User Stories 1 and 2 both work independently — the go-live precondition for SIT
negative testing (SC-004-011) is now testable.

---

## Phase 5: User Story 7 - Pause intake, purge the paused queue, resume only when re-enabled (Priority: P2)

**Goal**: an operator pause stops intake durably (across restarts and replicas), purge-queue removes
only pre-cut-off messages while paused, and the rollback's close-in-flight statement closes every
in-flight row.

**Independent Test**: pause; enqueue more; confirm no delivery taken,
`probation_intake_suspended{cause="operator"}` = 1, resend-leg refused `intake-paused`, purge-queue
allowed; purge; confirm count/cut-off and removal; resume; confirm deliveries resume. Separately run
the close-in-flight statement over seeded `PENDING`/`PARKED` rows.

### Tests for User Story 7 (write first, confirm red, then implement) ⚠️

- [X] T028 [P] [US7] `IntakePauseResumeTest`: pause stops delivery and sets the gauge; pausing a
      paused intake (or resuming a running one) is a no-op; a store outage and its recovery do not
      lift an operator pause (FR-004-051, FR-004-052) —
      `src/test/java/.../application/IntakePauseResumeTest.java`.
      **"Pause stops delivery and sets the gauge" is T009/T010's `ConsumerLifecycleController`
      reconciliation, already pinned there** — this test pins `IntakePauseAction`'s own contract:
      done/no-op from the repository write, and that the action never reads or reacts to store
      availability at all (store outages and operator pauses are separate holds by construction,
      since this class has no `StoreGate`/probe dependency to react through).
      RED: 2 `UnnecessaryStubbingException`s (the stub `pause()`/`resume()` always returned `Done`,
      ignoring the mocked repository) + 2 `AssertionFailedError: expected: NoOp[] but was: Done[]`.
      GREEN: all 6 cases pass.
- [X] T029 [P] [US7] `PurgeQueueActionTest`: refused `intake-not-paused` while running, removing
      nothing; while paused, removes every message enqueued before the run's start instant, leaves
      the DLQ untouched, reports count + cut-off (FR-004-053) —
      `src/test/java/.../application/PurgeQueueActionTest.java`.
      New port `application/QueuePurger.java` (`purgeBefore(Instant)`, `PurgeResult`,
      `QueuePurgeException`) stands in for the Service Bus admin/management client — "leaves the
      DLQ untouched" is the port's own contract (a dead-letter call is never made by this action);
      no production adapter is wired yet (same stub boundary as T021's `ReplayRunner` — open_points).
      RED: `NullPointerException` (the stub body always called the purger, ignoring the pause gate)
      + an `UnnecessaryStubbingException`.
      GREEN: all 3 cases pass.
- [X] T030 [P] [US7] `RollbackFencingIT` — the close-in-flight case (FR-004-049a): over a seeded
      `RECEIVED`+`PENDING` row, a `RETRYING`+`PARKED` row, a `FAILED(leg-exhausted)`+`PENDING`-resend
      row and a `COMPLETED`+`PENDING`-resend row, the statement closes each leg row
      `FAILED(rolled-back)` with the correct `note`/`prev=` form, changes only `status`/`reason`/
      `note`/`updated_at` (+ a fresh `claim_token` on the resend row), and maps each request
      correctly (`RECEIVED`/`RETRYING` → `FAILED(rolled-back)`, `FAILED` keeps its reason/id,
      `COMPLETED` untouched); a late runner's write after the close changes nothing and counts as a
      stale-runner rejection — **(confirmed runner ownership 2026-10-26, FR-004-049)** —
      `src/test/java/.../persistence/RollbackFencingIT.java` (this file also carries US1's and US3's
      late-runner cases, T015/T037, assembled together once this task lands).
      All four acceptance-scenario rows and **US1's late-runner case (T015's second bullet — a late
      queue and a late replay runner)** are implemented and pass, in `CloseInFlightRows` and
      `LateRunnerFencing` nested classes respectively. **US3's late resend-runner case (T041) is
      deliberately not added here**: it needs a guarded resend-completion write method that does
      not exist until Phase 6 (T046) — out of scope for this pass.
      RED: all 6 cases failed against a stub `closeInFlight()` returning `(0, 0)` — the 4
      acceptance-scenario cases with `AssertionFailedError: expected: 1 but was: 0` on
      `pendingClosed()`/`parkedClosed()`, and the 2 late-runner cases because the rows were never
      closed in the first place (`expected: 0 but was: 1`; `expected: "FAILED" but was: "PARKED"`).
      GREEN: all 6 pass against the real `JdbcRollbackFencingRepository`.
- [X] T031 [P] [US7] `ResendConcurrencyIT` (US7 half): resend-leg refuses a whole run submitted while
      paused (`intake-paused`, nothing read/changed); a pause beginning mid-run claims no further ID
      and reports the rest `not-run` (FR-004-021) —
      `src/test/java/.../application/ResendConcurrencyIT.java` (shared file with US3's T038; this
      task writes the pause-related cases first since they need no resend machinery beyond the pause
      read).
      Written as a plain Mockito test (no Testcontainers yet — "no resend machinery beyond the
      pause read" needs none); US3's T038/T041 extensions, which need real cross-run concurrency,
      will add `@Testcontainers`/the Service Bus emulator to this same class when they land.
      RED: `AssertionError: Expecting Optional to contain: INTAKE_PAUSED but was empty` +
      `UnnecessaryStubbingException` + a `containsExactly` mismatch against an empty `notRun` list
      (the stub always proceeded with an empty `notRun`, never reading the pause gate).
      GREEN: all 3 cases pass.

### Implementation for User Story 7

- [X] T032 [US7] Implement `application/IntakePauseAction.java` / the resume counterpart: write
      `intake_control.paused_by_operator` (+ `paused_at`) first, then the in-memory flag and gauge,
      inside the existing JDBC transaction template; `no-op` when already in the requested state
      (FR-004-051) — **(confirmed 2026-10-26, FR-004-052)**.
      `IntakePauseAction.pause()`/`.resume()` call `IntakeControlRepository.pause()`/`.resume()`
      (a single guarded `UPDATE ... WHERE paused_by_operator = <opposite>`, atomic, no explicit
      transaction template needed for one statement) and map the changed/unchanged row count to
      `Done`/`NoOp`, catching `DataAccessException` as `StoreUnavailable`.
      **Deferred, not done here**: the action does not force an immediate update of
      `ConsumerLifecycleController`'s in-memory flag/gauge after a successful write — it relies on
      the next `distribution.store.probe-interval` reconciliation (T010) to pick up the change,
      per research.md Decision 3's own "acceptable gap...made observable rather than silent"
      framing. An immediate push would need `IntakePauseAction` to hold a reference to the
      lifecycle controller (or a narrower "force reconcile now" port), which no task in this range
      asks for; flagged in open_points for a later pass if sub-probe-interval pause latency turns
      out to matter operationally.
- [X] T033 [US7] Implement `application/PurgeQueueAction.java`: refuse `intake-not-paused` unless
      paused; otherwise remove messages on `probation.requests` enqueued before the run's start
      instant via the Service Bus admin/management client, leave the DLQ untouched, report count +
      cut-off, log one structured line (FR-004-053).
      Reads `IntakePauseGate.pausedByOperator()` first; when paused, calls the new `QueuePurger`
      port with the clock's current instant and maps its `PurgeResult`/`QueuePurgeException` to
      `Done`/`BrokerUnavailable`, logging one structured line per outcome. **No production
      `QueuePurger` adapter (the actual Service Bus admin/management client call) is implemented in
      this range** — no task here names that adapter class, and building one needs a receive-and
      classify-by-enqueued-time loop over the live queue that no test in this phase exercises;
      flagged in open_points, same boundary as T021's unwired `ReplayEnqueuer`.
- [X] T034 [US7] Implement `application/RollbackCloseInFlightAction.java`: one transaction per
      `PENDING`/`PARKED` row exactly as FR-004-049a states — **(confirmed 2026-10-26, FR-004-049)**.
      The per-row transaction statement itself (FR-004-049a) lives in
      `persistence/JdbcRollbackFencingRepository` (T030), per `design_rules.md`'s ports-and-adapters
      rule (`application/` must not import a JDBC type); `RollbackCloseInFlightAction` is the thin
      pause-gate wrapper research.md Decision 2 names — refused `intake-not-paused` unless paused
      (the same guard as `PurgeQueueAction`), otherwise runs the statement and reports the result.
      Added `RollbackCloseInFlightActionTest` (not separately task-numbered; required by the
      repo's own TDD mandate for any new production class) pinning just the wrapper's own
      pause-gate behaviour, since the statement's correctness is `RollbackFencingIT`'s job.
      RED: `NullPointerException` (the stub body ignored the pause gate and always called the
      repository with no result to wrap) + an `UnnecessaryStubbingException`.
      GREEN: both cases pass.
- [X] T035 [US7] [P] Implement `cli/PauseIntakeRunner.java`, `cli/ResumeIntakeRunner.java`,
      `cli/PurgeQueueRunner.java`, `cli/RollbackCloseInFlightRunner.java` per
      `contracts/cli-runners.md` §3, §4, §6 — each refuses per its own guard before any store read.
      Each is the `ReplayRunner` shape: a thin switch mapping its action's sealed outcome to
      `RunnerOutcome`, with its own `*RunnerTest`. Initially left unwired like T021's `ReplayRunner`;
      these four are now registered by T037. Each delegates its guard to the application action,
      rather than duplicating it in the runner.
      RED (all four, a single run): 5 `AssertionFailedError`s — each store-unavailable/
      broker-unavailable/refused case answered `DONE` against a stub body that always returned
      `RunnerOutcome.DONE` without inspecting the action's outcome.
      GREEN: all 11 cases across the four `*RunnerTest` classes pass.
      T037 now registers these four runners in `OperatorCommandLine`'s separate CLI composition
      root; pause/resume and close-in-flight are executable. Purge has an explicit unavailable
      adapter until broker-native cutoff deletion can be integrated safely.
- [X] T036 [US7] Wire `LegResender`'s and `PurgeQueueAction`'s pause checks to the reconciled
      in-memory flag from T010 (read-before-claim for resend-leg, FR-004-021; read-before-remove for
      purge-queue) — `application/LegResender.java` stub wiring only; full `LegResender` lands in
      Phase 6.
      Both depend on the same `application.IntakePauseGate` port `ConsumerLifecycleController`
      implements (T010) — never a fresh database read. `LegResender`'s stub (this task) checks the
      gate once before the whole run and once per id in the loop, with no claim/send logic behind
      it yet; `PurgeQueueAction` (T033) already read this same port. Both are clearly marked as the
      hand-off point for Phase 6 (`LegResender`) — nothing in this stub's shape should need to
      change when the real eligibility/claim/send pipeline replaces its loop body.
- [ ] T037 Run `quickstart.md` step 6 (pause/purge/resume) and the close-in-flight smoke test locally.
      **Partially implemented**: `Application` selects a separate non-web CLI composition root,
      registers the four runners with `RunnerDispatcher`, validates input before startup, closes
      resources and returns the contracted exit code. `PackagedIntakeCommandsIT` executes the built
      jar against PostgreSQL and the Service Bus emulator: durable pause, a live controller holding
      messages while paused, resume releasing them, guard refusals, and closing pending/parked rows.
      RED: `/private/tmp/t037-cli-red.xml` records the assertion failure when `pause-intake` started
      the normal service and attempted a Redis connection; the isolated bootstrap removes that path.
      GREEN (2026-10-27): `./gradlew build` completed successfully; 877 tests, zero failures/errors/
      skips, including all four `PackagedIntakeCommandsIT` cases; PMD and Checkstyle clean.
      Code review and QA PASS, spec validation COMPLIANT for this partial implementation.
      **Outstanding**: successful purge. Java SDK 7.17.19 has no public native cutoff-delete
      operation. A receive/abandon implementation was rejected in review because it can exhaust
      later messages' delivery budgets and dead-letter them. The current paused purge fails with
      exit 2 (`unsupported-broker-operation` / `broker-unavailable`), receiving/removing nothing;
      unpaused purge refuses with exit 1. Microsoft's [native batch-delete feature](https://learn.microsoft.com/en-us/azure/service-bus-messaging/batch-delete)
      performs cutoff selection on the broker. A supported integration and a successful purge
      smoke test are required before this task can be checked off.

**Checkpoint**: intake pause is durable and the pause/resume/rollback commands are executable.
Purge remains unavailable; 006's rollback runbook cannot treat the purge step as implemented.

---

## Phase 6: User Story 3 - Resend the HMPPS leg for an explicit list of requests (Priority: P2)

**Goal**: an operator-supplied list of `requestId`s is each checked, claimed and run on its own; a
resend never parks; recovery completes a `FAILED` request on a terminal class-1/`SENT`/
`NOT_APPLICABLE` outcome.

**Independent Test**: two `FAILED` latest rows, one `SENT`, one under a live claim, one unknown ID;
run resend-leg with the gateway stub answering 200; observe two new attempt rows, three refusals by
code, five report entries, refusal counters incremented.

### Tests for User Story 3 (write first, confirm red, then implement) ⚠️

- [X] T038 [P] [US3] `ResendByRequestIdsIT`: the mixed-list scenario above; duplicates collapsed and
      run once; empty list → `missing-argument`; over-cap distinct IDs → `too-many-request-ids`
      before any read; per-ID check order `unknown-request` → `request-claimed` →
      `resend-in-progress` → `not-eligible` (FR-004-022); eligibility table incl. attempt-1
      `FAILED(rolled-back)` = `not-eligible`, attempt ≥ 2 `FAILED(rolled-back)` = eligible
      (FR-004-023); new attempt row committed (claim + `resend_run_id` + `operator`) before any POST
      (FR-004-025); the resend halves of DV-07 (Redis failure → query API → `FAILED(payload-
      unavailable)`, never `PARKED`) and DV-17 (every resend's query-API read carries the system
      user as `CJSCPPUID`) (FR-004-050a) — `src/test/java/.../application/ResendByRequestIdsIT.java`
      Written over a shared `application/ResendTestSupport` (extends `EndToEndTestSupport`: real
      Postgres, Redis and WireMock, the production payload chain, users-groups client and gateway
      publisher; no broker — resend-leg is a direct call). Compile-safe seams landed with it:
      `ResendRequest`, `ResendRunResult`, `ResendEntryOutcome`, `domain/ResendAttempt`, the
      `LegResender` five-argument constructor (loop body still the T036 pause stub),
      `ProcessedRequestRepository.claimResend` (seam: always `unknown-request`, not yet called),
      `OutboundLog.recordResend`/`OutboundMessageRepository.writeResendOutcome` (seam:
      0 rows), `DistributionRunner.runResend` (seam: unrecorded `PENDING`) and
      `OutcomeMetrics.staleRunnerRejection`/`resendRefusal` (seam: not counted).
      `LegResenderStubOutcome` is replaced by `ResendRunResult` (same `wholeRunRefusal()`/`notRun()`
      shape); T031's three pause cases updated mechanically to the new constructor and stay green.
      RED: all 15 `ResendByRequestIdsIT` cases fail on assertions against the stub loop — e.g.
      `MixedList > two FAILED legs resent ... : Expecting actual: [] to contain exactly (and in same
      order): [...]`, `Input > an empty list is refused whole missing-argument : Expecting Optional
      to contain: MISSING_ARGUMENT but was empty`, `CheckOrder > ... : Expecting Optional to contain:
      Refused[reason=NOT_ELIGIBLE] but was empty`, `ClaimBeforePost > ... : Expected size: 1 but was:
      0`, `RegisteredDeviations > DV-07 ... : Expecting Optional to contain: Ran[status=FAILED,
      reason=Optional[PAYLOAD_UNAVAILABLE]] but was empty`.
      GREEN (after T043–T046): all 15 `ResendByRequestIdsIT` cases PASSED.
- [X] T039 [P] [US3] `ResendSharedTimeCheckIT`: the `sharedTime` check runs directly after the fetch,
      before any leg stage, comparing as instants at stored precision; a cache-vs-query rendering
      difference still proceeds; a later/absent/unparseable `sharedTime` →
      `FAILED(payload-changed)` (no `youth_switch`/`body_digest`, nothing sent) unless
      `allow-changed-payload` (then `override=true`+note form) (FR-004-029–031) —
      `src/test/java/.../application/ResendSharedTimeCheckIT.java`
      Nine cases over `ResendTestSupport`: the stored rendering, an offset rendering, a
      zone-named rendering (`...+01:00[Europe/London]`) and a nanosecond rendering equal at the
      stored microsecond precision all proceed; a later, absent or unparseable `sharedTime` is
      `FAILED(payload-changed)` with `payload_source` kept, no `youth_switch`/`body_digest`/
      `service_version`, no users-groups read, no POST, the request unchanged and an `audit_note`
      line quoting no payload content; `allow-changed-payload` over a change records
      `override = true` and `override=allow-changed-payload`, and over a match records neither.
      RED: all 9 fail on assertions against the T038 seam (`runResend` records nothing) — e.g.
      `Changed > a later sharedTime fails payload-changed ... : Expecting Optional to contain:
      Ran[status=FAILED, reason=Optional[PAYLOAD_CHANGED]] but was empty`, `Proceeds > a rendering
      that names a zone still proceeds : Expecting Optional to contain: Ran[status=SENT,
      reason=Optional.empty] but was empty`.
      GREEN (after T044–T046): all 9 `ResendSharedTimeCheckIT` cases PASSED.
- [X] T040 [P] [US3] `RequestRecoveryTransitionTest` (US3 half, extends T015's file): a resend that
      ends `SENT`/`NOT_APPLICABLE`/class-1-`FAILED` recovers a `FAILED` request to
      `COMPLETED(recovered)` in the outcome's transaction, clearing both columns and appending to
      `audit_note`; a class-2 `FAILED` outcome leaves the request untouched; no statement moves a
      `COMPLETED` request (FR-004-032–034) — extends
      `src/test/java/.../application/RequestRecoveryTransitionTest.java`
      Added as the `ResendRecovery` nested class (9 cases), driving
      `OutboundMessageRepository.writeResendOutcome` over a seeded `PENDING` resend row: `SENT`,
      `NOT_APPLICABLE`, `FAILED(receiver-rejected)` and `FAILED(rules-exception)` recover a `FAILED`
      request to `COMPLETED(recovered)`, clearing `failure_reason`/`exhausted_message_id` and
      appending one `RECOVERED_BY_RESEND prior=<reason> exhausted=<id>` line; `gateway-transient`,
      `payload-unavailable` and `payload-changed` leave it as it was; a failed or `SENT` resend of a
      `COMPLETED` request's leg leaves it `COMPLETED(both-legs-terminal)`.
      RED: 8 of the 9 fail on assertions against the `writeResendOutcome` seam (0 rows) — e.g.
      `a SENT resend recovers ... : expected: 1 but was: 0`, `[1] reason = RECEIVER_REJECTED :
      expected: "COMPLETED" but was: "FAILED"`, `a failed resend of a COMPLETED request's leg ... :
      expected: "FAILED" but was: "PENDING"`. The ninth (`a SENT resend of a COMPLETED request's
      leg does not re-complete it`) asserts only an absence and passes against the seam too; it is
      kept as the FR-004-034 regression guard.
      GREEN (after T046): all 9 `ResendRecovery` cases PASSED, with T015's three still PASSED.
- [X] T041 [P] [US3] `ResendConcurrencyIT` (US3 half, extends T031's file): two resend runs and a
      late queue delivery for one leg, or a replay and a resend racing in either order — exactly one
      runs the leg and exactly one POST results (FR-004-035); a late resend-runner completion by its
      rotated claim token changes nothing and counts as a stale-runner rejection — RollbackFencingIT's
      "late resend runner" case, pinned here (FR-004-028) — extends
      `src/test/java/.../application/ResendConcurrencyIT.java`
      The class now extends `ResendTestSupport` (real PostgreSQL, Redis, WireMock); T031's three
      Mockito pause cases are kept as they are. Five real-store cases in two nested classes: each
      race is made deterministic by running the competitor from inside the first run's send, after
      its attempt row is committed — a second resend run (`resend-in-progress`, one POST, two
      rows), a late queue delivery through `IdempotencyGuard` (`ReplayDeferred`), a replay admitted
      first (`request-claimed`, no row, no POST), a replay statement reached after the resend's claim
      committed (not admitted, `replayed_at` NULL — the guard's read-then-update window), and a late
      resend runner completing after `JdbcRollbackFencingRepository.closeInFlight` rotated its token
      (nothing changes: the row stays `FAILED(rolled-back)` with no digest, the request `FAILED`, the
      entry `not-run`, `probation_stale_runner_rejections_total` = 1). The last is RollbackFencingIT's
      deferred "late resend runner" case, pinned here as T041 names.
      RED: all 5 fail on assertions against the T038 seams — e.g. `Races > two resend runs ... :
      Expecting Optional to contain: Ran[status=SENT, reason=Optional.empty] but was empty`,
      `Races > a replay statement reached after a resend claimed the leg is not admitted : Expecting
      actual: [] to contain exactly (and in same order): [false]`, `LateResendRunner > ... :
      Expecting Optional to contain: NotRun[] but was empty`. The three pause cases stay green.
      GREEN (after T043–T046): all 8 `ResendConcurrencyIT` cases PASSED.
- [X] T042 [US3] `PropertiesValidatorTest`/eligibility unit test for the confirmed FR-004-024
      resolution: an expired `PENDING` resend row is refused `resend-in-progress`, never reclaimed —
      **(confirmed 2026-10-26)**; assert unchanged attempt and claim, and no POST — add to `ResendByRequestIdsIT` (T038) as its own case, not a new file
      Added as `ResendByRequestIdsIT$CheckOrder.expired_pending_resend_row_is_never_reclaimed`
      (attempt 2 stays `PENDING` with its seeded claim token, no third row, no POST,
      `probation_resend_refusals_total{reason="resend-in-progress"}` = 1).
      RED: `Expecting Optional to contain: Refused[reason=RESEND_IN_PROGRESS] but was empty`.
      GREEN (after T043): `CheckOrder > an expired PENDING resend row is refused
      resend-in-progress, never reclaimed (T042) PASSED`.

### Implementation for User Story 3

- [X] T043 [US3] Implement `application/LegResender.java`: input validation per FR-004-020/
      FR-004-019 (collapse duplicates, cap check, whole-run refusals before any read), the per-ID
      check order of T038 (`unknown-request` → `request-claimed` → `resend-in-progress` →
      `not-eligible`), sequential execution per run (research.md Decision 4). **For each eligible
      ID, in the transaction that checked it: INSERT the new attempt row — `max(attempt)+1`,
      `attempt_origin=resend`, `PENDING`, its own leg claim (`claim_owner`, fresh `claim_token`,
      `claim_expires_at`), `resend_run_id`, `operator` — and COMMIT that transaction before issuing
      any POST** (FR-004-025) — satisfies the claim-before-POST half of T038
      `LegResender` validates whole-run input (empty list or blank/control-only operator or reason →
      `missing-argument`; operator or reason over 200 → `argument-too-long`; distinct ids over the cap
      → `too-many-request-ids`; then the pause → `intake-paused`), collapses duplicates in list
      order, and runs the ids one after another, latching an operator pause seen mid-run. The
      per-ID check and claim are `JdbcProcessedRequestRepository.claimResend`: one transaction that
      locks the request row `FOR UPDATE`, then the leg's latest row, refusing in the FR-004-022
      order, and inserts the `PENDING` resend row (`max(attempt)+1`, its own leg claim at the
      configured lease, `resend_run_id`, sanitised `operator`), committed before the run starts.
      Refusals count `probation_resend_refusals_total{reason}` (`MetricRegistry.resendRefusal`,
      on the `OutcomeMetrics` port); an unrecorded completion counts a stale-runner rejection and is
      reported `not-run`; a per-ID store failure is logged at ERROR and reported `not-run`. One
      structured `resend-leg report` line (run id, leg, overrides, counts, `id=outcome` entries; no
      operator or reason text). T031's two non-paused Mockito cases now stub `claimResend`.
      GREEN (partial, as planned): `ResendByRequestIdsIT` `Input` (empty, over-cap, operator/reason)
      and `CheckOrder` refusal cases (request-claimed first, live and expired `PENDING` resend rows —
      T042 —, attempt-1 rolled-back, `PARKED`, `PENDING` attempt 1) PASSED, and
      `ResendConcurrencyIT$Races > a replay admitted first leaves the resend refused
      request-claimed` PASSED; the cases that need a send stay red on `NotRun` until T044–T046.
- [X] T044 [US3] Implement the `sharedTime` comparison stage (instant parsing, honouring offset/zone,
      stored-precision comparison) inside `LegResender`'s run, before the switch read (FR-004-029) —
      satisfies T039
      `application/SharedTimeCheck` (package-private, pure): reads the payload's top-level
      `sharedTime` string and parses it with `ZonedDateTime.parse(..., ISO_DATE_TIME)` — an offset
      or a named zone honoured, a rendering with neither unparseable — and compares it with the
      stored `shared_time` truncated to microseconds, the `timestamptz` precision. The resend
      parses for itself rather than reusing `adapter.payload.SharedTimes` (offset only), so the
      queue run's `payload_shared_time` recording is unchanged. Wired into the run by T045; GREEN
      recorded with T046, when the attempt's completion is first written.
- [X] T045 [US3] Implement the resend run's stage sequence (fetch → `sharedTime` check → switch →
      redaction → body digest → send), reusing slice 002's `HearingPayloadSource`,
      `YouthFilterSource`, `HmppsRedaction`, `HmppsSender` unchanged, with the resend-only rules: no
      park (`FAILED(payload-unavailable)`/`FAILED(gateway-transient)` at once), always the system
      user as `CJSCPPUID` (FR-004-026, FR-004-027, FR-004-050a)
      `DistributionRunner.runResend(command, attempt, allowChangedPayload)`: the same fetch →
      switch → `HmppsRedaction` → digest → send stages as the queue run, with the `sharedTime`
      check (T044) directly after the fetch — `FAILED(payload-changed)` with `payload_source` and the
      parsed instant only, no switch read and no send, unless `allow-changed-payload` lets it
      through with the override recorded — and every transient end mapped as on a final delivery,
      so a resend never parks (`FAILED(payload-unavailable)`/`FAILED(gateway-transient)` at once).
      The system user comes from the command itself: `claimResend` rebuilds it from the request's
      columns with no `userId`, so the unchanged results-query client always sends the system user
      (DV-17). Each outcome is written through `OutboundLog.recordResend` under the attempt's own
      leg claim, labelled `origin=resend`, `forced=false`. The runner's three write paths (queue,
      replay, resend) now share one private `LegWriter` strategy in place of the
      `Optional<String> replayMessageId` flag; `DistributionRunnerTest`, `OutcomeMetricsTest` and
      `LegOutcomeLogLineTest` stay green unchanged (25 cases PASSED). GREEN for T038/T039 recorded
      with T046, when the completion is first written.
- [X] T046 [US3] Implement the recovery transition inside the attempt's completion transaction
      (FR-004-032–034), reusing the request-row-lock discipline from T017 — satisfies T040. **The
      completion UPDATE itself is `WHERE id = ? AND claim_token = ?`: zero rows updated (the token
      was rotated by the rollback protocol, or another runner already completed the row) means
      nothing changes and the run counts one `probation_stale_runner_rejections_total`, without
      attempting recovery** (FR-004-028) — satisfies the claim-token-guard half of T041
      `OutboundMessageRepository.writeResendOutcome` (behind `OutboundLog.recordResend`), in one
      transaction: lock the request row `FOR UPDATE`; then one `UPDATE outbound_message ... WHERE
      id = :id AND claim_token = :token AND status = 'PENDING'` writing the outcome columns,
      `override`/`note` (`override=allow-changed-payload` only when the override was used) and
      `attempts + 1`, keeping the claim and `resend_run_id`; zero rows → 0, nothing else written (the
      runner's unrecorded result is the stale-runner rejection `LegResender` counts). On a write: the
      body replaced as for every attempt; a `payload-changed` outcome appends one `RESEND_REFUSED
      attempt=<n> reason=payload-changed` line to `audit_note` and changes nothing else; a `SENT`,
      `NOT_APPLICABLE` or class-1 `FAILED` outcome moves a `FAILED` request to
      `COMPLETED(recovered)` (`... WHERE status = 'FAILED'`, so a `COMPLETED` request is never
      moved), clearing `failure_reason`/`exhausted_message_id`/`replayed_at` and appending one
      `RECOVERED_BY_RESEND attempt=<n> prior=<reason> exhausted=<id>` line.
      Also, for FR-004-035 (T041's replay race): `JdbcProcessedRequestRepository.admitReplay` now
      locks the request row `FOR UPDATE` in its own transaction before the replay statement, and the
      statement adds `AND NOT EXISTS` a `PENDING` resend latest row — so a guard whose read preceded
      a resend's committed claim is not admitted (it abandons, and the redelivery is deferred by
      FR-004-003's existing path) instead of running beside the resend.
      GREEN: `ResendByRequestIdsIT` 15/15, `ResendSharedTimeCheckIT` 9/9, `ResendConcurrencyIT` 8/8
      (T041's five and T031's three), `RequestRecoveryTransitionTest` 12/12 (T040's nine and T015's
      three) PASSED; `RequestReplayStatementTest` 4/4, `IdempotencyGuardTest` 20/20 and
      `RollbackFencingIT` 6/6 still PASSED.
- [X] T047 [US3] [P] Implement `cli/ResendLegRunner.java` per `contracts/cli-runners.md` §2:
      argument parsing/validation, calls `LegResender`, prints the per-ID report + summary, one
      structured log line, `probation_resend_refusals_total{reason}` per refused ID (FR-004-039)
      Test first: `cli/ResendLegRunnerTest` (11 cases) against a seam runner (`checkArguments`
      always empty, `run` always `DONE`).
      RED: 9 of 11 fail on assertions — e.g. `Arguments > a missing --leg, --request-ids, --operator
      or --reason is missing-argument : Expecting Optional to contain: MISSING_ARGUMENT but was
      empty`, `Arguments > the forced-resend override is refused until it is built : Expecting
      Optional to contain: UNREADABLE_ARGUMENT but was empty`, `Run > answers failed for
      store-unavailable : expected: FAILED but was: DONE`, `Run > hands the parsed ids ... : Wanted
      but not invoked: resender.resend(...)` (and its sibling's `UnnecessaryStubbingException`, the
      stub never being reached). `a readable invocation passes` is an absence check, and `the
      packaged command refuses unreadable arguments with exit 1` passes before the change only
      because `OperatorCommandLine` refuses every unregistered action with exit 1; both are kept as
      guards that registering the action does not open resources for unreadable input.
      Implementation: `ResendLegRunner.checkArguments` (static, the shape only: missing/blank
      `--leg`/`--request-ids`/`--operator`/`--reason` → `missing-argument`; a leg other than `HMPPS`,
      a list entry that is not a UUID, or `--force-resend-sent` → `unreadable-argument`; operator or
      reason over 200 → `argument-too-long`) and `run` (the same check, then `LegResender.resend`;
      one `resend-leg id ... outcome=<code>` line per ID and a `resend-leg done` summary; whole-run
      refusal → `REFUSED`, `store-unavailable` → `FAILED`, a report → `DONE`). The refusal counter
      and the one structured report line are `LegResender`'s (T043), so the operations API gets
      them too. `OperatorCommandLine` checks `resend-leg`'s arguments before building any context
      (exit 1), then runs it in the same separate CLI composition root as the four Phase 5 runners,
      registered in its `RunnerDispatcher`; the arguments reach the runner as an
      `OperatorArguments` bean, never as Spring command-line properties. The resend pipeline (Redis
      client and connection, results-query and users-groups clients, gateway publisher, outbound
      log, `DistributionRunner`, `LegResender`) is `@Lazy` and built only when `resend-leg` is
      dispatched, from the same `distribution.*` properties the service binds.
      GREEN: all 11 `ResendLegRunnerTest` cases PASSED; `PackagedIntakeCommandsIT` 4/4 and
      `RunnerDispatcherTest` 3/3 still PASSED (the intake commands open no cache).
- [X] T048 [US3] [P] `OutcomeMetricsTest` (US3 half, extends T022's file): resend outcomes carry
      `origin=resend`, `forced=false`; log lines carry status/reason/HTTP status/body digest/
      `runOrigin`/`youth_switch` (FR-004-011, FR-004-040) — extends
      `src/test/java/.../application/OutcomeMetricsTest.java`
      Three cases driving `DistributionRunner.runResend` with mocked ports and a
      `SimpleMeterRegistry`: `leg_outcomes_total{origin=resend, forced=false, status=SENT}` = 1; a
      `FAILED(gateway-transient)` line carrying `status`, `reason`, `httpStatus=503`, `bodyDigest`,
      `runOrigin=RESEND`, `youthSwitch=ACTIVE` and not the body; and a defaulted read's line carrying
      `youthSwitch=UNREADABLE_DEFAULTED_ACTIVE` with `youthSwitchCause=TIMEOUT`.
      RED not reproducible: the labelling and the log line are the shared `record` path the resend
      run reached with T045, landed before this test as the task order places it; this is a
      characterization test, like T022's replay half. (One expectation of the first draft named the
      cause in lower case and failed — `Expecting actual: "... youthSwitchCause=TIMEOUT ..." to
      contain "youthSwitchCause=timeout"` — a test error, corrected to the wire value.)
      GREEN: all 6 `OutcomeMetricsTest` cases PASSED.
- [X] T049 Seed the `doc/DEVIATIONS.md` DV-07 and DV-17 rows' resend-half cross-reference, naming
      `ResendByRequestIdsIT` beside slice 002's queue-run tests (FR-004-050a) — `doc/DEVIATIONS.md`
      Both rows' "resend half with slice 004" placeholder now names `ResendByRequestIdsIT` (slice
      004), whose `RegisteredDeviations` cases pin DV-07's resend half (a closed Redis connection
      falls back to the query API and ends `FAILED(payload-unavailable)`, never `PARKED`) and
      DV-17's (the query-API read carries the system user although the stored command has a
      `userId`). Cross-references only: no row's behaviour text changed, no DV id added (next free
      stays DV-27); this phase found no unregistered behaviour difference — the resend's
      `sharedTime` check, recovery and fencing are slice 004's own requirements, not departures
      from the legacy, which has no resend. `DeviationRegisterTest` still passes.
- [ ] T050 Run `quickstart.md` steps 3–4 (resend, forced-resend precondition) locally
      **Partially run (2026-10-28), left unchecked.** What ran: `docker compose up -d postgres redis
      wiremock` (no Service Bus emulator); the built jar
      (`build/libs/service-cp-crime-probation-results-distribution-0.0.999.jar`, JDK 25) with the
      compose stack's `local`-profile environment. `resume-intake` migrated the store (exit 0). A
      `FAILED(leg-exhausted)` request with an attempt-1 `FAILED(payload-unavailable)` row was seeded
      directly in PostgreSQL (not through quickstart step 1's queue-and-500 path), its payload under
      the dated `EXT_` key in Redis, WireMock stubbed for the users-groups read (`active: true`) and
      the gateway (200). Step 4, `resend-leg --leg HMPPS --request-ids <id> --operator local-dev
      --reason "manual smoke test"`, exited 0 and logged the one `resend-leg report ... ids=1 taken=1
      sent=1 summary={SENT=1}` line, a `resend-leg id ... outcome=SENT` line and `resend-leg done`;
      the store then held attempt 2 `resend`/`SENT` with `operator = local-dev` and a
      `resend_run_id`, attempt 1 unchanged, and the request `COMPLETED(recovered)`. The forced-resend
      precondition: a second run over the now-`SENT` leg exited 0 reporting `not-eligible`, and the
      same run with `--force-resend-sent` exited 1 (refused `unreadable-argument` until US5 builds
      the override). What did not run: quickstart step 3 (resubmitting the original dead-letter
      entry with the emulator's tools and seeing a no-op once `COMPLETED`) — the emulator and a real
      step-1 dead-letter entry were not set up; that path is covered by the automated
      `DlqResubmissionReplayIT` (US2), not by this manual run.

**Checkpoint**: Stories 1, 2, 3 and 7 all work independently — recovery via replay and resend is
fully usable without forced resend, listings or submit-hearing.

---

## Phase 7: User Story 4 - Work the dead-letter queue by a runbook the service makes true (Priority: P2)

**Goal**: a published runbook states which DLQ entries to resend, never resend, or remove, and every
rule it states is already true of the service's behaviour (proven by Stories 1–3, not new code).

**Independent Test**: review the runbook against FR-004-046/047; exercise its four named cases (all
already covered by `DlqResubmissionReplayIT`, T024) and confirm the original entry survives a
completed replay until an operator removes it.

### Tests for User Story 4

- [ ] T051 [P] [US4] `RunbookCrossReferenceIT`: for each of the four dead-letter reasons
      (`leg-exhausted`, `exhausted`, `validation`, `collision`), assert the exact behaviour the
      runbook will state — reusing `DlqResubmissionReplayIT`'s fixtures rather than re-seeding —
      including "an `exhausted` entry whose request is already `COMPLETED` is a no-op" and "a
      `validation`/`collision` entry is dead-lettered again with no row written/untouched"
      (FR-004-046) — `src/test/java/.../inbound/RunbookCrossReferenceIT.java`

### Implementation for User Story 4

- [ ] T052 [US4] Write the operator runbook `doc/runbooks/dead-letter-recovery.md`: resend
      `leg-exhausted`/`exhausted`, never resend `validation`/`collision`, remove after completion,
      same-id one-admission-chance rule with no wait/no messageId edit needed, choosing IDs by the
      request listing or the documented `outbound_message_latest` query (incl. by
      `service_version`), reading each report code, confirming an outcome-unknown
      `gateway-transient` with HMPPS before any re-send, a forced resend as a genuine duplicate, the
      post-cache-expiry body-shape difference, and the `outbound_body` retention-window caveat
      (FR-004-046, FR-004-047)
- [ ] T053 Walk the runbook through with the owning team (SC-004-012) once T052 lands; record the
      outcome in the PR description, not in this repository

**Checkpoint**: the DLQ runbook is both published and proven true by the existing test suite.

---

## Phase 8: User Story 5 - Send an already-sent hearing again, deliberately (Priority: P3)

**Goal**: `allowTerminalSuccess` re-runs a `SENT`/`NOT_APPLICABLE` leg as a deliberate duplicate,
labelled `forced`, without moving the request off `COMPLETED`.

**Independent Test**: a `COMPLETED` request with latest row `SENT`; resend without the override
(`not-eligible`), then with it (new `resend` row, `override=true`, `override=force-resend` note,
`forced=true` metric/log, request stays `COMPLETED`).

### Tests for User Story 5

- [ ] T054 [P] [US5] `ForcedResendIT`: `SENT`/`NOT_APPLICABLE` without the override → `not-eligible`;
      with it → re-fetched, `sharedTime`-checked (with `allow-changed-payload` as any resend),
      switch re-read, re-redacted, digested, sent; row records operator + both override forms when
      both used; reason sanitised ≤ 200 chars appended to `audit_note`; request stays `COMPLETED`
      whatever the outcome; outcome counted `origin=resend, forced=true`; override for a `FAILED` ID
      is silently not used (`forced=false`, no reason line) (FR-004-006, FR-004-036–038) —
      `src/test/java/.../application/ForcedResendIT.java`

### Implementation for User Story 5

- [ ] T055 [US5] Extend `LegResender`'s eligibility check (T043) to accept `SENT`/`NOT_APPLICABLE`
      only under `allowTerminalSuccess`, routing through the same claim/run path as any resend row
      (FR-004-036)
- [ ] T056 [US5] Implement the forced-resend audit write: `operator`, `override=true`,
      `override=force-resend` (+ `override=allow-changed-payload` when both used), sanitised reason
      line appended to `audit_note`, applied per-ID only (FR-004-037)
- [ ] T057 [US5] Wire `forced=true`/`false` labelling into the outcome metric and log line from T048
      (FR-004-038) — extends `application/LegResender.java`'s outcome recording
- [ ] T058 [US5] Add `--force-resend-sent` parsing to `cli/ResendLegRunner.java` (T047) per
      `contracts/cli-runners.md` §2

**Checkpoint**: all five send-side user stories (1, 2, 3, 4, 5) are independently functional.

---

## Phase 9: User Story 6 - Find what to recover: list requests and completed DLQ entries (Priority: P3)

**Goal**: two read-only runners — the request listing and the completed-DLQ-entry listing — make the
existing selection procedures visible and repeatable.

**Independent Test**: seed requests across latest-row states and a DLQ with entries for completed,
failed and unknown requests; run both runners; compare output with seeded state.

### Tests for User Story 6

- [ ] T059 [P] [US6] `RequestListingQueryTest`: by hearing+day shows every request for that
      hearing/day with status, reasons and every leg attempt; by status/reason/`updated_at` range
      reads from `outbound_message_latest`; prints no stored command/body/payload/`audit_note`
      text/operator/`userId`; pages at 500 and says when more exist (FR-004-043, FR-004-041;
      Research Decision 5) — `src/test/java/.../application/RequestListingQueryTest.java`
- [ ] T060 [P] [US6] `DlqCompletedListingQueryTest`: lists only DLQ entries whose request is
      `COMPLETED`, with their dead-letter reason, removes nothing — same privacy bound as T059 —
      `src/test/java/.../application/DlqCompletedListingQueryTest.java`

### Implementation for User Story 6

- [ ] T061 [US6] Implement `application/RequestListingQuery.java` against
      `outbound_message_latest`, two input forms per `contracts/cli-runners.md` §7, input checked
      per FR-004-020
- [ ] T062 [US6] Implement `application/DlqCompletedListingQuery.java`: peeks the DLQ via the
      Service Bus management client, joins against `processed_request.status = 'COMPLETED'`
- [ ] T063 [US6] [P] Implement `cli/RequestListingRunner.java` and
      `cli/DlqCompletedListingRunner.java` per `contracts/cli-runners.md` §7, §8
- [ ] T064 [US6] `TelemetryPrivacyTest`/`LogStatementSweepTest` extension: sweep both listings'
      output for the forbidden content set (FR-004-041, FR-004-009 SC) —
      `src/test/java/.../support/LogStatementSweepTest.java` (extend, do not duplicate)

**Checkpoint**: operators can select resend IDs and DLQ cleanup candidates without reading code or
writing ad-hoc SQL.

---

## Phase 10: User Story 8 - Submit a hearing the service never admitted (Priority: P3)

**Goal**: submit-hearing rebuilds the producer's `requestId` from the cached payload's raw strings
and enqueues a normal command marked as support.

**Independent Test**: seed only an `EXT_` payload (no request row); run submit-hearing; observe one
enqueued command with the recipe's `requestId`, no `userId`, `origin=SUPPORT`; let it process;
observe a new request with the audit line and `origin=queue` outcomes.

### Tests for User Story 8

- [ ] T065 [P] [US8] `RequestIdRecipeTest`: the pinned vector
      (`0aa5bf35-1e51-45e5-9e42-64bd57e15c11|2026-08-19|2026-08-19T16:42:07.512Z` →
      `e74ed270-0b62-36d9-bb10-12bd6baa5bea`) reproduces exactly (FR-004-055; DIGEST §14) —
      `src/test/java/.../application/RequestIdRecipeTest.java`
- [ ] T066 [P] [US8] `SubmitHearingActionTest`: dated key, then undated key, never the query API;
      neither key present → `payload-unavailable`, nothing enqueued; enqueued command carries the
      raw `sharedTime` string, `eventType=Hearing_Resulted`, no `userId`,
      `messageId=RESULTS:{requestId}`, `correlationId=hearingId`, `origin=SUPPORT` +
      operator/runId/reason; admission appends operator/runId/reason to `audit_note`, runs as
      `origin=queue` unless it meets a `FAILED` request (then replay, route `support`); a
      non-UUID/non-date argument → `unreadable-argument` without echoing the value; **and enqueues
      normally while `intake_control.paused_by_operator = true`** — submit-hearing is available
      whatever the intake state and is never refused `intake-paused` (FR-004-054–058, FR-004-058) —
      `src/test/java/.../application/SubmitHearingActionTest.java`

### Implementation for User Story 8

- [ ] T067 [US8] Implement `application/RequestIdRecipe.java`: `UUID.nameUUIDFromBytes` over
      `hearingId|hearingDay|sharedTime` raw strings — satisfies T065
- [ ] T068 [US8] Implement `application/SubmitHearingAction.java`: cache-only read (dated, then
      undated `EXT_` key), `requestId` via T067, command assembly, enqueue via the existing Service
      Bus sender client (FR-004-054–056, FR-004-058)
- [ ] T069 [US8] Wire the `origin=SUPPORT` admission audit (operator, runId, reason →
      `audit_note`) and the `origin=queue`-unless-`FAILED` run-kind decision into the guard/runner
      from T011/T025 (FR-004-057)
- [ ] T070 [US8] [P] Implement `cli/SubmitHearingRunner.java` per `contracts/cli-runners.md` §5
- [ ] T071 Run `quickstart.md` step 7 (submit-hearing) locally

**Checkpoint**: all eight user stories are independently functional.

---

## Phase 11: Polish & Cross-Cutting Concerns

- [ ] T072 [P] Run the full `TelemetryPrivacyTest`/`LogStatementSweepTest` suite across every new
      class this slice adds (not just T064's listings) — zero findings (FR-004-041; SC-004-009)
- [ ] T073 [P] Confirm `SchemaVocabularyIT` (T005) and slice 002's existing instance both pass
      together after this slice's migration — no SQL/Java vocabulary drift
- [ ] T074 Run `./gradlew build` (PMD, Checkstyle, JaCoCo gate) and confirm the coverage thresholds
      are unchanged (SC-004-013)
- [ ] T075 Run every `quickstart.md` step end to end in one sitting against a fresh local stack
- [ ] T076 [P] Re-read `research.md`'s confirmed decisions against any subsequent owning-team
      (H31) changes; if any decision is overturned, apply the isolated change
      `research.md` names and re-run the affected story's tests only
- [ ] T077 Update `CLAUDE.md`'s Setup section once this slice merges to `main`, and hand the
      Dependencies → "005-operations-api" and "006-production-readiness" sections (plan.md,
      `contracts/cli-runners.md`) to whoever starts those slices next

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — BLOCKS every user story (the replay-admission
  predicate of T010a–T012 underlies US1 and US2; the `intake_control` mechanism of T004/T010
  underlies US7 and, through it, US3)
- **US1 (Phase 3)**: depends on Foundational only — the MVP
- **US2 (Phase 4)**: depends on Foundational (T011's predicate) and is easiest proved once US1's
  replay statement exists (T017), though US2's own code (route derivation, T025) does not depend on
  US1's `ReplayAction`
- **US7 (Phase 5)**: depends on Foundational (T004/T010); independent of US1/US2
- **US3 (Phase 6)**: depends on Foundational and on US7's pause read (T036) for FR-004-021; otherwise
  independent of US1/US2
- **US4 (Phase 7)**: depends on US1, US2 and US3 being implemented (it documents and cross-checks
  their behaviour; it adds no new production code)
- **US5 (Phase 8)**: depends on US3's resend/claim machinery (T043–T046)
- **US6 (Phase 9)**: depends on Foundational only (reads existing schema) — could move earlier if
  staffing allows; placed last among P2/P3 stories here only to mirror spec.md's own ordering
- **US8 (Phase 10)**: depends on Foundational and on US2's route derivation (T025) for the
  `support`-route case; otherwise independent
- **Polish (Phase 11)**: depends on every story to be included in that release being complete

### Parallel Opportunities

- T002, T003 in parallel after T001
- T005–T007 in parallel after T004
- Within each story's test phase, every `[P]`-marked test task runs in parallel (different files)
- US6 and US8's implementations have no file overlap with US3/US5 and may be staffed in parallel
  once Foundational and US7 (for US3's pause check, if US3 is in flight concurrently) are done

---

## Implementation Strategy

### MVP First

1. Setup → Foundational → US1. Stop and validate against US1's Independent Test. This alone gives
   operations the general-use recovery route (replay) the spec's own "Why this priority" calls the
   only way back after a rollback purge.

### Incremental Delivery

1. Setup + Foundational → US1 (MVP) → US2 (closes the go-live precondition, SC-004-011) → US7
   (rollback's prerequisite stop) → US3 (the second recovery route) → US4 (proves the runbook true,
   no new code) → US5 (deliberate duplicates) → US6 (operator visibility) → US8 (the last repair
   route) → Polish.

Each checkpoint above is independently demoable; none breaks an earlier story, because no story
after Foundational modifies another story's files except where explicitly noted (US3 extends US7's
pause-check stub at T036; US5 extends US3's `LegResender` and `ResendLegRunner`; US8 extends US2's
route-derivation class at T069).
