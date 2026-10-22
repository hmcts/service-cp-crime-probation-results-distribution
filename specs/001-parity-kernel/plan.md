# Implementation Plan: Parity kernel — the ported HMPPS rules, the SIT corpus, the comparator and the deviation register

**Branch**: `001-parity-kernel` | **Date**: 2026-10-21 | **Spec**: `specs/001-parity-kernel/spec.md`
**Input**: Feature specification from `specs/001-parity-kernel/spec.md`

## Summary

Port the legacy HMPPS redaction rules (`PF/functions.js` at `cpp-context-azure-legalaidagency@122e2152`)
to one pure Java function, `HmppsRedaction.redact(JsonNode, EffectiveSwitchState) ->
RedactionResult`, verbatim and in the legacy's order (R0, R1, R3–R11; no R2; R12 deliberately not
ported, DV-09). Prove it matches the oracle with: the fifteen legacy Jest cases ported as JUnit
twins over byte-identical fixtures; a SIT corpus (both payload forms, both switch states) compared
by a reused semantic-JSON comparator; and `doc/DEVIATIONS.md`, seeded from design §7.2, as the one
register of every intentional difference. Nothing in this slice performs I/O — no queue, cache,
database, HTTP or Spring context. The two `[NEEDS CLARIFICATION]` markers (FR-001-020, FR-001-021)
were resolved during planning (both Option B — no `INACTIVE` expectation for that form/share; held
to the `ACTIVE` expectation and what is derived from it); see research.md R5.

## Technical Context

**Language/Version**: Java 25 (already the repo's toolchain; this slice adds no new language
feature dependency beyond records and sealed interfaces).
**Primary Dependencies**: None new at `main` scope — `HmppsRedaction` and its rule methods use only
`tools.jackson.databind.JsonNode` (already on the classpath via Spring Boot's Jackson 3 BOM) and the
JDK. At `test` scope: JUnit Jupiter + AssertJ (already present); four files and one resource copied
from `service-cp-crime-informant-register`'s parity pack (`JsonParity`, `JsonParityTest`,
`RegisteredFieldDeviations`, `ComparatorContractTest`, `comparator-vectors/vectors.json`) per
research.md R2; no new Gradle dependency is required for the copy (same Jackson 3 tree this repo
already uses).
**Storage**: N/A. No Flyway migration, no database — `processed_request`/`outbound_message`/
`outbound_body` belong to slice 002 (design §4.4).
**Testing**: JUnit Jupiter + AssertJ, golden-file/corpus comparison via the reused `JsonParity`
comparator; no Testcontainers, no WireMock — this slice has no adapter to stub.
**Target Platform**: JVM, same service/module as the rest of `service-cp-crime-probation-results-distribution`; no new deployable unit.
**Project Type**: Single Spring Boot service (existing project), but this slice contributes
framework-free library code only — no `@Component`, no Spring context required to test it
(constitution Principle V: "the redaction... MUST be testable with golden files alone").
**Performance Goals**: None stated or needed — a pure in-memory JSON-tree transform with no I/O has
no latency budget of its own; the service-wide send budget (design §4.7) is slice 002's concern.
**Constraints**: No I/O, no clock, no randomness, no log line, no mutation of the caller's input
(FR-001-001, FR-001-002); numbers compared at exact decimal value, no tolerance (FR-001-013); no
corpus value (payload, expectation or result) ever appears in a log line, test report, failure
message or document (FR-001-015, FR-001-031, constitution Principle VII); no production (PRD/PRP)
data committed to this public repository (FR-001-028).
**Scale/Scope**: 15 legacy-case twins over 9 fixture files; a SIT/synthetic corpus whose size the
design deliberately leaves open (§6.4 deferred to D8, closed by H21 with no number) — this spec sets
minimum required coverage (9 named blind-spot cases + case 13, FR-001-024) and the corpus itself
records its own counts (FR-001-029).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — no changes to this
table were needed; the design that follows does not touch any port, adapter, message, or persisted
row.*

