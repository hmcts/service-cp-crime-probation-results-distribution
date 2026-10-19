# service-cp-crime-probation-results-distribution

Replaces the `hmpps` ("probation") Azure Function App. Consumes one command per resulted regular
hearing share from a dedicated Azure Service Bus queue, `probation.requests`, published by a
flag-gated increment in `cpp-context-results`; reads the hearing's `EXT_` payload from Redis, with
the results query API as fallback; reads the users-groups `EMYouthFilter` permission per message;
redacts the payload by the legacy HMPPS rules (R0–R12, the youth rules R3/R4 only while the switch
is active); and POSTs what is left to the HMPPS route on the APIM gateway, recording one outcome per
leg per attempt in its own processed log.

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
| **Design (authoritative)** | Confluence — [Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service) (CRA space), rev 3.3 (H8 adopted; H9 not yet). This repo carries **no** design narrative; do not create `doc/*_DESIGN.md` or `SOLUTION_BRIEF.md` here |
| Constitution | `.specify/memory/constitution.md` |
| Contracts | `doc/API_CONTRACTS.md` |
| Deviation register | `doc/DEVIATIONS.md` — seeded from design §7.2 by the first feature that lands behaviour; not present yet |
| Specifications | none yet — the first feature starts with `/speckit-specify` |
| Inbound message schema | `src/main/resources/contracts/distribution-command.schema.json` — design Appendix B.1, vendored with the first feature |
| Outbound HMPPS schema (interim) | `src/main/resources/contracts/outbound/` — derived from the recorded corpus by design §6.6; **pre-build gate G1, open** |

## Message-Contract Rule
This service exposes **no business REST API**: nothing about intake, redaction or sending is
reachable over HTTP. Its HTTP surface is Spring Boot Actuator. The operator actions of design §4.8
(replay, resend-leg, pause and resume intake, submit-hearing, purge-queue) are decided capabilities
that run as command-line runners in the image; whether they also get an `/operations/**` API is
**open (O-1)**, and adding any HTTP endpoint before then is a constitution amendment. Its contracts
are:
- **Inbound**: the `probation.requests` queue message (`distribution-command.schema.json`,
  `additionalProperties: false`; `messageId = RESULTS:{requestId}`), agreed with `cpp-context-results`
  (the publisher). Operator markers (`origin = REPLAY`, `origin = SUPPORT`) are broker application
  properties, never body fields.
- **Outbound**: the redacted HMPPS body, validated before every POST against the interim closed
  schema approved at gate G1, until the HMPPS ICD (Q6) replaces it. **What is sent does not change in
  this delivery** (D10).
- **Consumed, never redefined**: the Redis `EXT_` claim-check payload; the results query API
  `hearingDetails` fallback; the users-groups permission read; the APIM gateway's HMPPS route with
  the subscription key the function app uses today (D4a — no gateway change).

Contract changes are cross-team events. The spec-validator agent checks contract compliance and the
deviation register after implementation.

## Parity Rule
The legacy HMPPS code at `cpp-context-azure-legalaidagency` `122e2152` is the oracle for every
behaviour that is not a registered deviation, recorded by executing it over a corpus in both youth
switch states. Every intended difference is a DV row in `doc/DEVIATIONS.md` naming its pinning
test; a difference without a row is reverted. Carried quirks stay carried — P17 (the first
permission decides), P18 (with the switch on, an application with no `masterDefendant` is dropped),
P35. P16 (an unreadable switch sends nothing) is fixed under DV-25 (H8): the leg applies
`distribution.youth-switch.default-when-unreadable` and sends, recording
`youth_switch = UNREADABLE_DEFAULTED_<value>`; corpus case 13 derives its expected output from the
oracle's `ACTIVE` output. DV ids are never reused; the next free one is DV-26. See constitution
Principles I and IX.

## Scope Rule
HMPPS only — no VEP projection, sender, schema, stub, key or flag (H5). The service does **not**
read the cutover flag `ProbationResultsDistributionService` (Q15): the producer and the legacy
triggers do, and nothing here is a second lever. Out of scope: the producer increment (built in
`cpp-context-results`), the LAA feed, the Results Store, SJP hearings, any APIM gateway change.

## Public Repository
This repository is **public**. Never commit internal hostnames, keys, connection strings, personal
data or production hearing data — environment-specific values are environment-variable placeholders.
The recorded parity corpus is **SIT payloads only** and is committed under `src/test/resources/corpus/`
(SIT holds no production data; decision 2026-10-19). PRD/PRP payloads are never committed.

## Deployment
- **CI/CD**: GitHub Actions → **ADO Pipeline 460** → ACR → deployed by **Flux** using the shared
  **`springboot-app`** Helm chart.
- **Secrets** come from **Azure Key Vault via the CSI driver, with workload identity** — the gateway
  subscription key, the Redis password, the datasource credentials, the App Insights connection
  string and the system user ids. No static key, no committed connection string, no secret in a Helm
  value or an environment default.
- Workload identity holds `AzureServiceBusDataReceiver` and, for submit-hearing,
  `AzureServiceBusDataSender` on `probation.requests`.
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
No active feature yet. The constitution (`.specify/memory/constitution.md`, v1.0.0) is ratified;
the first feature starts with `/speckit-specify`, and its plan (`specs/<NNN-slug>/plan.md`, with
`spec.md` and `tasks.md` alongside it) becomes the current context here. Its plan records the status
of pre-build gate G1.
<!-- SPECKIT END -->
