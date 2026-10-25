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

**Provisional decisions** (flagged for the design owner — `research.md`): FR-004-024 resolved as
"refuse `resend-in-progress`", FR-004-049 resolved as "this slice owns the close-in-flight runner",
FR-004-052 resolved as "a persisted `intake_control` row". Tasks implementing these are marked
**(provisional)**.

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

- [ ] T004 Add the provisional `intake_control` single-row table migration
      `src/main/resources/db/migration/V{next}__add_intake_control.sql` exactly as specified in
      data-model.md (columns `id`, `paused_by_operator`, `paused_at`, `updated_at`; CHECK
      `id = 1`; seed row) — **(provisional, FR-004-052)**. `{next}` is the first unused Flyway
      version once this branch is rebased onto 002's (and, if landed first, 003's) migrations —
      never renumber an already-applied migration
- [ ] T005 [P] Add `SchemaVocabularyIT` assertions (extend slice 002's existing test, do not
      duplicate it) confirming `intake_control` has exactly one row and the CHECK holds —
      `src/test/java/.../persistence/SchemaVocabularyIT.java`
- [X] T006 [P] Add closed value-set enums in `domain/`: `ReplayRoute` (`SERVICE`, `SUPPORT`, `COPY`),
      `ResendRefusalReason` (`UNKNOWN_REQUEST`, `NOT_ELIGIBLE`, `RESEND_IN_PROGRESS`,
      `REQUEST_CLAIMED`), `WholeRunRefusal` (`INTAKE_PAUSED`, `TOO_MANY_REQUEST_IDS`,
      `MISSING_ARGUMENT`, `UNREADABLE_ARGUMENT`, `ARGUMENT_TOO_LONG`, `STORE_UNAVAILABLE`,
      `BROKER_UNAVAILABLE`, `INTAKE_NOT_PAUSED`), `RunnerOutcome` (`DONE`, `REFUSED`, `FAILED`) — one
      file per enum under `domain/`
- [ ] T007 [P] `PropertiesValidatorTest` case: `distribution.resend.max-ids-per-run` must be ≥ 1 at
      startup, refusal names the property — `src/test/java/.../config/PropertiesValidatorTest.java`
      (extend the existing class; this is the one named case this slice adds, FR-004-019)
- [ ] T008 Bind and validate `distribution.resend.max-ids-per-run` (default 100) in the
      `distribution.*` `@ConfigurationProperties` class — `config/DistributionProperties.java` (or
      the equivalent existing properties record) — makes T007 pass
- [ ] T009 [P] `IntakeControlReconciliationIT` (Testcontainers Postgres): a pod that starts with the
      row `paused_by_operator = true` reconciles to paused before taking its first delivery; a pod
      that restarts mid-pause stays paused after reconciliation —
      `src/test/java/.../inbound/IntakeControlReconciliationIT.java` — write first, confirm it fails
      for the right reason, before T010 starts
- [ ] T010 Extend `ConsumerLifecycleController` (`inbound/`) with a `paused_by_operator` read path
      against the `intake_control` row: on startup and every `distribution.store.probe-interval`,
      reconcile the in-memory pause flag and `probation_intake_suspended{cause="operator"}` to the
      persisted row, making T009 pass — **(provisional, FR-004-052)** —
      `inbound/ConsumerLifecycleController.java`
- [ ] T010a [P] `ReplayAdmissionPredicateTest` (plain unit test, no Spring context): the DV-26
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
      `src/test/java/.../application/ReplayAdmissionPredicateTest.java`
- [ ] T011 Replace slice 002's interim replay-admission rule (FR-002-008) with the DV-26 predicate in
      the guard's admission statement (FR-004-001, FR-004-002): a `FAILED` request's delivery is
      admitted only if `messageId ≠ exhausted_message_id`, or `exhausted_message_id IS NULL`, or
      (`messageId = exhausted_message_id` and broker delivery count = 1); otherwise dead-lettered
      `exhausted`, request row unchanged; the exhausted-identity test runs before the
      `PENDING`-resend-row deferral check of T012 — `inbound/` (the guard's admission SQL/statement
      class) and `application/` (the predicate itself, unit-testable without Spring) — satisfies
      T010a (FR-004-001, FR-004-002)
- [ ] T012 Add the `REPLAY_DEFERRED` abandon path (FR-004-003): when the leg's latest row is a
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

