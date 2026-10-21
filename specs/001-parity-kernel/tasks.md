# Tasks: Parity kernel — the ported HMPPS rules, the SIT corpus, the comparator and the deviation register

**Input**: Design documents from `specs/001-parity-kernel/` (`plan.md`, `spec.md`, `research.md`, `data-model.md`, `quickstart.md`)
**Prerequisites**: `plan.md`, `spec.md` (required); `research.md`, `data-model.md`, `quickstart.md` present; no `contracts/` (this slice has no external interface)

**Tests**: Mandatory throughout (constitution Principle II). Every test task is ordered before the
implementation task it guards. A test task lands the compile-safe seam (a stub type/method that
compiles but fails the assertion) so the recorded red run is a failing assertion, never a compile
error, per this repo's TDD convention (`CLAUDE.md`, Repository Conventions).

**Organization**: Grouped by user story (US1–US5) from `spec.md`, all P1 except US5 (P2).

**Note on corpus content**: this slice's own required coverage is the fixed blind-spot set
(cases 1, 2, 3, 4, 5, 8, 9, 11, 12) plus case 13, which `spec.md`'s Assumptions says may be
synthetic where SIT lacks the shape. Real SIT hearing payloads require the owning team's capture
within SIT's retention window (`spec.md` Dependencies, External) — an out-of-repository activity,
not a coding task. Tasks below build every blind-spot case as **synthetic**, each flagged for later
replacement with a real SIT case if one becomes available before this slice merges; this does not
block the build gate (`spec.md` Assumptions, "corpus size... the design sets none").

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no unmet dependency)
- **[Story]**: US1–US5, mapping to `spec.md`'s user stories

## Path Conventions

Single project (existing service). Production code under
`src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/{domain,pipeline}/`; tests under the
mirrored `src/test/java/.../{domain,pipeline}/` tree; fixtures and corpus under
`src/test/resources/{fixtures,corpus,comparator-vectors}/`; the register at `doc/DEVIATIONS.md`.

---

## Phase 1: Setup

**Purpose**: Bring in the two external inputs this slice ports/reuses, unmodified, before any new
code references them.

- [X] T001 [P] Copy the 9 legacy Jest fixtures byte-identical from
  `cpp-context-azure-legalaidagency@122e2152:azure-functions/durable-functions/ProbationHearingResultedFilter/test/`
  into `src/test/resources/fixtures/`: `hearing.sjp.cases.resulted.in.sjp.session.json`,
  `box.hearing.json`, `hearing.prosection.case.with.laa.reference.json`,
  `hearing.only.court.application.json`, `hearing.multiple.court.applications.courtorder.json`,
  `hearing.prosection.case.with.youth.defendant.json`,
  `hearing.prosection.case.with.all.youth.defendant.json`,
  `hearing.single.prosection.case.with.all.youth.defendant.json`,
  `hearing.only.court.application.with.youth.defendant.json` (FR-001-011)
