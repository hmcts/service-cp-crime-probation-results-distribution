# Implementation Plan: Operations API — replay and resend-leg over HTTP

**Branch**: `005-operations-api` (planned on `002-hmpps-distribution` / PR #8, the consolidated
integration branch) | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/005-operations-api/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

This slice puts exactly two operator actions on HTTP — `POST /operations/requests/{requestId}/replay`
and `POST /operations/resend-leg` — as a thin driving adapter in front of slice 004's application
services, behind five deployment gates none of which live in this repository. The technical approach
is almost entirely **composition over what the scaffold already wires**: `cp-auth-rules-filter` and
`cp-audit-filter-springboot` are already on the classpath with `authz.http`/`audit.http`/`cp.audit`
configured in `application.yaml`; `MetricRegistry.operationsFailure(OperationsAction,
OperationsFailure)` and the `probation_operations_failures_total` series are already registered; the
OpenAPI placeholder (`src/main/resources/probation-results-distribution-openapi.yaml`, empty
`paths: {}`) is already wired as the audit filter's spec source. This slice's own work is: two Spring
MVC controllers with no business logic of their own, a global RFC 9457 `ProblemDetail` exception
mapping, the Drools `.drl` allow-rules file (none exists yet), filling in the OpenAPI document, and
calling slice 004's `ReplayAction`/`LegResender` — which do not yet exist in code on this branch
(004 is planned but only its `cli`/vocabulary foundations are implemented). This plan treats 004's
application-service contracts (`plan.md`/`contracts/cli-runners.md`) as the authoritative interface
this slice's controllers call, same as 004's own `cli` runners will.

## Technical Context

