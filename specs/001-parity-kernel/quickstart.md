# Quickstart: Parity kernel

This slice is pure Java with no Docker/Testcontainers dependency — no broker, cache, gateway or
database. Everything runs from `./gradlew test`.

## Run just this slice's suites

```bash
./gradlew test --tests "uk.gov.hmcts.cp.probationresultsdistribution.pipeline.*"
./gradlew test --tests "*LegacyTwin*"        # HmppsRedactionLegacyTwinTest
./gradlew test --tests "*ComparatorContractTest*"
./gradlew test --tests "*CorpusParityIT*"    # HmppsRedactionCorpusParityIT
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

## Recording an `ACTIVE`/synthetic expectation from the oracle (T028)

`tools/oracle-harness/run-oracle.js` runs the pinned legacy filter over a payload and writes the
redacted body(ies) plus provenance, ready to merge into a corpus case's `meta.json`
(FR-001-022, FR-001-029). It is plain Node (18+), has no npm dependencies of its own (it resolves
`lodash` from the legacy checkout's own `node_modules`), and never runs inside the Gradle build.

```bash
cd tools/oracle-harness
npm test                      # node --test; no legacy checkout needed, everything is stubbed

node run-oracle.js \
  --payload /path/to/hearing-payload.json \
  --out-dir ../../src/test/resources/corpus/<caseId> \
  --switch BOTH \
  [--form CACHE|FALLBACK] [--hearing-id <id>] [--legacy-repo <path-to-legacy-checkout>]
```

The legacy checkout must be at the pinned commit (`122e2152...`) — the harness refuses to run
otherwise. Output: `expectations/active.json`/`inactive.json` (only for states that produced a
body) and `oracle.json` (provenance + one expectation entry per switch state), which a case's
`meta.json` incorporates by hand. Nothing from the payload or a produced body is ever written to
stdout (FR-001-031) — only the classification, file paths, digests and provenance.
