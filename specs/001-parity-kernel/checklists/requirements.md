# Specification Quality Checklist: Parity kernel — the ported HMPPS rules, the SIT corpus, the comparator and the deviation register

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs). The spec chooses no language,
      framework or library. It does name things the design and the constitution already fix: the
      legacy's source lines; the JSON members the rules touch; the design's component names
      (`HmppsRedaction`, and the informant pack's `JsonParity`, `ParityCase` and
      `RegisteredFieldDeviations`); and the repository paths (`src/test/resources/corpus/`,
      `src/test/resources/fixtures/`, `doc/DEVIATIONS.md`). The brief allows this for a message-driven
      service with frozen contracts. The one library named, lodash, is the legacy's, and it is named
      only to define the semantics the port must reproduce.
- [x] Focused on user value and business needs. The value is that what HMPPS receives does not
      change (D1, D10), and that go-live gate rows 4 and 12 have evidence behind them.
- [x] Written for non-technical stakeholders. The context, user stories, success criteria and out of
      scope are in plain language. The functional requirements are necessarily technical: the slice's
      subject is a verbatim port of JSON rules. That is a qualification, not a gap.
- [x] All mandatory sections completed: user scenarios and testing, requirements, success criteria
      and assumptions. Also present: context, clarifications, dependencies, out of scope and design
      traceability.

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain. Both were resolved during planning, 2026-10-21 (spec.md
      Clarifications, "Session 2026-10-21 (planning)"): FR-001-020 and FR-001-021 both took Option B
      below — no `INACTIVE` expectation for that form/share; it is held to its `ACTIVE` expectation
      and what is derived from it, and the harness records the reason and never counts it as a pass.
      See Questions 1 and 2 below for the recorded choice.
- [x] Requirements are testable and unambiguous. The build checks the mechanical parts. Two custody
      clauses are checked at review, not by the build: FR-001-028's "only under
      `src/test/resources/corpus/`" (no build can recognise SIT data elsewhere) and FR-001-031's
      "never in a document, pull request or review finding". Their mechanical counterparts are tested:
      every corpus file belongs to a case with complete provenance and a pinned digest (FR-001-030,
      FR-001-032), and no value appears in the output of a custody check, a comparison or a parity run
      (User Story 3 scenario 5, User Story 4 scenario 9, SC-001-009). Capturing SIT data within its
      retention windows is an external dependency, not a requirement. The open choices of FR-001-020
      and FR-001-021 have testable interim behaviour: the case-state is reported as having no
      expectation and is never counted as a pass.
- [x] Success criteria are measurable: counts and percentages (15 of 15 twins; 100% of the vectors;
      zero unexplained differences; all 9 required cases; 6 of 6 guards; 100% of files belonging to a
      case and matching their digests; no cut-short body compared whole; 100% of the case-states
      without an expectation listed with their reason; DV-01 … DV-26 present exactly once, with DV-27
      next).
- [x] Success criteria are technology-agnostic. They refer to the build, the twins, the corpus and the
      register, not to tools.
- [x] All acceptance scenarios are defined: five stories, each with Given/When/Then scenarios and an
      independent test that states what it builds on.
- [x] Edge cases are identified: JavaScript truthiness and key-presence quirks, unguarded reads,
      a rule failure that depends on the switch, R1 before R11, numbers the legacy cannot represent
      exactly, cut-short log lines, a payload source SIT's log does not show, pairing by share,
      retention, and case-states and derivations without an expectation.