- [ ] T013 [P] [US1] `RequestReplayStatementTest`: the admission statement moves
      `FAILED → RECEIVED`, clears `failure_reason`/`exhausted_message_id`, sets `replayed_at`,
      appends `REPLAYED_AFTER_FAILURE prior=<reason>` to `audit_note`, takes the request claim — one
      statement, condition = T011's predicate (FR-004-001, FR-004-004) —
      `src/test/java/.../application/RequestReplayStatementTest.java`
- [ ] T014 [P] [US1] `LegRerunTest`: an in-place re-run of an attempt-1 `FAILED`/`PARKED` row writes
      one UPDATE (no `PENDING` first), increments `attempts`, rewrites `note` to
      `prev=<STATUS>/<reason>` + `rerun-by=<messageId>`, replaces the stored body in the same
      transaction (FR-004-008); a `FAILED` row with attempt ≥ 2 gets a new appended `replay` row,
      never an in-place re-run (FR-004-009); a redelivery whose `note` already names the message in
      `rerun-by` is skipped, a `PARKED` row is re-run on every delivery (FR-004-010) —
      `src/test/java/.../application/LegRerunTest.java`
- [ ] T015 [P] [US1] `RequestRecoveryTransitionTest` (US1 half): a terminal leg after a replay
      completes the request with `completion_reason = recovered` whenever `audit_note` contains
      `REPLAYED_AFTER_FAILURE`, including when the first replayed run parks and a later redelivery
      settles it (FR-004-012); every leg-outcome write of a replayed run is guarded by the request
      claim's owner/token — a mismatch is a stale-runner rejection, counted, run does not settle
      (FR-004-013; this is RollbackFencingIT's "late queue or replay runner" case, pinned here as its
      own assertion so US1 is independently testable without the rollback runner existing yet) —
      `src/test/java/.../application/RequestRecoveryTransitionTest.java`
