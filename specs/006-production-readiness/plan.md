# Implementation Plan: Production readiness and cutover inputs

**Branch**: `006-production-readiness` | **Date**: 2026-11-02 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/006-production-readiness/spec.md`

## Summary

This slice adds **nothing to the message path**. It turns five chapters of the design into
artefacts the owning team can run and check before, during and after cutover: the one-week SIT →
STE replay comparison (the pre-cutover proof); the alert catalogue and the dashboard/daily
reconciliation; the go/no-go evidence index for every row of design §9.2 and §9.5; the four
runbook procedures' service-side inputs (cutover, planned rollback, urgent rollback, roll-forward);
and the service-side decommissioning evidence. Every deliverable reads the existing V1/V2/V3 schema
(002/003/004) read-only — no new migration, no new metric, no new endpoint, no new operator action.
Three ambiguities the spec carried (the replay's injection mechanism and payload source, the
reconciliation's count source and day boundary, and the row-9 test call) were resolved in the
2026-11-02 clarification session and are now plain requirements, not open questions.

## Technical Context

**Language/Version**: Java 25 (constitution Technology Stack); no new runtime dependency.
**Primary Dependencies**: Spring Boot 4.1 (existing); 001-parity-kernel's comparator support
classes (`pipeline/support`: `JsonParity`, `ParityCase`, `RegisteredFieldDeviations`), extended in
place rather than duplicated; Jackson's `JsonNode` tree (Principle IV) for the STE/SIT bodies the
comparator reads. No YAML or config-file parser is added for the alert catalogue: it is a typed
Java registry (`List<AlertDefinition>` records), consistent with Principle IV's "everything this
service originates is typed Java records and enums" and avoiding a dependency this repo does not
already carry.
**Storage**: PostgreSQL 15, the existing three tables and one view (002's V1, 003's V2, 004's V3) —
read-only. No new table, column or migration. The go/no-go, reconciliation and decommissioning
evidence are `SELECT`-only queries over `outbound_message_latest`, `processed_request` and
`scheduled_job_status`; the replay comparison reads STE's own copies of the same tables plus the
SIT export (committed nowhere — held under corpus controls as inputs to a run, per Assumption 2).
**Testing**: JUnit 5 + Mockito + AssertJ (existing); Testcontainers PostgreSQL for every evidence
and reconciliation query's `*IT`, following 002's `SchemaVocabularyIT`/`OutboundBodySchemaIT`
pattern exactly; the replay comparator's own tests are synthetic-input unit tests needing neither
STE nor SIT (User Story 1's own Independent Test), consistent with 001's golden-file comparator
tests. No new test dependency.
**Target Platform**: Linux containers on AKS, the same image as 001–005; the evidence and
reconciliation queries run inside the deployed service (read-only, no new HTTP surface); the
replay comparison runs against STE specifically, pointed at its database and fed the week's SIT
export, by the owning team — not a CI-time check.
**Project Type**: Single Spring Boot service (message-driven pipeline; no web frontend) — Option 1
of the generic layout, realised as the package tree below. This slice adds no new top-level
runtime concern (no listener, no adapter, no port): everything it adds is either pure comparison
logic or a read-only query, both testable with plain Testcontainers/synthetic fixtures and no
Spring context where the existing convention already avoids one.
**Performance Goals**: None new. The existing per-message time budget (payload 60 s + switch 25 s +
send 130 s = 215 s < 240 s) is unchanged; this slice's queries are read-only and run outside the
message path, so they carry no latency budget of their own. The measurable outcomes are
completeness and hygiene (SC-006-001 to SC-006-011), not throughput.
**Constraints**: No new metric, log line, operator action, endpoint or property (explicit Out of
Scope); no change to what is sent to HMPPS (D10); zero input value (payload, body, personal name,
hostname, cloud resource name, environment label, production figure) in any report, evidence
output, catalogue entry or runbook input this slice adds (FR-006-012, FR-006-030, FR-006-050;
SC-006-003, SC-006-008); every service-side read-back the runbook inputs name MUST be an evidence
query or a recorded action output, pinned by a test (FR-006-046); the replay comparison commits no
SIT body or payload to this repository (Principle VII).
**Scale/Scope**: 7 user stories, 54 functional requirements (FR-006-001 to FR-006-053, with
FR-006-054 the P16 no-DV note), 11 measurable outcomes; one new package (`evidence`); extensions to
one existing package (`pipeline/support`); one new typed registry under `config`; four new
documentation artefacts (`doc/go-live/evidence-index.md`,
`doc/operations/cutover-rollback-runbook-inputs.md`, the alert catalogue's human-readable mirror,
and amendments to `doc/operations/body-partition-maintenance.md` where alert 9's rule changed); no
new Flyway migration.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Check | Verdict |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | This slice changes no ported rule, no redaction, no byte of what is sent (D10): it reads what 002–004 already store and compares it with SIT's already-logged bodies. The one-week SIT → STE replay is already named in the constitution's own Principle I text as "the run-time half of the same discipline" — this slice builds exactly that, not a new parity mechanism. No DV row is needed; nothing here is an intended behaviour difference. | PASS |
| II. Test-Driven Development | Every new class (the comparator's pairing/classing/report, each evidence query, the alert catalogue, the reconciliation query) gets its failing test first, per the existing convention; the Independent Test named in each user story is that red test. | PASS |
| III. Message-Contract First | No new endpoint, no change to the inbound schema or the outbound body. The replay's injection mechanism (publishing a share onto `probation.requests` in STE) is an owning-team operational activity using existing tooling, external to this repository (Assumption 1: "the replay adds only the pairing, the classes, the explanations and the report" — not the injection). This slice adds no operator action and so raises no O-1 question. | PASS |
| IV. Canonical JSON In, Typed Models Out | The replay comparator reads STE's `outbound_body` text and SIT's exported logged bodies as `JsonNode` trees, exactly as 001's comparator already does, canonicalised before comparison (Principle I harness). Everything this slice originates — `HearingComparison`, `ExplanationEntry`, `AlertDefinition`, every evidence row — is a typed Java record or enum with a bounded value set. | PASS |
| V. SOLID with Ports and Adapters | The comparator is pure: JSON trees and typed records in, a typed report out, no I/O, no clock, no randomness — testable with synthetic fixtures alone, like 001's golden suite. The evidence and reconciliation queries are read-only JDBC, with no new port needed, since they read existing tables through the existing `JdbcClient` pattern (002/003's own queries), not through `application`'s ports. | PASS |
| VI. Explicit Failure | Nothing this slice adds settles a delivery or writes an outcome — it only reads. Where a pre-run check fails (FR-006-008's checks (a)-(e)), the run does not start and the failed check is recorded, never swallowed. | PASS |
| VII. Privacy in Telemetry | FR-006-012, FR-006-030 and FR-006-050 are this slice's own restatement of this principle: no payload, body, personal name, hostname or production figure in any report, evidence output or runbook input; identifiers, classes, DV ids, counts and JSON paths only. The hygiene sweep (SC-006-003, SC-006-008) extends the existing `TelemetryPrivacyTest`/`LogStatementSweepTest` pattern to this slice's new outputs. | PASS |
| VIII. Estate Conventions | Gradle, the existing package root, Conventional Commits, no AI attribution — unchanged. The new `evidence` package gets its own `package-info.java`; `pipeline/support`'s existing one is amended, not replaced. | PASS |
| IX. Scope — the HMPPS Leg Only | No VEP path. No read of the cutover switch `ProbationResultsDistributionService`: the runbook inputs explicitly state the service reads no App Configuration (FR-006-045) and name the two switches that are not this service's. The results-side daily share count (FR-006-024) is obtained by the results team's own viewstore query, external to this service — this slice does not add an HTTP client to results' viewstore, which would itself be a new integration this slice's Out of Scope forbids. | PASS |

No violation requires an entry in Complexity Tracking.

**Known pre-existing drift, not this slice's to fix**: the constitution (version 2.0.1, last
amended 2026-10-21) predates design decisions H36–H39 and still describes the `outbound_body`
digest trigger (H39 removed it; see 002/003's 2026-11-02 catch-up work) and does not name
`scheduled_job_status` (H38). This plan is written against the constitution's *principles*, which
are unaffected by H36–H39; the Technology Stack section's prose is stale and due its own amendment,
separately from this feature.

**Staleness found and corrected in this plan, 2026-11-13 — H41, alert 6**: `spec.md` (Clarifications
and FR-006-014's table row) already carries H41 (2026-11-09): alert 6 is renamed "Gateway refusing",
its labels widen from `http="401"` to `http=~"401|4xx-other"` (403/404 page too, each as terminal a
credential/key/route failure as a 401), and its threshold status moves from `proposal` to `fixed` —
"no longer a proposal". `config/AlertCatalogue.java`, as it stands on `main` at the time this plan
was last amended, was written before H41 (2026-11-09) and still has alert 6 as `"Repeated 401s"`,
`http="401"` only, `PROPOSAL`. This is a code/spec gap, not a planning ambiguity: the Project
Structure entry for `AlertCatalogue.java` below and `contracts/evidence-and-catalogue-shapes.md` §3
now cross-reference H41 explicitly, so `/speckit.tasks` can add the task to bring the code in line.
No new alert count, no new series, no new table — alert 6's own data changes, nothing else in this
slice's scope.

**Post-Design re-check** (after Phase 1 — `data-model.md`, `contracts/`): unchanged, still PASS
across every principle. The design phase added no new table, port, endpoint, metric or log line
beyond what the Constitution Check above already accounted for; `evidence`, the extended
`pipeline/support`, and `config.AlertCatalogue` are exactly the read-only, typed-record shape
assumed before Phase 0. No new Complexity Tracking entry is needed.

## Project Structure

### Documentation (this feature)

```text
specs/006-production-readiness/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md         # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── evidence-and-catalogue-shapes.md  # record shapes this slice's outputs commit to
├── checklists/
│   └── requirements.md  # existing, from /speckit.specify
└── tasks.md              # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

