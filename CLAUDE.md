# service-cp-crime-probation-results-distribution

Replaces the `hmpps` ("probation") Azure Function App. Consumes one command per resulted regular
hearing share from a dedicated Azure Service Bus queue, `probation.requests`, published by a
flag-gated increment in `cpp-context-results`; reads the hearing's `EXT_` payload from Redis, with
the results query API as fallback; reads the users-groups `EMYouthFilter` permission per message;
redacts the payload by the legacy HMPPS rules (R0–R12, the youth rules R3/R4 only while the switch
is active); and POSTs what is left to the HMPPS route on the APIM gateway, recording one outcome per
leg per attempt in its own processed log, with the exact body POSTed kept for six months.

It is a **lift-and-shift** port (design decision D1): the rules and what is sent are copied
verbatim, quirks included, and the delivery and reliability defects around them are fixed — each as
a numbered deviation (DV-n) with its own pinning test. It is **HMPPS-only**: the legacy's VEP leg is
not built (H5). The youth switch is **retained at legacy parity** (H7), except that a switch that
cannot be read resolves to the configured default (`ACTIVE` unless set otherwise) and the hearing is
sent (H8, DV-25).

## Programme
Crime Common Platform (CPP) — Modern by Default (MbD)
Organisation: HMCTS / Ministry of Justice — Team: Resulting Assistant
Jira: none — this work carries no ticket; features land on `main` through pull requests

## Stack
- Spring Boot 4.1, Java 25, **Gradle — never Maven**
- Package: uk.gov.hmcts.cp.probationresultsdistribution
- Port: 8082 (local) / 4550 (Kubernetes)
- Deployment/release name: `probation-results-distribution`
- Provenance: derived from the `hmcts/service-hmcts-crime-springboot-template` crime Spring Boot
  template by way of the `service-cp-crime-informant-register` and
  `service-cp-crime-yot-results-distribution` reference implementations. **Never scaffold from
  scratch and never use Spring Initializr** — the shape of this repo is inherited, and a hand-rolled
  skeleton loses the estate conventions baked into it.

## Key Documentation
| Document | Location |
|---|---|
| **Design (authoritative)** | Confluence — [Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service) (CRA space), rev 3.6 (decisions H1–H34). This repo carries **no** design narrative: do not create `doc/*_DESIGN.md`, `SOLUTION_BRIEF.md` or any other rationale document here |
| Design digest | `doc/design/DIGEST.md` — rev 3.6's rules for spec authors and implementers, each tagged with its design row id; rules only, no rationale. The design wins where they differ |
| Constitution | `.specify/memory/constitution.md` |
| Contracts | `doc/API_CONTRACTS.md` |
| Deviation register | `doc/DEVIATIONS.md` — seeded from design §7.2 (DV-01 … DV-26) by the first feature that lands behaviour; not present yet |
| Specifications | none yet — the first feature starts with `/speckit-specify` |
| Inbound message schema | `src/main/resources/contracts/distribution-command.schema.json` — design Appendix B.1, vendored with the first feature |
| Outbound HMPPS schema | **none in this release** — outbound schema validation is parked and gate G1 is not a gate (H13); the HMPPS ICD v0.8 is information only (H12) |

## Message-Contract Rule
This service exposes **no business REST API**: no hearing is submitted to it over HTTP and no
consumer reads anything out of it. Its HTTP surface is Spring Boot Actuator and the operations API
under `/operations/**`, which is **in scope for replay and resend-leg** (the forced-resend override
included) behind YOT's five deployment gates, with the command-line runners in the image as the
fallback (H9). The other operator actions of design §4.8 — list, pause and resume intake,
submit-hearing, purge-queue, the optional flag check, the recommended `dlq-completed` listing — run
as command-line runners; their HTTP exposure is **open (O-1)**, and adding one is a constitution
amendment. Its contracts are:
- **Inbound**: the `probation.requests` queue message (`distribution-command.schema.json`,
  `additionalProperties: false`; `messageId = RESULTS:{requestId}`), agreed with `cpp-context-results`
  (the publisher). Operator markers (`origin = REPLAY`, `origin = SUPPORT`) are broker application
  properties, never body fields; a dead-letter resubmission or a producer republish carries none, and
  the replay route (`service`, `support`, `copy`) is recorded at admission (H9).
- **Outbound**: the redacted HMPPS body, **not validated against any schema in this release** (H13):
  redact → body digest → send. The HMPPS ICD v0.8 is obtained — success is 200 only, the POST is not
  idempotent — and its schema is stale, so it is information only (H12). **What is sent does not
  change in this delivery** (D10).
- **Operations API**: its OpenAPI document is owned and versioned here once built (H9).
- **Consumed, never redefined**: the Redis `EXT_` claim-check payload; the results query API
  `hearingDetails` fallback; the users-groups permission read; the APIM gateway's HMPPS route with
  the subscription key the function app uses today (D4a — no gateway change).

Contract changes are cross-team events. The spec-validator agent checks contract compliance and the
deviation register after implementation.

