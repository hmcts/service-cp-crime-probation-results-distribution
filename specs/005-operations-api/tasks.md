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

**Hard dependency, status as of this pass**: this slice's controllers call slice 004's
`ReplayAction` and `LegResender` directly (plan.md, "no new port").
**`application.ReplayAction` now exists** on this branch — `public ReplayOutcome
replay(UUID requestId)`, exactly the shape `research.md` Decision 5 assumed — so **US1's tasks are
no longer blocked on a nonexistent class** and are unmarked below. **`application.LegResender`
still does not exist**; US2, US3 and US7's tasks remain `[BLOCKED-ON-004]`. One gap remains even
for US1: `ReplayAction` has no production `ReplayEnqueuer` wired in Spring yet (004's own
`cli/ReplayRunner` has the same gap), so a *manual, end-to-end* run (T023) still can't happen until
that adapter lands — the `@WebMvcTest` controller tests (which mock `ReplayAction` at the boundary)
are unaffected by that gap and can be written and run for real now.
Per the lesson already learned once this session (004's own `ReplayRoute`/`ResendRefusalReason`
duplicating classes that turned out to already exist on this branch): **do not stub, fake or
pre-define `LegResender` here.** Where a task needs it to compile, write the test against the
method shape `research.md` Decision 5 commits to, mark it blocked, and move on; do not invent the
real class under this slice's ticket.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US7, mapped to spec.md's seven user stories
- File paths are under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/` and
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/` unless stated otherwise

---

## Phase 1: Setup

**Purpose**: the request/response shapes and the skeleton every story's controller needs — no
business logic, no dependency on 004.

- [X] T001 [P] Create `api/dto/ReplayResponse.java` (record: `requestId`, `outcome`, `messageId`)
      per data-model.md
- [X] T002 [P] Create `api/dto/ResendLegRequest.java` (record: `requestIds`, `leg`, `operator`,
      `reason`, `allowTerminalSuccess`, `allowChangedPayload`, the last two defaulting `false`) per
      data-model.md
- [X] T003 [P] Create `api/dto/ResendLegAcceptedResponse.java` (record: `resendRunId` only — no
      per-ID detail, research.md Decision 4)
- [X] T004 [P] Add `src/main/resources/acl/operations-api.drl`: two rules, one per action
      (`probation-results-distribution-operations.replay`,
      `probation-results-distribution-operations.resend-leg`), each admitting `Second Line Support`
      and nothing else, no catch-all (research.md Decision 1; FR-005-020)

**Checkpoint**: DTOs and the Drools rules file compile/load; no controller yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the error-handling and audit-publishing infrastructure every endpoint depends on.
**⚠️ CRITICAL**: no user-story phase starts before this one is complete.

- [X] T005 [P] `OperationsProblemDetailHandlerTest`: for each closed `reason` in FR-005-029, the
      status family it maps to (FR-005-030), that a `400` about one argument names the argument and
      never its value, that no exception message/stack trace/far-end text reaches the body
      (FR-005-031), that an unmapped path/method answers without a stack trace or a path echo
      (FR-005-034), **and that every `5xx` answer increments
      `MetricRegistry.operationsFailure(OperationsAction, OperationsFailure)` with the reason
      matching the mapped `OperationsFailure` value (FR-005-043)** —
      `src/test/java/.../api/OperationsProblemDetailHandlerTest.java`
- [X] T006 Implement `api/OperationsProblemDetailHandler.java` (`@RestControllerAdvice`): maps every
      refusal/failure type to an RFC 9457 `ProblemDetail` with the closed `reason` set and status
      families of FR-005-029/030; satisfies T005
- [X] T007 [P] `OperationsRequestMapperTest`: a non-UUID `requestId` or `requestIds` entry →
      `unreadable-argument` naming the right field; `leg` absent → `missing-argument`, not `HMPPS` →
      `unreadable-argument`; `operator`/`reason` absent, blank, or > 200 chars →
      `missing-argument`/`argument-too-long` naming the argument, refused not truncated
      (FR-005-010, FR-005-011, FR-005-016; Assumption 4); an unknown JSON field → `400
      unknown-field`; replay's body — none or `{}` accepted, any field refused `unknown-field`
      (FR-005-033) — `src/test/java/.../api/OperationsRequestMapperTest.java`
