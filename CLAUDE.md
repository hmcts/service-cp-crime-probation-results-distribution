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
| **Design (authoritative)** | Confluence — [Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service) (CRA space), rev 3.6 (decisions H1–H34) and H35 (2026-10-21). This repo carries **no** design narrative: do not create `doc/*_DESIGN.md`, `SOLUTION_BRIEF.md` or any other rationale document here |
| Design digest | `doc/design/DIGEST.md` — the rules of rev 3.6 and H35 for spec authors and implementers, each tagged with its design row id; rules only, no rationale. The design wins where they differ |
| Constitution | `.specify/memory/constitution.md` |
| Contracts | `doc/API_CONTRACTS.md` |
| Deviation register | `doc/DEVIATIONS.md` — seeded from design §7.2 (DV-01 … DV-26; next free DV-27); every intended difference from the legacy with its pinning test, plus the carried and pending-external defects |
| Specifications | `specs/001-parity-kernel`, `specs/002-hmpps-distribution`, `specs/003-body-retention`, `specs/004-recovery`, `specs/005-operations-api`, `specs/006-production-readiness` — each spec, plan, research, data model, tasks and quickstart; see Setup below for each slice's own status |
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
All six features are specified (design rev 3.6 + H35, constitution v2.0.1). **This branch,
`002-hmpps-distribution` (PR #8), is now the one common integration branch for every slice**: all
further work on any spec, for any slice, lands here, rather than spreading across per-slice
branches — open one long-lived PR, not one per slice. `001-parity-kernel`, `002-hmpps-distribution`
and `003-body-retention` are implemented here in full (Flyway V1/V2, enums, state machine, ports,
metric registry, the redaction pipeline, the ported rules, the body-maintenance job) — every task in
each of their `tasks.md` is checked. `001-parity-kernel`'s own corpus is still **not** a satisfied
go-live evidence gate (H21, FR-001-017): the committed corpus is synthetic-only, holding only the
`CACHE` form, pending the owning team's SIT capture (`doc/DEVIATIONS.md` "Corpus status"; an external
dependency, not a code gap). Both `[NEEDS CLARIFICATION]` markers (FR-001-020, FR-001-021) are
resolved (spec.md Clarifications).

`004-recovery` (`specs/004-recovery/plan.md`, with `research.md`, `data-model.md`, `quickstart.md`,
`contracts/` and `tasks.md` alongside it) is implemented: all three confirmed decisions (FR-004-024,
FR-004-049, FR-004-052) landed, replay is wired through a production `ReplayEnqueuer` in both
composition roots (T078), and 74 of its 79 tasks are checked. Five remain open, none of them a
guard/runner/port gap: T037 (the `pause-intake`/`rollback-close-in-flight`/`resume-intake` halves of
the quickstart's step 6 are proven live against a running instance 2026-11-02, but `purge-queue`
itself stays open — the installed Service Bus SDK has no native cutoff-delete operation, a dependency
gap recorded 2026-10-27, not an application bug); T053 (walking the DLQ runbook with the owning team
— a live session, not a build step, pending T052's 2026-10-30 landing); T075 (run live end to end
2026-11-02: every other quickstart step, including replay, resend-leg, forced resend and
submit-hearing, matches `quickstart.md` exactly); T076 (re-checked 2026-11-02: no owning-team change
has overturned any of the three confirmed decisions since 2026-10-26); and this paragraph (T077).