## Parity Rule
The legacy HMPPS code at `cpp-context-azure-legalaidagency` `122e2152` is the oracle for every
behaviour that is not a registered deviation (H21): for SIT cases the expected body is **SIT's own
logged HMPPS body** (switch inactive); the legacy JavaScript is executed only for the switch-active
expectations and for synthetic cases. Before cutover, a one-week SIT → STE replay against a stub
gateway compares STE's `outbound_body` hearing by hearing with SIT's logged bodies; any unexplained
difference blocks cutover. There is no shadow profile. Every intended difference is a DV row in
`doc/DEVIATIONS.md` naming its pinning test; a difference without a row is reverted. Carried quirks
stay carried (design §7.1) — P17 (the first permission decides), P18 (with the switch on, an
application with no `masterDefendant` is dropped) and P19 (no null guards: a rule that throws is
`FAILED(rules-exception)`, never guarded); P21 and P35 (`plea.delegatedPowers` kept) are pending
external and stay carried until HMPPS agrees (D10). Application-only hearings are sent as today
and HMPPS rejects them with 400 (D16 (a)): a known class to count, not a defect to fix (H33). P16
(an unreadable switch sends nothing) is fixed under DV-25 (H8): the leg applies
`distribution.youth-switch.default-when-unreadable` and sends, recording
`youth_switch = UNREADABLE_DEFAULTED_<value>`; corpus case 13 derives its expected output from the
`ACTIVE` output. Design §7.2 includes DV-26 (a dead-letter copy under its exhausted `messageId` is
a replay at broker delivery count 1, H9). DV ids are never reused; the next free one is DV-27. See
constitution Principles I and IX.

## Scope Rule
HMPPS only — no VEP projection, sender, schema, stub, key or flag (H5). The service does **not**
read the cutover switch `ProbationResultsDistributionService` (Q15, H26): Results gates the publish
with `FeatureControlGuard` from App Configuration, fail-closed, and the legacy function app reads
no switch — it is switched off at its Event Grid subscription (H22). The service reads **no App
Configuration** at all; a service-side check of the switch was considered and left out for now, and
pause-intake is the service-side stop (row A3-92). Nothing here is a second lever. Out of scope:
the producer increment (built in `cpp-context-results`), the LAA feed, the Results Store, SJP
hearings, any APIM gateway change.

## Public Repository
This repository is **public**. Never commit internal hostnames, keys, connection strings, personal
data or production hearing data — environment-specific values are environment-variable placeholders.
The recorded parity corpus is **SIT payloads only** and is committed under `src/test/resources/corpus/`
(SIT holds no production data; decision 2026-10-19). SIT's logged HMPPS bodies, used as expected
output, are the same kind of extract and follow the same rule and location (H21). PRD/PRP payloads
and bodies are never committed.

## Deployment
- **CI/CD**: GitHub Actions → **ADO Pipeline 460** → ACR → deployed by **Flux** using the shared
  **`springboot-app`** Helm chart.
- **Secrets** come from **Azure Key Vault via the CSI driver, with workload identity** — the gateway
  subscription key, the Redis password, the datasource credentials, the App Insights connection
  string and the service's **own system user id** (H30): one id, from a static-data patch, sent as
  `CJSCPPUID` on the users-groups read, and on the results fallback when the command has no `userId`
  and on every resend.
  No static key, no committed connection string, no secret in a Helm value or an environment default.
- Workload identity holds `AzureServiceBusDataReceiver` and, for the actions that enqueue
  (submit-hearing and replay), `AzureServiceBusDataSender` on `probation.requests`. No App
  Configuration role.
- The deployment wiring (helmsman entry, values, queue terraform, identity, network policy) lives in
  the sibling infrastructure repos, not here; the platform asks are design §8.4.

## Build & Test
```bash
./gradlew build              # Compile + the full test suite + PMD + Checkstyle (main and test
                             # sources for both) + the JaCoCo coverage gate. Every analysis runs
                             # in `check`; none of them has to be named separately
./gradlew test               # The whole suite: unit and *IT alike — there is no separate
                             # integrationTest task; the Testcontainers suites run here and
                             # need Docker only when those tests are in the selection
./gradlew checkstyleMain checkstyleTest   # Checkstyle alone (google_checks, maxWarnings 0)
./gradlew pmdMain pmdTest    # PMD alone; src/test uses .github/pmd-test-ruleset.xml
./gradlew jacocoTestReport check          # The order CI uses: the coverage report is written
                             # before jacocoTestCoverageVerification reads it, so a failing gate
                             # still leaves a report saying which lines were missed
./gradlew bootRun            # Run locally
```

The toolchain is JDK 25. `docker compose up -d` starts the local stack (Service Bus emulator,
Postgres, Redis, WireMock).

## Repository Conventions
- Conventional Commits; no AI attribution in commits, PRs, comments or docs.
- TDD red-run convention per `specs/*/tasks.md`: a test task lands its compile-safe seams so the
  recorded red run is a failing assertion, never a compile error; the paired implementation task
  quotes the green run.
- A golden file changes only in the same commit as its `doc/DEVIATIONS.md` row.
- Never run two committing agents concurrently in this repo.

## Setup

<!-- SPECKIT START -->
All six features are specified (design rev 3.6, constitution v2.0.0); none is planned yet.
Implement in dependency order: `001-parity-kernel` → `002-hmpps-distribution` (owns the shared core:
Flyway V1, enums, state machine, ports, metric registry) → `003-body-retention` and `004-recovery`
→ `005-operations-api` → `006-production-readiness`.

To start a feature: branch from `main` with the spec's folder name (e.g. `git switch -c
002-hmpps-distribution`) — the Spec Kit scripts resolve `specs/<NNN>-*` from the branch prefix —
then run `/speckit-clarify` for any remaining `[NEEDS CLARIFICATION]` (answers come from the design
owner), `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze`, `/speckit-implement`. The plan
(`specs/<NNN-slug>/plan.md`, with `spec.md` and `tasks.md` alongside it) then becomes the current
context here. Specs are written against `doc/design/DIGEST.md` and the design it cites.
<!-- SPECKIT END -->
