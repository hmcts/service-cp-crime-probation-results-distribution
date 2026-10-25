# Implementation Plan: Operator recovery — replay, resend-leg and the operator actions

**Branch**: `004-recovery` | **Date**: 2026-10-24 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/004-recovery/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

This slice gives operations three bounded ways to get a hearing to HMPPS after a first attempt
fails or a sent hearing must go again, without ever duplicating a leg already recorded `SENT` or
`NOT_APPLICABLE` by accident: a dead-letter resubmission admitted as a replay at broker delivery
count 1 (DV-26); the service's own replay action, which re-enqueues the stored command byte for
byte under a fresh `messageId`; and resend-leg, which re-runs the HMPPS leg for an explicit list of
`requestId`s, with a forced-resend override for a deliberate duplicate. Around these sit the
runners rollback and go-live rely on: pause/resume intake, purge-queue, submit-hearing, and two
read-only listings. Everything here is reached either through the existing ASB listener (the
dead-letter and service-replay routes reuse slice 002's guard and settlement) or through new
command-line runners in the image — there is no HTTP surface in this slice (O-1; slice 005).

The technical approach is almost entirely in the **application layer**: new use cases
(`ReplayAction`, `LegResender`, `SubmitHearingAction`, `RequestIdRecipe`, the intake-pause and
purge-queue actions, the two listings, the rollback close-in-flight statement) composed from
slice 002's ports (`HearingPayloadSource`, `YouthFilterSource`, `HmppsSender`, `OutboundLog`, the
request store) with **no new port and no new adapter**. The only schema change this slice may need
is a persisted intake-pause flag (FR-004-052; see Research, Decision 3) — everything else reuses
002's three tables and one view unchanged. A new driving-adapter package, `cli`, hosts the six
runners and the two listings as thin Spring Boot `CommandLineRunner`s that parse arguments, call one
application service, and print its bounded outcome — mirroring how `inbound` and `api` already sit
beside `application` without it depending on either.

## Technical Context