- [X] T008 Implement `api/OperationsRequestMapper.java`: typed parsing per T007, refusing whole
      before any downstream call (FR-005-003, FR-005-033)
- [X] T009 [P] `OperationsAuditPublisherTest`: a request-event publish failure is surfaced as a
      checked outcome before any downstream call; a response-event publish failure is caught,
      logged ERROR, and does not change the call's already-decided outcome (FR-005-027), **and
      increments `MetricRegistry.operationsFailure(action, OperationsFailure.
      AUDIT_RESPONSE_UNPUBLISHED)` (FR-005-043)** — `src/test/java/.../api/OperationsAuditPublisherTest.java`
- [X] T010 Implement `api/OperationsAuditPublisher.java`: wraps `cp-audit-filter-springboot`'s
      publish call directly rather than relying on its default (swallowing) publisher
      (research.md Decision 3); satisfies T009's non-metric cases
- [X] T011 Implement the `MetricRegistry.operationsFailure(OperationsAction, OperationsFailure)`
      call sites in `OperationsProblemDetailHandler` (every `5xx`) and `OperationsAuditPublisher`
      (an unpublished response event) — satisfies T005's and T009's metric assertions above
      (FR-005-043); the series and both enums already exist, this task only adds the call sites.
      Depends on T006 and T010 (edits their files) and on T005/T009 carrying the metric
      assertions — no longer `[P]`

**Checkpoint**: error mapping, request parsing, and the audit publisher are unit-tested in
isolation. No controller yet.

**T001–T011 are implemented and green** (`./gradlew test checkstyleMain checkstyleTest pmdMain
pmdTest` all pass for them). Two findings surfaced while landing them, not named by any task above,
now fixed:
- `config.MetricRegistry`'s `@Bean` was defined inside `ServiceBusConsumerConfig`, gated on
  `distribution.consumer.enabled=true`. `OperationsProblemDetailHandler`/`OperationsAuditPublisher`
  need it unconditionally (a pod must serve replay/resend-leg with intake off), so it moved to a new,
  always-on `config/MetricRegistryConfiguration.java`. `ServiceBusConsumerConfigIT`'s
  `ApplicationContextRunner` calls that exercise the real composition root were updated to include
  it alongside `ServiceBusConsumerConfig.class`.
- Spring Boot 4's `JacksonAutoConfiguration` registers a Jackson 3 {@code JsonMapper}
  (`tools.jackson.databind.json.JsonMapper`), not a Jackson 2 `com.fasterxml.jackson.databind.ObjectMapper`
  bean, and nothing else in this service exposed one. `OperationsConfiguration`'s `@Bean` method
  constructs its own `ObjectMapper` directly instead of asking Spring to autowire one.
- `cp-auth-rules-filter` and `cp-audit-filter-springboot`'s own transitive dependencies
  (`spring-jms`, `jakarta.jms-api`) are declared `implementation` by those libraries, so they are
  runtime-only for this project; `build.gradle` now adds both `implementation` directly for
  `OperationsAuditPublisher` to compile against `JmsTemplate`.

**Landing record (this pass)**: T001-T022 were written in one working session before any of it was
committed. Landing them properly meant auditing each task's description against the actual files,
then committing in TDD-ordered, task-scoped commits. Where a test's paired production class could be
replaced by a small, signature-preserving stub without breaking compilation elsewhere (i.e. the class
has no other compile-time dependents at that point in the landing order), the stub was swapped in,
the test re-run to confirm a genuine assertion-level failure (never a compile error), then the real
class restored and the test re-run green — both runs recorded below against real `./gradlew test
--tests <Class>` output. Where a class is compiled into by many other already-written classes (the
composition root: `Application`, `OperationsConfiguration`, `build.gradle`), a signature-preserving
stub was impractical without a wider, riskier edit than this landing pass justified; for those, only
the GREEN run is recorded and the red run is understood to predate this commit (the test was written
and failed for the right reason during the original working session, before the implementation beside
it landed) rather than invented here.