This is a single Spring Boot service — Option 1 (single project) of the generic layout, realised as
the ports-and-adapters tree `design_rules.md` already fixes. This slice adds one new top-level
package (`evidence`), extends one existing package (`pipeline/support`) and adds one new class
under an existing package (`config`); it adds no listener, no adapter, no port, and no HTTP
controller.

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── pipeline/support/                    # EXTENDED (001's comparator lives here)
│   ├── HearingPairing.java              # NEW — pairs a SIT and an STE share by
│   │                                      (hearingId, hearingDay, sharedTime-as-instant);
│   │                                      FR-006-002, FR-006-003
│   ├── HearingComparisonClass.java      # NEW enum — match | registered-deviation(DV id) |
│   │                                      explained-difference | unexplained-difference |
│   │                                      unpaired(sit-only|ste-only|ambiguous) |
│   │                                      sit-body-unusable; FR-006-001, FR-006-005 to FR-006-007
│   ├── ExplanationEntry.java            # NEW record — role, reason, the paths or share it covers;
│   │                                      FR-006-006
│   ├── ReplayComparator.java            # NEW — classes one union of shares, applying
│   │                                      explanations, over 001's JsonParity/
│   │                                      RegisteredFieldDeviations; no I/O, synthetic-testable
│   └── ReplayReport.java                # NEW record — per-share classes, the verdict
│                                          (FR-006-011), carrying no input value (FR-006-012)
├── evidence/                            # NEW package — read-only queries over the existing store
│   ├── package-info.java                # depends on persistence's existing JdbcClient pattern
│   │                                      and domain only; nothing depends on it
│   ├── GoNoGoEvidenceIndex.java         # NEW — the §9.2/§9.5 row catalogue (closed rows carry
│   │                                      their design status; built rows carry their query)
│   ├── GoNoGoEvidenceQuery.java         # NEW — one query per build-supported row (1, 7, 9, 21,
│   │                                      …), each returning a typed, identifier-only result
│   ├── DailyReconciliationQuery.java    # NEW — per-day counts from outbound_message_latest
│   │                                      (FR-006-023), never processed_request.status
│   └── DecommissioningEvidenceQuery.java # NEW — §9.6 row 1's post-soak evidence (FR-006-047)
└── config/
    ├── AlertDefinition.java             # NEW record — series, labels, condition, threshold
    │                                      status, rule type, routing, runbook entry, lapse
    └── AlertCatalogue.java              # NEW — the closed list of 11 paging alerts and 5 counted
                                           classes (FR-006-013 to FR-006-030); the registry
                                           SC-006-005's "no dead alerts" test reads. Alert 6's own
                                           data (name, labels, threshold status) is H41's
                                           (2026-11-09): "Gateway refusing",
                                           http=~"401|4xx-other", fixed — not a proposal

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── pipeline/support/
│   ├── ReplayComparatorTest.java        # synthetic: one example of every class (US1 Independent
│   │                                      Test); needs neither STE nor SIT
│   └── ReplayReportPrivacyTest.java     # sentinel-value sweep (SC-006-003)
├── evidence/
│   ├── GoNoGoEvidenceQueryIT.java       # Testcontainers Postgres, seeded per row (US2
│   │                                      Independent Test)
│   ├── DailyReconciliationQueryIT.java # seeded day of mixed outcomes (US5 Independent Test,
│   │                                      SC-006-009)
│   └── DecommissioningEvidenceQueryIT.java
└── config/
    └── AlertCatalogueCoverageIT.java    # every named series/label exists on the real registry
                                           (SC-006-005); every rule's condition evaluated at and
                                           around its threshold (US4 Independent Test)

