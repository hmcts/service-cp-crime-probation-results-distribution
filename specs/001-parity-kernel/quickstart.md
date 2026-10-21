# Quickstart: Parity kernel

This slice is pure Java with no Docker/Testcontainers dependency — no broker, cache, gateway or
database. Everything runs from `./gradlew test`.

## Run just this slice's suites

```bash
./gradlew test --tests "uk.gov.hmcts.cp.probationresultsdistribution.pipeline.*"
./gradlew test --tests "*LegacyCaseTwin*"
./gradlew test --tests "*ComparatorContractTest*"
./gradlew test --tests "*CorpusParityIT*"   # name proposed; see tasks.md
```

## Run everything this slice touches, with full analysis

```bash
./gradlew build
```
Runs PMD, Checkstyle and the JaCoCo coverage gate alongside the tests (constitution Principle VIII).
No `integrationTest` task exists in this repo; `./gradlew test` is the whole suite.

## Add a new blind-spot or SIT corpus case

1. Add `src/test/resources/corpus/<caseId>/meta.json`, `inputs/{cache.json,fallback.json}` (as
   available), `expectations/{active.json,inactive.json}` (or a `no-message` marker file) per
   `data-model.md`'s `CorpusCase`/`Expectation` shapes.
2. Pin each file's SHA-256 digest in `meta.json` — the custody checks (FR-001-030) fail the build on
   a mismatch or a missing provenance field, by design.
3. Update `src/test/resources/corpus/index.json`'s counts and, if the case is excluded from one
   switch state, its `excludedCases` entry with a reason.
4. Never commit PRD/PRP data. SIT or synthetic-from-SIT only (constitution Principle VII).

## Add or amend a deviation

1. Add or amend the row in `doc/DEVIATIONS.md` first, in the same commit as the behaviour that
   relies on it (constitution, Development Workflow & Quality Gates).
2. If the deviation changes an expected outcome, express it as a derivation
   (`RegisteredFieldDeviations` or the case-13 wrapper — see `data-model.md`), never by editing a
   golden file.

## Where the oracle lives

`cpp-context-azure-legalaidagency` at commit `122e2152`,
`azure-functions/durable-functions/ProbationHearingResultedFilter/` (`functions.js`, `index.js`,
`index.test.js`). Run outside this build; its Jest fixtures are copied byte-identical, never
regenerated to make a test pass (constitution Principle I, II).
