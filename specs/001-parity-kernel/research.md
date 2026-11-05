# Phase 0 Research: Parity kernel

Input: `specs/001-parity-kernel/spec.md`, `.specify/memory/constitution.md`, `doc/design/DIGEST.md`.
Grounded against the oracle (`cpp-context-azure-legalaidagency`, `ProbationHearingResultedFilter`)
and the reused parity pack (`service-cp-crime-informant-register`), both read directly from the
sibling checkouts in this workspace.

## R1 — Reproducing "reading of nothing throws" in Jackson, not Jackson's own forgiving semantics

**Decision**: Rule methods navigate the `JsonNode` tree with `get(String)` (returns `null` for an
absent field) and `isArray()`/`elements()`/iteration that a `null` makes throw a
`NullPointerException`, never with `path(String)` (Jackson's forgiving accessor, which returns a
`MissingNode` and silently tolerates absence). A thin internal unchecked exception,
`RuleFailureSignal(String ruleId)`, is thrown at the point a rule would read through an absent or
null value the legacy's JavaScript throws on (an iteration, a length/size read, or a property read
off `undefined`), and `HmppsRedaction.redact(...)` catches only `RuleFailureSignal` at its outermost
boundary to produce `RedactionResult.RulesFailure(ruleId)`. Nothing else is caught there: a genuine
programming defect (e.g. an `IndexOutOfBoundsException` from the port's own logic, not the legacy's)
must not be silently reclassified as a rules failure.

**Rationale**: FR-001-005(d) and FR-001-008 require the port to fail exactly where the legacy throws
— on `defendants`, `offences`, `courtOrderOffences`/`offence`, `judicialResults`
(`removeLaaReferenceFromProsecutionCase`, `removeLaaReferenceFromCourtOrder`,
`removeDelegatedPowersFromProsecutionCase` in the legacy iterate these with no guard; confirmed
against `ProbationHearingResultedFilter/functions.js`) and on `subject.masterDefendant`
(`removeCourtApplicationIfDefendantIsYouth`). Jackson's `path()` would instead silently produce an
empty traversal and let the rule complete with no failure — a materially different, undetectable
divergence from the oracle. Using `get()` plus deliberate iteration reproduces the throw point
exactly; `path()` would mask the P19 carried defects the harness exists to pin (FR-001-027(b)).

**Alternatives considered**: (a) bind each legacy "section" to a lenient, nullable Java accessor
and translate every possible NPE site into `RulesFailure` via a single outer try/catch around the
whole rule chain — rejected because it cannot name *which* rule failed (FR-001-007(d) requires the
failing rule's id), and a defect in the port's own code could be silently absorbed as a "rules
failure" rather than surfacing as a broken build. (b) A schema-validate-first approach that rejects
malformed payloads before running any rule — rejected outright: FR-001-010 forbids schema
validation in the redaction, and the legacy has no such gate either.

## R2 — Reusing the informant register's parity pack

**Decision**: Copy, not depend on, four files from
`service-cp-crime-informant-register:src/test/java/uk/gov/hmcts/cp/informantregister/support/`
(`JsonParity.java`, `JsonParityTest.java`, `RegisteredFieldDeviations.java`,
`ComparatorContractTest.java`) and the vectors resource
(`src/test/resources/comparator-vectors/vectors.json`, 57 entries) into this repository's
equivalent `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/pipeline/support/` and
`src/test/resources/comparator-vectors/vectors.json`, with only the package declaration and
`ComparatorContractTest`'s "binding point" (which wraps `JsonParity.assertMatches` into a boolean
verdict for the vector loop) changed. `ParityCase.java` is **not** copied as-is: it is
informant-register's own domain model (`hearing`/`subscriptions`/`clockPin`/`RefdataAnswer`) and is
re-authored for this service's two-form, two-switch-state corpus shape (FR-001-017, FR-001-018) —
same provenance/digest discipline, different fields.

**Rationale**: the informant-register repo's own `ComparatorContractTest` doc comment states this
exact reuse convention ("drop-in: put this file at `.../support/ComparatorContractTest.java`...");
there is no published shared library for it, no separate Gradle module, and FR-001-014 requires the
pack "copied unchanged" for the parts that are generic (the comparator and the derivation
mechanism), while the case loader is inherently feature-specific. `RegisteredFieldDeviations` is
copied with its one existing entry (`hearingStartTime`, a DST-rendering deviation) removed or kept
inert — this service's first derivation entry is DV-25 (FR-001-038), added fresh, not inherited.