doc/
├── go-live/
│   └── evidence-index.md                # human-readable mirror of GoNoGoEvidenceIndex: every
│                                          row of §9.2 and §9.5, its evidence, its supplier
├── operations/
│   ├── cutover-rollback-runbook-inputs.md # NEW — the four procedures, step by step, actor,
│   │                                        action, read-back, named placeholders only
│   │                                        (FR-006-037 to FR-006-046)
│   └── body-partition-maintenance.md    # AMENDED — alert 9's condition already changed under
│                                           H37/H38 (003's work); this slice cross-references it
│                                           from the alert catalogue, not a behaviour change
└── DEVIATIONS.md                        # unchanged — this slice registers no new DV
```

**Structure Decision**: single-project ports-and-adapters, unchanged from slices 001–005. The one
new package, `evidence`, holds every read-only query this slice adds, kept separate from
`persistence` (which owns the write paths and the repositories 002–004 already built) so a reviewer
can see at a glance that nothing under `evidence` ever issues an `UPDATE` or `INSERT`. The replay
comparator extends `pipeline/support` because it *is* 001's comparator, carried forward — a new
package here would split one concern (SIT ↔ STE/legacy comparison) across two places for no reason.
`AlertDefinition`/`AlertCatalogue` sit in `config` because they are static, typed metadata about the
service's own observability surface, the same shape of thing `MetricRegistry` and the startup
validator already are there.

## Complexity Tracking

*No entries — the Constitution Check above has no unjustified violation.*

<!-- token-budget: compacted (level=medium) on 2026-11-13T14:02:15Z; original at plan.full.md -->
