# Phase 1 Data Model: Parity kernel

No database, no Flyway migration, no Spring context in this slice (constitution Principle V; design
§4.4 — the payload fetch, the switch, the send and the store all belong to slice 002). Every type
below is a plain Java record, sealed interface or enum under
`uk.gov.hmcts.cp.probationresultsdistribution.domain` or `...pipeline`, held in memory for the
duration of one redaction call or one test. "Entity" here means a modelled concept, not a persisted
row.

## `EffectiveSwitchState` (enum) — `domain`

```
ACTIVE, INACTIVE
```
The redaction's second input (FR-001-001). Slice 002 derives this from the four-value recorded
`youth_switch` vocabulary (`ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`,
`UNREADABLE_DEFAULTED_INACTIVE`) by collapsing the two `UNREADABLE_DEFAULTED_*` values onto their
suffix (FR-001-006); this slice only ever sees the two-value effective state.

## `RedactionResult` (sealed interface) — `domain`

```java
sealed interface RedactionResult
    permits RedactionResult.Body, RedactionResult.EmptiedByR1,
            RedactionResult.EmptiedByR11, RedactionResult.RulesFailure {

    record Body(JsonNode payload) implements RedactionResult { }
    record EmptiedByR1() implements RedactionResult { }
    record EmptiedByR11() implements RedactionResult { }
    record RulesFailure(String ruleId) implements RedactionResult { }
}
```
Exactly FR-001-007's four outcomes. `ruleId` is one of `R0, R1, R3–R11` (`String`, not an enum,
because the id is also used verbatim in test and parity-report output naming the failing rule — an
enum would need a matching `fromValue`/`toString` pair for no behavioural gain in this slice).
Slice 002 maps `Body` → attempt send, `EmptiedByR1` → `NOT_APPLICABLE(sjp-or-box-hearing)`,
`EmptiedByR11` → `NOT_APPLICABLE(nothing-left-after-redaction)`, `RulesFailure` →
`FAILED(rules-exception)` (FR-001-007; out of this slice's scope to implement).

## `HmppsRedaction` (pure function, not a data type) — `pipeline`

```java
RedactionResult redact(JsonNode hearingPayload, EffectiveSwitchState switchState)
```
Deep-copies `hearingPayload` once on entry (FR-001-002); never mutates its argument; no field,
clock or random source (FR-001-001). Internals per research.md R1 and R4.

## `LegacyCaseTwin` (test fixture record, not production) — `src/test/java`

| Field | Type | Notes |
|---|---|---|
| `legacyTestName` | `String` | e.g. `T7` |
| `fixturePath` | `String` | under `src/test/resources/fixtures/` |
| `switchState` | `EffectiveSwitchState` | fixed per FR-001-011's table |
| `expectedOutcome` | an assertion closure, not a stored value | twins assert what the legacy case asserts (FR-001-011), not a canned `RedactionResult` |

Fifteen twins (T1–T15), nine distinct fixture files (several legacy tests share a fixture), copied
byte-identical from `cpp-context-azure-legalaidagency/azure-functions/durable-functions/
ProbationHearingResultedFilter/test/`: `hearing.sjp.cases.resulted.in.sjp.session.json` (T1),
`box.hearing.json` (T2), `hearing.prosection.case.with.laa.reference.json` (T3, T13, T14, T15),
`hearing.only.court.application.json` (T4), `hearing.multiple.court.applications.courtorder.json`
(T5), `hearing.prosection.case.with.youth.defendant.json` (T6 `INACTIVE`, T7 `ACTIVE`),
`hearing.prosection.case.with.all.youth.defendant.json` (T8), `hearing.single.prosection.case.with.
all.youth.defendant.json` (T9 `INACTIVE`, T10 `ACTIVE`), `hearing.only.court.application.with.
youth.defendant.json` (T11 `INACTIVE`, T12 `ACTIVE`).

## `CorpusCase` — `src/test/resources/corpus/<caseId>/meta.json` (not a Java class; test-resource shape)

| Field | Notes |
|---|---|
| `caseId` | stable id, e.g. `case-03-masterdefendant-missing` or `case-13-switch-unreadable` |
| `environment` | `SIT` or `SYNTHETIC` (with `syntheticFrom: <caseId>` and the change made) |
| `formsHeld` | subset of `{CACHE, FALLBACK}` |
| `share` | `{hearingId, hearingDay, sharedTimeInstant}` (FR-001-017) |
| `payloadSourceRead` | `CACHE \| QUERY_API \| UNKNOWN` — from SIT telemetry, for the `INACTIVE` expectation's form pairing (FR-001-019) |
| `expectations` | map keyed `(switchState, form)` → `Expectation` (below) |
| `provenance` | capture dates per form/outcome, legacy commit + runtime for an executed expectation, digests (FR-001-029) |

Digests are SHA-256 over each input/expectation file's bytes, pinned in `meta.json`, following the
informant-register `pinning/<id>/meta.json` convention (`digests.convention`,
per-file hash) read during reconnaissance — same idea, this corpus's own fields.

## `Expectation` (test-resource shape, read into a small Java record for the harness)

```java
sealed interface Expectation permits Expectation.ExpectedBody, Expectation.NoMessage,
                                      Expectation.None { }

record ExpectedBody(JsonNode body, ExpectationSource source) implements Expectation { }
record NoMessage(NoMessageKind kind, ExpectationSource source) implements Expectation { }
record None(String reason) implements Expectation { } // FR-001-019/020/021 "no expectation"
```
`NoMessageKind` ∈ `{EMPTIED_BY_R1, EMPTIED_BY_R11, RULES_FAILURE, UNKNOWN}` (a no-message
expectation records its kind "wherever its source shows it", FR-001-018 — `UNKNOWN` when SIT's log
shows no message but not which kind). `ExpectationSource` ∈ `{SIT_LOG, EXECUTED_LEGACY,
DERIVED(dvId: String)}`.

## `ComparatorVerdict` / `JsonParity` — reused, plus one label-assembling wrapper

`JsonParity.assertMatches(JsonNode expected, JsonNode actual, String what)` (copied from
informant-register per research.md R2) is the verdict mechanism; its "verdict" is implicit
(no exception thrown = equal) rather than a returned value type, matching the pack's existing shape.

`JsonParity`'s `what` parameter is a single opaque label — it has no notion of "case", "form" or
"switch state". FR-001-015 and User Story 2's Acceptance Scenario 5 require a comparison failure to
name all three plus each differing JSON path, so this slice adds one thin wrapper,
`ParityAssertions.assertSameBody(String caseId, Form form, EffectiveSwitchState switchState,
JsonNode expected, JsonNode actual)`, in `src/test/java/.../pipeline/support/ParityAssertions.java`,
whose only job is formatting `what` from its three labels before delegating to
`JsonParity.assertMatches`. This is the one wrapper this slice introduces — nothing deeper, and no
change to `JsonParity` itself.

## `RegisteredFieldDeviations` — reused for one new entry

Copied with its existing `hearingStartTime` entry removed (not this service's domain). This slice's
own live entry, added under FR-001-038:

| Property under derivation | Reference | Derivation |
|---|---|---|
| (whole-body derivation, not a single field — see note) | `doc/DEVIATIONS.md` DV-25 | `oracleValue` = the case's `ACTIVE` (or `INACTIVE`, if so configured) expectation; permitted renderings = that same body (or "no message"), for every configured default value |

Note: DV-25's derivation is over an entire case-state's outcome, not one JSON field, unlike the
informant pack's `hearingStartTime` example. `RegisteredFieldDeviations`'s `Function<String,
List<String>>` shape is per-field; the case-13 derivation (FR-001-038) is implemented as a thin
harness-level wrapper that re-expresses "derive case 13 from the default" in those same terms
(oracle value = a serialised form of the `ACTIVE`/`INACTIVE` expectation, permitted = itself) rather
than forcing a square peg through the per-field API — documented here so the Phase 2 task that
builds it does not have to rediscover this seam.

## `DeviationRow` — `doc/DEVIATIONS.md`, a Markdown table, not a Java type

One row per FR-001-034 field set, for DV-01 … DV-26 (`doc/design/DIGEST.md` §4's table is the seed
content), plus the carried/pending-external/accepted-risk entries of FR-001-035. No Java
representation is needed in this slice; `SchemaVocabularyIT` (slice 002) is what ties enum vocab to
SQL, which does not exist yet here.

## `CorpusMetadata` — `src/test/resources/corpus/index.json` (test-resource shape)

Corpus-wide counts and exclusions per FR-001-029's second paragraph: `environment`,
`captureDateRange`, `hearingCount`, `casesPerSwitchState`, `syntheticCount`,
`excludedCases: [{caseId, reason}]`, `comparatorVectorCount` (57, pinned so a future addition is
visible as a diff, never a silent drop per FR-001-014).

## Relationships

```
CorpusCase 1 ──< holds >── 0..2 payload forms (cache, fallback)
CorpusCase 1 ──< has >──── 1..4 case-states (switchState × form present)
CaseState   N ──< one >─── Expectation
CorpusCase 1 ──< derives >─ Case 13 (via RegisteredFieldDeviations + DV-25), per default value
HmppsRedaction ── consumed by ──> every twin and every case-state's harness run
JsonParity  ── compares ──> (harness result, Expectation.ExpectedBody.body)
```
