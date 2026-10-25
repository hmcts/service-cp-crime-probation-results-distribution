---
description: "Task list for 005-operations-api: replay and resend-leg over HTTP"
---

# Tasks: Operations API — replay and resend-leg over HTTP

**Input**: Design documents from `/specs/005-operations-api/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/operations-api.md,
quickstart.md

**Tests**: MANDATORY (constitution Principle II). Every test task is ordered before the
implementation task it guards; its commit records the failing assertion (not a compile error)
before the paired implementation task's commit records the green run.

**Hard dependency, stated plainly**: this slice's controllers call slice 004's `ReplayAction` and
`LegResender` directly (plan.md, "no new port"). Neither class exists in code yet — 004 has only
its `cli` package skeleton and two vocabulary enums implemented. **Tasks marked
`[BLOCKED-ON-004]` cannot be completed for real until those two classes exist** on this branch.
Per the lesson already learned once this session (004's own `ReplayRoute`/`ResendRefusalReason`
duplicating classes that turned out to already exist on this branch): **do not stub, fake or
pre-define `ReplayAction`/`LegResender` here.** Where a task needs them to compile, write the test
against the method shape `research.md` Decision 5 commits to, mark it blocked, and move on; do not
invent the real classes under this slice's ticket.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US7, mapped to spec.md's seven user stories
- File paths are under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/` and
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/` unless stated otherwise

---

## Phase 1: Setup

**Purpose**: the request/response shapes and the skeleton every story's controller needs — no
business logic, no dependency on 004.

- [ ] T001 [P] Create `api/dto/ReplayResponse.java` (record: `requestId`, `outcome`, `messageId`)
      per data-model.md
- [ ] T002 [P] Create `api/dto/ResendLegRequest.java` (record: `requestIds`, `leg`, `operator`,
      `reason`, `allowTerminalSuccess`, `allowChangedPayload`, the last two defaulting `false`) per
      data-model.md
- [ ] T003 [P] Create `api/dto/ResendLegAcceptedResponse.java` (record: `resendRunId` only — no
      per-ID detail, research.md Decision 4)
- [ ] T004 [P] Add `src/main/resources/acl/operations-api.drl`: two rules, one per action
      (`probation-results-distribution-operations.replay`,
      `probation-results-distribution-operations.resend-leg`), each admitting `Second Line Support`
      and nothing else, no catch-all (research.md Decision 1; FR-005-020)

**Checkpoint**: DTOs and the Drools rules file compile/load; no controller yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the error-handling and audit-publishing infrastructure every endpoint depends on.
**⚠️ CRITICAL**: no user-story phase starts before this one is complete.

- [ ] T005 [P] `OperationsProblemDetailHandlerTest`: for each closed `reason` in FR-005-029, the
      status family it maps to (FR-005-030), that a `400` about one argument names the argument and
      never its value, that no exception message/stack trace/far-end text reaches the body
      (FR-005-031), and that an unmapped path/method answers without a stack trace or a path echo
      (FR-005-034) — `src/test/java/.../api/OperationsProblemDetailHandlerTest.java`
- [ ] T006 Implement `api/OperationsProblemDetailHandler.java` (`@RestControllerAdvice`): maps every
      refusal/failure type to an RFC 9457 `ProblemDetail` with the closed `reason` set and status
      families of FR-005-029/030; satisfies T005
- [ ] T007 [P] `OperationsRequestMapperTest`: a non-UUID `requestId` or `requestIds` entry →
      `unreadable-argument` naming the right field; `leg` absent → `missing-argument`, not `HMPPS` →
      `unreadable-argument`; `operator`/`reason` absent, blank, or > 200 chars →
      `missing-argument`/`argument-too-long` naming the argument, refused not truncated
      (FR-005-010, FR-005-011, FR-005-016; Assumption 4); an unknown JSON field → `400
      unknown-field`; replay's body — none or `{}` accepted, any field refused `unknown-field`
      (FR-005-033) — `src/test/java/.../api/OperationsRequestMapperTest.java`
- [ ] T008 Implement `api/OperationsRequestMapper.java`: typed parsing per T007, refusing whole
      before any downstream call (FR-005-003, FR-005-033)
- [ ] T009 [P] `OperationsAuditPublisherTest`: a request-event publish failure is surfaced as a
      checked outcome before any downstream call; a response-event publish failure is caught,
      logged ERROR, and does not change the call's already-decided outcome (FR-005-027) —
      `src/test/java/.../api/OperationsAuditPublisherTest.java`
- [ ] T010 Implement `api/OperationsAuditPublisher.java`: wraps `cp-audit-filter-springboot`'s
      publish call directly rather than relying on its default (swallowing) publisher
      (research.md Decision 3); satisfies T009
- [ ] T011 [P] Wire `MetricRegistry.operationsFailure(OperationsAction, OperationsFailure)` calls
      into `OperationsProblemDetailHandler` (every `5xx`) and `OperationsAuditPublisher` (an
      unpublished response event) — FR-005-043; the series and both enums already exist, this task
      only adds the call sites

**Checkpoint**: error mapping, request parsing, and the audit publisher are unit-tested in
isolation. No controller yet.

---

## Phase 3: User Story 4 - Only admitted callers reach either action, and every call is audited (Priority: P1)

**Goal**: every `/operations/**` request is authorised against `Second Line Support` before any
application service is reached, and every admitted call produces a before/after audit event pair.

**Independent Test**: for each endpoint, call with no identity, an identity outside the group, and
one inside, through the real authorisation filter with users-groups stubbed; assert refusals, the
service call (or its absence), and the two audit events.

**Note**: this story's own endpoints don't exist until US1/US2 land; its tests here exercise the
authorisation/audit *filter chain* against a minimal mapped test route, and are re-run unchanged
once the real controllers exist in US1/US2.

### Tests for User Story 4 (write first, confirm red, then implement) ⚠️

- [ ] T012 [P] [US4] `OperationsAuthorisationIT` (real `cp-auth-rules-filter` wired, users-groups
      stubbed via WireMock): no/blank `CJSCPPUID` → `401`; caller outside `Second Line Support` →
      `403`; users-groups unreachable → `403` (fail closed); a caller admitted to `replay` who sends
      an action header naming `resend-leg` is still authorised by path+method, never the header
      (FR-005-018, FR-005-019, FR-005-021, FR-005-022) — **confirms research.md Decision 2** (how
      the filter derives the action from the OpenAPI document) against the real library; if it
      needs different wiring than assumed, fix `config/` only, not this test's assertions —
      `src/test/java/.../api/OperationsAuthorisationIT.java`
- [ ] T013 [P] [US4] `OperationsAuditIT` (real `cp-audit-filter-springboot` wired, Artemis
      stubbed/embedded): an admitted call produces exactly two audit events, before and after,
      naming the caller/action/outcome, with no request/response body (FR-005-025, FR-005-026); a
      request-event publish failure refuses the call `503 audit-unavailable` with nothing read or
      changed (FR-005-027) — `src/test/java/.../api/OperationsAuditIT.java`
- [ ] T014 [P] [US4] `OperationsConfigDefaultsTest`: the authorisation and audit switches default
      on; `deny-when-no-rules: true` holds; a pod with the audit filter on and its transport off
      logs one WARN naming both settings at startup and does not refuse to start (FR-005-028) —
      `src/test/java/.../config/OperationsConfigDefaultsTest.java`

### Implementation for User Story 4

- [ ] T015 [US4] Confirm (or add, if missing) the `authz.http`/`audit.http`/`cp.audit` wiring in
      `application.yaml` matches T012–T014's expectations — the scaffold already has this block;
      this task is a verification pass, not new authoring, per plan.md
- [ ] T016 [US4] Add the startup WARN for "audit filter on, transport off" if not already present
      (FR-005-028) — `config/StartupValidationConfig.java` or `config/StartupValidator.java`
      (extend, do not duplicate)

**Checkpoint**: the authorisation/audit filter chain is proven correct in isolation, ready for US1
and US2's controllers to sit behind it.

---

## Phase 4: User Story 1 - Replay a `FAILED` request over HTTP (Priority: P1) 🎯 MVP

**Goal**: `POST /operations/requests/{requestId}/replay` calls slice 004's replay action and
renders its result.

**Independent Test**: with `ReplayAction` mocked at the service boundary, call the endpoint once
per result it can give and assert the status/outcome/no-echo; then, against the store and broker
emulator, replay one `FAILED` request and assert one command enqueued, byte-identical, under a
fresh `messageId` with `origin = REPLAY`.

### Tests for User Story 1 (write first, confirm red, then implement) ⚠️ `[BLOCKED-ON-004]`

- [ ] T017 [P] [US1] `ReplayControllerTest` (`@WebMvcTest`, `ReplayAction` mocked): enqueued →
      `200 {requestId, outcome: enqueued, messageId}`; `COMPLETED` → `200 {outcome: no-op}`;
      `PENDING` resend row → `409 resend-in-progress`; no request → `404 unknown-request`;
      `RECEIVED`/`RETRYING` → `409 not-eligible`; store down → `503 store-unavailable`; broker can't
      take the enqueue → `503 broker-unavailable`; available while intake is paused, no refusal of
      this endpoint's own (FR-005-006–008) — `[BLOCKED-ON-004]`, since it mocks `ReplayAction`'s
      real shape — `src/test/java/.../api/ReplayControllerTest.java`
- [ ] T018 [P] [US1] `OperationsResponseMapperTest` (replay half): `ReplayAction`'s result →
      `ReplayResponse` per T017's cases, with the `requestId` always the service's own canonical
      parse (FR-005-031) — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/OperationsResponseMapperTest.java`

### Implementation for User Story 1 `[BLOCKED-ON-004]`

- [ ] T019 [US1] Implement `api/OperationsResponseMapper.java` (replay half): maps `ReplayAction`'s
      result to `ReplayResponse` — satisfies T018 — **cannot compile until `application.ReplayAction`
      exists**
- [ ] T020 [US1] Implement `api/ReplayController.java`: `POST
      /operations/requests/{requestId}/replay`, no body or `{}` only (FR-005-009, FR-005-033), calls
      `ReplayAction` via `OperationsRequestMapper`/`OperationsResponseMapper`, wrapped by
      `OperationsAuditPublisher` (request event before the call, response event after) — satisfies
      T017 — **cannot compile until `application.ReplayAction` exists**
- [ ] T021 Run `quickstart.md`'s replay steps once `application.ReplayAction` lands on this branch

**Checkpoint**: replay over HTTP is implementable the moment 004's `ReplayAction` exists; everything
up to that point (DTOs, error handling, auth/audit, the test shapes) is already in place.

---

## Phase 5: User Story 2 - Resend the HMPPS leg for an explicit list of requests (Priority: P1)

**Goal**: `POST /operations/resend-leg` calls slice 004's resend-leg capability and answers `202`
with the run id.

**Independent Test**: with `LegResender` stubbed, assert the call reaches it with the list as sent
and every other input unchanged, and every whole-call refusal renders; end to end, send the design's
mixed list (two eligible `FAILED`, one unknown, one `SENT` without the override, one under a live
claim) and assert the run accepted with a `resendRunId`, then read 004's report and assert the five
outcomes.

### Tests for User Story 2 (write first, confirm red, then implement) ⚠️ `[BLOCKED-ON-004]`

- [ ] T022 [P] [US2] `ResendLegControllerTest` (`@WebMvcTest`, `LegResender` mocked): the list
      passed to `LegResender` exactly as sent — no collapse, no cap, no reorder (FR-005-011,
      FR-005-013); `202 {resendRunId}` on acceptance, immediately, not waiting for any ID
      (FR-005-015); whole-call refusals render 004's own codes
      (`missing-argument`/`argument-too-long`/`too-many-request-ids`/`unreadable-argument`/
      `intake-paused`/`store-unavailable`) per contracts/operations-api.md §2 — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/ResendLegControllerTest.java`
- [ ] T023 [P] [US2] `OperationsResponseMapperTest` (resend-leg half): `LegResender`'s accepted-run
      result → `ResendLegAcceptedResponse` carrying only `resendRunId` (research.md Decision 4) —
      `[BLOCKED-ON-004]` — extends `src/test/java/.../api/OperationsResponseMapperTest.java`
- [ ] T024 [P] [US2] `OperationsRequestMapperTest` (resend-leg list half): `allowChangedPayload`
      passed through unchanged, `false` when absent (FR-005-010) — `[BLOCKED-ON-004]` for the
      end-to-end case, but the parsing-only assertions can run against T008 today — extends
      `src/test/java/.../api/OperationsRequestMapperTest.java`

### Implementation for User Story 2 `[BLOCKED-ON-004]`

- [ ] T025 [US2] Implement `api/OperationsResponseMapper.java` (resend-leg half): maps
      `LegResender`'s accepted-run result to `ResendLegAcceptedResponse` — satisfies T023 —
      **cannot compile until `application.LegResender` exists**
- [ ] T026 [US2] Implement `api/ResendLegController.java`: `POST /operations/resend-leg`, parses via
      `OperationsRequestMapper`, calls `LegResender`, answers `202` via
      `OperationsResponseMapper`, wrapped by `OperationsAuditPublisher` — satisfies T022 — **cannot
      compile until `application.LegResender` exists**
- [ ] T027 Run `quickstart.md`'s resend-leg steps once `application.LegResender` lands

**Checkpoint**: resend-leg over HTTP is implementable the moment 004's `LegResender` exists.

---

## Phase 6: User Story 3 - Force a resend of a hearing already sent, on the record (Priority: P1)

**Goal**: `allowTerminalSuccess: true` reaches `LegResender` unchanged and every audit/record trail
says it was requested.

**Independent Test**: with `LegResender` stubbed, send a list with the override and assert it
reaches the capability as `true` with operator/reason unchanged, and both audit events say so; end
to end, send a request whose latest row is `SENT` with the override, then without it, and assert a
new attempt then `not-eligible`.

### Tests for User Story 3 (write first, confirm red, then implement) ⚠️ `[BLOCKED-ON-004]`

- [ ] T028 [P] [US3] `ForcedResendControllerIT`: `allowTerminalSuccess: true` reaches `LegResender`
      unchanged with operator/reason; both audit events say the override was requested
      (FR-005-017); without the override the same ID renders `not-eligible` and the call still
      succeeds (`202`); a reason or operator over 200 characters is `400 argument-too-long`
      (FR-005-016); neither operator nor reason appears in the answer, a log line, or a metric label
      (FR-005-023, FR-005-031); a forced resend whose send later fails is still a `202`-accepted
      call — the failure surfaces only in 004's report (FR-005-014) — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/ForcedResendControllerIT.java`

### Implementation for User Story 3 `[BLOCKED-ON-004]`

- [ ] T029 [US3] Confirm `ResendLegController`/`OperationsAuditPublisher` (T026, T010) already pass
      `allowTerminalSuccess` through unchanged and surface it on both audit events without a new
      code path — satisfies T028; if a gap is found, fix `OperationsAuditPublisher`'s event-field
      mapping only

**Checkpoint**: Stories 1–4 together give the whole P1 slice — replay, resend-leg, the forced
override, and the authorisation/audit chain all sitting on the same, already-tested foundation.

---

## Phase 7: User Story 5 - Every refusal is bounded and quotes nothing the caller sent (Priority: P2)

**Goal**: a runbook step can always tell "declined, nothing changed" from "tried and could not,"
and nothing an operator types ever comes back.

**Independent Test**: drive each endpoint into each refusal and assert status, bounded reason,
argument name where applicable, and the absence of every supplied value from the answer and the
log.

### Tests for User Story 5

- [ ] T030 [P] [US5] `OperationsRefusalSweepTest` (extends `OperationsProblemDetailHandlerTest`,
      T005, with both real endpoints once built — `[BLOCKED-ON-004]` for the endpoint-level cases,
      but the problem-detail shape itself is already covered by T005): every refusal is RFC 9457
      with a closed `reason`, no exception message, no stack trace; a successful call's `requestId`
      is always the canonical parse, never the raw characters sent (FR-005-029, FR-005-031) —
      `src/test/java/.../api/OperationsRefusalSweepTest.java`
- [ ] T031 [P] [US5] `TelemetryPrivacyTest`/`LogStatementSweepTest` extension: sweep every new log
      line and audit event this slice adds for the forbidden content set (operator, reason, an
      unreadable identifier, `CJSCPPUID`) — `src/test/java/.../support/LogStatementSweepTest.java`
      (extend, do not duplicate)

### Implementation for User Story 5

- [ ] T032 [US5] Close any gap T030/T031 find — expected to be none, since T006/T008 already
      implement the bounded-refusal and no-echo rules; this phase is a verification pass

**Checkpoint**: the no-echo, bounded-refusal guarantee is proven across both endpoints together.

---

## Phase 8: User Story 6 - The API is described before it is built, and agrees with its description (Priority: P2)

**Goal**: `probation-results-distribution-openapi.yaml` describes exactly the two operations, and a
contract test proves it both ways.

**Independent Test**: a contract test compares served operations/statuses/reasons/closed value sets
with the document, both ways.

### Tests for User Story 6

- [ ] T033 [P] [US6] `OperationsContractTest`: every operation and every `reason` the code can
      answer is in the document; everything the document describes is served; each closed value set
      in the document (`leg`, per-ID status/reason/refusal code, replay outcome) equals the
      service's own vocabulary value for value (FR-005-035) — `[BLOCKED-ON-004]` for the
      served-operations half, since the controllers don't exist until Phases 4–5 land —
      `src/test/java/.../api/OperationsContractTest.java`

### Implementation for User Story 6

- [ ] T034 [US6] Fill in `src/main/resources/probation-results-distribution-openapi.yaml`: the two
      operations, each request/success shape/status/bounded `reason`, `operationId` set to the
      action strings of FR-005-019 (research.md Decision 2), every closed value set mirrored from
      the service's vocabularies, version raised above `0.0.0` (FR-005-035)
- [ ] T035 [US6] Update `doc/API_CONTRACTS.md` §3 with the two endpoints, their refusals, the five
      gates and the runner fallback (FR-005-036) — in the same commit as T034

**Checkpoint**: the OpenAPI document and the served API are provably in agreement.

---

## Phase 9: User Story 7 - The API and its command-line fallback give the same answer (Priority: P3)

**Goal**: the same mixed list through the API and through 004's runner gives the same report, ID
for ID.

**Independent Test**: run the same mixed list through the runner and through the API, each against
an identically seeded store, and compare the reports ID for ID.

### Tests for User Story 7 `[BLOCKED-ON-004]`

- [ ] T036 [P] [US7] `OperationsRunnerParityIT`: the design's mixed list through
      `cli/ResendLegRunner` (004) and through `POST /operations/resend-leg` (005), each against an
      identically seeded store, give the same whole-call refusal or the same outcome/refusal code
      per ID (FR-005-005); a replay refused through the API is refused with the same code through
      the runner in the same state — needs **both** 004's `cli` runners (beyond today's dispatcher
      skeleton) and 005's controllers implemented — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/OperationsRunnerParityIT.java`

### Implementation for User Story 7

- [ ] T037 [US7] Confirm the replay and resend-leg command-line runners remain present and
      documented as the fallback once this slice is built (FR-005-005) — a verification task, no
      new code

**Checkpoint**: all seven user stories are independently functional once 004 lands for real.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [ ] T038 [P] `OperationsConfigPropertiesTest`: this slice adds 0 properties to the closed
      `distribution.*` catalogue and makes 0 App Configuration or Results-switch reads
      (SC-005-011) — `src/test/java/.../config/OperationsConfigPropertiesTest.java`
- [ ] T039 [P] Run the full `TelemetryPrivacyTest`/`LogStatementSweepTest` suite across every new
      class this slice adds — zero findings (FR-005-023, FR-005-031, FR-005-032; SC-005-004)
- [ ] T040 Run `./gradlew build` (PMD, Checkstyle, JaCoCo gate) and confirm the coverage thresholds
      are unchanged (SC-005-010)
- [ ] T041 Run every `quickstart.md` step end to end once 004 is fully implemented
- [ ] T042 [P] Confirm research.md Decision 2 (action derivation via `cp-auth-rules-filter`) against
      the real library in T012; if the assumption was wrong, apply the isolated `config/` fix the
      decision names and re-run T012 only
- [ ] T043 Update `CLAUDE.md`'s Setup section once this slice is fully implemented (not just
      planned), and hand the Dependencies → "006-production-readiness" section to whoever starts
      that slice next

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — pure DTOs and the Drools rules file
- **Foundational (Phase 2)**: depends on Setup — BLOCKS every user story (error handling, request
  parsing, the audit publisher)
- **US4 (Phase 3)**: depends on Foundational; proves the authorisation/audit chain in isolation
  before any controller exists
- **US1 (Phase 4)**: depends on Foundational and US4 (its controller sits behind the proven
  authz/audit chain); **blocked on `application.ReplayAction` existing** (004)
- **US2 (Phase 5)**: depends on Foundational and US4; **blocked on `application.LegResender`
  existing** (004); independent of US1's controller
- **US3 (Phase 6)**: depends on US2's controller and mapper (T025, T026) — it is the override path
  through the same endpoint, not a new one
- **US5 (Phase 7)**: depends on US1–US4 existing to sweep across real endpoints, though its
  foundational half (T005/T008) is already provable without them
- **US6 (Phase 8)**: the OpenAPI authoring (T034) can happen any time after Setup; the contract
  test's served-operations half needs US1/US2's controllers
- **US7 (Phase 9)**: depends on US1–US3 **and** on 004's `cli` runners being implemented beyond
  today's dispatcher skeleton (004 tasks.md T047, T021 in that slice's numbering)
- **Polish (Phase 10)**: depends on every story to be included in that release being complete

### Parallel Opportunities

- T001–T004 in parallel (Setup)
- T005, T007, T009 in parallel (Foundational tests, different files)
- T012–T014 in parallel (US4 tests)
- Once `application.ReplayAction`/`LegResender` exist: T017/T018 (US1) and T022–T024 (US2) can
  proceed in parallel — different files, independent endpoints
- T034 (the OpenAPI document) can be authored in parallel with US1–US3's implementation, since it
  does not depend on the controllers existing, only on the shapes this plan already fixes

---

## Implementation Strategy

### MVP First

1. Setup → Foundational → US4 (authorisation/audit proven) → US1 (replay), **the moment
   `application.ReplayAction` exists on this branch**. Stop and validate against US1's Independent
   Test. This alone gives operators the HTTP route to the general-use recovery action.

### Incremental Delivery

1. Setup + Foundational + US4 → ready, independent of 004's progress.
2. The instant 004's `ReplayAction` lands: US1 (MVP).
3. The instant 004's `LegResender` lands: US2 → US3 (same endpoint, the override).
4. US5 and US6 can be finished alongside US1–US3, since their foundational halves need no
   endpoint.
5. US7 last, once 004's `cli` runners are implemented for real, not just their dispatcher skeleton.

Nothing in Phases 1–3 (Setup, Foundational, US4) needs to wait on 004 at all — they are the biggest
single chunk of this slice's work that can start today.