- [x] Scope is clearly bounded: Out of Scope, plus the per-slice table in Dependencies (what 002, 004
      and 006 own, including 002's open decision on a payload with no `hearing`).
- [x] Dependencies and assumptions identified: slices 002, 004 and 006; the legacy repository and a
      JavaScript runtime; the informant pack; SIT access, and capture within its retention windows;
      the data owner; the design owner.

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria. Each requirement group is tagged
      with its user story, and each story's scenarios and success criteria cover its requirements: US1
      covers FR-001-001 – 012 (SC-001-001, 007, 012); US2 covers FR-001-013 – 016, FR-001-016 through
      scenario 7 (SC-001-002, 009); US3 covers FR-001-028 – 032 (SC-001-009, 010); US4 covers
      FR-001-017 – 027 and 039 (SC-001-003 – 008); US5 covers FR-001-033 – 038 (SC-001-004, 006,
      011). The two custody clauses checked at review are noted above.
- [x] User scenarios cover primary flows: porting and twinning the rules, the comparator, custody of
      the data, the corpus against SIT reality, and registered derivations. The stories are numbered
      in build order: the redaction and the comparator stand alone; custody comes before any SIT case
      is committed; the corpus builds on all three; the derivations build on the corpus, while the
      seeded register lands with the redaction (FR-001-036).
- [x] Feature meets measurable outcomes defined in Success Criteria: every story has at least one
      success criterion, and gate rows 4 and 12 map to SC-001-003 and SC-001-005.
- [x] No implementation details leak into the specification, beyond the design-fixed names noted
      under Content Quality.

## Notes

- **Validation iterations.** Iteration 1 found four issues, all fixed in the spec:
  - FR-001-018 required an expectation for every state and form, contradicting FR-001-020's open
    case. It now names the exception.
  - FR-001-038's derivation for a default of `INACTIVE` read as conditional. It now covers both values
    the default can take, quoting DV-25.
  - DV-09's pinning test in this slice was unstated, although its named privacy sweep lands with
    slice 002. FR-001-036 now names the redaction's no-logging check.
  - The dependency table did not say where the pins of P07, P17 and P36 land. It now does.

  Iteration 2: every item passes except the open marker.

  Iteration 3 (review): nine findings, each checked against the DIGEST, the design and the legacy
  code at `122e2152`, and applied:
  - The spec had decided how to treat SIT logged bodies cut short at the trace limit, which the
    design does not cover (H28 is a capacity measurement, not an oracle rule). FR-001-021 now carries
    the second marker, with interim behaviour (Question 2). The resolution, its assumption and its
    clarification are removed, and H28 is cited only as the source of the limit.
  - A case-13 derivation from an `INACTIVE` expectation that is pending is now pending itself
    (FR-001-038, FR-001-039, SC-001-004, User Story 5 scenario 3).
  - The evidence for the form the legacy read is named: SIT's telemetry shows whether the cache
    query found the payload or fell back to the query API (FR-001-019, FR-001-029, the SIT logged
    evidence entity). A share whose source the log does not show has no `INACTIVE` expectation (Edge
    Cases; User Story 4 scenario 8).
  - Process rules had been written as requirements. The capture-timing rule moved to Dependencies
    (External); FR-001-032 is now the file-level custody check; User Story 3 scenarios 1 and 6 are
    rewritten as checks; User Story 2 scenario 7 covers FR-001-016.
  - The stories are numbered in build order (custody is now User Story 3, the corpus User Story 4),
    and each Independent Test states what it builds on. Following from that, User Story 2 scenario 5
    no longer needs the harness, and User Story 4 scenario 9 carries the parity run's no-values check.
  - Numbers the legacy cannot represent exactly: Edge Cases and Assumptions now say what happens.
  - R9's unguarded `defendants` read is carried in the rule table. R10's cell also omitted
    `defendants` and `offences`, and now names them. FR-001-008 names members, not rules, so it now
    gives the rules that read each guarded member, R9 included.
  - The working-conventions file is referred to by its README label (FR-001-033, FR-001-036). The
    finding also named a Clarifications bullet, but none of them names the file.
  - The 002 hand-off for a parseable payload with no `hearing` is spelt out in Dependencies. One
    correction to the finding's wording: the legacy handler is not silent there, it logs one line
    (`PF/index.js:10`) and sends nothing.
- **Clarify-style pass.** Sixteen ambiguities were resolved from the DIGEST, the design and the
  legacy code, and recorded under the spec's Clarifications, session 2026-10-21. In iteration 3 the
  answer on cut-short bodies was withdrawn (it is now a marker), and an answer on the evidence for the
  form the legacy read was added. The two questions left open are the markers below.
- **Resolved, but to be confirmed with the design owner (not markers):**
  - **The legacy handler's `hearing` check** (`PF/index.js:9`). It is outside the rules, so this slice
    only states that the redaction fails as `PF/functions.js` would. What the HMPPS leg does with such
    a payload is for slice 002 and the design owner (Dependencies, the 002 row).
  - **Data-owner re-approval of the controls for `EXT_` data** (§6.4). The 2026-10-19 decision to
    commit SIT data stays in force until the data owner says otherwise.
- **Process.** This spec was written by hand, as the slice plan requires. The `before_specify` hook
  (branch creation) and the `after_specify` hook (commit) were not run, and `.specify/feature.json` was
  not changed.
- **Cross-spec pass, 2026-10-21.** Question 2's context no longer quotes a production figure: H28's
  share of bodies at the trace limit is stated without its number. The other slices now match this
  spec's hand-offs, so its Dependencies table stands unchanged: 006 carries FR-001-021 as an open
  question with this spec's interim rule (its FR-006-007); 002 states the outcome of a payload with
  no `hearing` as an open item for the design owner (its Edge Cases and Assumptions); 002 lands the
  service-side pin of P36 (`FailureSignalIT`); and 004 pins the resend halves of DV-07 and DV-17
  (FR-004-050a).
- **Public-repository hygiene.** The spec contains no personal names (roles only), no hostnames or
  URLs, no cloud resource names, no environment-specific values and no payload content. Legacy sources
  are cited by file and line only, and the working-conventions file by its README label.

### Question 1: The `INACTIVE` expectation for the form SIT's legacy did not read

**Context**: FR-001-020: "The `INACTIVE` expectation for the other form of a SIT share, the form
SIT's legacy did not read (normally the fallback form), is [NEEDS CLARIFICATION …]". Design §6.1 and
row A3-67 take SIT's logged body as the `INACTIVE` expectation, but that body comes from the form the
legacy read at the time, normally the cache form. A3-67 runs the legacy only for `ACTIVE` and
synthetic cases. The cache-form body and a fallback-form body for the same share differ: the fallback
form has no `cpsProsecutorIds` and a different `hearing` field set (§5.3 note; Q19).

**What we need to know**: where does the `INACTIVE` expectation of the other form of the same share
come from?

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | The executed legacy, as for `ACTIVE` | Every case-state has an expectation, but this form's `INACTIVE` evidence is the baseline code rather than SIT's own output (the U14 version caveat applies, with no SIT cross-check) |
| B | No `INACTIVE` expectation for that form; it keeps its `ACTIVE` expectation and the case-13 derivation from it | Only the evidence SIT actually produced is used. The fallback form under `INACTIVE` is pinned only through the cache form's rules and the twins |
| C | `INACTIVE` expectations for that form only from shares SIT's legacy actually read in that form (a cache miss at the time) | Real SIT evidence for both forms, but the fallback form's `INACTIVE` coverage depends on how many SIT cache misses the capture window holds |
| Custom | Provide your own answer | State the source and whether it counts towards gate row 4 |

**Your choice**: B — resolved 2026-10-21 (spec.md Clarifications, "Session 2026-10-21 (planning)").

### Question 2: The `INACTIVE` expectation of a share whose SIT logged body is cut short

**Context**: FR-001-021: "The `INACTIVE` expectation of a SIT share whose logged body is cut short is
[NEEDS CLARIFICATION …]". SIT's telemetry keeps at most 32,768 characters of a trace line. A share
of production bodies reach that limit (H28, a capacity measurement, not an oracle rule); SIT's share
is not known. Design §6.1 and row A3-67 take SIT's logged body
as the `INACTIVE` expectation and run the legacy only for `ACTIVE` and synthetic cases. Neither covers
a logged body that was cut short, and a cut-short body cannot be compared as a whole body.

**What we need to know**: where does the `INACTIVE` expectation of such a share come from, if
anywhere?

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | The executed legacy, admitted only where its own log line for that output matches the kept text character for character; a share that does not match is excluded, with its reason | Every admitted share has an `INACTIVE` expectation, and SIT's own evidence still checks the part its log kept. The part the log cut off rests on the baseline code, not on SIT's deployed build (the U14 version caveat applies to it). Goes beyond A3-67's "only for `ACTIVE` and synthetic cases". If chosen, FR-001-018 and FR-001-029 add that each recording keeps the executed legacy's log-line text, so the build can re-check the match, and the list of expectation sources gains "the executed legacy, checked against a cut-short SIT log" |
| B | No `INACTIVE` expectation for that share; its `ACTIVE` expectation, and what is derived from it, stand; the share is recorded as excluded from the `INACTIVE` comparison, with its reason | Only evidence SIT produced is used, within A3-67's wording. The largest hearings, where the rules have the most to remove, lose their `INACTIVE` pin; the twins and the `ACTIVE` expectations carry them alone |
| C | The kept text compared as a prefix of the port's rendered body, with no executed legacy | SIT's own evidence is used as far as it goes, with no executed legacy. The check compares characters, not JSON: the port's rendering must match the legacy's over the kept part (member order, number and string rendering), which the semantic comparator (FR-001-013) does not require, and the body's bytes are slice 002's. The part the log cut off is not checked |
| Custom | Provide your own answer | State the source and whether such shares count towards gate row 4 |

**Your choice**: B — resolved 2026-10-21 (spec.md Clarifications, "Session 2026-10-21 (planning)").