**Language/Version**: Java 25 (constitution Technology Stack)
**Primary Dependencies**: `spring-boot-starter-web` (already a dependency; MVC `@RestController`,
not WebFlux); `uk.gov.hmcts.cp:cp-auth-rules-filter:1.0.7` (Drools-rule authorisation, already on
the classpath and configured under `authz.http` in `application.yaml` — no rules file exists yet);
`uk.gov.hmcts.cp:cp-audit-filter-springboot:1.0.5` (request/response audit events over Artemis,
already configured under `audit.http`/`cp.audit`); no new dependency
**Storage**: none of this slice's own — every read and write goes through slice 004's application
services against slice 002's three tables and one view
**Testing**: JUnit 5 + Mockito + AssertJ, `@WebMvcTest` with slice 004's services mocked at the
boundary for the controller/authorisation/audit/error-mapping layer (Independent Tests of stories
1–5); a contract test asserting the served operations against the OpenAPI document both ways
(story 6); an end-to-end test comparing the API's and 004's runner's reports for the same seeded
state (story 7, needs 004's runners built for real)
**Target Platform**: the same AKS pod as every other slice — no new deployment artefact, only a new
route (`/operations/**`) the five gates (platform asks 2, 12, 14) must admit before any environment
serves it
**Project Type**: single Spring Boot service (message-driven pipeline with, from this slice on, one
narrow HTTP surface for operators) — Option 1 of the generic layout
**Performance Goals**: no latency target of its own; the resend-leg call must answer `202` without
waiting for any ID to finish (FR-005-015), so its own handler-side latency is whatever it takes to
validate the request and hand off to 004 — no stage budget applies to the HTTP layer itself
**Constraints**: no new `distribution.*` property (FR-005-039); no App Configuration or Results-switch
read (FR-005-040); zero caller-supplied text (operator, reason, an unreadable identifier) in any
answer, log line or metric label (FR-005-031, FR-005-032); the service's own publisher must replace
the audit library's default, which swallows its own publish failures (FR-005-027)
**Scale/Scope**: 7 user stories, 43 functional requirements, 2 HTTP operations, 1 Drools rules file,
1 OpenAPI document to fill in, 1 global exception handler, 0 new persistence

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Verdict |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | No DV row: the legacy function app has no operator surface to deviate from, and no ported rule (R0–R12) is touched (spec Context, "This slice adds no deviation"). | PASS |
| II. Test-Driven Development | Every FR maps to a story's Independent Test and acceptance scenarios; `tasks.md` orders each test task before the implementation task it guards, per this repository's TDD red-run convention. | PASS |
| III. Message-Contract First | No change to the inbound queue schema or the outbound HMPPS body. The operations API is this slice's one new owned contract: `src/main/resources/probation-results-distribution-openapi.yaml` is updated **before** the controllers that serve it (FR-005-035), and `doc/API_CONTRACTS.md` §3 before the code (FR-005-036) — exactly the sequencing Principle III already names for this API once it is built. | PASS |
| IV. Canonical JSON In, Typed Models Out | This slice never touches a hearing payload or the outbound body; its own request/response shapes are small, closed, typed Java records (`requestIds`, `leg`, `operator`, `reason`, the two override booleans; the resend report). No `JsonNode` crosses this layer. | PASS |
| V. SOLID with Ports and Adapters | `api/` is a driving adapter exactly like `inbound` and the new `cli` (004): it calls into `application` and speaks `domain`, holds no store repository, broker client, outbound HTTP client, eligibility rule or lock of its own (FR-005-003). No new port — it calls 004's `ReplayAction`/`LegResender` directly, the same services the `cli` runners call. | PASS |
| VI. Explicit Failure | Every refusal is a bounded RFC 9457 `reason` from a closed set (FR-005-029); every `5xx` is logged at ERROR and counted on `probation_operations_failures_total` (FR-005-043); a request event that cannot be published refuses the call before anything changes, and a response event that cannot be published is logged and counted rather than silently dropped — the one behaviour this spec decides itself, flagged for H31's sign-off (FR-005-027). | PASS |
| VII. Privacy in Telemetry | No caller-supplied value (operator, reason, an unreadable identifier, `CJSCPPUID`) reaches an answer, a log line or a metric label (FR-005-023, FR-005-031, FR-005-032); the audit transport's `include-payload-body: false` is already set in the scaffold. | PASS |
| VIII. Estate Conventions | Gradle, the existing package root, Conventional Commits — unchanged. `api/` gets its first real classes under the `package-info.java` already in place, describing exactly this shape. | PASS |
| IX. Scope — the HMPPS Leg Only | `leg` is `HMPPS` only (FR-005-010); no VEP path; no App Configuration read; no second cutover lever (FR-005-040). | PASS |

No violation requires an entry in Complexity Tracking.

## Project Structure

### Documentation (this feature)

```text
specs/005-operations-api/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md         # Phase 1 output (/speckit.plan command)
├── quickstart.md         # Phase 1 output (/speckit.plan command)
├── contracts/            # Phase 1 output (/speckit.plan command)
│   └── operations-api.md    # The two HTTP operations' request/response/refusal contract, ahead of
│                             # filling in the real OpenAPI document (FR-005-035)
├── checklists/
│   └── requirements.md  # Already PASS (both NEEDS CLARIFICATION markers resolved 2026-10-25)
└── tasks.md              # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Single Spring Boot service, ports-and-adapters, unchanged from slices 001/002/004. `api/` already
has its `package-info.java` (written ahead of this slice, during 002's work) describing exactly this
shape; this slice is the first to put real classes in it.

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── api/                                 # unchanged package, first real classes
│   ├── ReplayController.java            # FR-005-006–009: one POST, calls application.ReplayAction
│   ├── ResendLegController.java         # FR-005-010–017: one POST, calls application.LegResender
│   ├── OperationsRequestMapper.java     # JSON body → typed request record; UUID/leg/bound parsing
│   │                                    # (FR-005-010, FR-005-011, FR-005-033)
│   ├── OperationsResponseMapper.java    # ReplayAction/LegResender result → the answer shapes of
│   │                                    # FR-005-007, FR-005-014
│   ├── OperationsProblemDetailHandler.java  # @RestControllerAdvice: every refusal/failure →
│   │                                    # RFC 9457 ProblemDetail, status family + closed reason
│   │                                    # (FR-005-029, FR-005-030, FR-005-034)
│   ├── OperationsAuditPublisher.java    # the service's own publisher, replacing the audit
│   │                                    # library's default (FR-005-027, assumption 6)
│   └── dto/
│       ├── ReplayResponse.java
│       ├── ResendLegRequest.java
│       └── ResendLegAcceptedResponse.java
├── application/                         # unchanged package; this slice adds no class here —
│                                        # ReplayAction and LegResender are 004's
└── domain/                              # unchanged package; OperationsAction/OperationsFailure
                                         # already exist (built ahead of this slice during 002's
                                         # work); no new enum needed

src/main/resources/
├── probation-results-distribution-openapi.yaml   # filled in (FR-005-035); currently paths: {}
└── acl/
    └── operations-api.drl                        # NEW: the two allow rules (FR-005-020),
                                                    # admitting Second Line Support, loaded by
                                                    # cp-auth-rules-filter's classpath:/acl/**/*.drl

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/api/
├── ReplayControllerTest.java             # @WebMvcTest, ReplayAction mocked
├── ResendLegControllerTest.java          # @WebMvcTest, LegResender mocked
├── OperationsAuthorisationIT.java        # the real authz filter wired, users-groups stubbed
├── OperationsAuditIT.java                # the real audit filter wired, Artemis stubbed/embedded
├── OperationsProblemDetailHandlerTest.java
├── OperationsContractTest.java           # story 6: served operations vs. the OpenAPI document,
│                                          # both ways
└── OperationsRunnerParityIT.java         # story 7: API vs. 004's CLI runner, same seeded state
```

**Structure Decision**: single-project ports-and-adapters, unchanged. `api/` becomes the third driving
adapter (alongside `inbound` and `cli`) to reach full use: controllers in, through a mapper, into one
of 004's two application services, out through a response mapper — no business logic, no store
access, no broker call of its own (FR-005-003). The Drools `.drl` rules file and the filled-in OpenAPI
document are the two genuinely new configuration artefacts; everything else in the authz/audit chain
is already wired in `application.yaml` from the scaffold.

## Complexity Tracking

*No entries — the Constitution Check above has no unjustified violation.*
