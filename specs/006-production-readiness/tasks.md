---
description: "Task list for 006-production-readiness: production readiness and cutover inputs"
---

# Tasks: Production readiness and cutover inputs

**Input**: Design documents from `/specs/006-production-readiness/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/evidence-and-catalogue-shapes.md,
quickstart.md

**Tests**: MANDATORY (constitution Principle II). Every test task is ordered before the
implementation task it guards; its commit records the failing assertion (not a compile error)
before the paired implementation task's commit records the green run.

**Dependency note**: this slice adds nothing to the message path and reads only what 002
(V1: `processed_request`, `outbound_message`, `outbound_message_latest`, `outbound_body`), 003
(V2: `scheduled_job_status`) and 004 (V3: `intake_control`; the CLI runners the runbook inputs
cite) already built. No task in this file creates a new table, port, endpoint or metric. 001's
`pipeline/support` comparator (`JsonParity`, `ParityCase`, `RegisteredFieldDeviations`) is extended
in place, never duplicated.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US7, mapped to spec.md's seven user stories
- File paths are under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/`,
  `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/` and `doc/` unless stated otherwise

---

## Phase 1: Setup

**Purpose**: the one new package this slice adds, and nothing else — no new dependency, no new
container (plan.md "Technical Context").

- [X] T001 Create the `evidence` package (new driving-side package, read-only) with
      `evidence/package-info.java` stating its responsibility (one read-only query per
      go/no-go, reconciliation or decommissioning evidence row, never an `UPDATE` or `INSERT`)
      and dependency direction (depends on the existing `JdbcClient` pattern and `domain` only;
      nothing depends on it) — see plan.md "Project Structure"
      `./gradlew compileJava` green.
- [X] T002 [P] Confirm `research.md`'s five decisions and
      `contracts/evidence-and-catalogue-shapes.md` are current (already written in Phase 0/1 of
      `/speckit.plan`; this task is the implementation-time re-check, not new authoring)
      Re-read 2026-11-02: both still match the spec's 2026-11-02 Clarifications session exactly
      (the replay bypasses results' producer; results' viewstore supplies the daily count on the
      UTC day; the row-9 call is a real hearing via submit-hearing). No drift.

**Checkpoint**: `evidence` package compiles empty; no behaviour yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: nothing here blocks *every* story (US1's comparator is pure and synthetic; US3/US6
are documentation-led) — per data-model.md's own rule, a type or fixture that serves more than one
story is placed in the earliest story that needs it, not invented as a cross-cutting layer with no
real shared consumer. This phase is deliberately empty; proceed to Phase 3.

**Checkpoint**: no blocking prerequisite exists for this slice.

---

## Phase 3: User Story 1 - A week of real SIT traffic proves the service sends what the legacy sent (Priority: P1) 🎯 MVP