**Language/Version**: Java 25 (constitution Technology Stack)
**Primary Dependencies**: Spring Boot 4.1 (`spring-boot-starter-jdbc`, `spring-boot-starter-flyway`,
`spring-boot-starter-actuator`); `com.azure:azure-messaging-servicebus` + `azure-identity` (already
depended on for slice 002's listener; this slice's replay action and submit-hearing runner reuse the
same `ServiceBusSenderClient` on `probation.requests` rather than adding a dependency); no new
library — the runners are plain `CommandLineRunner` beans, gated by a Spring profile/argument switch
so a runner invocation does not start the message listener (FR-004-045)
**Storage**: PostgreSQL 15, the same three tables and one view from slice 002
(`processed_request.command`/`replayed_at`, `outbound_message` with `attempt_origin`/the leg
claim/`resend_run_id`/`operator`/`override`/`note`, `outbound_message_latest`, `outbound_body`) —
unchanged by this slice; one additional single-row control table is the provisional answer to
FR-004-052 (Research, Decision 3), added by a new Flyway migration at this slice's own version
number, following the precedent of slice 003's `V2__…body_maintenance_state` addition
**Testing**: JUnit 5 + Mockito + AssertJ for the application layer (plain mocks, no Spring context);
Testcontainers (`testcontainers-postgresql`, `testcontainers-azure` for the Service Bus emulator,
`testcontainers-toxiproxy`) for `*IT` suites; WireMock for the results-query and users-groups stubs
reused from slice 002 (resend's read-path tests need no new stub); no new test dependency
**Target Platform**: Linux containers on AKS, same image as slices 001/002; runners execute as the
pod's own process under its workload identity (FR-004-044) — no new deployment artefact
**Project Type**: Single Spring Boot service (message-driven pipeline; no web frontend) — Option 1
of the generic layout, realised as the package tree below
**Performance Goals**: A resend attempt fits its stage budgets (payload 60 s, switch 25 s, send
130 s) within the processing deadline (4 min) and before the leg claim's lease (`PT4M30S`) expires,
exactly as a queue run does (FR-004-026, Assumption 9); a resend run over the cap
(`distribution.resend.max-ids-per-run`, default 100) has no stated latency target — Assumption 12
leaves run-serially-or-concurrently to this plan (Research, Decision 2)
**Constraints**: No outbound schema validation (H13); no new leg claim shape beyond slice 002's; no
change to what is sent to HMPPS (D10); zero PII, stored body, payload value, operator name or reason
text in any log line, metric label, report, listing or refusal (FR-004-041); every runner result is
one of done/refused/failed, testable without reading free text (FR-004-045)
**Scale/Scope**: 8 user stories, 58 functional requirements, 6 runners + 2 listings, one new Flyway
migration (the pause-state table); no new external integration

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Verdict |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | This slice changes no redaction rule and no byte of what is sent (D10); it reuses slice 001's `HmppsRedaction` unmodified. It pins DV-26 (already allocated, H9) and the resend halves of DV-07 and DV-17 (FR-004-050, FR-004-050a) — no new DV id is needed; the next free id stays DV-27. | PASS |
| II. Test-Driven Development | Every FR names its pinning test in Dependencies → "Pinning tests the design names"; `tasks.md` orders each test task before the implementation task it guards, per this repository's TDD red-run convention. | PASS |
| III. Message-Contract First | No change to the inbound closed schema: the replay message and the submit-hearing command are the **same** schema, carrying only new broker *application properties* (`origin`, `operator`, `runId`, `reason`), which the schema already excludes from its `additionalProperties: false` surface because they are not body fields (constitution Principle III, design A3-34). No change to the outbound body or to H13's no-validation stance. No HTTP contract is added — `/operations/**` stays slice 005's. | PASS |
| IV. Canonical JSON In, Typed Models Out | The replay action re-sends `processed_request.command` as opaque bytes — never re-parsed into a `JsonNode` and re-serialised, so byte-for-byte equality (SC-004-001) is structural, not incidental. Submit-hearing reads the cached payload as `JsonNode` exactly as slice 002's `HearingPayloadSource` already does, and takes `sharedTime` as the raw JSON string without round-tripping it through a parsed form (FR-004-055). | PASS |
| V. SOLID with Ports and Adapters | No new port: `ReplayAction`, `LegResender` and `SubmitHearingAction` compose existing ports (`HearingPayloadSource`, `YouthFilterSource`, `HmppsSender`, `OutboundLog`) plus the request store the guard already uses. The six runners and two listings are a new **driving** adapter package (`cli`), symmetric with `inbound` and `api`: it calls into `application` and depends on nothing else in the service. | PASS |
| VI. Explicit Failure | Every action ends in one of `enqueued`/`no-op`/a bounded refusal code, or a declared failure (`store-unavailable`, `broker-unavailable`); no action may return "success" from a catch block. A resend never parks (FR-004-027) — it is `FAILED` at once, which is itself an explicit-failure discipline applied to a run kind with no delivery to park against. | PASS |
| VII. Privacy in Telemetry | FR-004-041 is the slice's own restatement of this principle: no stored/sent body, command, payload value, `userId`, operator name or reason text in any log line, metric label, report, listing or refusal, with the four places an operator's text *is* allowed enumerated exhaustively. The privacy sweeps (`TelemetryPrivacyTest`, `LogStatementSweepTest`) extend to this slice's new log lines and reports. | PASS |
| VIII. Estate Conventions | Gradle, the existing package root, Conventional Commits, no AI attribution — unchanged. The new `cli` package gets its own `package-info.java` stating its responsibility and dependency direction, as every existing package already does. | PASS |
| IX. Scope — the HMPPS Leg Only | No VEP code path of any kind (every runner and listing is `--leg HMPPS` only, the leg's only value). No App Configuration read is added — the operator pause is a service-internal control, not a second cutover lever (constitution IX; FR-004-052's provisional store does not touch `ProbationResultsDistributionService`). | PASS |

No violation requires an entry in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/004-recovery/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── cli-runners.md       # The six runners' and two listings' argument/outcome contract
│   └── broker-properties.md # The application properties this slice adds, against the unchanged schema
├── checklists/
│   └── requirements.md  # Already PASS
└── tasks.md              # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

This is a single Spring Boot service — Option 1 (single project) of the generic layout, realised as
the ports-and-adapters tree the constitution and `design_rules.md` already fix. This slice adds no
new top-level package other than `cli`; every class below either already has a home (per the
existing `package-info.java` files, several of which already name this slice's classes) or needs
one new package symmetric with `inbound`/`api`.

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── application/                        # unchanged package, new classes (no new port)
│   ├── ReplayAction.java               # FR-004-014–017: one requestId, decide-and-enqueue
│   ├── LegResender.java                # FR-004-018–039: resend-leg, per-ID claim/run/report
│   ├── SubmitHearingAction.java        # FR-004-054–058
│   ├── RequestIdRecipe.java            # FR-004-055: name-based UUID over raw strings
│   ├── IntakePauseAction.java          # FR-004-051, 052: pause/resume, read by LegResender/PurgeQueueAction
│   ├── PurgeQueueAction.java           # FR-004-053
│   ├── RequestListingQuery.java        # FR-004-043 (action 1)
│   ├── DlqCompletedListingQuery.java   # FR-004-043 (dlq-completed)
│   └── RollbackCloseInFlightAction.java# FR-004-049/049a (provisional owner; Research Decision 1)
├── inbound/                            # unchanged package, DV-26 predicate replaces 002's interim rule
│   └── (guard changes only — FR-004-001–013; no new class, ReplayAdmission logic inside the
│        existing admission statement)
├── cli/                                # NEW: driving adapter, symmetric with inbound/api
│   ├── ReplayRunner.java
│   ├── ResendLegRunner.java
│   ├── PauseIntakeRunner.java / ResumeIntakeRunner.java
│   ├── PurgeQueueRunner.java
│   ├── SubmitHearingRunner.java
│   ├── RollbackCloseInFlightRunner.java (provisional; Research Decision 1)
│   ├── RequestListingRunner.java / DlqCompletedListingRunner.java
│   └── package-info.java               # new; dependency direction = inbound/api's
├── persistence/                        # unchanged package, no schema change except Decision 3's table
│   └── db/migration/V<n>__add_intake_control.sql   # provisional; Research Decision 3
└── domain/                             # unchanged package, new closed value sets only
    └── (ReplayRoute, ResendRefusalReason, RunOrigin additions if not already in 002's enums)

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── application/
│   ├── ReplayActionTest.java
│   ├── RequestReplayStatementTest.java
│   ├── LegRerunTest.java
│   ├── OutcomeMetricsTest.java
│   ├── RequestRecoveryTransitionTest.java
│   ├── RequestIdRecipeTest.java
│   └── PropertiesValidatorTest.java (max-ids-per-run case)
├── inbound/
│   └── DlqResubmissionReplayIT.java    # Testcontainers Service Bus emulator
└── it/ (or alongside the above, per this repo's existing *IT placement)
    ├── ResendByRequestIdsIT.java
    ├── ResendSharedTimeCheckIT.java
    ├── ResendConcurrencyIT.java
    ├── ForcedResendIT.java
    └── RollbackFencingIT.java
```

**Structure Decision**: single-project ports-and-adapters, unchanged from slices 001/002. The one
addition is the `cli` package as a third driving adapter alongside `inbound` (queue) and `api`
(HTTP, slice 005): command-line arguments in, one application-service call, one bounded outcome
printed, nothing else. This keeps the parity-critical and fencing-critical logic in `application`
testable with plain mocks and no Spring context, exactly as slice 002 established, and keeps the
runners from duplicating any rule already enforced there (FR-004-042's "exactly the same rules
through the same application service" is enforced by construction, not by code review).

## Complexity Tracking

*No entries — the Constitution Check above has no unjustified violation.*