**Alternatives considered**: publishing the pack as a shared library artifact — rejected as
out of scope for this slice and not how the informant-register repo itself expects reuse (no POM/
Gradle coordinates are published for it); the informant-register repo also predates any shared
parity-pack module existing anywhere in the estate.

## R3 — Canonical JSON comparison needs BigDecimal numerics, which needs its own `ObjectMapper`

**Decision**: A small test-support utility, `CanonicalJsonMapper`
(`uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support`), wraps a Jackson 3
(`tools.jackson.databind.ObjectMapper`) configured with
`JsonReadFeature`/`DeserializationFeature` equivalent to `USE_BIG_DECIMAL_FOR_FLOATS` (mirroring
`JacksonConfig.contractObjectMapper()` in the informant-register repo), used by every corpus/twin
test to parse fixtures and expectations. Production code deep-copies the already-parsed inbound
`JsonNode` (constitution Principle IV) rather than re-reading JSON text, so this mapper is test-only
in this slice.

**Rationale**: `JsonParity`'s number comparison (`expected.decimalValue().compareTo(actual.decimalValue())`)
is only exact if both sides were parsed with big-decimal-preserving numerics; parsing fixtures with
the default double-backed mapper would silently reintroduce the floating-point tolerance the design
explicitly rules out (§6.2, FR-001-013).

**Alternatives considered**: none — this is the same configuration the constitution already names
for the service generally; the only decision here is scoping it to test support for this I/O-free
slice.

## R4 — The redaction's internal structure mirrors the legacy's rule functions one-for-one

**Decision**: `HmppsRedaction` exposes one package-private method per rule
(`filterSjpOrBoxHearing`, `removeYouthProsecutionCaseDefendants`,
`removeYouthCourtApplications`, `removeLaaReferenceFromProsecutionCase`,
`removeLaaReferenceFromApplication`, `removeLaaReferenceFromCourtOrder`, `removeJudiciary`,
`removeAssociatedPersons`, `removeDelegatedPowers`, `collapseIfNothingSurvives`), named for
cross-reference against `functions.js`'s matching function (not for the rule id alone, since R5–R7
are three distinct legacy functions despite being adjacent in the FR-001-004 table). The
payload-level guard (`_.some(json)`, FR-001-005(b)) is one shared predicate,
`hasAnyTruthyTopLevelMember(JsonNode)`, called at the top of every rule from R3 onward, matching the
legacy's repeated `_.some(json) && ...` guard literally.

**Rationale**: a 1:1 mapping to the oracle's own function boundaries is what makes "disable one rule,
watch a case fail" (SC-001-007) a trivial, auditable exercise, and keeps each rule independently
unit-testable per FR-001-004's "separately testable unit" requirement.

## R5 — FR-001-020 and FR-001-021, resolved during planning (both Option B)

**Decision**: both `[NEEDS CLARIFICATION]` markers were resolved in a `/speckit-clarify` pass during
planning, 2026-10-21 (spec.md Clarifications, "Session 2026-10-21 (planning)"), each taking Option B
from `checklists/requirements.md`'s Questions 1 and 2: the SIT form the legacy did not read
(FR-001-020) and a SIT share whose logged body is cut short (FR-001-021) both have **no `INACTIVE`
expectation of their own** — each is held to its `ACTIVE` expectation and what is derived from it,
and the harness records the reason and never counts the case-state as a pass. The harness's "no
expectation, with reason" bookkeeping (FR-001-039) is therefore not an interim fallback but the
settled design for these two cases.

**Rationale**: an `INACTIVE` expectation comes only from SIT's own complete logged behaviour
(constitution Principle I); executing the legacy ourselves for either case, or comparing a partial
log line as a text prefix, would substitute a different oracle source for the one the comparator is
built to trust. Both answers keep the same discipline FR-001-019 already applies elsewhere in this
spec.

## Summary carried into Phase 1

Resolving FR-001-020/021 changed only corpus **content and bookkeeping reasons**, not the data model
(`Expectation.None` already models "no expectation, with reason") or the comparator contract.