A staleness surfaced 2026-11-13: H40 (`close-expired-resend`, FR-004-059/060, decided 2026-11-09)
was fully specified in `spec.md` — its own Clarifications entries, the FR pair and its
`doc/design/DIGEST.md` row — but was never carried into this slice's Phase 0/1 artifacts and had no
task in `tasks.md` at all, so it was never implemented. `plan.md` (Summary, Technical Context
Scale/Scope, Constitution Check, Project Structure), `research.md` (Decision 1's amendment) and
`contracts/cli-runners.md` §9 and `quickstart.md` step 9 (appended, not inserted mid-list, so the
section/step numbers this slice's own completed-task records already cite — T037's "step 6", T071's
"step 7", `cli-runners.md` §1/§2/§3/§4/§5/§6/§7/§8 — stay correct) now carry it as a seventh runner,
`CloseExpiredResendAction`/`CloseExpiredResendRunner`. No new port, table or migration is needed — it
reuses slice 002's `note`/`claim_token`/`audit_note` columns and the request-row-then-leg-row lock
order already established for rollback close-in-flight. `/speckit-tasks` (2026-11-13) added the
missing work as T050a–T050g in Phase 6 (US3), letter-suffixed off T050 so no existing task
renumbers; it also found FR-004-060 (shutdown waits for a resend-leg run in progress) is not yet
wired to the shared `inFlightRuns` drain counter `inbound/ConsumerLifecycleController` already uses
for queue deliveries — T050f closes that.

`/speckit-implement` (2026-11-13) built T050a–T050f: `application/CloseExpiredResendAction.java`,
`application/CloseExpiredResendOutcome.java`, `cli/CloseExpiredResendRunner.java`,
`persistence/{CloseExpiredResendRepository,JdbcCloseExpiredResendRepository}.java` (new), and
`api/ResendLegExecutor.java`/`config/OperationsConfiguration.java`/`config/OperatorCommandLine.java`
(modified — the shared-counter wiring and the new runner's registration). The mandatory
code-reviewer/qa/spec-validator gate found code-reviewer PASS (0 High/Medium; 2 Low advisories, not
fixed — the request-row lock is scoped by `request_id` alone rather than `(source, request_id)`,
safe today since every row carries `source = RESULTS`) and spec-validator COMPLIANT, but qa FAIL on
two coverage gaps: no test at all for the runner's own argument validation, and the note merge's
"replacing any earlier `prev=`" branch was never exercised (every seeded test row had a `null`
note). Both fixed test-first in the same pass — `cli/CloseExpiredResendRunnerTest.java` (new, 9
cases) and a new `CloseExpiredResendIT` case seeding a prior `prev=`/`override=` note and asserting
the merge — `./gradlew build` green after each. T050g (the manual quickstart run against a live
Service Bus emulator) stays open; no live local stack was available this pass. **86 tasks: 79 of 86
checked (74 original + T050a–T050f); T050g and the 5 pre-existing opens (T037, T053) remain.**

`005-operations-api` (`specs/005-operations-api/plan.md`, with `research.md`, `data-model.md`,
`quickstart.md` and `contracts/` alongside it) is **implemented in full — all 52 tasks checked** —
over 004's real `ReplayAction` and `LegResender`; both `[NEEDS CLARIFICATION]` markers are resolved
(resend-leg answers `202` accepted-run; the caller group defaults to `Second Line Support` pending
H31's confirmation). The two audit defects (T051, T052) its own manual quickstart run exposed — the
transport-off silent-503 bug and the library audit filter's double-publish — are fixed and pinned
(2026-10-30), and both are now re-confirmed live, not just by the automated suite: a 2026-11-02 run
with the audit transport off got the documented WARN and normal `200`/`202` answers, never the old
`503`; a second run with a real Artemis broker (after freeing Docker's VM disk, which had been
stalling real brokers — see below) put exactly two bounded audit events per call, neither carrying
the caller's operator, reason or the request body. The `[BLOCKED-ON-004]` tags once attached to
US2/US3/US7 are gone: 004's T078 wired the real `ReplayAction`/`LegResender` into both composition
roots, and the same live runs confirmed replay, resend-leg and forced resend all work over real
HTTP against the real services, with the exact `202`/`200` shapes `quickstart.md` documents, the
`401` with no `CJSCPPUID`, and the US7 HTTP/CLI parity check. **Nothing left open in 005.**
`006-production-readiness` (`specs/006-production-readiness/plan.md`, with `research.md`,
`data-model.md`, `quickstart.md`, `contracts/` and `tasks.md` alongside it): 43 of its original 43
tasks are checked. All 3 `[NEEDS CLARIFICATION]` markers were resolved 2026-11-02
before planning (the replay publishes directly onto `probation.requests`, bypassing results, with
`EXT_` payloads loaded from the SIT export into STE's Redis; the daily reconciliation's
results-side count comes from a results viewstore query on the UTC calendar day; the row-9
reachability-proof call is a real hearing via `submit-hearing`, with HMPPS's agreement that the
duplicate is acceptable). This slice adds nothing to the message path — no new migration, no new
endpoint, no new metric — only a replay comparator extending 001's `pipeline/support`
(`ReplayComparator`/`ReplayReport`, over `HearingPairing`/`HearingComparisonClass`/
`ExplanationEntry`/`PreRunChecks`), a new read-only `evidence` package (`GoNoGoEvidenceQuery`/
`GoNoGoEvidenceIndex`, `DailyReconciliationQuery`, `DecommissioningEvidenceQuery`,
`LegacyBothPathsCheck`), and a typed `config.AlertCatalogue` (no new dependency). 001's own
`JsonParity`/`RegisteredFieldDeviations` moved from `src/test` to `src/main` in the same package
(2026-11-02) so `ReplayComparator` — production code the owning team runs against real STE data,
not a JUnit fixture — can depend on them; no import changed. A real staleness was found and fixed
while implementing User Story 3: FR-006-040 said rollback step 4 had no supported execution path,
but 004 had already built and wired `rollback-close-in-flight` (commits 33787cf/f1e4c1f) before
this phase started — the spec, research notes, tasks and runbook doc are corrected to name the
real runner. One governance item surfaced while planning, not this slice's to fix: the
constitution (version 2.0.1, last amended 2026-10-21) predates H36–H39 and still describes the
`outbound_body` digest trigger H39 removed and omits `scheduled_job_status` (H38) — due its own
amendment separately from any feature, and not closed by this slice.

A second staleness surfaced 2026-11-13: H41 (decided 2026-11-09, after this slice's own planning
and User Story 4 work) amends `spec.md`'s acceptance scenario 4 — alert 6 pages on a 403 or 404
from the gateway, not only a 401, and is renamed "Gateway refusing" with its threshold status fixed
rather than proposed — but `config/AlertCatalogue.java`'s alert 6 entry and its coverage test
(`AlertCatalogueCoverageIT.repeated_401s_alert_is_a_proposal`) were never updated. `/speckit-plan`
(2026-11-13) flagged the gap in `plan.md`, `research.md` (Decision 3's amendment) and
`contracts/evidence-and-catalogue-shapes.md` §3; `/speckit-tasks` (2026-11-13) added the fix as
T027a (rewrite the stale test to assert H41's name/labels/status) and T027b (update the entry) in
Phase 6, letter-suffixed off T027 so no existing task renumbers. `/speckit-implement` (2026-11-13)
built both test-first (RED: `AssertionFailedError: expected: "Gateway refusing" but was: "Repeated
401s"`, never a compile error; GREEN after the entry was corrected): alert 6 is now name
`"Gateway refusing"`, labels `reason="receiver-rejected"`/`http="401|4xx-other"`, status `FIXED`.
`doc/operations/alert-catalogue.md` (T027's own human-readable mirror) already carried the correct
H41 text — only the Java code was stale. A scoped review (correctness against spec.md, test
quality, no regression on the other ten alerts, PMD/Checkstyle, scope discipline) found nothing
requiring action. `./gradlew build` green. **45 tasks, all 45 checked.**

**Environment note**: this branch's local Docker Desktop VM disk was found at 95% capacity
(unused build cache and dangling images from unrelated projects sharing the daemon), which makes a
real or Testcontainers-managed Artemis broker silently block producers rather than error — the
likely cause of an unrelated `OperationsAuditTransportIT` hang found mid-session. Freed with
`docker buildx prune -f` (8.376 GB) and `docker image prune -f` (154.4 MB), down to 44% used. If a
broker-based test or manual run stalls with no error again, check `docker run --rm alpine df -h /`
before assuming a code regression.

**The Spec Kit scripts resolve the active feature from the current branch name by default**
(`specs/<NNN>-*` matching the branch's numeric prefix), which only resolves correctly for
`002-hmpps-distribution` itself while everything lands on this one branch. Before running
`/speckit-clarify`, `/speckit-plan`, `/speckit-tasks`, `/speckit-analyze` or `/speckit-implement` for
any other feature (e.g. `004-recovery`, `005-operations-api`), export
`SPECIFY_FEATURE=<NNN-slug>` first so the scripts resolve that feature's own `specs/<NNN-slug>/`
folder instead of this branch's. `/speckit-clarify` resolves any remaining `[NEEDS CLARIFICATION]`
(answers come from the design owner). The plan (`specs/<NNN-slug>/plan.md`, with `spec.md` and
`tasks.md` alongside it) then becomes the current context here. Specs are written against
`doc/design/DIGEST.md` and the design it cites.
<!-- SPECKIT END -->

<!-- BEGIN token-budget compact-backups -->

## Token Budget — backup guard

Files ending in `.full.md` inside `specs/` and `.specify/memory/`
(e.g. `spec.full.md`, `plan.full.md`) are pre-compaction backups created
by `/speckit.token-budget.compact`. **Do not read them.** They contain the
full uncompacted content; loading them cancels the token savings compaction
achieved. To revert an artifact to its original state, run
`/speckit.token-budget.restore` instead.

<!-- END token-budget compact-backups -->