| Principle | Status | Note |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | **PASS — this slice's purpose** | `HmppsRedaction` is the ported oracle; the corpus/comparator/register are exactly its proof mechanism. |
| II. Test-Driven Development | **PASS, gated at task level** | Every rule and every corpus case needs a failing test landed first; `tasks.md` must order test tasks before their implementation tasks (constitution: non-negotiable). |
| III. Message-Contract First | **N/A** | This slice touches neither the inbound schema nor the outbound body/HMPPS send — no contract surface changes. |
| IV. Canonical JSON In, Typed Models Out | **PASS** | The payload stays `JsonNode` end to end; `HmppsRedaction` deep-copies once on entry (FR-001-002) and never binds to a typed model; the HMPPS body stays the redacted tree, not a DTO. |
| V. SOLID with Ports and Adapters | **PASS, trivially** | No port or adapter is introduced in this slice — `HmppsRedaction` is core logic with zero I/O, which is the simplest possible instance of "the redaction MUST be testable with golden files alone." |
| VI. Explicit Failure | **PASS** | A rule that would throw in the legacy throws inside the port too (via `RuleFailureSignal`, research.md R1) and is reported as `RulesFailure(ruleId)` — never silently absorbed, never a partial body. |
| VII. Privacy in Telemetry | **PASS, by construction** | No log line is written by the redaction at all (not even DEBUG); corpus content is confined to `src/test/resources/{fixtures,corpus}/`; SIT data only, no PRD/PRP (FR-001-028). |
| VIII. Estate Conventions | **PASS** | Gradle, no wildcard imports, package root `uk.gov.hmcts.cp.probationresultsdistribution`, Conventional Commits, no AI attribution. |
| IX. Scope — HMPPS Leg Only | **N/A for this slice specifically** | The redaction has only ever had one leg's rules to port (HMPPS); VEP is out of scope by construction (H5) and this slice builds nothing VEP-shaped. |

No violations; **no Complexity Tracking entries are required.**

## Project Structure

### Documentation (this feature)

```text
specs/001-parity-kernel/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
└── tasks.md              # Phase 2 output (/speckit-tasks — not created by this command)
```
No `contracts/` directory: this slice exposes no external interface (no REST endpoint, no message
schema, no new operations-API surface). Its only "contract" is the Java API surface documented in
`data-model.md` (`HmppsRedaction`, `RedactionResult`, the reused `JsonParity`), which is internal to
this repository and versioned with it rather than with a separate schema artefact.

### Source Code (repository root)

This repository is a single Spring Boot service (not a multi-package monorepo); this slice adds to
the existing package tree defined in `.claude/rules/design_rules.md` and does not introduce new
top-level packages.

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── domain/
│   ├── EffectiveSwitchState.java          # enum: ACTIVE, INACTIVE
│   └── RedactionResult.java               # sealed: Body, EmptiedByR1, EmptiedByR11, RulesFailure
└── pipeline/
    ├── HmppsRedaction.java                # the pure function; orchestrates the rule chain
    ├── RuleFailureSignal.java             # internal unchecked exception, caught only here
    └── (package-private rule methods live on HmppsRedaction or a small set of
         sibling classes grouped by the legacy's own function boundaries — one
         class per functions.js function, per research.md R4)

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/
├── HmppsRedactionLegacyTwinTest.java       # T1–T15, one @Nested or @ParameterizedTest per case
├── HmppsRedactionCorpusParityIT.java       # name proposed; the full corpus/comparator harness
├── CorpusCustodyTest.java                 # digest + provenance checks (FR-001-030, User Story 3)
├── DeviationRegisterTest.java             # FR-001-033..037: register shape, DV-25 derivation
└── support/
    ├── JsonParity.java                    # copied from service-cp-crime-informant-register
    ├── JsonParityTest.java                # copied
    ├── RegisteredFieldDeviations.java     # copied, hearingStartTime entry replaced by DV-25
    ├── ComparatorContractTest.java        # copied, binding point adjusted
    ├── ParityCase.java                    # re-authored for this service's two-form/two-state shape
    ├── ParityAssertions.java              # new: case/form/switch-state label wrapper over JsonParity (data-model.md)
    ├── CorpusCustodyChecker.java          # new: digest/provenance/orphan-file checks (US3)
    └── CanonicalJsonMapper.java           # Jackson 3 ObjectMapper, BigDecimal numerics (research.md R3)