- **T005/T006** (`OperationsProblemDetailHandlerTest` / `OperationsProblemDetailHandler`): RED
  reconstructed by swapping in a stub handler that always answers `400` and never counts a `5xx` —
  `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsProblemDetailHandlerTest"` →
  `24 tests completed, 23 failed`, every failure an `AssertionFailedError`/`AssertionError` on the
  expected status or property, no compile error. Real handler restored →
  `BUILD SUCCESSFUL`, all 24 green.
- **T007/T008** (`OperationsRequestMapperTest` / `OperationsRequestMapper`): RED reconstructed by
  swapping in a stub mapper that never refuses anything and returns a fixed, empty parse —
  `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsRequestMapperTest"` →
  `15 tests completed, 13 failed`, every failure an assertion on the expected refusal/parse, no
  compile error. Real mapper restored → `BUILD SUCCESSFUL`, all 15 green.
- **T009/T010/T011** (`OperationsAuditPublisherTest` / `OperationsAuditPublisher`, plus the metric
  call sites T011 adds here and in `OperationsProblemDetailHandler` above): RED reconstructed by
  swapping in a stub publisher whose two methods are silent no-ops —
  `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsAuditPublisherTest"` →
  `6 tests completed, 4 failed` (the two "transport deliberately off" cases still pass, correctly,
  since a no-op is what they expect), every failure an assertion, no compile error. Real publisher
  restored → `BUILD SUCCESSFUL`, all 6 green. T011's own two call sites
  (`OperationsProblemDetailHandler`'s `metrics.operationsFailure(...)` on every 5xx,
  `OperationsAuditPublisher`'s on an unpublished response event) are pinned by the metric assertions
  already recorded in both tests above; no separate commit is needed for T011.
- **T012/T013** (`OperationsActionResolutionFilterTest` / `OperationsActionResolutionFilter`): RED
  reconstructed by swapping in a stub filter that always passes the request through unwrapped —
  `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.config.OperationsActionResolutionFilterTest"` →
  `6 tests completed, 4 failed` (the two cases that expect no wrapping — outside `/operations/**`,
  an unmapped method on an operations path — correctly still pass), every failure an assertion, no
  compile error. Real filter restored → `BUILD SUCCESSFUL`, all 6 green.
- **T019/T020/T021/T022** (`ReplayControllerTest`, `OperationsResponseMapperTest` /
  `OperationsResponseMapper`, `ReplayController`): the replay half of the response mapper and the
  controller itself are entangled, at compile time, with the composition root
  (`Application`'s `ReplayController` scan exclusion, `OperationsConfiguration`'s conditional bean,
  `build.gradle`'s `spring-jms`/`spring-boot-restclient` additions) landed in the same working
  session, so they are committed together with that root rather than split further.
  - `OperationsResponseMapperTest`/`OperationsResponseMapper`: RED reconstructed by swapping in a
    stub mapper that always renders `no-op` — `./gradlew test --tests
    "uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsResponseMapperTest"` →
    `7 tests completed, 6 failed` (the one genuine `NoOp` case still passes), no compile error. Real
    mapper restored → `BUILD SUCCESSFUL`, all 7 green.
  - `ReplayControllerTest`/`ReplayController`: RED reconstructed by swapping in a stub controller
    that never validates the body, never calls `ReplayAction`, never audits, and always answers
    `no-op` — `./gradlew test --tests
    "uk.gov.hmcts.cp.probationresultsdistribution.api.ReplayControllerTest"` →
    `10 tests completed, 9 failed` (the one genuine `NoOp` case still passes), every failure an
    assertion on the expected status/body, no compile error. Real controller restored →
    `BUILD SUCCESSFUL`, all 10 green.
- **T014** (`OperationsAuthorisationIT`): needs the full composition root (`OperationsConfiguration`,
  the testfixture controllers, the real `cp-auth-rules-filter`/users-groups wiring) to run as a
  `@SpringBootTest`, so it lands in the same commit as that root. RED reconstructed by temporarily
  disabling `OperationsActionResolutionFilter`'s `FilterRegistrationBean`
  (`registration.setEnabled(false)`) — `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsAuthorisationIT"` →
  `7 tests completed, 2 failed`: `admitted_caller_reaches_replay` and
  `admitted_caller_reaches_resend_leg` each failed `expected: 200/202 but was: 403 FORBIDDEN` — a
  genuine assertion failure proving the filter is load-bearing (without it, `cp-auth-rules-filter`'s
  own path/method fallback action name matches no Drools rule and denies by default), no compile
  error. Registration restored → `BUILD SUCCESSFUL`, all 7 green. This also serves as the "one
  assertion" research.md Decision 2 flagged as not yet re-verified against the real filter chain
  (`action_header_cannot_redirect_authorisation`, which already passed throughout since its caller
  is outside `Second Line Support` regardless of the header).
- **T017**: verified, not re-authored — `application.yaml`'s existing `authz.http`/`audit.http`/
  `cp.audit` block already matches T014-T016's expectations (`authz.http.enabled` and
  `audit.http.enabled` default `true`, `deny-when-no-rules: true`, `cp.audit.enabled` default
  `false` so the scaffold's own default is exactly the "filter on, transport off" case T016/T018
  exercise). No change made under this task.
- **T016/T018** (`OperationsConfigDefaultsTest` / the startup WARN in
  `config/StartupValidationConfig.java`): written genuinely test-first in this pass (not
  reconstructed). RED — `./gradlew test --tests
  "uk.gov.hmcts.cp.probationresultsdistribution.config.OperationsConfigDefaultsTest"` →
  `4 tests completed, 1 failed`: `warns_once_and_starts` failed
  `Expected size: 1 but was: 0 in: []` (no WARN logged yet), a genuine assertion failure, no compile
  error; the two `application.yaml` default assertions and the "no warning when the transport is
  also on" case passed immediately since they need no new production code. GREEN, after adding
  `StartupValidationConfig.warnIfAuditFilterOnWithTransportOff(Environment)` (called from the
  existing `distributionConfigValidator` `ApplicationRunner`, extending it rather than adding a
  parallel validator) → `4 tests completed, 0 failed`, confirmed alongside the full
  `YouthSwitchDefaultConfigTest`/`StartupValidationTest` suites (`BUILD SUCCESSFUL`, no regression).
  `checkstyleTest`/`pmdTest` both pass (one `LiteralsFirstInComparisons` finding fixed in the test
  itself before landing).

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

**research.md Decision 2 is now CONFIRMED, not provisional**: `cp-auth-rules-filter` resolves its
Drools `Action` fact's name from — in this priority order — a `vnd.` token on `Content-Type`, then
on `Accept`, then the `CPP-ACTION` header verbatim, and only last a `"{method} {path}"` fallback
(read directly from the library's bytecode; it never reads the OpenAPI document). The first three
are caller-controlled, which is exactly the bypass FR-005-022 forbids. T012/T013 below build the one
new component this finding requires.

### Tests for User Story 4 (write first, confirm red, then implement) ⚠️

- [X] T012 [P] [US4] `OperationsActionResolutionFilterTest`: for `POST
      /operations/requests/{requestId}/replay` and `POST /operations/resend-leg`, the wrapped
      request's `getHeader("CPP-ACTION")` always returns this slice's fixed action string
      (`probation-results-distribution-operations.replay` / `...resend-leg`) regardless of what the
      caller sent for that header, and `getContentType()`/`getHeader("Accept")` never carry a `vnd.`
      token through, even when the caller supplied one; a request outside `/operations/**` is
      returned unwrapped (FR-005-022; research.md Decision 2) —
      `src/test/java/.../config/OperationsActionResolutionFilterTest.java`
- [X] T013 [US4] Implement `config/OperationsActionResolutionFilter.java` (`OncePerRequestFilter`)
      and register it via a `FilterRegistrationBean` ordered before `cp-auth-rules-filter`'s own
      filter, scoped to `/operations/*` — satisfies T012
- [X] T014 [P] [US4] `OperationsAuthorisationIT` (real `cp-auth-rules-filter` wired, users-groups
      stubbed via WireMock): no/blank `CJSCPPUID` → `401`; caller outside `Second Line Support` →
      `403`; users-groups unreachable → `403` (fail closed); a caller admitted to `replay` who sends
      an action header naming `resend-leg` is still authorised by path+method, never the header
      (FR-005-018, FR-005-019, FR-005-021, FR-005-022) — **depends on T013**:
      `OperationsActionResolutionFilter` is what makes this true against the real library, whose own
      precedence otherwise lets the caller's header/media type win (research.md Decision 2) —
      `src/test/java/.../api/OperationsAuthorisationIT.java`
- [ ] T015 [P] [US4] `OperationsAuditIT` (real `cp-audit-filter-springboot` wired, Artemis
      stubbed/embedded): an admitted call produces exactly two audit events, before and after,
      naming the caller/action/outcome, with no request/response body (FR-005-025, FR-005-026); a
      request-event publish failure refuses the call `503 audit-unavailable` with nothing read or
      changed (FR-005-027) — `src/test/java/.../api/OperationsAuditIT.java`
- [X] T016 [P] [US4] `OperationsConfigDefaultsTest`: the authorisation and audit switches default
      on; `deny-when-no-rules: true` holds; a pod with the audit filter on and its transport off
      logs one WARN naming both settings at startup and does not refuse to start (FR-005-028) —
      `src/test/java/.../config/OperationsConfigDefaultsTest.java`

### Implementation for User Story 4

- [X] T017 [US4] Confirm (or add, if missing) the `authz.http`/`audit.http`/`cp.audit` wiring in
      `application.yaml` matches T014–T016's expectations — the scaffold already has this block;
      this task is a verification pass, not new authoring, per plan.md
- [X] T018 [US4] Add the startup WARN for "audit filter on, transport off" if not already present
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

### Tests for User Story 1 (write first, confirm red, then implement) ⚠️

- [X] T019 [P] [US1] `ReplayControllerTest` (`@WebMvcTest`, `ReplayAction` mocked): `Enqueued` →
      `200 {requestId, outcome: enqueued, messageId}`; `NoOp` → `200 {outcome: no-op}`;
      `Refused(RESEND_IN_PROGRESS)` → `409 resend-in-progress`; `Refused(UNKNOWN_REQUEST)` →
      `404 unknown-request`; `Refused(NOT_ELIGIBLE)` → `409 not-eligible`; `StoreUnavailable` →
      `503 store-unavailable`; `BrokerUnavailable` → `503 broker-unavailable`; available while
      intake is paused, no refusal of this endpoint's own (FR-005-006–008) — `ReplayAction` exists
      now (`public ReplayOutcome replay(UUID requestId)`, `application.ReplayOutcome`'s five
      `sealed` cases), so this test can be written and run against the real class, mocked at the
      boundary — `src/test/java/.../api/ReplayControllerTest.java`
- [X] T020 [P] [US1] `OperationsResponseMapperTest` (replay half): `ReplayAction`'s result →
      `ReplayResponse` per T019's cases, with the `requestId` always the service's own canonical
      parse (FR-005-031) — `src/test/java/.../api/OperationsResponseMapperTest.java`

### Implementation for User Story 1

- [X] T021 [US1] Implement `api/OperationsResponseMapper.java` (replay half): maps
      `application.ReplayOutcome`'s five cases (`Enqueued`, `NoOp`, `Refused` with its
      `ResendRefusal`, `StoreUnavailable`, `BrokerUnavailable`) to `ReplayResponse` — satisfies T020
- [X] T022 [US1] Implement `api/ReplayController.java`: `POST
      /operations/requests/{requestId}/replay`, no body or `{}` only (FR-005-009, FR-005-033), calls
      `ReplayAction.replay(UUID)` via `OperationsRequestMapper`/`OperationsResponseMapper`, wrapped
      by `OperationsAuditPublisher` (request event before the call, response event after) —
      satisfies T019
- [ ] T023 Run `quickstart.md`'s replay steps manually. **Still blocked**: `ReplayAction` has no
      production `ReplayEnqueuer` wired in Spring (004's own gap too — its `cli/ReplayRunner` is in
      the same position); a manual end-to-end run needs that adapter first. T019–T022 do not need it
      (they mock `ReplayAction` at the boundary) and can be completed now.

**Checkpoint**: replay over HTTP is implementable today — `ReplayAction` exists with the assumed
shape. Only the manual end-to-end smoke test (T023) waits on the `ReplayEnqueuer` Azure adapter.

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

- [ ] T024 [P] [US2] `ResendLegControllerTest` (`@WebMvcTest`, `LegResender` mocked): the list
      passed to `LegResender` exactly as sent — no collapse, no cap, no reorder (FR-005-011,
      FR-005-013); `202 {resendRunId}` on acceptance, immediately, not waiting for any ID
      (FR-005-015); whole-call refusals render 004's own codes
      (`missing-argument`/`argument-too-long`/`too-many-request-ids`/`unreadable-argument`/
      `intake-paused`/`store-unavailable`) per contracts/operations-api.md §2 — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/ResendLegControllerTest.java`
- [ ] T025 [P] [US2] `OperationsResponseMapperTest` (resend-leg half): `LegResender`'s accepted-run
      result → `ResendLegAcceptedResponse` carrying only `resendRunId` (research.md Decision 4) —
      `[BLOCKED-ON-004]` — extends `src/test/java/.../api/OperationsResponseMapperTest.java`
- [ ] T026 [P] [US2] `OperationsRequestMapperTest` (resend-leg list half): `allowChangedPayload`
      passed through unchanged, `false` when absent (FR-005-010) — `[BLOCKED-ON-004]` for the
      end-to-end case, but the parsing-only assertions can run against T008 today — extends
      `src/test/java/.../api/OperationsRequestMapperTest.java`

### Implementation for User Story 2 `[BLOCKED-ON-004]`

- [ ] T027 [US2] Implement `api/OperationsResponseMapper.java` (resend-leg half): maps
      `LegResender`'s accepted-run result to `ResendLegAcceptedResponse` — satisfies T025 —
      **cannot compile until `application.LegResender` exists**
- [ ] T028 [US2] Implement `api/ResendLegController.java`: `POST /operations/resend-leg`, parses via
      `OperationsRequestMapper`, calls `LegResender`, answers `202` via
      `OperationsResponseMapper`, wrapped by `OperationsAuditPublisher` — satisfies T024 — **cannot
      compile until `application.LegResender` exists**
- [ ] T029 Run `quickstart.md`'s resend-leg steps once `application.LegResender` lands

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

- [ ] T030 [P] [US3] `ForcedResendControllerIT`: `allowTerminalSuccess: true` reaches `LegResender`
      unchanged with operator/reason; both audit events say the override was requested
      (FR-005-017); without the override the same ID renders `not-eligible` and the call still
      succeeds (`202`); a reason or operator over 200 characters is `400 argument-too-long`
      (FR-005-016); neither operator nor reason appears in the answer, a log line, or a metric label
      (FR-005-023, FR-005-031); a forced resend whose send later fails is still a `202`-accepted
      call — the failure surfaces only in 004's report (FR-005-014) — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/ForcedResendControllerIT.java`

### Implementation for User Story 3 `[BLOCKED-ON-004]`

- [ ] T031 [US3] Confirm `ResendLegController`/`OperationsAuditPublisher` (T028, T010) already pass
      `allowTerminalSuccess` through unchanged and surface it on both audit events without a new
      code path — satisfies T030; if a gap is found, fix `OperationsAuditPublisher`'s event-field
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

- [ ] T032 [P] [US5] `OperationsRefusalSweepTest` (extends `OperationsProblemDetailHandlerTest`,
      T005, with both real endpoints once built — `[BLOCKED-ON-004]` for the endpoint-level cases,
      but the problem-detail shape itself is already covered by T005): every refusal is RFC 9457
      with a closed `reason`, no exception message, no stack trace; a successful call's `requestId`
      is always the canonical parse, never the raw characters sent (FR-005-029, FR-005-031) —
      `src/test/java/.../api/OperationsRefusalSweepTest.java`
- [ ] T033 [P] [US5] `TelemetryPrivacyTest`/`LogStatementSweepTest` extension: sweep every new log
      line and audit event this slice adds for the forbidden content set (operator, reason, an
      unreadable identifier, `CJSCPPUID`) — `src/test/java/.../support/LogStatementSweepTest.java`
      (extend, do not duplicate)

### Implementation for User Story 5

- [ ] T034 [US5] Close any gap T032/T033 find — expected to be none, since T006/T008 already
      implement the bounded-refusal and no-echo rules; this phase is a verification pass

**Checkpoint**: the no-echo, bounded-refusal guarantee is proven across both endpoints together.

---

## Phase 8: User Story 6 - The API is described before it is built, and agrees with its description (Priority: P2)

**Goal**: `probation-results-distribution-openapi.yaml` describes exactly the two operations, and a
contract test proves it both ways.

**Independent Test**: a contract test compares served operations/statuses/reasons/closed value sets
with the document, both ways.

### Tests for User Story 6

- [ ] T035 [P] [US6] `OperationsContractTest`: every operation and every `reason` the code can
      answer is in the document; everything the document describes is served; each closed value set
      in the document (`leg`, per-ID status/reason/refusal code, replay outcome) equals the
      service's own vocabulary value for value (FR-005-035) — `[BLOCKED-ON-004]` for the
      served-operations half, since the controllers don't exist until Phases 4–5 land —
      `src/test/java/.../api/OperationsContractTest.java`

### Implementation for User Story 6

- [ ] T036 [US6] Fill in `src/main/resources/probation-results-distribution-openapi.yaml`: the two
      operations, each request/success shape/status/bounded `reason`, `operationId` set to the
      action strings of FR-005-019 (research.md Decision 2), every closed value set mirrored from
      the service's vocabularies, version raised above `0.0.0` (FR-005-035)
- [ ] T037 [US6] Update `doc/API_CONTRACTS.md` §3 with the two endpoints, their refusals, the five
      gates and the runner fallback (FR-005-036) — in the same commit as T036

**Checkpoint**: the OpenAPI document and the served API are provably in agreement.

---

## Phase 9: User Story 7 - The API and its command-line fallback give the same answer (Priority: P3)

**Goal**: the same mixed list through the API and through 004's runner gives the same report, ID
for ID.

**Independent Test**: run the same mixed list through the runner and through the API, each against
an identically seeded store, and compare the reports ID for ID.

### Tests for User Story 7 `[BLOCKED-ON-004]`

- [ ] T038 [P] [US7] `OperationsRunnerParityIT`: the design's mixed list through
      `cli/ResendLegRunner` (004) and through `POST /operations/resend-leg` (005), each against an
      identically seeded store, give the same whole-call refusal or the same outcome/refusal code
      per ID (FR-005-005); a replay refused through the API is refused with the same code through
      the runner in the same state — needs **both** 004's `cli` runners (beyond today's dispatcher
      skeleton) and 005's controllers implemented — `[BLOCKED-ON-004]` —
      `src/test/java/.../api/OperationsRunnerParityIT.java`