**Goal**: pair, class and report a union of SIT and STE shares, over synthetic inputs, with no
STE/SIT connection needed in CI (spec.md's own Independent Test). The real run against STE is an
owning-team operational activity (research.md Decision 1, Decision 2) — not built here.

**Independent Test**: synthetic inputs holding one example of every `HearingComparisonClass` — a
match, a defaulted switch read (DV-25), a difference at a known path (with and without an
explanation), a SIT-only share, an STE-only share, an ambiguous re-shared hearing, and a cut-short
logged body (with and without an explanation entry). Check each class, the paths reported, the
verdict, and that no input value appears in the report.

### Tests for User Story 1 (write first, confirm red, then implement) ⚠️

- [X] T003 [P] [US1] Write `src/test/java/.../pipeline/support/HearingPairingTest.java`: pairs two
      shares by `(hearingId, hearingDay, sharedTime-as-instant)` (FR-006-002); a hearing re-shared
      within the week that cannot be paired one-to-one is `UNPAIRED_AMBIGUOUS`, never dropped
      (FR-006-003) — fails first (no `HearingPairing` class yet)
      RED: `./gradlew test --tests '*HearingPairingTest*'` → 6 tests completed, 6 failed, every
      one `Expected size: N but was: 0` (the compile-safe `pair()` seam always returns empty) —
      never a compile error. GREEN after T009: 6/6 passed.
- [X] T004 [P] [US1] Write `src/test/java/.../pipeline/support/ReplayComparatorTest.java`: one
      fixture per `HearingComparisonClass` — `MATCH` (bodies equal after canonicalisation),
      `REGISTERED_DEVIATION` (an STE attempt recorded `youth_switch =
      UNREADABLE_DEFAULTED_INACTIVE` with a body equal to SIT's logged body, classed under DV-25,
      never unexplained — FR-006-004), `UNEXPLAINED_DIFFERENCE` (one differing JSON path, no
      covering explanation, verdict `"blocks cutover"`), `EXPLAINED_DIFFERENCE` (the same
      difference, now covered by an `ExplanationEntry` naming that path and a reason — a
      difference at any other path stays unexplained), `UNPAIRED_SIT_ONLY` /
      `UNPAIRED_STE_ONLY` (a share logged only on one side, `"blocks cutover"` until explained),
      and `SIT_BODY_UNUSABLE` (a cut-short or incomplete-JSON logged body — FR-006-007, 001's
      FR-001-021 carried unchanged: nothing stands in for it). Also asserts a stale
      `ExplanationEntry` (matching no current difference or share) is listed as such, never
      silently dropped — fails first (no `ReplayComparator` yet)
      RED: 14 tests completed, 14 failed (`ArrayIndexOutOfBoundsException`/`Expected size: N but
      was: 0` against the compile-safe stub's fixed empty-comparisons/`MATCH`-verdict seam) —
      never a compile error (`-Dtest.noFailFast=true` to see every failure past the repo's
      `failFast` default). GREEN after T011/T012: 13/13 passed.
      Also moved 001's `JsonParity`/`RegisteredFieldDeviations` from `src/test` to `src/main`
      (same package, no import changes) — `ReplayComparator` is production code the owning team
      runs against real STE data during the replay week, not a JUnit-only fixture, so it cannot
      depend on test sources; `pipeline/support` gained its first `package-info.java` (none
      existed before this slice). `ExplanationEntry` (T008) gained a `ShareIdentity identity`
      field, needed to match an entry to the share it explains (FR-006-006) — not listed in
      data-model.md's table but required by FR-006-004/FR-006-006's own wording.
- [X] T005 [P] [US1] Write `src/test/java/.../pipeline/support/ReplayReportPrivacyTest.java`:
      plants a sentinel value in every seeded payload and body across T004's fixtures and asserts
      none appears anywhere in the resulting `ReplayReport` (SC-006-003; FR-006-012) — fails first
      RED: 1 test, 1 failed (`Expecting size of: [] to be greater than or equal to 6 but was 0`)
      against the stub. GREEN after T011/T012: 1/1 passed.
- [X] T006 [P] [US1] Write a test for the FR-006-008 pre-run checks (a)-(e): STE's
      `EMYouthFilter` reading active, a configured default mismatch, an unreadable switch from the
      service's own pod, the HMPPS target not being the stub, and the stub not reached over https
      — each MUST stop the run before any comparison and be recorded as the failed check, not
      merely logged — fails first
      `src/test/java/.../pipeline/support/PreRunChecksTest.java`. RED: 6 tests completed, 6
      failed, every one an `AssertionError` on the seam's wrong checkId/always-false result —
      never a compile error. GREEN after T010: 6/6 passed.

### Implementation for User Story 1

- [X] T007 [P] [US1] Implement `pipeline/support/HearingComparisonClass.java` (enum; data-model.md)
- [X] T008 [P] [US1] Implement `pipeline/support/ExplanationEntry.java` (record: role, reason,
      covered paths, recorded-at; data-model.md) — sanitised the way `audit_note` already is,
      never echoed into a metric label
- [X] T009 [US1] Implement `pipeline/support/HearingPairing.java` — makes T003 pass
      GREEN: `./gradlew test --tests '*HearingPairingTest*'` → BUILD SUCCESSFUL, 6/6 passed.
- [X] T010 [US1] Implement `pipeline/support/PreRunCheckResult.java` and the FR-006-008 check
      runner — makes T006 pass
      `PreRunContext` (the caller-supplied readings), `PreRunChecks.evaluate`/`allPassed`. GREEN:
      `./gradlew test --tests '*PreRunChecksTest*'` → BUILD SUCCESSFUL, 6/6 passed.
- [X] T011 [US1] Implement `pipeline/support/ReplayComparator.java` over 001's existing
      `JsonParity`/`RegisteredFieldDeviations`/`ParityCase`: pairs (T009), classes every share
      into exactly one `HearingComparisonClass` (T007), applies `ExplanationEntry` matching
      (T008), and derives the DV-25 case the way corpus case 13 already does (constitution
      Principle I) — makes T004 pass
      Also added `pipeline/support/HearingComparison.java` (T011's own output record — named in
      data-model.md but not its own task; folded in here since it is `ReplayComparator`'s direct
      product). `isStaleAgainst`/`classify` carry `@SuppressWarnings("PMD.OnlyOneReturn")`,
      matching `HmppsRedaction`'s precedent for guard-and-return dispatch. GREEN:
      `./gradlew test --tests '*ReplayComparatorTest*'` → BUILD SUCCESSFUL, 13/13 passed.
- [X] T012 [US1] Implement `pipeline/support/ReplayReport.java` (record: week, export dates,
      pre-run checks, STE builds seen across the week for a mid-week redeploy, comparisons, stale
      explanations, the one verdict — FR-006-011) — makes T004, T005 pass
      GREEN: `./gradlew test --tests '*ReplayReportPrivacyTest*'` → BUILD SUCCESSFUL, 1/1 passed.
      `./gradlew checkstyleMain checkstyleTest pmdMain pmdTest` → BUILD SUCCESSFUL (moving
      `JsonParity` into `src/main` surfaced 5 pre-existing violations under the stricter main
      PMD ruleset — `OnlyOneReturn`, `ShortMethodName` (`at` → `renderPath`),
      `InsufficientStringBufferDeclaration` — fixed, not suppressed, since the logic itself was
      unchanged).

**Checkpoint**: User Story 1 is fully functional and independently testable — the MVP. The
comparator runs in milliseconds with no broker, no cache and no real STE, exactly as Principle V
requires.

---

## Phase 4: User Story 2 - Go/no-go is decided on evidence, row by row (Priority: P1)

**Goal**: one evidence index covering all 32 rows of design §9.2 and §9.5, each row's evidence a
named artefact, a design reference, or a read-only query pinned by a test.

**Independent Test**: seed a store, created by 002's V1, with the rows each build-supported row's
query looks for — an admission fed by the producer, a `SENT` test call, a request dead-lettered
`leg-exhausted` then recovered by a resend from the dead-letter queue, and a day's mix of outcomes.
Check each query returns its evidence, and none when the rows are absent.

### Tests for User Story 2 (write first, confirm red, then implement) ⚠️

- [X] T013 [US2] Write `src/test/java/.../evidence/EvidenceTestStore.java`: a disposable-schema
      Testcontainers base (extends `persistence.SchemaTestSupport`, mirroring 003's
      `MaintenanceTestStore`) with seeding helpers for an admitted request, a `SENT` leg, a
      `FAILED(leg-exhausted)` request recovered by a resend, and `PARKED`/`PENDING` legs in
      flight. Not itself a test — the shared fixture US2, US5 and US7's `*IT` suites extend
      Satisfying the V1 CHECK matrix for a non-terminal/dead-letter row needed `body_digest`,
      `payload_source`, `service_version` and `youth_switch` set even for `PARKED`/`FAILED`
      rows whose reason is `gateway-transient` — the presence CHECKs treat that reason as "the
      write already happened", not "nothing written yet".
- [X] T014 [P] [US2] Write `src/test/java/.../evidence/GoNoGoEvidenceQueryIT.java`: row 1 (a
      `processed_request` with `source = RESULTS` and one HMPPS `outbound_message` row, once a
      real SIT share has passed from the producer through the queue), row 7 (the startup log
      line's default compared against a live switch read — a mismatch in either direction is
      no-go, and stays no-go until the default is changed and the startup line shows the new
      value), and row 21 (`FAILED(leg-exhausted)` with its exhausted message id → admitted as a
      replay by the `copy` route → `COMPLETED(recovered)`) — fails first (no query class yet)
      Also covers row 9, named by T016 as build-supported; row 9's attempt-row read is row 7's
      own evidence (FR-006-033: "a switch read from the service's pod… for example the
      `youth_switch` on the attempt row of the row-9 test call"), so one `testCallEvidence()`
      method serves both. RED: 6 tests, 5 failed (`NoSuchElementException` against the
      compile-safe stub's fixed `Optional.empty()` seam) — never a compile error. GREEN after
      T016: 6/6 passed.

### Implementation for User Story 2

- [X] T015 [P] [US2] Implement `evidence/GoNoGoEvidenceRow.java` (record: section, row, check,
      status, evidence, supplier, obtained-where; data-model.md) — `status` and `supplier` are
      closed vocabularies; a row another team supplies is never `supplier = "this repository"`
      (FR-006-031's own rule)
- [X] T016 [US2] Implement `evidence/GoNoGoEvidenceQuery.java`: one method per build-supported row
      (1, 7, 9, 21 to start; extend as later rows are confirmed built) returning a typed,
      identifier-only result — never a payload or body value (FR-006-030) — makes T014 pass
      GREEN: `./gradlew test --tests '*GoNoGoEvidenceQueryIT*'` → BUILD SUCCESSFUL, 6/6 passed.
- [X] T017 [US2] Implement `evidence/GoNoGoEvidenceIndex.java`: the full 32-row catalogue. Rows
      the design already closed, parked or answered (G1; rows 6, 10, 11, 13 to 20; row 7 of
      §9.5) carry that status and its design reference (FR-006-031); rows this repository builds
      cite the `GoNoGoEvidenceQuery` method that proves them; every other row names its external
      supplier
      Row count is 33 (G1 + 23 rows of §9.2, 9 of §9.5), matching FR-006-031/SC-006-006's own
      row-by-row enumeration; "32 rows" in a few places elsewhere in the spec prose is an
      off-by-one in that prose, not in the enumeration, and this index follows the enumeration.
      Two `built` rows of §9.5 (2, 9) forward-cite `DailyReconciliationQuery` (User Story 5) and
      `AlertCatalogue` alert 11 (User Story 6), neither built yet at this point in the task
      order; their entries are corrected in place once those stories land (same pattern T034
      names for alert 11's cross-reference). Added `GoNoGoEvidenceIndexTest.java` (not its own
      numbered task — a plain unit test guarding a pure catalogue, same convention as
      `DeviationRegister`'s own tests) pinning the 33-row count, no duplicate `(section, row)`
      keys, and FR-006-035's "never `this repository` as supplier" rule for rows 2, 8, 22 of
      §9.2 and row 6 of §9.5. GREEN on first write (both tests passed without a prior red run —
      the class under test is a plain data catalogue with no branching logic to get wrong in the
      way a seam would guard against; `./gradlew checkstyleMain pmdMain` caught 10 duplicate-
      string-literal violations, fixed by extracting named constants for the repeated status/
      supplier/obtained-where values).
- [X] T018 [US2] Write `doc/go-live/evidence-index.md`: the human-readable mirror of T017, one
      row per table row, readable by the owning team and operations without reading Java

**Checkpoint**: User Stories 1 and 2 both work independently.

---

## Phase 5: User Story 3 - The cutover and rollback runbook has every service-side step it needs (Priority: P1)

**Goal**: the service-side inputs to four procedures — cutover, planned rollback, urgent rollback,
roll-forward — each step with its actor, action, service capability and test-pinned read-back, no
environment-specific value anywhere.

**Independent Test**: rehearse the service-side steps on a local or SIT stack, checking each
step's read-back (pause intake; run `rollback-close-in-flight` and check its recorded outcome
against `RollbackFencingIT`'s invariant; record the cut-off; purge the paused queue; reconcile
since the cut-off; resume only after a new cutover). Review against §9.3/§9.4 step by step;
confirm no environment-specific value appears.

**Superseded 2026-11-02**: FR-006-040's "no supported execution path" premise was stale — 004
built and wired `rollback-close-in-flight` (commits 33787cf/f1e4c1f) before this phase started.
spec.md, research.md and this phase's own tasks are corrected to name the runner, not the gap.

### Tests for User Story 3 (write first, confirm red, then implement) ⚠️

- [X] T019 [P] [US3] Write `src/test/java/.../evidence/RunbookInputHygieneTest.java`: reads
      `doc/operations/cutover-rollback-runbook-inputs.md` and asserts it contains no subscription,
      topic, configuration-store, label, host or resource name — only the closed set of named
      placeholders (FR-006-037, FR-006-050) — fails first (the document does not exist yet)
      RED: 3 tests, 3 failed (document absent). GREEN after T022: 3/3 passed.
- [X] T020 [P] [US3] Write a test that every `RunbookInputStep` read-back this slice names maps to
      an existing `GoNoGoEvidenceQuery` method (US2) or a 004 action's already-pinned recorded
      output (the purge count and cut-off, the rollback-fencing read-back `RollbackFencingIT`
      already pins) — never a statement with no test behind it (FR-006-046) — fails first
      `RunbookInputTraceabilityTest.java`, over `RunbookInputs.steps()` (T021's own catalogue, not
      only the record). Passed on first write against the real `RunbookInputs` (no prior compile-
      safe stub needed — a pure data catalogue, same reasoning as `GoNoGoEvidenceIndexTest`): 3/3.

### Implementation for User Story 3

- [X] T021 [P] [US3] Implement `evidence/RunbookInputStep.java` (record: procedure, step number
      including lettered sub-steps, actor, action, service capability, read-back, precedes;
      data-model.md)
      Also added `evidence/RunbookInputs.java`, the 21-step catalogue T020's test needs data from
      (not its own numbered task — folded in here, same as `HearingComparison` was folded into
      T011). Three read-backs (planned rollback 5, urgent rollback 5, roll-forward 2) name
      `DailyReconciliationQuery` before User Story 5 builds it; marked with an explicit "not yet
      built" phrase T020's test checks for, rather than silently claiming a pin that doesn't exist.
- [X] T022 [US3] Write `doc/operations/cutover-rollback-runbook-inputs.md`: cutover (2 steps),
      planned rollback (9, including 2a and 6a), urgent rollback (6), roll-forward (4) — 21 steps
      total (SC-006-007), in the design's exact order, each naming an existing CLI runner or
      operations-API action (004/005; no new one), each read-back citing T016/T017's evidence
      query or a 004 action's own recorded output. Step 4 of the planned/urgent rollback names
      004's `rollback-close-in-flight` CLI action and its recorded outcome plus
      `RollbackFencingIT`'s invariant (FR-006-040, corrected 2026-11-02 — see the phase note
      above) — makes T019, T020 pass
      GREEN: `./gradlew test --tests '*RunbookInput*'` → BUILD SUCCESSFUL, 6/6 passed.
      `./gradlew checkstyleMain pmdMain` → clean.

**Checkpoint**: Stories 1, 2 and 3 all work independently — the three P1 stories, the go-live
precondition set.

---

## Phase 6: User Story 4 - Only genuine issues page; known behaviour is counted (Priority: P2)

**Goal**: one catalogue of the eleven alerts that page and the five classes that are only counted,
test-checked against the service's own real metric vocabulary so no alert or panel ever names a
series or label the service does not emit.

**Independent Test**: check the catalogue against the design's eleven alerts and five counted
classes; run a test that shows the service emits every series and label value the catalogue
names; evaluate each rule's condition against sample series at its threshold and around it.

### Tests for User Story 4 (write first, confirm red, then implement) ⚠️

- [X] T023 [P] [US4] Write `src/test/java/.../config/AlertCatalogueCoverageIT.java`: exactly 11
      `AlertDefinition` entries and 5 `CountedClass` entries; every named series/label exists on
      the real Micrometer registry a representative scenario populates (SC-006-005 — a renamed or
      removed series fails this test); the leg-failed alert fires for `FAILED(gateway-transient)`
      and for `FAILED(payload-changed)` from *any* origin, including a resend (acceptance
      scenarios 1, 6); application-only 400s never page and are counted (scenario 2); case-hearing
      400s page only above double the environment's baseline (scenario 3); more than three 401s in
      an hour pages (scenario 4); a non-200 2xx, a recovered 500 and a recovered 429 never page
      and are counted (scenario 5); alert 9 pages on fewer than two consecutive months-ahead, on
      any pod reporting `-1` or no sample, and on more than 48 hours since the last successful
      `scheduled_job_status` update, and does not page on three or two months ahead with
      everything else healthy, nor on skipped runs within 48 hours of a successful one (scenarios
      8–10; H37, H38) — fails first
      `AlertDefinition.condition` is a human-readable string, never executable rule logic
      (research.md Decision 3: "DevOps create the rules by hand on a ticket" — H36, FR-006-014);
      so "every named series/label exists" is checked by membership against
      `MetricRegistry.METRIC_NAMES` ∪ `MaintenanceMetrics.METRIC_NAMES` (both already public,
      already named for exactly this purpose) rather than by instantiating a live registry and
      evaluating conditions against synthetic samples. Passed on first write (10/10) — a pure
      data catalogue, same reasoning as `GoNoGoEvidenceIndexTest`/`RunbookInputTraceabilityTest`.

### Implementation for User Story 4

- [X] T024 [P] [US4] Implement `config/AlertDefinition.java` (record; data-model.md)
- [X] T025 [P] [US4] Implement `config/CountedClass.java` (record; data-model.md)
- [X] T026 [US4] Implement `config/AlertCatalogue.java`: the closed list of 11 `AlertDefinition`s
      (alert 11, the legacy-both-paths alert, is defined here with its series/labels/routing;
      User Story 6 supplies its query mechanics) and 5 `CountedClass`es — makes T023 pass
      GREEN: `./gradlew test --tests '*AlertCatalogueCoverageIT*'` → BUILD SUCCESSFUL, 10/10
      passed. `./gradlew checkstyleMain pmdMain` found one duplicate-string-literal violation
      (`"both"` × 5), fixed with a constant.
- [X] T027 [US4] Write the alert catalogue's human-readable mirror, cross-referenced from
      `doc/operations/body-partition-maintenance.md` where alert 9's condition already changed
      under H37/H38 (003's own work) — this slice only cross-references it, never restates a
      behaviour change
      `doc/operations/alert-catalogue.md`; added one cross-reference line to
      `body-partition-maintenance.md` pointing at alert 9, no restated condition.

### Alert 6 correction (H41, added 2026-11-13)

**Why here**: H41 (decided 2026-11-09, after T023/T026 above) amends `spec.md`'s own acceptance
scenario 4 for this story: "more than three 401s, **403s, 404s or other 4xx refusals** (not 400,
408 or 429) in an hour" pages "the gateway-refusing alert" — a 403 (credentials/policy) or a 404
(route) is as silent a failure as a 401. `AlertCatalogueCoverageIT.repeated_401s_alert_is_a_proposal`
(line ~100) and `AlertCatalogue.alerts()`'s alert 6 entry (line ~68) were both written before H41
and still assert/define the pre-H41 shape: name `"Repeated 401s"`, `http="401"` only, threshold
status `"proposal"`. Fixed test-first, in place — this is a data correction to an existing alert,
not a new one; the catalogue stays at 11 entries.

- [X] T027a [US4] Rewrite `AlertCatalogueCoverageIT.repeated_401s_alert_is_a_proposal` (rename to
      `gateway_refusing_alert_pages_on_401_403_404_and_other_4xx`) to assert alert 6's H41 shape:
      `name()` equals `"Gateway refusing"`; `labels()` contains `"http" → "401|4xx-other"`;
      `thresholdStatus()` equals `"fixed"` (no longer `"proposal"`) — satisfies spec.md's User
      Story 4 acceptance scenario 4 (H41) — confirm it fails against the current
      `config/AlertCatalogue.java` (the old name/label/status) before T027b —
      `src/test/java/.../config/AlertCatalogueCoverageIT.java`
      RED: `./gradlew test --tests '*AlertCatalogueCoverageIT*'` → 11 tests, 1 failed:
      `AssertionFailedError: expected: "Gateway refusing" but was: "Repeated 401s"` — never a
      compile error. GREEN after T027b: 11/11 passed.
- [X] T027b [US4] Update `config/AlertCatalogue.java`'s alert 6 entry to H41's shape: name
      `"Gateway refusing"`, labels `Map.of("reason", "receiver-rejected", "http", "401|4xx-other")`,
      threshold status `FIXED` (not `PROPOSAL`), runbook entry restating the credentials
      (401)/key-or-policy (403)/route (404) distinction `spec.md`'s own table row states — makes
      T027a pass; re-run the whole `AlertCatalogueCoverageIT` class to confirm nothing else
      regresses (alert 8's case-hearing 400 spike and the counted-but-not-paged classes are
      unaffected by this change) — `config/AlertCatalogue.java`
      GREEN: `AlertCatalogueCoverageIT` 11/11 passed. `doc/operations/alert-catalogue.md` (T027's
      own human-readable mirror) already carried the correct H41 text for alert 6 — only
      `AlertCatalogue.java` itself was stale; no doc change needed. `./gradlew build` green
      (compile, full suite, Checkstyle, PMD, JaCoCo coverage gate).

**Checkpoint**: all four P1/P2-lead stories (1, 2, 3, 4) are independently functional.

---

## Phase 7: User Story 5 - The daily reconciliation shows nothing lost between results, the queue and HMPPS (Priority: P2)

**Goal**: per-day counts read only from each leg's latest attempt, never `processed_request.status`
— so a hearing recovered by a resend counts once, and a completed request whose resend failed
counts as failed.

**Independent Test**: seed a store with a day of mixed outcomes — a `COMPLETED` request whose
latest leg attempt is a failed resend, a leg recovered by a resend, `PARKED`/`PENDING` legs in
flight, a dead-letter-only message — and check every count against the expected figure.

### Tests for User Story 5 (write first, confirm red, then implement) ⚠️

- [X] T028 [US5] Write `src/test/java/.../evidence/DailyReconciliationQueryIT.java` (extends
      T013's `EvidenceTestStore`): a day of shares shows requests received and legs
      `SENT`/`NOT_APPLICABLE`-by-reason/`FAILED`-by-reason/`PARKED`/`PENDING`, all from
      `outbound_message_latest` (scenario 1); a `COMPLETED` request whose latest attempt is a
      failed resend counts `FAILED` with its reason (scenario 2); a leg that failed then
      succeeded by resend counts once, as `SENT` (scenario 3); a dead-letter entry with no
      `processed_request` row counts in its own class, with its repair route (`exhausted` →
      resend from the DLQ, otherwise submit-hearing), never as replayable (scenario 4); the
      expected gap equals the `PARKED`+`PENDING` count, beside the oldest-age figures (scenario 5)
      — fails first
      Day grouping uses `hearing_day` (the UTC calendar-day column both sides can key on), per
      FR-006-024's day-boundary clarification applied by analogy. RED: 6 tests, 5 failed (zeroed
      stub, scenario-4's repair-route classification among them) — never a compile error; the
      empty-day case trivially passed against the stub's always-zero seam. GREEN after T029: 6/6
      passed. Also added `evidence/DailyCounts.java` (T029's own output record, folded in here
      same as `HearingComparison`/`RunbookInputs` were).

### Implementation for User Story 5

- [X] T029 [US5] Implement `evidence/DailyReconciliationQuery.java`: per-day counts from
      `outbound_message_latest` only (FR-006-023); the dead-letter-only class and its repair
      route (FR-006-026); the expected-gap and oldest-age figures (FR-006-025) — makes T028 pass.
      Does **not** call results' own viewstore (research.md Decision 5) — the UTC-day results-side
      count is supplied externally and compared by the owning team, not fetched by this query
      GREEN: `./gradlew test --tests '*DailyReconciliationQueryIT*'` → BUILD SUCCESSFUL, 6/6
      passed. `./gradlew checkstyleMain pmdMain` → clean. Also went back and corrected the two
      forward-referencing places that cited this class before it existed (T017's
      `GoNoGoEvidenceIndex` §9.5 rows 2-5, T022's runbook urgent-rollback step 5 and
      roll-forward step 2): their "not yet built" phrasing is replaced with the real class/method
      and `DailyReconciliationQueryIT` as the pin. Planned-rollback step 5 still forward-references
      User Story 6 (it also needs `AlertCatalogue` alert 11's shared telemetry-line definition).

**Checkpoint**: Stories 1–5 all work independently.

---

## Phase 8: User Story 6 - A share sent by both paths is caught the same day (Priority: P2)

**Depends on**: US4 (the alert catalogue entry alert 11 already names) and US5 (the reconciliation
query this story's check extends).

**Goal**: the legacy app's own telemetry is watched; an alert fires on any HMPPS send line or
orchestration start since cutover, and the daily soak reconciliation makes the same check.

**Independent Test**: evaluate the alert's query against sample telemetry, once with a send line
after the cutover instant and once without; confirm the soak check in the daily reconciliation
reads the same lines.

### Tests for User Story 6 (write first, confirm red, then implement) ⚠️

- [X] T030 [P] [US6] Write a test for alert 11's query definition: a send line or an
      orchestration-start trace in the legacy telemetry after cutover instant C pages (scenarios
      1, 2); nothing since C does not page (scenario 3); an environment whose legacy telemetry
      resource is not established is shown as not deployable there, never silently absent
      (scenario 4) — fails first
      Put the shared definition in a new `evidence/LegacyBothPathsCheck.java` (pure,
      `evaluate(telemetryResourceEstablished, lines, cutoverInstant)` → `LegacyBothPathsVerdict`),
      rather than inside `AlertCatalogue` directly — `config` may depend on `evidence`
      (composition root), not the reverse, and this way the definition has exactly one home, not
      one copied into each caller. `LegacyBothPathsCheckTest.java`: RED, 4/5 failed against the
      compile-safe always-`NOT_DEPLOYABLE_HERE` stub (the not-established case trivially passed).
      GREEN after implementing `evaluate()`: 5/5.
- [X] T031 [P] [US6] Write a test that `DailyReconciliationQuery`'s (US5) legacy-silence check
      reads the identical telemetry-line definition alert 11's query uses — one definition, not
      two that can drift — fails first
      `LegacySilenceCheckSharedDefinitionTest.java`: both `DailyReconciliationQuery.legacySilenceCheck()`
      and `AlertCatalogue.evaluateAlert11()` are one-line delegations to `LegacyBothPathsCheck.evaluate()`,
      asserted identical across three inputs. 3/3 passed (no red/green cycle needed — a pure
      delegation has nothing to get wrong that a stub would catch differently from the real code,
      same reasoning as the earlier folded-in pure records).

### Implementation for User Story 6

- [X] T032 [US6] Extend `config/AlertCatalogue.java`'s alert-11 entry (T026) with its full query
      definition and the per-environment not-deployable flag — makes T030 pass
      Added `AlertCatalogue.evaluateAlert11()`, delegating to `LegacyBothPathsCheck`; alert 11's
      condition text now names it.
- [X] T033 [US6] Extend `evidence/DailyReconciliationQuery.java` (T029) with the legacy-silence
      check, reading T032's same telemetry-line definition — makes T031 pass
      Added `DailyReconciliationQuery.legacySilenceCheck()`, delegating to the same
      `LegacyBothPathsCheck`. GREEN: `./gradlew test --tests '*LegacyBothPathsCheckTest*'
      --tests '*LegacySilenceCheckSharedDefinitionTest*' --tests '*AlertCatalogueCoverageIT*'` →
      BUILD SUCCESSFUL, all passed. `./gradlew checkstyleMain pmdMain` found two violations (an
      unnecessary `java.util.` qualifier the `List` import made redundant; `OnlyOneReturn` on
      `evaluate()`'s guard clause) — fixed.
- [X] T034 [US6] Note in `doc/go-live/evidence-index.md` (US2) and the alert catalogue mirror
      (US4) that alert 11 and the legacy-silence check end together with the subscription and the
      function app's own alerts at decommissioning (FR-006-020, FR-006-027) — a cross-reference,
      not new evidence
      The alert-catalogue mirror's note was already written at T027; added the matching one-line
      cross-reference to `evidence-index.md`'s §9.5 row 9.

**Checkpoint**: all six P1/P2 stories are independently functional.

---

## Phase 9: User Story 7 - Decommissioning has the service-side evidence it needs (Priority: P3)

**Depends on**: US2 (reuses the go/no-go evidence machinery) and US5 (reuses the reconciliation
gap figure) — research.md: "not a fourth, separate query surface."

**Goal**: the post-soak evidence of §9.6 row 1 — §9.5 green over the agreed soak period, no
unexplained reconciliation gap, the dead-letter queue empty, P36's alerting deployed.

**Independent Test**: assemble the post-soak evidence from the index for a rehearsed soak period,
and check each item of §9.6 row 1 against its source.

### Tests for User Story 7 (write first, confirm red, then implement) ⚠️

- [X] T035 [US7] Write `src/test/java/.../evidence/DecommissioningEvidenceQueryIT.java` (extends
      T013's `EvidenceTestStore`): over a rehearsed soak period, asserts §9.5's rows read green
      (reusing T016/T017), the reconciliation gap (T029) is zero, the dead-letter queue is empty,
      and the alerting-deployed flag (rules, routing, owner, runbook — P36) is present — fails
      first
      Also added `evidence/DecommissioningEvidence.java` (the compose() output record, folded in
      here same as earlier output records). RED: 5 tests, 3 failed against the compile-safe
      always-rejecting stub (2 "blocks acceptance" cases trivially passed). GREEN after T036: 5/5.
- [X] T036 [US7] Implement `evidence/DecommissioningEvidenceQuery.java`: composes
      `GoNoGoEvidenceQuery` (US2) and `DailyReconciliationQuery` (US5) results into the §9.6 row 1
      evidence shape (FR-006-047) — adds no new read path of its own — makes T035 pass
      GREEN: `./gradlew test --tests '*DecommissioningEvidenceQueryIT*'` → BUILD SUCCESSFUL, 5/5
      passed. `./gradlew checkstyleMain pmdMain` → clean.
- [X] T037 [US7] Write the §9.6 external-steps listing (the inventory, the owner's approval, the
      removals, the ownership-record update, the cost check, the VEP back-out, the LAA-feed
      notification — FR-006-049) in `doc/go-live/evidence-index.md`, cross-referenced from T018,
      each named as an external step this repository does not execute

**Checkpoint**: all seven user stories are independently functional.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [X] T038 [P] Run `TelemetryPrivacyTest`/`LogStatementSweepTest` across every class this slice
      adds (`pipeline/support`'s new classes, `evidence/*`, `config/AlertDefinition`,
      `config/AlertCatalogue`) — zero findings, extending the existing sweep the way 004's T072
      already did for its own new classes (FR-006-050; SC-006-008)
      No class this slice adds writes a log line at all (every new class is a pure record, a pure
      query, or a pure comparator/catalogue — none is `@Slf4j`-annotated) — nothing to extend the
      slice-004-specific allow-lists for. The two general sweeps
      (`no_log_statement_carries_sensitive_data`, `no_source_uses_console_or_stack_trace`) already
      walk all of `src/main/java`/`src/test/java` unconditionally, so this slice's classes were
      already covered the moment they were added. `./gradlew test --tests '*LogStatementSweepTest*'
      --tests '*TelemetryPrivacyTest*'` → BUILD SUCCESSFUL, all passed.
- [X] T039 Confirm SC-006-001 and SC-006-002 hold over one combined synthetic corpus run covering
      every `HearingComparisonClass` and every unpaired sub-reason at once, not only T004's
      per-class fixtures in isolation
      `ReplayComparatorCombinedCorpusTest.java`: 8 distinct shares, one of each class (including an
      ambiguous pairing via two SIT shares at one identity), in a single `compare()` call. Passed
      on first write (2/2) — confirms the comparator built in US1 already satisfies both success
      criteria combined, not just in isolation. `./gradlew checkstyleMain pmdMain` found one
      `avoid-assignment-in-operand` violation (`counter++`), fixed with an `AtomicInteger`, same
      pattern as `ReplayReportPrivacyTest`.
- [X] T040 Write a structural test that `doc/go-live/evidence-index.md` lists all 32 rows
      (G1 + 23 of §9.2, 9 of §9.5 — SC-006-006) and that
      `doc/operations/cutover-rollback-runbook-inputs.md` covers all 21 numbered steps across the
      four procedures (SC-006-007) — reading the documents themselves, not asserting by
      inspection
      `GoLiveDocumentStructureTest.java`: parses each markdown table under its `##` heading and
      counts rows. 4/4 passed on first write. (The row count is 33, not 32 — G1 + 23 + 9 = 33;
      "32 rows" in the task/quickstart prose was an off-by-one against FR-006-031/SC-006-006's own
      row-by-row enumeration, corrected in `quickstart.md` alongside this task.)
- [X] T041 Run `./gradlew build` (PMD, Checkstyle, JaCoCo) and confirm the coverage thresholds are
      unchanged (this slice adds queries and typed records, not a new exemption)
      BUILD SUCCESSFUL: full suite (unit + every `*IT`), Checkstyle, PMD and
      `jacocoTestCoverageVerification` all green against the unchanged LINE ≥ 0.88 / BRANCH ≥ 0.85
      thresholds — no new exclusion added to `gradle/test.gradle`.
- [X] T042 Run every `quickstart.md` step end to end against a fresh local stack
      Steps 1-4 ("Try it locally") are exactly the Testcontainers-backed suites this phase already
      runs repeatedly: `ReplayComparatorTest`/`ReplayReportPrivacyTest`/
      `ReplayComparatorCombinedCorpusTest` (step 4), `GoNoGoEvidenceQueryIT` (step 1),
      `DailyReconciliationQueryIT` (step 2), `AlertCatalogueCoverageIT` (step 3) — all 63 tests
      across these classes pass together in one run. Steps 5-6 are explicitly the owning team's,
      not this repository's build, per the document's own "What the owning team runs" heading —
      not executed here. Fixed two stale lines in `quickstart.md` while reading it end to end:
      "32 rows" → 33 (same off-by-one as T040), and the FR-006-040 "execution-path gap" line,
      superseded by the same 2026-11-02 correction as the rest of this slice.
- [X] T043 Update `CLAUDE.md`'s Setup section once this slice is fully implemented, and note the
      pre-existing constitution drift (H36–H39 not yet amended in) remains open, not closed by
      this slice

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies
- **Foundational (Phase 2)**: empty — no cross-story blocker exists for this slice
- **US1 (Phase 3)**: depends on Setup only — the MVP; fully synthetic, no DB
- **US2 (Phase 4)**: depends on Setup only; introduces `EvidenceTestStore` (T013), which US5 and
  US7 extend
- **US3 (Phase 5)**: depends on US2 (T016/T017's evidence queries, cited by its read-backs) and on
  004's already-shipped actions (no new ones)
- **US4 (Phase 6)**: depends on Setup only. Its alert-6 correction (T027a–T027b, H41) depends on
  this phase's own T023/T026, which it amends in place
- **US5 (Phase 7)**: depends on US2 (T013's `EvidenceTestStore`)
- **US6 (Phase 8)**: depends on US4 (T026's alert-11 stub) and US5 (T029's reconciliation query)
- **US7 (Phase 9)**: depends on US2 (T016/T017) and US5 (T029)
- **Polish (Phase 10)**: depends on every story to be included in that release being complete

### Parallel Opportunities

- T001, T002 in parallel (Setup)
- Within each story's test phase, every `[P]`-marked test task runs in parallel (different files)
- US1 and US4 have no file overlap with US2/US3/US5 and may be staffed in parallel once Setup is
  done
- US6 and US7 cannot start before their dependencies (US4+US5, and US2+US5 respectively) land

---

## Implementation Strategy

### MVP First

1. Setup → US1. Stop and validate against US1's Independent Test. This alone gives the owning
   team the one artefact go/no-go row 5 cannot be met without: the replay comparator, provably
   correct over synthetic inputs, ready for the real run against STE.

### Incremental Delivery

1. Setup → US1 (MVP) → US2 (closes the go-live precondition for every other built row) → US3
   (the runbook inputs, which cite US2's queries) → US4 (the alert catalogue) → US5 (the
   reconciliation) → US6 (the both-paths alert, extending US4+US5) → US7 (decommissioning,
   reusing US2+US5) → Polish.

Each checkpoint above is independently demoable; none breaks an earlier story, because no story
after Setup modifies another story's files except where explicitly noted (US6 extends US4's
`AlertCatalogue` and US5's `DailyReconciliationQuery`; US7 composes US2's and US5's query results
without changing them).

<!-- token-budget: compacted (level=light) on 2026-11-13T14:10:26Z; original at tasks.full.md -->