- [X] T002 [P] Copy `JsonParity.java`, `JsonParityTest.java`, `RegisteredFieldDeviations.java`,
  `ComparatorContractTest.java` from
  `service-cp-crime-informant-register:src/test/java/uk/gov/hmcts/cp/informantregister/support/`
  into `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/` (package
  declaration changed only; remove `RegisteredFieldDeviations`'s `hearingStartTime` entry — not
  this service's domain), and `comparator-vectors/vectors.json` (57 entries, unmodified) into
  `src/test/resources/comparator-vectors/vectors.json` (FR-001-014, research.md R2)

**Checkpoint**: the copied pack compiles in this repo's package; `ComparatorContractTest` and
`JsonParityTest` run (not yet asserted against this service's domain — that's US2).

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Shared types and infrastructure every user story phase below needs. No user story
implementation starts before this phase is green.

- [X] T003 Create `doc/DEVIATIONS.md` seeded from `doc/design/DIGEST.md` §4 and design §7.2: one
  row for each of DV-01 … DV-26 (withdrawn DV-01/02/03/15, unallocated DV-11/12, reserved
  DV-18…DV-22, active rows included), `DV-27` named as the next free id, and entries for the carried
  defects (P17, P18, P19) and pending-external defects (P07, P21, P35, P36) per FR-001-035. Update
  the pointer in `README.md`'s artefact table (currently "not present yet") (FR-001-033, FR-001-034)
- [X] T004 [P] Write `CanonicalJsonMapperTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/CanonicalJsonMapperTest.java`:
  asserts `1`, `1.0` and `1.00` parse to numerically-equal `BigDecimal` nodes (red: the mapper class
  does not exist yet — a compile-safe stub throwing `UnsupportedOperationException` lands with this
  task) (research.md R3)
  - RED: `CanonicalJsonMapper > parses 1, 1.0 and 1.00 ... FAILED` — `java.lang.UnsupportedOperationException: CanonicalJsonMapper.create() lands in T006`
- [X] T005 [P] Write `DomainTypesTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/domain/DomainTypesTest.java`:
  asserts `EffectiveSwitchState` has exactly `ACTIVE`/`INACTIVE`, and each `RedactionResult` variant
  (`Body`, `EmptiedByR1`, `EmptiedByR11`, `RulesFailure`) constructs and exposes its field(s) (red:
  compile-safe stub enum/sealed interface with no working accessors lands with this task)
  (data-model.md)
  - RED: `EffectiveSwitchState > has exactly ACTIVE and INACTIVE FAILED` (STUB_PENDING present); `RulesFailure exposes its rule id` expected `"R10"` but was `null`
- [X] T006 [P] Implement `CanonicalJsonMapper` (Jackson 3 `ObjectMapper`, `USE_BIG_DECIMAL_FOR_FLOATS`
  equivalent enabled) in
  `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/CanonicalJsonMapper.java`
  — makes T004 green
  - GREEN: `CanonicalJsonMapper > parses 1, 1.0 and 1.00 ... PASSED`
- [X] T007 [P] Implement `EffectiveSwitchState`
  (`src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/domain/EffectiveSwitchState.java`),
  `RedactionResult`
  (`src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/domain/RedactionResult.java`) and
  `RuleFailureSignal`
  (`src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/RuleFailureSignal.java`) —
  makes T005 green
  - GREEN: all four `domain types` assertions PASSED

**Checkpoint**: domain types and the canonical-JSON test utility exist and are green. User story
phases can now proceed.

---

## Phase 3: User Story 1 - The ported HMPPS rules produce the legacy's output, and nothing else (Priority: P1) 🎯 MVP

**Goal**: One pure function, `HmppsRedaction.redact(JsonNode, EffectiveSwitchState)`, that
reproduces every legacy rule R0, R1, R3–R11 verbatim, including the carried defects.

**Independent Test**: run the 15 legacy-case twins over their byte-identical fixtures (T001). No
corpus, no comparator vectors, no other story.

### Tests for User Story 1 (write and run first; record the failing assertion) ⚠️

- [X] T008 [P] [US1] Write twin tests for T1 (SJP fixture) and T2 (box-hearing fixture) in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/HmppsRedactionLegacyTwinTest.java`:
  asserts `redact(...)` returns `RedactionResult.EmptiedByR1` for both (FR-001-011)
  - RED: `T1 ... emptied by R1 FAILED`, `T2 ... emptied by R1 FAILED` (stub returned Body)
- [X] T009 [P] [US1] Add twin tests for T3, T4, T5 (`laaApplnReference` removed from
  prosecution-case, application and court-order offences) to the same test class (FR-001-011)
  - RED: `T3/T4/T5 ... FAILED` (laaApplnReference still present)
- [X] T010 [P] [US1] Add twin tests for T6–T12 (youth rules under both switch states, including
  `EmptiedByR11` for T10 and T12) to the same test class (FR-001-011)
  - RED: `T7/T8/T10/T12 ... FAILED` (R3/R4/R11 not yet implemented); T6/T9/T11 already PASSED (INACTIVE)
- [X] T011 [P] [US1] Add twin tests for T13, T14 (vacuous per P34 — assert absence of members the
  fixture never had) and T15 (`delegatedPowers`, `associatedPersons`, `hearing.judiciary` all gone)
  to the same test class (FR-001-011, FR-001-012)
  - RED: `T15 ... FAILED` (R8/R9/R10 not yet implemented); T13/T14 PASSED (vacuous)
- [ ] T012 [US1] Write `HmppsRedactionPurityTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/HmppsRedactionPurityTest.java`:
  for every corpus-independent fixture, the caller's input is unchanged after `redact(...)`, no log
  line is written, and a second call returns an equal result — this is DV-09's pinning test named in
  `doc/DEVIATIONS.md` (FR-001-001, FR-001-036, SC-001-012)
- [ ] T013 [US1] Write `HmppsRedactionRulesFailureTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/HmppsRedactionRulesFailureTest.java`:
  asserts `RulesFailure(ruleId)` for each carried null-guard site (P19 — missing `defendants`,
  `offences`, `courtOrderOffences`/`offence`, `judicialResults`) and for a court application with no
  `masterDefendant` under `ACTIVE` vs kept under `INACTIVE` (P18) (FR-001-007(d), FR-001-008)

### Implementation for User Story 1

- [X] T014 [US1] Implement R0 (`policeCases` drop) and R1 (SJP/box-hearing collapse) in
  `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/HmppsRedaction.java` —
  makes T008 green
  - GREEN: `T1 ... emptied by R1 PASSED`, `T2 ... emptied by R1 PASSED`
- [X] T015 [US1] Implement R5, R6, R7 (`laaApplnReference` removal from the three offence
  locations) in `HmppsRedaction.java` — makes T009 green
  - GREEN: `T3/T4/T5 ... PASSED`
- [X] T016 [US1] Implement R3, R4 (youth rules, gated on `EffectiveSwitchState.ACTIVE`, including
  the shared `hasAnyTruthyTopLevelMember` payload-level guard) and R11 (final collapse) in
  `HmppsRedaction.java` — makes T010 green
  - GREEN: `T6..T12 ... PASSED` (all seven)
- [X] T017 [US1] Implement R8, R9, R10 (`judiciary`, `associatedPersons`, `delegatedPowers`
  removal) in `HmppsRedaction.java` — makes T011 green
  - GREEN: `T13/T14/T15 ... PASSED` (all 15 twins green)
- [ ] T018 [US1] Implement the single deep-copy-on-entry and confirm no logging call exists
  anywhere in `pipeline/` — makes T012 green
- [ ] T019 [US1] Implement `RuleFailureSignal` throw sites at the carried null-guard reads (P19)
  and the `subject.masterDefendant` read (P18), caught only at `redact(...)`'s outer boundary and
  mapped to `RedactionResult.RulesFailure(ruleId)` — makes T013 green; update `doc/DEVIATIONS.md`'s
  DV-09 row to "built: yes"

**Checkpoint**: User Story 1 is fully functional and independently testable via the 15 twins alone.

---

## Phase 4: User Story 2 - One comparator decides whether two bodies are the same (Priority: P1)

**Goal**: One reused comparator, with safe (value-free) failure messages, that the corpus harness
and any other caller (slice 006's STE replay) can use identically.

**Independent Test**: run the comparator over the 57 reused vectors, then force one mismatch and
inspect its message. Needs nothing from User Story 1.

### Tests for User Story 2 (write and run first) ⚠️

- [ ] T020 [P] [US2] Run the copied `ComparatorContractTest` (T002) as-is against all 57 vectors,
  both directions; confirm 100% pass with no `UNSPECIFIED` verdict left unhandled (FR-001-014,
  SC-001-002)
- [ ] T021 [US2] Write `ParityAssertionsTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/ParityAssertionsTest.java`:
  forces a mismatch between two labelled bodies and asserts the failure message names the case, the
  form, the switch state and each differing JSON path, and contains no value from either side (red:
  `ParityAssertions` does not exist yet) (FR-001-015, SC-001-009)

### Implementation for User Story 2

- [ ] T022 [US2] If `ComparatorContractTest`'s binding point needs adjustment to call the copied
  `JsonParity` in this package, make it — confirms T020
- [ ] T023 [US2] Implement `ParityAssertions.assertSameBody(caseId, form, switchState, expected,
  actual)` in
  `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/ParityAssertions.java`
  (test-only utility; lives under `src/test/java/...` if no production caller exists yet),
  delegating to `JsonParity.assertMatches` with a formatted, value-free label — makes T021 green

**Checkpoint**: User Story 2 is fully functional and independently testable via the vectors and one
forced mismatch alone.

---

## Phase 5: User Story 3 - SIT data is held only as the public repository allows (Priority: P1)

**Goal**: Custody checks (digest pinning, provenance completeness, no orphan files) in place
**before** any SIT case is committed.

**Independent Test**: with no SIT data, run the custody checks over a scratch corpus; tamper with
one file, remove one provenance item, add an orphan file — each fails the build, naming it. Needs
User Story 2's comparator for one scenario (forced comparison failure showing no values) but nothing
else.

### Tests for User Story 3 (write and run first) ⚠️

- [ ] T024 [P] [US3] Write `CorpusCustodyTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/CorpusCustodyTest.java`
  using a scratch corpus fixture under `src/test/resources/corpus-custody-fixtures/`: a digest
  mismatch, a case missing a required provenance field, and a file belonging to no case each fail
  with a message naming the file/case (FR-001-030, FR-001-032)
- [ ] T025 [US3] Add a case to `CorpusCustodyTest` asserting custody-check output never contains a
  corpus value, passing or failing (FR-001-031)

### Implementation for User Story 3

- [ ] T026 [US3] Create `src/test/resources/corpus/index.json` with the `CorpusMetadata` shape from
  `data-model.md` (`environment`, `captureDateRange`, `hearingCount`, `casesPerSwitchState`,
  `syntheticCount`, `excludedCases`, `comparatorVectorCount: 57`), empty case list initially
  (FR-001-029)
- [ ] T027 [US3] Implement `CorpusCustodyChecker` (digest verification against `meta.json`,
  provenance-field completeness per FR-001-029, orphan-file detection under
  `src/test/resources/corpus/`) in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/CorpusCustodyChecker.java`
  — makes T024 and T025 green

**Checkpoint**: User Story 3 is fully functional and independently testable with zero SIT cases
committed.

---

## Phase 6: User Story 4 - The port is pinned to what the legacy actually sent from SIT (Priority: P1)

**Goal**: The corpus/oracle parity harness: every required case, in both switch states and every
form it holds, compared with zero unexplained differences.

**Independent Test**: on top of User Stories 1–3, run the parity suite over the committed corpus.

**Depends on**: User Story 1 (redaction), User Story 2 (comparator), User Story 3 (custody checks
must exist before any case is committed).

### Tooling (before any case is authored)

- [ ] T028 [US4] Set up the oracle-execution harness: a small Node.js script, run **outside** this
  Gradle build, that invokes `cpp-context-azure-legalaidagency@122e2152`'s
  `ProbationHearingResultedFilter/functions.js` (`probationFilter`) with the switch forced to a fixed
  value, over a given payload, and writes the result plus provenance (the legacy commit SHA, the
  Node/npm version) in the shape `meta.json` expects. Document its usage in `quickstart.md`. Every
  `ACTIVE`/synthetic expectation authored below is produced by this script, never hand-written
  (FR-001-022, FR-001-029)

### Tests for User Story 4 (write and run first) ⚠️

- [ ] T029 [US4] Write `HmppsRedactionCorpusParityIT` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/HmppsRedactionCorpusParityIT.java`:
  runs `redact(...)` over every committed corpus case, in each switch state and form that has an
  expectation, compares via `ParityAssertions`; a case-state with no expectation
  (FR-001-019/020/021) is listed with its reason and never counted as a pass; any other difference
  fails the build. The report asserts all seven FR-001-039 fields: cases compared per state/form,
  matches, cases derived by DV id, no-expectation case-states with reasons, case-13 derivations with
  no source expectation, excluded cases with reasons, and the unexplained-difference count (asserted
  zero) (FR-001-026, FR-001-039)
- [ ] T030 [P] [US4] Write `WholeCorpusChecksTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/WholeCorpusChecksTest.java`:
  every body produced over the corpus carries a non-null `hearing.id`; every carried guard (P19's
  five sites, P18's `masterDefendant`) is reached by a case or has an accepted-risk entry in
  `doc/DEVIATIONS.md` (FR-001-027)

### Implementation for User Story 4

- [ ] T031 [US4] Implement the corpus case loader `ParityCase` (re-authored for this service's
  two-form/two-switch-state shape per `data-model.md`, not a copy of informant-register's) in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/ParityCase.java`,
  reading `meta.json`/`inputs/`/`expectations/`
- [ ] T032 [P] [US4] Author synthetic case 1 (virtual-box hearing; always synthetic, A2-8) under
  `src/test/resources/corpus/case-01-virtual-box-hearing/` with `meta.json`, inputs and both-state
  expectations produced via T028 (FR-001-024)
- [ ] T033 [P] [US4] Author case 2 (top-level `judiciary` and `associatedPersons`) under
  `src/test/resources/corpus/case-02-top-level-members/` (FR-001-024)
- [ ] T034 [P] [US4] Author case 3 (court application with no `masterDefendant`, both switch
  states) under `src/test/resources/corpus/case-03-no-masterdefendant/` (FR-001-024)
- [ ] T035 [P] [US4] Author case 4 (prosecution-case offence without `judicialResults`) under
  `src/test/resources/corpus/case-04-no-judicial-results/` (FR-001-024)
- [ ] T036 [P] [US4] Author case 5 (`courtOrder` without `courtOrderOffences`) under
  `src/test/resources/corpus/case-05-no-court-order-offences/` (FR-001-024)
- [ ] T037 [P] [US4] Author case 8 (`plea.delegatedPowers` present, pins P21/P35 as carried) under
  `src/test/resources/corpus/case-08-delegated-powers/` (FR-001-024)
- [ ] T038 [P] [US4] Author case 9 (non-boolean `isYouth`, e.g. `"false"` is truthy) under
  `src/test/resources/corpus/case-09-non-boolean-isyouth/` (FR-001-024)
- [ ] T039 [P] [US4] Author case 11 (youth defendant beside adult defendants, adult-court hearing)
  under `src/test/resources/corpus/case-11-youth-among-adults/` (FR-001-024)
- [ ] T040 [P] [US4] Author case 12 (every case and application removed under `ACTIVE`) under
  `src/test/resources/corpus/case-12-everything-removed/` (FR-001-024)
- [ ] T041 [P] [US4] Author the R0-proving case (a payload carrying `policeCases`, truthy) under
  `src/test/resources/corpus/case-r0-policecases/` — without this, R0 has no twin and no corpus
  case, leaving FR-001-025 unmet (FR-001-024, FR-001-025; found in `/speckit-analyze`, finding C1)
- [ ] T042 [P] [US4] Author the P21-proving case (a court application that keeps
  `associatedPersons`/`delegatedPowers`, both switch states) under
  `src/test/resources/corpus/case-p21-application-keeps-associated/` (FR-001-024; found in
  `/speckit-analyze`, finding C1)
- [ ] T043 [US4] Implement the case-13 derivation: for every corpus payload and form, derive the
  switch-read-failure expectation from that case's `ACTIVE` (or configured-default) expectation
  under DV-25, never recorded as its own fixture (FR-001-038)
- [ ] T044 [US4] Update `src/test/resources/corpus/index.json` counts and `excludedCases` to match
  the committed corpus (all synthetic at this point; flag for SIT-case substitution once the
  external capture dependency is met)

**Checkpoint**: User Story 4 is fully functional — the parity run reports zero unexplained
differences over the synthetic blind-spot corpus, and every rule R0, R1, R3–R11 is proved by at
least one twin or corpus case (FR-001-025). Real SIT cases are a follow-up contingent on SIT access
(external dependency, not a code gap).

---

## Phase 7: User Story 5 - Every intended difference is registered, derived and tested (Priority: P2)

**Goal**: `doc/DEVIATIONS.md` fully populated for this slice's built rows, and the case-13
derivation demonstrably load-bearing (withdraw the row, watch the derivation fail).

**Independent Test**: read the register (needs only T003's seed); for the derivation-withdrawal
scenario, needs User Story 4's harness.

### Tests for User Story 5 (write and run first) ⚠️

- [ ] T045 [P] [US5] Write `DeviationRegisterTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/DeviationRegisterTest.java`:
  asserts `doc/DEVIATIONS.md` has exactly one row per DV-01…DV-26 (withdrawn/unallocated/reserved
  included) and names DV-27 as the next free id (FR-001-033, SC-001-011)
- [ ] T046 [US5] Write `Case13DerivationWithdrawalTest` in
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/Case13DerivationWithdrawalTest.java`:
  withdrawing DV-25's row in a scratch copy of the register makes every case-13 derivation fail,
  quoting DV-25 (Acceptance Scenario 4, User Story 5)

### Implementation for User Story 5

- [ ] T047 [US5] Complete `doc/DEVIATIONS.md`'s full field set (legacy behaviour with `file:line`
  citation, new behaviour, reason, approval by role and decision id, whether it changes a message
  body, pinning test, built-yet flag) for this slice's built rows (DV-09, DV-25) and the carried /
  pending-external / accepted-risk entries (FR-001-034, FR-001-035) — makes T045 green
- [ ] T048 [US5] Wire the case-13 derivation (T043) to read its permitted outcome from
  `doc/DEVIATIONS.md`'s DV-25 row so withdrawing the row breaks every derivation — makes T046 green

**Checkpoint**: User Story 5 is fully functional — the register is complete and the derivation
mechanism is proven load-bearing, not decorative.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [ ] T049 [P] Run every command in `quickstart.md` end-to-end; fix any drift between the doc and
  the actual Gradle test filters
- [ ] T050 [P] Confirm `./gradlew build` passes with this slice's new packages included in PMD,
  Checkstyle (`google_checks`, `maxWarnings=0`) and the JaCoCo gate (LINE ≥ 0.88, BRANCH ≥ 0.85) —
  no new exclusion beyond the existing `Application`/`config/**`
- [ ] T051 Cross-check each of SC-001-001 … SC-001-013 against an actual passing test or report;
  for SC-001-007 specifically, disable one rule at a time in `HmppsRedaction` (a local, uncommitted
  edit) and confirm at least one twin or corpus case fails each time, then revert; note any gap in
  the PR description
- [ ] T052 [P] Record in `doc/DEVIATIONS.md` and `src/test/resources/corpus/index.json` that the
  committed corpus is synthetic-only pending the external SIT-capture dependency (`spec.md`
  Dependencies, External), so a reviewer does not mistake it for a shortfall

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: depends on Setup — blocks every user story
- **US1 (Phase 3)**: depends on Foundational only
- **US2 (Phase 4)**: depends on Foundational only (reuses Setup's copied pack directly)
- **US3 (Phase 5)**: depends on Foundational and, for one scenario, US2's comparator
- **US4 (Phase 6)**: depends on US1, US2 and US3 all being green; T028 (the oracle-execution
  harness) must land before any case-authoring task (T032–T042)
- **US5 (Phase 7)**: depends on T003 (register seed) for its first test; the derivation-withdrawal
  test additionally depends on US4's harness (T043)
- **Polish (Phase 8)**: depends on all seven prior phases

### Parallel Opportunities

- T001, T002 (Setup) run in parallel
- T004, T005 (Foundational tests) run in parallel; then T006, T007 (Foundational implementation)
  run in parallel
- Within US1: T008–T011 (twin tests, different fixtures) run in parallel; implementation tasks
  T014–T017 touch the same `HmppsRedaction.java` file sequentially, T018/T019 likewise
- Within US4: T032–T042 (eleven corpus cases, each its own directory) all run in parallel once T028
  (the oracle harness) and T031 (the loader) are green
- US1, US2 and US3 can be staffed in parallel once Foundational is green; US4 waits on all three

## Implementation Strategy

### MVP First

1. Setup (T001–T002) → Foundational (T003–T007) → User Story 1 (T008–T019)
2. **Stop and validate**: run the 15 twins independently — this is the code every later story and
   every later slice depends on
3. Then User Stories 2 and 3 (independent of each other and of US1's internals, both needed before
   US4)
4. Then User Story 4 (the corpus/oracle harness — the slice's actual go-live gate evidence,
   SC-001-003), starting with the oracle-execution harness (T028) before any case is authored
5. Then User Story 5 (register completion — can trail US4 slightly since P2, but FR-001-036 still
   requires the register to exist no later than US1's DV-09 row, which T003 already satisfies)
6. Polish

### Suggested MVP Scope

User Story 1 alone (T001–T019) is a legitimate stopping point for a first review: it proves the
rules are ported correctly against the legacy's own test suite, with zero dependency on corpus data,
custody machinery or the deviation register's full content.