- [ ] T016 [P] [US1] `ReplayActionTest`: `replay --request-id` decides under the request-row lock —
      no row → `unknown-request`; `COMPLETED` → `no-op`, nothing enqueued; `RECEIVED`/`RETRYING` →
      `not-eligible`; leg's latest row a `PENDING` resend row (live or expired) → `resend-in-progress`
      (provisional, FR-004-024); otherwise enqueue `processed_request.command` byte-for-byte under a
      fresh `messageId` (never equal to `RESULTS:{requestId}` or any earlier replay message's id),
      `correlationId = hearingId`, `origin = REPLAY`; the action itself writes nothing to the store;
      **and proceeds unchanged while `intake_control.paused_by_operator = true`** — replay is
      available at any time and is never refused `intake-paused` (FR-004-014–017, FR-004-016) —
      `src/test/java/.../application/ReplayActionTest.java`

### Implementation for User Story 1

- [ ] T017 [US1] Implement the replay statement's SQL/JdbcClient call in `persistence/` behind the
      request store port, satisfying T013 — `persistence/ProcessedRequestRepository.java` (or
      equivalent)
- [ ] T018 [US1] Implement the in-place re-run and replay-row-append write paths in `persistence/`
      behind `OutboundLog`, satisfying T014 — `persistence/OutboundMessageRepository.java`
- [ ] T019 [US1] Implement `application/ReplayAction.java`: one `requestId`, checks per T016, returns
      a bounded outcome (`enqueued`/`no-op`/refusal) or fails (`store-unavailable`,
      `broker-unavailable`) with nothing changed; never throws past its outcome
- [ ] T020 [US1] Wire the replayed run's settlement consequences into the existing
      `DistributionRunner`/settlement statement: `replayed_at`-gated eligibility column
      (FR-004-007), `runOrigin = replay` labelling (FR-004-011), recovery on terminal leg
      (FR-004-012), claim-guarded writes (FR-004-013) — `application/DistributionRunner.java`
- [ ] T021 [US1] [P] Implement `cli/ReplayRunner.java`: parses `--request-id`, validates per
      FR-004-020 (missing → `missing-argument`, not a UUID → `unreadable-argument`, before any
      store/cache/broker read), calls `ReplayAction`, prints the bounded outcome and exit code per
      `contracts/cli-runners.md` §1
- [ ] T022 [US1] [P] `OutcomeMetricsTest` (US1 half): every replay outcome increments
      `probation_leg_outcomes_total{origin="replay", forced="false"}` and
      `probation_replays_total{route}`; logs carry `runOrigin`, `route`, status, reason, HTTP status,
      body digest (FR-004-011, FR-004-040) — `src/test/java/.../application/OutcomeMetricsTest.java`
- [ ] T023 [US1] Run `quickstart.md` steps 1–2 locally against the Service Bus emulator; confirm the
      Independent Test scenario end to end

**Checkpoint**: User Story 1 is fully functional and independently testable — the MVP.

---

## Phase 4: User Story 2 - A DLQ resubmission is admitted as a replay (Priority: P1)

**Goal**: a dead-letter resubmission (same or new `messageId`) is admitted as a replay exactly once,
re-dead-lettered `exhausted` above delivery count 1 under the same id, and the route is recorded.

**Independent Test**: three `FAILED(leg-exhausted)` requests, each exhausted by its own `messageId`
X; deliver a copy under X at count 1 (admitted, route `copy`), a delivery of X at count 2
(dead-lettered `exhausted`, unchanged), a copy under a new id (admitted, route `copy`).

### Tests for User Story 2 (write first, confirm red, then implement) ⚠️

- [ ] T024 [P] [US2] `DlqResubmissionReplayIT` (Testcontainers Service Bus emulator): the three-case
      matrix above, plus a copy carrying `origin=SUPPORT` under X at count 1 → route `support`; a
      dead-lettered copy of a service-replay message (keeps `origin=REPLAY`) → route `service`; a
      producer republish (same X, count 1) → route `copy`, no `SENT`/`NOT_APPLICABLE` leg re-sent; a
      `COMPLETED` request meeting any copy → no-op; a `FAILED(rolled-back)` request (NULL
      `exhausted_message_id`) admitted at any count (FR-004-001, FR-004-002, FR-004-005, FR-004-006,
      FR-004-048, FR-004-050; DV-26) — `src/test/java/.../inbound/DlqResubmissionReplayIT.java`

### Implementation for User Story 2

- [ ] T025 [US2] Implement route derivation at admission (`service`/`support`/`copy` from
      `origin`/absence) as an audit-only label: append to `audit_note`, log field, increment
      `probation_replays_total{route}` — no eligibility/settlement/sending rule reads `origin`
      itself (FR-004-005) — `application/` (alongside T011's predicate class)
- [ ] T026 [US2] Confirm (and extend if needed) the inherited redelivery/final-delivery
      `exhausted`/`collision`/`validation` dead-letter paths still hold under T011's replaced
      predicate (FR-004-001's parenthetical: "every delivery of a `FAILED` request is judged by this
      predicate from this slice on") — `inbound/`
- [ ] T027 [US2] Seed the `doc/DEVIATIONS.md` DV-26 row (create the file if slice 002 has not yet;
      constitution Principle I) naming `DlqResubmissionReplayIT` as its pinning test (FR-004-050) —
      `doc/DEVIATIONS.md`

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

- [ ] T028 [P] [US7] `IntakePauseResumeTest`: pause stops delivery and sets the gauge; pausing a
      paused intake (or resuming a running one) is a no-op; a store outage and its recovery do not
      lift an operator pause (FR-004-051, FR-004-052) —
      `src/test/java/.../application/IntakePauseResumeTest.java`
- [ ] T029 [P] [US7] `PurgeQueueActionTest`: refused `intake-not-paused` while running, removing
      nothing; while paused, removes every message enqueued before the run's start instant, leaves
      the DLQ untouched, reports count + cut-off (FR-004-053) —
      `src/test/java/.../application/PurgeQueueActionTest.java`
- [ ] T030 [P] [US7] `RollbackFencingIT` — the close-in-flight case (FR-004-049a): over a seeded
      `RECEIVED`+`PENDING` row, a `RETRYING`+`PARKED` row, a `FAILED(leg-exhausted)`+`PENDING`-resend
      row and a `COMPLETED`+`PENDING`-resend row, the statement closes each leg row
      `FAILED(rolled-back)` with the correct `note`/`prev=` form, changes only `status`/`reason`/
      `note`/`updated_at` (+ a fresh `claim_token` on the resend row), and maps each request
      correctly (`RECEIVED`/`RETRYING` → `FAILED(rolled-back)`, `FAILED` keeps its reason/id,
      `COMPLETED` untouched); a late runner's write after the close changes nothing and counts as a
      stale-runner rejection — **(provisional runner ownership, FR-004-049)** —
      `src/test/java/.../persistence/RollbackFencingIT.java` (this file also carries US1's and US3's
      late-runner cases, T015/T037, assembled together once this task lands)
- [ ] T031 [P] [US7] `ResendConcurrencyIT` (US7 half): resend-leg refuses a whole run submitted while
      paused (`intake-paused`, nothing read/changed); a pause beginning mid-run claims no further ID
      and reports the rest `not-run` (FR-004-021) —
      `src/test/java/.../application/ResendConcurrencyIT.java` (shared file with US3's T038; this
      task writes the pause-related cases first since they need no resend machinery beyond the pause
      read)

### Implementation for User Story 7

- [ ] T032 [US7] Implement `application/IntakePauseAction.java` / the resume counterpart: write
      `intake_control.paused_by_operator` (+ `paused_at`) first, then the in-memory flag and gauge,
      inside the existing JDBC transaction template; `no-op` when already in the requested state
      (FR-004-051) — **(provisional, FR-004-052)**
- [ ] T033 [US7] Implement `application/PurgeQueueAction.java`: refuse `intake-not-paused` unless
      paused; otherwise remove messages on `probation.requests` enqueued before the run's start
      instant via the Service Bus admin/management client, leave the DLQ untouched, report count +
      cut-off, log one structured line (FR-004-053)
- [ ] T034 [US7] Implement `application/RollbackCloseInFlightAction.java`: one transaction per
      `PENDING`/`PARKED` row exactly as FR-004-049a states — **(provisional, FR-004-049)**
- [ ] T035 [US7] [P] Implement `cli/PauseIntakeRunner.java`, `cli/ResumeIntakeRunner.java`,
      `cli/PurgeQueueRunner.java`, `cli/RollbackCloseInFlightRunner.java` per
      `contracts/cli-runners.md` §3, §4, §6 — each refuses per its own guard before any store read
- [ ] T036 [US7] Wire `LegResender`'s and `PurgeQueueAction`'s pause checks to the reconciled
      in-memory flag from T010 (read-before-claim for resend-leg, FR-004-021; read-before-remove for
      purge-queue) — `application/LegResender.java` stub wiring only; full `LegResender` lands in
      Phase 6
- [ ] T037 Run `quickstart.md` step 6 (pause/purge/resume) and the close-in-flight smoke test locally

**Checkpoint**: intake pause is durable and testable in isolation from resend-leg's full
implementation; purge-queue and the rollback close statement are usable by 006's runbook inputs.

---

## Phase 6: User Story 3 - Resend the HMPPS leg for an explicit list of requests (Priority: P2)

**Goal**: an operator-supplied list of `requestId`s is each checked, claimed and run on its own; a
resend never parks; recovery completes a `FAILED` request on a terminal class-1/`SENT`/
`NOT_APPLICABLE` outcome.

**Independent Test**: two `FAILED` latest rows, one `SENT`, one under a live claim, one unknown ID;
run resend-leg with the gateway stub answering 200; observe two new attempt rows, three refusals by
code, five report entries, refusal counters incremented.

### Tests for User Story 3 (write first, confirm red, then implement) ⚠️

- [ ] T038 [P] [US3] `ResendByRequestIdsIT`: the mixed-list scenario above; duplicates collapsed and
      run once; empty list → `missing-argument`; over-cap distinct IDs → `too-many-request-ids`
      before any read; per-ID check order `unknown-request` → `request-claimed` →
      `resend-in-progress` → `not-eligible` (FR-004-022); eligibility table incl. attempt-1
      `FAILED(rolled-back)` = `not-eligible`, attempt ≥ 2 `FAILED(rolled-back)` = eligible
      (FR-004-023); new attempt row committed (claim + `resend_run_id` + `operator`) before any POST
      (FR-004-025); the resend halves of DV-07 (Redis failure → query API → `FAILED(payload-
      unavailable)`, never `PARKED`) and DV-17 (every resend's query-API read carries the system
      user as `CJSCPPUID`) (FR-004-050a) — `src/test/java/.../application/ResendByRequestIdsIT.java`
- [ ] T039 [P] [US3] `ResendSharedTimeCheckIT`: the `sharedTime` check runs directly after the fetch,
      before any leg stage, comparing as instants at stored precision; a cache-vs-query rendering
      difference still proceeds; a later/absent/unparseable `sharedTime` →
      `FAILED(payload-changed)` (no `youth_switch`/`body_digest`, nothing sent) unless
      `allow-changed-payload` (then `override=true`+note form) (FR-004-029–031) —
      `src/test/java/.../application/ResendSharedTimeCheckIT.java`
- [ ] T040 [P] [US3] `RequestRecoveryTransitionTest` (US3 half, extends T015's file): a resend that
      ends `SENT`/`NOT_APPLICABLE`/class-1-`FAILED` recovers a `FAILED` request to
      `COMPLETED(recovered)` in the outcome's transaction, clearing both columns and appending to
      `audit_note`; a class-2 `FAILED` outcome leaves the request untouched; no statement moves a
      `COMPLETED` request (FR-004-032–034) — extends
      `src/test/java/.../application/RequestRecoveryTransitionTest.java`
- [ ] T041 [P] [US3] `ResendConcurrencyIT` (US3 half, extends T031's file): two resend runs and a
      late queue delivery for one leg, or a replay and a resend racing in either order — exactly one
      runs the leg and exactly one POST results (FR-004-035); a late resend-runner completion by its
      rotated claim token changes nothing and counts as a stale-runner rejection — RollbackFencingIT's
      "late resend runner" case, pinned here (FR-004-028) — extends
      `src/test/java/.../application/ResendConcurrencyIT.java`
- [ ] T042 [US3] `PropertiesValidatorTest`/eligibility unit test for the provisional FR-004-024
      resolution: an expired `PENDING` resend row is refused `resend-in-progress`, never reclaimed —
      **(provisional)** — add to `ResendByRequestIdsIT` (T038) as its own case, not a new file

### Implementation for User Story 3

- [ ] T043 [US3] Implement `application/LegResender.java`: input validation per FR-004-020/
      FR-004-019 (collapse duplicates, cap check, whole-run refusals before any read), the per-ID
      check order of T038 (`unknown-request` → `request-claimed` → `resend-in-progress` →
      `not-eligible`), sequential execution per run (research.md Decision 4). **For each eligible
      ID, in the transaction that checked it: INSERT the new attempt row — `max(attempt)+1`,
      `attempt_origin=resend`, `PENDING`, its own leg claim (`claim_owner`, fresh `claim_token`,
      `claim_expires_at`), `resend_run_id`, `operator` — and COMMIT that transaction before issuing
      any POST** (FR-004-025) — satisfies the claim-before-POST half of T038
- [ ] T044 [US3] Implement the `sharedTime` comparison stage (instant parsing, honouring offset/zone,
      stored-precision comparison) inside `LegResender`'s run, before the switch read (FR-004-029) —
      satisfies T039
- [ ] T045 [US3] Implement the resend run's stage sequence (fetch → `sharedTime` check → switch →
      redaction → body digest → send), reusing slice 002's `HearingPayloadSource`,
      `YouthFilterSource`, `HmppsRedaction`, `HmppsSender` unchanged, with the resend-only rules: no
      park (`FAILED(payload-unavailable)`/`FAILED(gateway-transient)` at once), always the system
      user as `CJSCPPUID` (FR-004-026, FR-004-027, FR-004-050a)
- [ ] T046 [US3] Implement the recovery transition inside the attempt's completion transaction
      (FR-004-032–034), reusing the request-row-lock discipline from T017 — satisfies T040. **The
      completion UPDATE itself is `WHERE id = ? AND claim_token = ?`: zero rows updated (the token
      was rotated by the rollback protocol, or another runner already completed the row) means
      nothing changes and the run counts one `probation_stale_runner_rejections_total`, without
      attempting recovery** (FR-004-028) — satisfies the claim-token-guard half of T041
- [ ] T047 [US3] [P] Implement `cli/ResendLegRunner.java` per `contracts/cli-runners.md` §2:
      argument parsing/validation, calls `LegResender`, prints the per-ID report + summary, one
      structured log line, `probation_resend_refusals_total{reason}` per refused ID (FR-004-039)
- [ ] T048 [US3] [P] `OutcomeMetricsTest` (US3 half, extends T022's file): resend outcomes carry
      `origin=resend`, `forced=false`; log lines carry status/reason/HTTP status/body digest/
      `runOrigin`/`youth_switch` (FR-004-011, FR-004-040) — extends
      `src/test/java/.../application/OutcomeMetricsTest.java`
- [ ] T049 Seed the `doc/DEVIATIONS.md` DV-07 and DV-17 rows' resend-half cross-reference, naming
      `ResendByRequestIdsIT` beside slice 002's queue-run tests (FR-004-050a) — `doc/DEVIATIONS.md`
- [ ] T050 Run `quickstart.md` steps 3–4 (resend, forced-resend precondition) locally

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
- [ ] T076 [P] Re-read `research.md`'s three provisional decisions against anything the owning team
      (H31) has since confirmed; if any decision is overturned, apply the isolated change
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