### Implementation for User Story 7

- [ ] T039 [US7] Confirm the replay and resend-leg command-line runners remain present and
      documented as the fallback once this slice is built (FR-005-005) — a verification task, no
      new code

**Checkpoint**: all seven user stories are independently functional once 004 lands for real.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [ ] T040 [P] `OperationsConfigPropertiesTest`: this slice adds 0 properties to the closed
      `distribution.*` catalogue and makes 0 App Configuration or Results-switch reads
      (SC-005-011) — `src/test/java/.../config/OperationsConfigPropertiesTest.java`
- [ ] T041 [P] Run the full `TelemetryPrivacyTest`/`LogStatementSweepTest` suite across every new
      class this slice adds — zero findings (FR-005-023, FR-005-031, FR-005-032; SC-005-004)
- [ ] T042 Run `./gradlew build` (PMD, Checkstyle, JaCoCo gate) and confirm the coverage thresholds
      are unchanged (SC-005-010)
- [ ] T043 Run every `quickstart.md` step end to end. The replay steps need only the
      `ReplayEnqueuer` adapter (004); the resend-leg steps need `LegResender` fully implemented
- [ ] T044 [P] Confirm T013's `OperationsActionResolutionFilter` ordering holds against the real
      filter chain in T014, including the one case research.md Decision 2 flags as not yet
      re-verified by a running test: a caller sending a conflicting `CPP-ACTION` header has no effect