# This tree is representative of the main packages, not an exhaustive test-class listing —
# see tasks.md for the full, authoritative file-per-task breakdown (test classes such as
# HmppsRedactionPurityTest, WholeCorpusChecksTest, DeviationRegisterTest, etc.).

src/test/resources/
├── fixtures/                              # the 9 legacy Jest fixtures, byte-identical copies
│   ├── hearing.sjp.cases.resulted.in.sjp.session.json
│   ├── box.hearing.json
│   ├── hearing.prosection.case.with.laa.reference.json
│   ├── hearing.only.court.application.json
│   ├── hearing.multiple.court.applications.courtorder.json
│   ├── hearing.prosection.case.with.youth.defendant.json
│   ├── hearing.prosection.case.with.all.youth.defendant.json
│   ├── hearing.single.prosection.case.with.all.youth.defendant.json
│   └── hearing.only.court.application.with.youth.defendant.json
├── comparator-vectors/
│   └── vectors.json                       # copied, 57 entries, none removed or altered
└── corpus/
    ├── index.json                         # corpus-wide metadata (FR-001-029, CorpusMetadata)
    └── <caseId>/
        ├── meta.json                      # provenance + digests (FR-001-029)
        ├── inputs/{cache.json, fallback.json}
        └── expectations/{active.json, inactive.json}  # or a no-message marker

doc/
└── DEVIATIONS.md                          # new; seeded DV-01..DV-26 (FR-001-033), next free DV-27
```

**Structure Decision**: single-project layout (the existing service), extending the `domain/` and
`pipeline/` packages `design_rules.md` already reserves for exactly this content ("`pipeline/`
HmppsRedaction (pure) and the JSON / JavaScript-truthiness shims"). No `tests/contract/` or
`tests/integration/` split is used — this repo's convention is `src/test/java` with `*Test`/`*IT`
suffixes (technical-rules.md Naming Conventions), which this plan follows rather than introducing a
parallel test-layout convention.

## Phase 0: Research

See `research.md`. Five questions resolved: reproducing the legacy's "read of nothing throws"
semantics in Jackson without `path()`'s forgiving behaviour (R1); the exact reuse mechanism for the
informant-register parity pack — copy, not a shared library, with `ParityCase` re-authored for this
service's domain (R2); the BigDecimal-numerics `ObjectMapper` needed for exact comparator behaviour
(R3); a 1:1 rule-to-function mapping against the legacy's own boundaries (R4); and confirmation that
FR-001-020/FR-001-021 stay open without blocking this design (R5).

## Phase 1: Design & Contracts

See `data-model.md` for the full entity set (`EffectiveSwitchState`, `RedactionResult`,
`LegacyCaseTwin`, `CorpusCase`, `Expectation`, the reused comparator types, `DeviationRow`,
`CorpusMetadata`) and `quickstart.md` for how a developer runs and extends this slice's tests.

No `contracts/` artefact: this slice has no external interface (see Project Structure above).

**Agent context update**: `CLAUDE.md`'s `<!-- SPECKIT START -->`/`<!-- SPECKIT END -->` block is
updated to point at this plan as the active context for `001-parity-kernel` (see repository root
`CLAUDE.md`, Setup section).

## Complexity Tracking

*Not applicable — the Constitution Check above found no violations to justify.*