- [ ] T045 Update `CLAUDE.md`'s Setup section once this slice is fully implemented (not just
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
  authz/audit chain). `application.ReplayAction` now exists, so T019–T022 are unblocked; only T023
  (the manual end-to-end run) waits on 004's `ReplayEnqueuer` adapter
- **US2 (Phase 5)**: depends on Foundational and US4; **blocked on `application.LegResender`
  existing** (004); independent of US1's controller
- **US3 (Phase 6)**: depends on US2's controller and mapper (T027, T028) — it is the override path
  through the same endpoint, not a new one
- **US5 (Phase 7)**: depends on US1–US4 existing to sweep across real endpoints, though its
  foundational half (T005/T008) is already provable without them
- **US6 (Phase 8)**: the OpenAPI authoring (T036) can happen any time after Setup; the contract
  test's served-operations half needs US1/US2's controllers
- **US7 (Phase 9)**: depends on US1–US3 **and** on 004's `cli` runners being implemented beyond
  today's dispatcher skeleton (004 tasks.md T047, T023 in that slice's numbering)
- **Polish (Phase 10)**: depends on every story to be included in that release being complete

### Parallel Opportunities

- T001–T004 in parallel (Setup)
- T005, T007, T009 in parallel (Foundational tests, different files)
- T012, T014–T016 in parallel (US4 tests); T013 depends on T012
- T019/T020 (US1) can proceed now, in parallel with US4's tests, since `application.ReplayAction`
  already exists; T024–T026 (US2) still wait on `application.LegResender`
- T036 (the OpenAPI document) can be authored in parallel with US1–US3's implementation, since it
  does not depend on the controllers existing, only on the shapes this plan already fixes

---

## Implementation Strategy

### MVP First

1. Setup → Foundational → US4 (authorisation/audit proven) → US1 (replay) — **buildable today**,
   since `application.ReplayAction` already exists with the assumed shape. Stop and validate
   against US1's Independent Test (its `@WebMvcTest` half; the manual end-to-end half waits on the
   `ReplayEnqueuer` adapter). This alone gives operators the HTTP route to the general-use recovery
   action.

### Incremental Delivery

1. Setup + Foundational + US4 → ready now.
2. US1 (MVP) → ready now (T019–T022); T023's manual run waits on 004's `ReplayEnqueuer` adapter.
3. The instant 004's `LegResender` lands: US2 → US3 (same endpoint, the override).
4. US5 and US6 can be finished alongside US1–US3, since their foundational halves need no
   endpoint.
5. US7 last, once 004's `cli` runners are implemented for real, not just their dispatcher skeleton.

Phases 1–4 (Setup, Foundational, US4, US1) no longer wait on 004 at all beyond the one adapter noted
above — that is most of this slice's P1 scope, buildable starting now.
