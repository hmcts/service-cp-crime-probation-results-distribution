# Feature Specification: Parity kernel — the ported HMPPS rules, the SIT corpus, the comparator and the deviation register

**Feature Branch**: `001-parity-kernel`
**Created**: 2026-10-21
**Status**: Draft
**Input**: Design rev 3.6 (2026-10-20; decisions H1–H34): §5 (porting map — §5.1 the HMPPS rules, §5.3
everything around them), §6.1–§6.4 (the oracle, the comparator, the blind-spot cases, corpus provenance
and personal data), §7.0–§7.2 (defect classes, P01–P36, the deviation register seed); rows H21, H28,
A3-17, A3-18, A3-28, A3-67 and A3-78 — as restated in `doc/design/DIGEST.md` §2 (quality gate), §4
(registered deviations), §10 (redaction) and §16 (open items). Slice 001 of six; it depends on none.

## Context

The legacy function app redacts every hearing payload by a set of JavaScript rules before it sends the
result to HMPPS (`PF/functions.js` at `122e2152` in `cpp-context-azure-legalaidagency`). This service
copies those rules verbatim, quirks included, because the instruction is lift and shift (D1) and nothing
about what HMPPS receives changes in this delivery (D10). "Verbatim" can only be proved by running the
legacy and comparing, so this slice builds the parity kernel every later slice stands on.

It delivers four things, and none of them does any I/O:

1. **The HMPPS redaction** (`HmppsRedaction`, design §4.4): rules R0–R11 as one pure function of a
   hearing payload and the effective youth-switch state. R12, which logs the payload, is not ported
   (DV-09).
2. **The SIT corpus and its oracle**: real SIT hearing payloads in both payload forms, with expected
   outcomes in both switch states. With the switch off, the expectation is the body SIT itself logged.
   With the switch forced on, and for synthetic cases, it is the output of the legacy JavaScript, run
   by us (H21; row A3-67).
3. **The comparator**: the one definition of "the same body", reused from the informant register's
   parity pack. The corpus harness uses it here, and the pre-cutover SIT → STE replay uses it in slice
   006.
4. **The deviation register** `doc/DEVIATIONS.md`, seeded from design §7.2 (DV-01 … DV-26; the next
   free id is DV-27). Where a deviation changes an expected outcome, the harness derives the new
   expectation from the register (DV-25, corpus case 13). Any other difference fails the build.

Slice 002 builds the HMPPS leg on this kernel. It reads the payload and the switch, calls the
redaction, maps the result to the leg's status and reason, and sends. Nothing in this slice reads a
queue, a cache, users-groups or a database, and nothing in it sends anything.

*Numbering:* requirement and success-criterion ids carry the slice number (FR-001-NNN, SC-001-NNN), so
they stay unique across slices 001–006.

## Clarifications

### Session 2026-10-21

Resolved from the design and the legacy code at `122e2152`; each answer is already applied to the
sections below.

- Q: Where does a SIT case's expected outcome come from when the switch is `INACTIVE`? → A: From SIT's
  own logged outcome for that share: either the body the legacy logged as it published it, or no
  message. It is used only where SIT's log shows the legacy read the switch as inactive for that share,
  and it is the expectation for the form the legacy read at the time, normally the cache form (H21; row
  A3-67; §6.1; §2.3 (ii)).
- Q: What shows which form the legacy read for a share? → A: SIT's telemetry. The legacy's cache query
  logs whether it found the payload in the cache or fell back to the query API
  (`HearingResultedCacheQuery/index.js:172-185`). Where SIT's log does not show it, the logged outcome
  cannot be assigned to a form, so neither form takes an `INACTIVE` expectation from it (Edge Cases).
- Q: Where do the `ACTIVE` expectations and the synthetic cases' expectations come from? → A: From
  running the legacy HMPPS filter and the publisher's body construction at `122e2152`, with the switch
  forced to a fixed value rather than looked up. The VEP code is not run (A3-67; A3-18; §6.1; H5).
- Q: How is a logged outcome paired with a captured payload? → A: By share: the same hearing id,
  hearing day and `sharedTime` instant. The two forms render that instant differently (§5.3 note; H17).
  A payload captured after a re-share is never paired with the earlier share's body, because the
  expected output for a payload is what the legacy sent for that payload (A3-67).
- Q: What does corpus case 13 (the switch read fails) expect, and how many such cases are there? → A:
  One derived expectation per corpus payload; it is never recorded separately. When the read fails the
  legacy sends no message (P16), and under DV-25 the expectation is the outcome for the configured
  default, which is the `ACTIVE` expectation by default (A3-28; §6.2; §6.3; DV-25). Each cause of an
  unreadable read is tested in slice 002.
- Q: Does the redaction take the four recorded `youth_switch` values? → A: No. It takes the effective
  state, `ACTIVE` or `INACTIVE`. R3 and R4 run for a read of `ACTIVE` and for
  `UNREADABLE_DEFAULTED_ACTIVE`. The recorded vocabulary and the mapping to the effective state belong
  to slice 002 (H7; H8; DIGEST §6 step 3).
- Q: When a rule meets a shape the legacy cannot handle, does the port add a guard? → A: No. The
  legacy throws there, and the port reports a rules failure with no body. It never adds a guard, skips
  the rule or returns a partial body (P19 is carried; standing rule of §7.0). Slice 002 records the
  failure as `FAILED(rules-exception)`.
- Q: How are numbers, member order and array order compared? → A: Numbers by their decimal value,
  ignoring scale and with no tolerance. Member order and whitespace are ignored; array order counts.
  Null, an absent member and an empty value are all different. This is the informant pack's comparator
  and its vectors (§6.2; §6.1).
- Q: May a failure message show the values that differ? → A: No. Corpus content never appears in logs
  or reports (§6.4; constitution Principle VII). A failure names only the case, the form, the switch
  state and the JSON paths.
- Q: Which blind-spot cases are required? → A: Cases 1, 2, 3, 4, 5, 8, 9, 11 and 12, each in both
  switch states. Each comes from SIT where SIT has the shape, or else from a synthetic case built from a
  real payload and marked as synthetic; case 1 is always synthetic (A2-8). Case 13 is derived. Cases 6,
  7 and 10 are dropped (§6.3).
- Q: Where is "the register" in which a carried guard with no corpus case is listed as an accepted
  risk? → A: `doc/DEVIATIONS.md`, next to the carried defects. It is the repository's only register of
  behaviour that differs from the legacy (§5.3; §6.3; constitution Principle I).
- Q: Must the pinning test of every seeded deviation row exist when this slice merges? → A: Every row
  names its pinning test as design §7.1 and §7.2 name it. The test lands with the row's behaviour, in
  the slice that builds that behaviour, and no behaviour lands without its test (constitution
  Principles I and II). A row whose behaviour is not built yet says so.
- Q: Is `hearing.id` checked at run time? → A: No. It is checked by the harness over the whole corpus
  (Q13, narrowed; §6.3; §9.2 row 12).
- Q: How large is the corpus? → A: The design sets no size: §6.4 left it to D8, and H21 closed D8
  without setting one. This spec sets minimum coverage, and the corpus records its own counts (§6.1;
  §6.4).
- Q: Are T13 and T14 twinned, although they test nothing? → A: Yes, faithfully, and corpus case 2 is
  the check that tests something (§5.1). The legacy suite depends on the order its tests run in; the
  twins do not reproduce that (P34).
- Q: What is the expectation for a share for which SIT's legacy sent nothing? → A: No message. SIT's
  log for that share must show it: either the filter's result was empty (`PF/functions.js:22` logs it,
  and `HMPPSExternalPublisher/index.js:68-70` publishes nothing for it), or the filter failed after the
  switch was read as inactive (`PF/index.js:29-32`) (§6.1: "the HMPPS body or 'no message'").

## User Scenarios & Testing *(mandatory)*

### User Story 1 - The ported HMPPS rules produce the legacy's output, and nothing else (Priority: P1)

A developer porting the rules, and later the HMPPS leg in slice 002, which calls them, needs one
function that takes a hearing payload and the effective youth-switch state. It must return exactly what
the legacy filter produced: a body, nothing, or a failure. It must change nothing else, not even its
own input.

**Why this priority**: this is the code everything sent to HMPPS passes through. Every other story in
this slice exists to prove it right. The seeded deviation register of User Story 5 lands with it,
because leaving out R12 is DV-09 (FR-001-036).

**Independent Test**: run the twins of the fifteen legacy Jest cases over their fixtures, copied
byte-identical, together with the purity checks. Nothing else is needed: no corpus, no comparator
vectors, no other slice.

**Acceptance Scenarios**:

1. **Given** the legacy SJP fixture (T1) or box-hearing fixture (T2), **When** it is redacted, **Then**
   there is no body and the result is "emptied by R1".
2. **Given** the legacy fixtures carrying `laaApplnReference` in prosecution-case offences (T3), in
   application-case offences (T4) and in court-order offences (T5), **When** each is redacted, **Then**
   the reference is gone where the legacy case asserts it is gone.
3. **Given** the youth fixtures (T6–T12), **When** each is redacted in the switch state the legacy case
   used, **Then** the twin's assertions are the legacy case's own. For example, under `ACTIVE` T7
   removes the youth defendant and keeps both cases, while under `INACTIVE` T6 keeps every defendant.
   Where the legacy expects `{}` (T10, T12), the result is "emptied by R11".
4. **Given** T15's fixture, **When** it is redacted, **Then** the prosecution-case judicial results'
   `delegatedPowers`, the defendants' `associatedPersons` and `hearing.judiciary` are gone.
5. **Given** any payload, **When** it is redacted, **Then** the caller's payload is unchanged, no log
   line or telemetry is written, and a second call returns an equal result.
6. **Given** a payload in which a prosecution-case offence that survives R3 has no `judicialResults`,
   **When** it is redacted, **Then** the result is a rules failure naming R10, with no body. No guard
   skips the offence.
7. **Given** a court application whose subject has no `masterDefendant`, **When** it is redacted under
   `ACTIVE`, **Then** the application is removed (P18, carried); **When** it is redacted under
   `INACTIVE`, **Then** it is kept.

---

### User Story 2 - One comparator decides whether two bodies are the same (Priority: P1)

The corpus harness, the reviewers and the pre-cutover replay (slice 006) need one comparator. Its
verdicts must be fixed in advance, and its failure messages must be safe to show in this public
repository's CI logs.

**Why this priority**: two go-live gates are decided by it: the SIT-data unit tests (§9.2 row 4) and
the STE replay (row 5). A lax comparator would make both gates a formality.

**Independent Test**: run the comparator over the reused comparator vectors, then force one mismatch
and inspect its message. It needs nothing from the other stories.

**Acceptance Scenarios**:

1. **Given** two bodies that differ only in member order or whitespace, **When** they are compared,
   **Then** they are equal.
2. **Given** `12.5` against `12.50`, or `1` against `1.0`, **When** compared, **Then** they are equal;
   **Given** two numbers that differ in their last digit, or `1` against `"1"`, **Then** they are
   different.
3. **Given** a member that is `null` on one side and absent on the other, or `[]` against absent,
   **When** compared, **Then** they are different.
4. **Given** two arrays with the same elements in a different order, **When** compared, **Then** they
   are different.
5. **Given** two bodies that differ, labelled with a case, a form and a switch state, **When** the
   difference is reported, **Then** the message names the case, the form, the switch state and each
   differing JSON path, and contains no value from either side.
6. **Given** every reused comparator vector, **When** it is run, **Then** the comparator returns the
   verdict the vector states.
7. **Given** two bodies supplied by a caller other than the corpus harness, as the replay of slice 006
   supplies them, **When** they are compared, **Then** the verdict follows the same rules, and each
   difference is reported by its JSON path with no value from either side (FR-001-016).

---

### User Story 3 - SIT data is held only as the public repository allows (Priority: P1)

This repository is public. SIT holds no production data, so SIT payloads and SIT's logged bodies may
be committed as test fixtures, but only in one place, with their provenance and digests, and never
printed. Production data never comes in.

**Why this priority**: a breach here is an incident, not a defect, and no SIT case can be committed
until these rules hold. That is why this story comes before the corpus (User Story 4).

**Independent Test**: on top of User Story 2's comparator, and with no SIT data, run the provenance and
digest checks over a scratch corpus. Tamper with one file, remove one provenance item and add a file
that belongs to no case, and watch the build fail each time. Force a comparison failure and inspect
the output for values. These checks are in place before any SIT case is committed.

**Acceptance Scenarios**:

1. **Given** the committed corpus, **When** the build runs, **Then** every file under
   `src/test/resources/corpus/` belongs to a case that has provenance and a pinned digest for that
   file, and no case names an environment other than SIT or synthetic built from SIT. A file that
   belongs to no case fails the build, which names the file.
2. **Given** a corpus file edited without its pinned digest being updated, **When** the build runs,
   **Then** it fails and names the case.
3. **Given** a case with no provenance, or with an environment other than SIT or synthetic built from
   SIT, **When** the build runs, **Then** it fails.
4. **Given** the committed corpus, **When** its metadata is read, **Then** it states the environment,
   the capture dates, the number of hearings, the cases per switch state, how many cases are synthetic,
   the excluded cases with their reasons, and each file's digest.
5. **Given** any run of the custody checks or of the comparator, passing or failing, **When** its
   output is read, **Then** no corpus value appears in it.
6. **Given** a case, **When** its provenance is read, **Then** it records the capture date of each
   form it holds and of its logged outcome, its environment, the source of each expectation, and, for
   a SIT case, the payload source the legacy read for the share, or that SIT's log does not show it.

---

### User Story 4 - The port is pinned to what the legacy actually sent from SIT (Priority: P1)

The go/no-go decision needs evidence for go-live gate row 4. For real SIT hearings, the port must
produce exactly what the legacy sent from SIT with the switch off, and exactly what the legacy code
produces with the switch forced on. Nothing may differ unless the difference has a number.

**Why this priority**: H21 makes these tests the build-time half of the quality gate; the one-week STE
replay (slice 006) is the run-time half. Without them, "verbatim" is an opinion.

**Independent Test**: on top of User Story 1's redaction, User Story 2's comparator and User Story 3's
custody checks, run the parity suite over the committed corpus. What this story adds is the corpus
and its oracle. The suite reports zero unexplained differences, every required case is present, and
both whole-corpus checks pass.

**Acceptance Scenarios**:

1. **Given** a SIT case whose share SIT logged as published, with the switch read as inactive, **When**
   it is redacted under `INACTIVE` in the form the legacy read, **Then** the result equals the logged
   body.
2. **Given** the same case, **When** it is redacted under `ACTIVE`, **Then** the result equals the
   output of the legacy, executed with the switch forced on for that payload.
3. **Given** a SIT share whose logged body was cut short at the trace limit, **When** the suite runs,
   **Then** the cut-short body is never compared as a whole body, and the share's `INACTIVE`
   case-state is reported as having no expectation (FR-001-021) and is never counted as a pass.
4. **Given** a synthetic case, **When** the suite runs, **Then** both of its expectations are
   recordings of the executed legacy, and it is marked synthetic, with the SIT case it was built from.
5. **Given** a share for which SIT's legacy sent nothing (the filter's result was empty, or the filter
   failed after an inactive read), **When** it is redacted under `INACTIVE`, **Then** the port produces
   no body either, of the same kind.
6. **Given** a deliberate change to any one rule, **When** the suite runs, **Then** at least one case
   fails and the build fails.
7. **Given** the whole corpus, **When** the suite runs, **Then** every body produced carries
   `hearing.id`, and each carried guard is reached by a case or listed as an accepted risk.
8. **Given** a case in the form SIT's legacy did not read (FR-001-020), or a share for which SIT's log
   does not show the payload source the legacy read (FR-001-019), **When** the suite runs under
   `INACTIVE`, **Then** the case-state is reported as having no expectation, with its reason, and is
   never counted as a pass.
9. **Given** any parity run, passing or failing, **When** its report and its failure messages are
   read, **Then** they name only cases, forms, switch states, DV ids and JSON paths, and no corpus value
   appears in them.

---

### User Story 5 - Every intended difference is registered, derived and tested; any other fails the build (Priority: P2)

Reviewers and later slices need one register of every deliberate difference from the legacy. From it
the harness derives what the port must produce instead, so a difference with a number passes and a
difference without one fails.

**Why this priority**: in this slice the register's derivation is used only by DV-25 (corpus case 13).
Its other rows are used by later slices. The twins, the comparator and the corpus (User Stories 1, 2
and 4) already lock the port without the derivation machinery. The seeded register itself is still
not optional: it lands no later than the redaction (FR-001-036), because leaving out R12 is DV-09.

**Independent Test**: scenarios 1 and 2 need only the register. The others run on top of User Story
4's harness: run the case-13 derivations, then withdraw DV-25's row in a scratch copy and watch every
case-13 derivation fail.

**Acceptance Scenarios**:

1. **Given** the register, **When** it is read, **Then** it holds one row for each of DV-01 … DV-26
   (withdrawn, unallocated and reserved ids included) and names DV-27 as the next free id.
2. **Given** an active row, **When** it is read, **Then** it gives the legacy behaviour with its
   citation, the new behaviour, the reason, the approval (by role and decision id), whether it changes a
   message body, its pinning test, and whether its behaviour is built yet. Once the behaviour is built,
   the pinning test exists and passes.
3. **Given** any corpus payload and a failed switch read, **When** the suite runs, **Then** the
   expectation is derived under DV-25 as the outcome for the configured default (`ACTIVE` unless set
   otherwise), where the legacy gives no message, and the port's result matches it. A derivation whose
   source expectation does not exist (FR-001-019 to FR-001-021) is listed as having no expectation and
   is never counted as a pass.
4. **Given** DV-25's row withdrawn from the register, **When** the suite runs, **Then** every case-13
   derivation fails and quotes DV-25.
5. **Given** a difference between the port and the oracle that no registered derivation explains,
   **When** the suite runs, **Then** the build fails.
6. **Given** a carried guard that no corpus case reaches, **When** the register is read, **Then** it
   lists the guard as an accepted risk.

---

### Edge Cases

- **A payload with no `hearing`, or a null one**: R1 reads `hearing`, so the redaction gives a rules
  failure, as `PF/functions.js` would throw. The legacy handler returns early on such a payload before
  any rule runs (`PF/index.js:9`); that check sits outside the rules. What the HMPPS leg does with such
  a payload is slice 002's decision (an open issue: the design does not cover it).
- **Non-boolean flags**: `isYouth: "false"` is truthy, so under `ACTIVE` the defendant is removed (case
  9). `isBoxHearing: "false"` is truthy too, so R1 empties the payload.
- **Numbers**: any zero (`0`, `0.0`, `-0`) is falsy. The port keeps every number at its exact decimal
  value (§6.2). The legacy's runtime holds numbers as double-precision values and renders them again,
  so a payload number it could not represent exactly (an integer beyond double precision, for
  example) is reported as an unexplained difference against the oracle, whether SIT's logged body or
  the executed legacy. It is investigated, never waved through, and any difference the legacy's
  rendering causes needs a DV row (D10) or an exclusion decided by the design owner.
- **Present but falsy**: `policeCases: null` is kept, because R0 tests truthiness. A falsy
  `hearing.judiciary` (`null`, `""`, `false`) is kept, because R8 tests truthiness there. A top-level
  `judiciary` is removed whatever its value, because R8 tests key presence. `delegatedPowers: null` on a
  prosecution-case judicial result is removed, because R10 tests key presence.
- **Present but null lists**: where a rule iterates a list, or reads its length, and the list is
  `null`, the rule fails as the legacy does. For example, `prosecutionCases: null` that reaches R11
  fails there.
- **Empty arrays are truthy**: `courtApplications: []` passes every guard. With no prosecution case
  and no application left, R11 empties the payload.
- **R1 first**: once R1 has emptied the payload, every later guard fails. The result is "emptied by
  R1", not "emptied by R11".
- **The failing rule can depend on the switch**: a prosecution case without `defendants` fails in R3
  under `ACTIVE` and in R5 under `INACTIVE`; R9 and R10 would fail on the same shape if they were
  reached. The expectation records the rule wherever the oracle shows it.
- **An application whose subject is missing**: under `ACTIVE`, R4 fails. Under `INACTIVE` R4 does not
  run, and R5–R11 decide the result.
- **Everything removed under `ACTIVE`** (case 12): youth defendants only, plus applications that are
  youth or have no `masterDefendant`. R11 empties the payload, so HMPPS gets nothing.
- **Application-only hearings** (no prosecution case): redacted like any other and kept. HMPPS rejects
  them with 400; that is slice 002's to count (D16 (a); H33).
- **The fallback form**: it has no `policeCases` (R0 does nothing), no `cpsProsecutorIds` (the body
  has none; Q19) and a different `hearing` field set. It is redacted as it comes.
- **A cut-short SIT log line**: the logged body is never compared as a whole body and never repaired.
  Until FR-001-021 is decided, the share's `INACTIVE` case-state has no expectation; it is listed and
  never counted as a pass.
- **SIT's log does not show the payload source the legacy read** (cache or query API): the logged
  outcome cannot be assigned to a form, so neither form of that share has an `INACTIVE` expectation.
  Both case-states are listed with the reason and never counted as a pass. The case keeps its `ACTIVE`
  expectations and what is derived from them.
- **The switch read for a share was not inactive** (active, an empty list, or a failed read): the share
  is not an `INACTIVE` SIT case.
- **A re-share after capture**: the captured payload and the logged body belong to different shares, so
  they are not paired.
- **A case-state with no expectation** (FR-001-019 to FR-001-021), or a case-13 derivation whose source
  expectation does not exist (FR-001-038): it is listed in the report with its reason and never
  counted as a pass.
- **A registered derivation meets an oracle value its rule does not describe**: that is a difference,
  not a pass.
- **Retention**: SIT's telemetry keeps logs about 90 days and `EXT_` keys live about 91 days. A day not
  captured in time cannot become a SIT case. A fallback form captured later has no `INACTIVE`
  expectation of its own (FR-001-020).

## Requirements *(mandatory)*

### Functional Requirements

**The redaction** *(User Story 1)*

- **FR-001-001**: The service MUST provide the HMPPS redaction (`HmppsRedaction`) as a pure function
  with two inputs: a hearing payload, in either form, and the effective youth-switch state (`ACTIVE` or
  `INACTIVE`). It performs no I/O, reads no clock, uses no randomness, writes no log line or telemetry,
  and returns the same result for the same inputs. (§4.4; §5; constitution Principle V)
- **FR-001-002**: The redaction MUST deep-copy the payload once, on entry, and apply every rule to that
  copy, leaving the caller's payload unchanged. This ports the legacy's activity serialisation
  boundary. Every value it does not remove stays as it was; numbers keep their exact decimal value.
  (§5, the deep-copy boundary; constitution Principle IV)
- **FR-001-003**: The redaction MUST apply the legacy HMPPS rules verbatim and in the legacy's order:
  R0, R1, R3, R4, R5, R6, R7, R8, R9, R10, R11. There is no R2. Each rule is a separately testable unit
  named by its rule id. R12, which logs the filtered payload (`PF/functions.js:22`), is not ported
  (DV-09). (§5.1; D1; constitution Principle I)
- **FR-001-004**: Each rule MUST have the legacy's condition and effect, as below (`PF/functions.js` at
  `122e2152`). Every rule after R1 first requires the payload-level guard of FR-001-005 (b):

  | Rule | Applies when | Effect | Carried |
  |---|---|---|---|
  | R0 (`:5-7`) | the payload's `policeCases` is truthy | removes `policeCases` | — |
  | R1 (`:9, 26-28`) | `hearing.isSJPHearing`, `hearing.isBoxHearing` or `hearing.isVirtualBoxHearing` is truthy | the payload becomes `{}`: result "emptied by R1" | — |
  | R3 (`:11-13, 30-39`) | the switch is `ACTIVE` and `hearing.prosecutionCases` is truthy | removes every prosecution-case defendant whose `isYouth` is truthy, then every case left with no defendant | `defendants` read without a guard (P19) |
  | R4 (`:11-14, 41-47`) | the switch is `ACTIVE` and `hearing.courtApplications` is truthy | keeps a court application only if `subject.masterDefendant` is truthy and its `isYouth` is not | an application with no `masterDefendant` is removed (P18) |
  | R5 (`:15, 54-64`) | `hearing.prosecutionCases` is truthy | removes `laaApplnReference` from every offence of every prosecution-case defendant | `defendants`, `offences` read without a guard (P19) |
  | R6 (`:16, 66-80`) | `hearing.courtApplications` is truthy | removes `laaApplnReference` from the offences of each application's cases, where they are present | guarded in the legacy |
  | R7 (`:17, 83-93`) | `hearing.courtApplications` is truthy, for each application whose `courtOrder` is truthy | removes `laaApplnReference` from each court-order offence's `offence` | `courtOrderOffences`, `offence` read without a guard (P19) |
  | R8 (`:18, 95-102`) | always | removes a top-level `judiciary` if the key is present, and `hearing.judiciary` if it is truthy | — |
  | R9 (`:19, 104-115`) | always | removes a top-level `associatedPersons` if the key is present, and `associatedPersons` from every prosecution-case defendant | `defendants` read without a guard (P19); not inside court applications (P21) |
  | R10 (`:20, 117-131`) | `hearing.prosecutionCases` is truthy | removes `delegatedPowers`, if the key is present, from every judicial result of every prosecution-case offence | `defendants`, `offences`, `judicialResults` read without a guard (P19); `plea.delegatedPowers` and application offences untouched (P35, P21) |
  | R11 (`:21, 49-52`) | always | the payload becomes `{}` unless a prosecution case or a court application is left: result "emptied by R11" | a present-but-null list fails when its length is read |

  (§5.1; A3-17)
- **FR-001-005**: Every test a rule makes MUST follow JavaScript semantics, as the legacy evaluates
  it:
  (a) **Truthiness.** `false`, `null`, an absent member, any numeric zero however it is written, and
  the empty string are falsy. Everything else is truthy, including an empty array, an empty object and
  the strings `"false"` and `"0"`.
  (b) **The payload-level guard.** The legacy tests the payload before R3–R11 (lodash `_.some` over its
  top-level values). The guard holds if and only if at least one top-level member's value is truthy.
  (c) **Key presence.** Where the legacy tests whether a key is present rather than whether its value
  is truthy (top-level `judiciary` and `associatedPersons`; a judicial result's `delegatedPowers`), a
  present member is removed whatever its value.
  (d) **Reads of nothing.** Where the legacy reads a member of an absent or null value, or iterates
  one, or reads its length, the rule fails (FR-001-008).
  (§5, "JS semantics")
- **FR-001-006**: R3 and R4 MUST run if and only if the effective switch state is `ACTIVE`, which it is
  for a read of `ACTIVE` and for an unreadable read defaulted to `ACTIVE`
  (`UNREADABLE_DEFAULTED_ACTIVE`). They run in the legacy's place, after R1 and before R5. The recorded
  `youth_switch` values, and how they map to the effective state, belong to slice 002. (H7; H8; A3-17;
  DIGEST §6 step 3)
- **FR-001-007**: The redaction's result MUST be exactly one of:
  (a) **a body**: the redacted payload, not empty;
  (b) **emptied by R1**;
  (c) **emptied by R11**: no prosecution case and no court application is left;
  (d) **a rules failure**, naming the rule in which it occurred, with no body.
  Slice 002 records (b) as `NOT_APPLICABLE(sjp-or-box-hearing)`, (c) as
  `NOT_APPLICABLE(nothing-left-after-redaction)` and (d) as `FAILED(rules-exception)`, and sends only
  (a). When R1 empties the payload the result is (b), even though R11 later sees the same empty
  payload. (§4.5; DIGEST §7, §10)
- **FR-001-008**: Where the legacy rule throws on the payload's shape, the redaction MUST give a rules
  failure (FR-001-007 (d)). This covers the carried missing guards (P19) on `defendants` (read by R3,
  R5, R9 and R10), `offences` (R5, R10), `courtOrderOffences` and `offence` (R7) and
  `judicialResults` (R10), the read of `subject.masterDefendant` (P18), and any other read the legacy
  makes of an absent or null value. No guard is added, no rule is skipped and no partial body is
  returned. (P19; standing rule, §7.0; §5.3)
- **FR-001-009**: The redaction MUST keep the legacy's quirks, because what is sent does not change in
  this delivery (D10):
  - with the switch `ACTIVE`, a court application with no truthy `subject.masterDefendant` is removed
    (P18, carried);
  - associated persons and delegated powers inside court applications are kept (P21, pending
    external);
  - `plea.delegatedPowers` is kept (P35, pending external);
  - an application-only hearing is redacted like any other and sent as today (D16 (a); A3-109);
  - a fallback-form payload, which has no `cpsProsecutorIds`, gives a body without one (Q19).
  (§7.1)
- **FR-001-010**: The redaction MUST NOT validate the body against any schema (H13), build any VEP
  projection (H5), or check `hearing.id` at run time; `hearing.id` is checked by the harness
  (FR-001-027; Q13).

**The legacy-case twins** *(User Story 1)*

- **FR-001-011**: Each of the fifteen cases of the legacy filter's Jest suite (`PF/index.test.js` at
  `122e2152`; T1–T15, numbered as in design §5.1) MUST have a twin. The twin runs the redaction over the
  same fixture, copied byte-identical into `src/test/resources/fixtures/`, in the switch state the
  legacy case used: `ACTIVE` for T1–T5, T7, T8, T10, T12 and T15; `INACTIVE` for T6, T9, T11, T13 and
  T14. It asserts what the legacy case asserts. Where the legacy case expects `{}`, the twin also
  asserts the kind of result: "emptied by R1" for T1 and T2, "emptied by R11" for T10 and T12. (§5;
  §5.1)
- **FR-001-012**: Each twin MUST start from its own unmodified copy of its fixture. The legacy suite
  shares fixture objects that its cases mutate, so its results depend on the order the cases run in
  (P34); the twins do not reproduce that. T13 and T14 assert the absence of top-level members their
  fixture never had, so they test nothing (§5.1); corpus case 2 is the check that tests something
  (FR-001-024).

**The comparator** *(User Story 2)*

- **FR-001-013**: Bodies MUST be compared by one comparator, using semantic JSON equality after
  canonicalisation (§6.2):
  - member order and whitespace are ignored;
  - array order counts;
  - numbers are equal when their decimal values are equal: `1`, `1.0` and `1.00` are one value, and
    there is no tolerance;
  - types are strict: `1` and `"1"` differ, and so do `true` and `"true"`;
  - `null`, an absent member and an empty string, array or object are all different from each other;
  - strings are compared exactly, with no Unicode normalisation;
  - a member present on one side only is a difference.
- **FR-001-014**: The comparator MUST give the stated verdict on every vector in the informant register
  parity pack's comparator vectors (`comparator-vectors/vectors.json`; there are 57 today). The vectors
  are copied unchanged, together with the pack's comparator, case and registered-derivation components
  (`JsonParity`, `ParityCase`, `RegisteredFieldDeviations`), as design §6.1 transfers them. Vectors may
  be added; none is removed or altered. (§6.1, "what transfers"; DIGEST §2)
- **FR-001-015**: A comparison failure MUST name the case, the payload form, the switch state (or, for a
  derived case, its DV id) and the JSON path of each difference. It MUST NOT print any value from the
  payload, the expectation or the result. (§6.4; constitution Principle VII)
- **FR-001-016**: The comparator MUST work on any two bodies, outside the corpus harness too, with the
  same verdict rules and the same rule against printing values (User Story 2, scenario 7). The
  pre-cutover SIT → STE replay compares the bodies STE stored with SIT's logged bodies using this same
  comparator (slice 006; §6.5; A3-68).

**The corpus and its oracle** *(User Story 4)*

- **FR-001-017**: The corpus MUST consist of real SIT hearing payloads and of synthetic cases. For each
  hearing it holds the cache form (the `EXT_` value) and the query-API fallback form of the same share,
  as far as each can be captured. Each synthetic case is built from a real SIT payload by a stated
  change and is marked synthetic. A share is identified by its hearing id, hearing day and `sharedTime`
  instant. Two forms are paired, and a payload is paired with a logged outcome, only when they belong
  to the same share. (A3-67; §6.1; §6.3; A2-5; A2-16; §5.3 note)
- **FR-001-018**: Every case MUST carry an expectation for each switch state, `ACTIVE` and `INACTIVE`
  (A3-18), and for each form it holds. The exceptions are the case-states for which this spec sets no
  expectation: those FR-001-020 and FR-001-021 leave pending a decision, and those for which SIT's log
  cannot establish one (FR-001-019). Each records that it has none, and why. An expectation is either a
  body or no message. A no-message expectation records its kind wherever its source shows it: emptied
  (by R1 or R11, where known), or a rules failure (with the rule, where known). Each expectation also
  records its source: SIT's log, the executed legacy, or a derivation under a DV id. (§6.1)
- **FR-001-019**: The `INACTIVE` expectation of a SIT case MUST be SIT's own logged outcome for that
  share. That is either the body the legacy logged as it published it, or no message, where SIT's log
  shows that the filter's result was empty or that the filter failed. It is used only where SIT's log
  shows the legacy read the switch as inactive for that share: the first permission is present and its
  `active` is falsy (§2.3 (ii)). It is the expectation for the form the legacy read at the time,
  normally the cache form. The case records which form that was, and the log evidence for it: SIT's
  telemetry shows whether the legacy's cache query found the payload in the cache or fell back to the
  query API (`HearingResultedCacheQuery/index.js:172-185` at `122e2152`). Where SIT's log does not show
  it, neither form has an `INACTIVE` expectation from SIT's log (Edge Cases). (H21; A3-67; §6.1;
  DIGEST §2)
- **FR-001-020**: The `INACTIVE` expectation for the other form of a SIT share, the form SIT's legacy
  did not read (normally the fallback form), is [NEEDS CLARIFICATION: design §6.1 and row A3-67 provide
  SIT's logged body only for the form the legacy read at the time, and run the legacy only for `ACTIVE`
  and synthetic cases, so this expectation is undefined (DIGEST §16, "§6.1 / A3-67"). Which of these
  applies? (a) The executed legacy, as for `ACTIVE`. (b) No `INACTIVE` expectation for that form; it is
  held to its `ACTIVE` expectation, and what is derived from it, only. (c) `INACTIVE` expectations for
  that form only from shares SIT's legacy actually read in that form.] Until this is decided, the
  harness lists every such case-state as having no expectation and never counts it as a pass.
- **FR-001-021**: SIT's telemetry keeps at most 32,768 characters of a trace line (the limit H28's
  production measurement records). A logged body is cut short when its log line reaches that limit, or
  when the logged body is not complete JSON. A cut-short body MUST NOT be compared as a whole body, and
  it is never repaired. The `INACTIVE` expectation of a SIT share whose logged body is cut short is
  [NEEDS CLARIFICATION: design §6.1 and row A3-67 take SIT's logged body as the `INACTIVE` expectation
  and execute the legacy only for `ACTIVE` and synthetic cases; a cut-short body cannot be an
  expectation, and the design does not cover one. Which applies? (a) The executed legacy, admitted
  only where its own log line matches the kept text character for character. (b) No `INACTIVE`
  expectation for that share; its `ACTIVE` expectation, and what is derived from it, stand, and the
  share is recorded as excluded from the `INACTIVE` comparison, with its reason. (c) The kept text
  compared as a prefix of the port's rendered body, with no executed legacy.] Until this is decided,
  the harness lists every such case-state as having no expectation and never counts it as a pass.
- **FR-001-022**: The `ACTIVE` expectation of every case, and both expectations of a synthetic case,
  MUST be recorded by running the legacy at `122e2152`: the HMPPS filter (`PF/functions.js`) and the
  HMPPS publisher's body construction, which publishes nothing for an empty result
  (`HMPPSExternalPublisher/index.js:68-70`). The switch is forced to a fixed value, not looked up. The
  VEP filter and publisher are not run. Each recording notes the legacy commit and the version of the
  runtime it ran on. (A3-67; A3-18; §6.1; H5)
- **FR-001-023**: Recorded expectations MUST NOT be regenerated or edited to make the port pass. An
  expectation file changes only in the same commit as a new or amended `doc/DEVIATIONS.md` entry. A
  registered deviation is asserted by derivation (FR-001-037), never by editing an expectation.
  (constitution Principle I and "Development Workflow & Quality Gates"; §6.2)
- **FR-001-024**: The corpus MUST contain, in both switch states, each blind-spot case of design §6.3:
  - 1: a virtual-box hearing. It is synthetic, because neither form carries `isVirtualBoxHearing`
    (A2-8).
  - 2: a top-level `judiciary` and a top-level `associatedPersons`.
  - 3: a court application whose subject has no `masterDefendant`.
  - 4: a prosecution-case offence without `judicialResults`.
  - 5: a `courtOrder` without `courtOrderOffences`.
  - 8: `plea.delegatedPowers` present.
  - 9: a non-boolean `isYouth`.
  - 11: a youth defendant beside adult defendants in an adult-court hearing.
  - 12: every case and application removed under `ACTIVE`.

  It MUST also contain a payload carrying `policeCases` (R0; §5.1) and a court application that keeps
  associated persons or delegated powers (P21; §7.1). Each case comes from SIT data where SIT has the
  shape, or else from a synthetic case. Case 13 is derived (FR-001-038). Cases 6, 7 and 10 are dropped
  (H5). (§6.3; §5.1; §7.1)
- **FR-001-025**: Each of the rules R0, R1 and R3–R11 MUST be proved by at least one case among the
  twins and the corpus, as the "proving fixture" column of design §5.1 lists them. (§5.1)
- **FR-001-026**: The harness MUST run the redaction over every case, in each switch state and each
  form that has an expectation, and compare the result with the expectation. A body is compared with a
  body by the comparator. A no-message expectation is met by a result with no body, of the same kind,
  and failing in the same rule, wherever the expectation records them. Any difference that no
  registered derivation explains MUST fail the build. (§6.2; §9.2 row 4)
- **FR-001-027**: Two whole-corpus checks MUST fail the build when they fail:
  (a) every body the redaction produces over the corpus, in every switch state and form, carries
  `hearing.id` with a non-null value, because APIM builds the backend path from it (Q13; §9.2 row 12);
  (b) each carried guard — `defendants`, `offences`, `courtOrderOffences`, `offence` and
  `judicialResults` (P19), and `subject.masterDefendant` (P18) — is reached by at least one case in
  which that member is missing where the rule reads it, or has an accepted-risk entry in the register
  (FR-001-035). (§6.3; §5.3)

**Custody, provenance and privacy** *(User Story 3)*

- **FR-001-028**: SIT payloads, SIT's logged outcomes (whole or cut short) and the synthetic cases
  built from them MUST be committed only under `src/test/resources/corpus/`. They use the informant
  pack's recorded layout: for each case, its inputs, its expectations and its provenance, with the
  digest of each file pinned. Nothing from PRD or PRP, or from any other environment that holds
  production data, is ever committed. The copied legacy Jest fixtures live under
  `src/test/resources/fixtures/` (FR-001-011). (constitution Principle VII; §6.4; DIGEST §2; decision of
  2026-10-19 by the document owner)
- **FR-001-029**: Each case MUST record its provenance:
  - its environment: SIT, or synthetic, with the SIT case it was built from and the change made;
  - the capture date of each form it holds and of its logged outcome;
  - the forms it holds;
  - for a SIT case, the payload source the legacy read for the share (cache or query API), as SIT's
    telemetry shows it, or that it does not show it (FR-001-019);
  - the source of each expectation (FR-001-018), with the legacy commit and the runtime for an executed
    one;
  - the digest of each input and each expectation.

  The corpus as a whole MUST record its environment, its capture dates, the number of hearings, the
  number of cases per switch state, how many cases are synthetic, and the excluded cases with their
  reasons. (§6.4)
- **FR-001-030**: The build MUST fail when any corpus file differs from its pinned digest, when a case
  has no provenance or lacks an item FR-001-029 requires, or when a case's environment is anything
  other than SIT or synthetic built from SIT. (§6.4; constitution Principle VII)
- **FR-001-031**: Corpus content (payloads, logged outcomes, expectations and results) MUST NOT appear
  in any log line, test report, failure message, document, pull request or review finding. A case is
  referred to by its case id. (§6.4; constitution Principle VII)
- **FR-001-032**: Every file under `src/test/resources/corpus/` MUST belong to a case that has
  provenance, with the file's digest pinned. A file that belongs to no case fails the build, which
  names the file and never prints its content. Capturing SIT data within SIT's retention windows is a
  dependency of this slice, not a check of its build (Dependencies, External). (§6.1, "what
  transfers"; §6.4)

**The deviation register and derivations** *(User Story 5)*

- **FR-001-033**: `doc/DEVIATIONS.md` MUST be created, seeded from design §7.2, with exactly one row for
  each id from DV-01 to DV-26. That includes the active rows, the withdrawn ids (DV-01, DV-02, DV-03,
  DV-15), the unallocated ids (DV-11 and DV-12: payload fixes that would need HMPPS's agreement, D10)
  and the reserved ids (DV-18 … DV-22). These are kept so that no id is ever reused. The register MUST
  name the next free id, DV-27. Once the register exists, the repository's documentation index MUST
  point at it: README's artefact table, and the key-documentation table of the working-conventions
  file (README's "Working conventions" entry), whose deviation-register row today says "not present
  yet". (§7.0; §7.2; constitution Principle I)
- **FR-001-034**: Each active row MUST state:
  - the legacy behaviour, with its `file:line` citation at `122e2152` where the design gives one;
  - the new behaviour;
  - the reason;
  - who approved it, by role and decision id;
  - whether it changes a message body;
  - its pinning test, as design §7.1 and §7.2 name it;
  - whether its behaviour is built yet.

  Once a row's behaviour is built, its pinning test MUST exist and pass. Where a §7.2 cell predates a
  later rev 3.6 decision, the row states the decision. For example, DV-17's system user is the
  service's own (H30); DV-23 covers only the results read (rows A3-8, A3-13); and DV-25 goes on to the
  send with no validation stage (H13). (constitution Principles I and II; §7.1; §7.2; DIGEST §1, §4)
- **FR-001-035**: The register MUST also list the carried defects of design §7.1 (P17, P18, P19) and
  the pending-external ones (P07, P21, P35, P36), each with the case or test that pins it. It MUST
  carry an accepted-risk entry for each carried guard of FR-001-027 (b) that no corpus case reaches. It
  re-decides no disposition. (§7.1; §5.3; §6.3; constitution Principle I)
- **FR-001-036**: The register MUST be on the main line no later than the redaction, because leaving
  out R12 is DV-09, the first deviation this slice lands. In this slice, DV-09's row names as its
  pinning test the redaction's check that it writes no log line (FR-001-001; SC-001-012), which would
  fail if R12 were ported. The service-wide log privacy sweep, which also pins DV-09, lands with slice
  002. (constitution Principles I and II; the working-conventions file's deviation-register row)
- **FR-001-037**: A deviation that changes an expected outcome MUST be asserted by a derivation keyed
  on its register row. The expectation is computed from the oracle's recorded outcome and the row's
  stated rule, over a whole outcome (as for DV-25) or over one component. The check is as strict as
  equality: an oracle value the derivation does not describe is a difference, and a failure quotes the
  DV id. A derivation whose DV id is not an active row of the register MUST fail the build. (§6.2; §6.1,
  "what transfers"; DIGEST §2)
- **FR-001-038**: Case 13, a failed switch read, MUST be derived under DV-25 for every corpus payload
  and form, and for each value the configured default can take. The legacy sends no message (P16;
  `PF/index.js:29-32`). The expectation is the outcome for the default: the case's `ACTIVE` expectation
  when the default is `ACTIVE`, which it is unless configured otherwise, and its `INACTIVE` expectation
  when the default is `INACTIVE` (DV-25: "the `ACTIVE`-redacted body, or the `INACTIVE` one if so
  configured"). It is recorded as derived, never recorded separately. A derivation whose source
  expectation does not exist (pending under FR-001-020 or FR-001-021, or not established from SIT's
  log, FR-001-019) has no expectation either: it is listed with the case-states that have no
  expectation and never counted as a pass. The causes of an unreadable read, and the run that
  records `UNREADABLE_DEFAULTED_<value>`, belong to slice 002. (A3-28; DV-25; §6.2; §6.3, case 13;
  H8)

**Gate evidence** *(User Stories 4 and 5)*

- **FR-001-039**: The parity run MUST report, without corpus values:
  - the cases compared, per switch state and form;
  - the matches;
  - the derived cases, by DV id;
  - the case-states with no expectation, each with its reason: pending under FR-001-020 or
    FR-001-021, or not established from SIT's log (FR-001-019);
  - the case-13 derivations with no source expectation (FR-001-038);
  - the excluded cases, with their reasons;
  - the count of unexplained differences, which must be zero.

  That report is the evidence for go-live gate rows 4 and 12. (§9.2 rows 4 and 12; §6.4)

### Key Entities

- **Hearing payload**: a hearing's results as they reach the rules, in one of two forms. The cache form
  is the `EXT_` value: `hearing`, `cpsProsecutorIds`, `policeCases` and `sharedTime`. The fallback form
  is the query API's answer: `hearing` and `sharedTime`, with a different `hearing` field set (§5.3
  note). It is a JSON tree owned elsewhere and is never bound to a typed model (constitution Principle
  IV).
- **Share**: one sharing of one hearing's results for one hearing day, identified by hearing id,
  hearing day and the `sharedTime` instant. A re-share is a different share.
- **Effective switch state**: `ACTIVE` or `INACTIVE`, the redaction's second input. Slice 002 derives
  it from the recorded `youth_switch` value.
- **Redaction result**: a body; emptied by R1; emptied by R11; or a rules failure naming its rule
  (FR-001-007).
- **Legacy-case twin**: the counterpart of one legacy Jest case (T1–T15), with its byte-identical
  fixture and its switch state.
- **Corpus case**: one share, or a synthetic variant of one. It holds one or both payload forms, an
  expectation per switch state and form, its provenance and the pinned digests of its files.
- **Case-state**: one case in one switch state and one form. This is the unit the harness compares and
  reports.
- **Expectation**: a body, or no message (with its kind and rule where known), together with its
  source: SIT's log; the executed legacy; or a derivation under a DV id. A case-state may instead have
  no expectation, with the reason: pending a decision (FR-001-020, FR-001-021), or not established from
  SIT's log (FR-001-019).
- **SIT logged evidence**: what SIT's telemetry kept for a share: the legacy's switch reading, the
  payload source it read (cache or query API), and its outcome (the published body, an empty filter
  result, or a filter failure). The body may be cut short at 32,768 characters.
- **Comparator verdict**: equal, or different with the JSON paths that differ, after any registered
  derivation has been applied.
- **Comparator vector**: a pair of JSON documents with a stated verdict and a reason, copied from the
  informant pack.
- **Deviation row (DV-n)**: id; status (active, withdrawn, unallocated or reserved); the defects it
  answers; legacy and new behaviour; reason; approval; whether it changes a message body; pinning test;
  whether it is built; and, for a deviation that changes an outcome, its derivation rule.
- **Carried defect, pending-external defect, accepted risk**: register entries beside the DV rows,
  each with its pinning case or test, or (for an accepted risk) the guard it covers and why no case
  reaches it.
- **Corpus metadata**: environment, capture dates, the number of hearings, cases per switch state, the
  synthetic count, the excluded cases with their reasons, and the digests.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001-001**: Each of the 15 legacy filter cases has a twin, and all 15 twins pass over their
  byte-identical fixtures.
- **SC-001-002**: The comparator gives the stated verdict on 100% of the reused comparator vectors.
- **SC-001-003**: Over the whole corpus (every case, in each switch state and form that has an
  expectation), the parity run reports zero unexplained differences. This is the evidence for go-live
  gate row 4.
- **SC-001-004**: All nine recorded blind-spot cases (1, 2, 3, 4, 5, 8, 9, 11, 12) are present in both
  switch states. Case 13 is derived for 100% of the corpus payloads, forms and default values whose
  source expectation exists; every other case-13 derivation is listed in the parity report, and none
  is counted as a pass.
- **SC-001-005**: 100% of the bodies produced over the corpus carry a non-null `hearing.id`. This is
  the evidence for go-live gate row 12.
- **SC-001-006**: All 6 carried guards are accounted for, each by a case or by an accepted-risk entry.
- **SC-001-007**: Disabling any one of the eleven ported rules makes at least one twin or corpus case
  fail.
- **SC-001-008**: No cut-short SIT logged body is compared as a whole body. 100% of the case-states
  with no expectation (FR-001-019 to FR-001-021) are listed in the parity report with their reason,
  none of them is counted as a pass, and every excluded case is listed with its reason.
- **SC-001-009**: A comparison forced to fail produces a message that contains no payload, expectation
  or result value.
- **SC-001-010**: 100% of corpus files belong to a case and match their pinned digests, 100% of cases
  carry complete provenance, and 0 cases come from PRD or PRP.
- **SC-001-011**: The register holds each of DV-01 … DV-26 exactly once and names DV-27 as the next
  free id. Every built row's pinning test exists and passes. Withdrawing DV-25's row makes every
  case-13 derivation fail.
- **SC-001-012**: For 100% of corpus inputs, the redaction leaves its input unchanged, writes no log
  line, and returns an equal result when called again.
- **SC-001-013**: The build stays green under its existing gates, and the coverage thresholds are not
  lowered.

## Assumptions

- **The oracle** is the legacy HMPPS code at `122e2152` of `cpp-context-azure-legalaidagency`: the
  filter, and the publisher's body construction. Its Jest suite is not the oracle; parts of it are
  vacuous or depend on test order (P34). Where SIT logged what the legacy actually did, that log is the
  oracle (H21; constitution Principle I). The legacy's runtime holds numbers as double-precision
  values and renders them again, while the port keeps their exact decimal value (§6.2). A payload
  number the legacy could not represent exactly is therefore reported as an unexplained difference
  against the oracle. It is investigated, never waved through, and any difference the legacy's
  rendering causes needs a DV row (D10) or an exclusion decided by the design owner.
- **SIT's build is not the baseline build.** SIT's logged bodies are the output of the build deployed
  in SIT, which was not the baseline's (§6.1, "one version caveat"; U14). A difference between that
  build and the baseline shows up as an unexplained difference against SIT's logged `INACTIVE` bodies.
  It is investigated, never waved through.
- **The legacy is run outside this repository's build**, as it was for the informant parity pack: its
  recordings are committed with their provenance, and the program that produced them is not part of
  the build. A recording is never regenerated to make a test pass.
- **Pairing by share** (FR-001-017): the logged body carries `hearing.id` and `sharedTime`, so a
  captured payload can be matched to the share the legacy sent.
- **"The register"** in design §5.3 and §6.3, for accepted risks, is `doc/DEVIATIONS.md`, the
  repository's only register of behaviour that differs from the legacy.
- **Seeded rows before their behaviour**: every row names its pinning test now. The test lands in the
  slice that builds the behaviour, and that is the point from which the row's test must exist and pass.
- **Corpus size**: the design sets none (§6.4 deferred it to D8, which H21 closed). Coverage is set by
  FR-001-024 and FR-001-025, and the corpus records its own counts.
- **Data controls**: the document owner's decision of 2026-10-19, that SIT data may be committed to
  this public repository, is in force. Whether the informant pack's controls need re-approval for
  `EXT_` data is for the data owner to say (§6.4). If they say yes, FR-001-028 to FR-001-031 take their
  conditions on.
- **The handler's `hearing` check** (`PF/index.js:9`) sits outside the rules. The redaction behaves as
  `PF/functions.js` does. What the HMPPS leg does with a payload that has no `hearing` is slice 002's
  decision.
- **The comparator vectors** number 57 in the informant pack today. The requirement is "every one of
  them", whatever the count.

## Dependencies

**On other slices.** Nothing upstream. Later slices build on this one; their specs are authoritative
for their own scope:

| Slice | What it takes from 001, or owns instead |
|---|---|
| 002-hmpps-distribution | Calls the redaction, maps its result kinds to the leg's (status, reason), and owns the `youth_switch` vocabulary and its mapping to the effective state. Also owns `YouthSwitchUnreadableIT` (DV-25, per cause), `RulesExceptionIT` (P19), the log privacy sweep (`TelemetryPrivacyTest`, `LogStatementSweepTest`; DV-09's logging half), the body bytes, `body_digest` and `service_version`, and the leg's outcome for a parseable payload with no `hearing`. The legacy handler drops such a payload before the switch read, logging one line and sending nothing (`PF/index.js:9-11`), while the redaction reports a rules failure in R1. The closed reason vocabulary has no reason for such a skip (the port's rules failure would be recorded `FAILED(rules-exception)`), so 002 and the design owner must decide. It lands the pinning tests of DV-04 … DV-08, DV-10, DV-13, DV-14, DV-16, DV-17, DV-23 and DV-24 (the resend halves of DV-07 and DV-17 with 004), and the pins of P07 (`GatewayConfigTest`), P17 (`FeaturePermissionClientTest`) and P36 (`FailureSignalIT`; P36's deployed alert rules with 006) |
| 004-recovery | DV-26's pinning test (`DlqResubmissionReplayIT`) |
| 006-production-readiness | Compares the STE replay with the comparator (FR-001-016). Takes this slice's parity report as the evidence for gate rows 4 and 12 (FR-001-039). Its replay-week export may supply corpus days (External, below) |

**External.**
- The legacy repository at `122e2152` (the rules, the publisher, the Jest suite and its fixtures), and
  a JavaScript runtime to run it, outside the build.
- The informant register's parity pack: comparator, vectors, case and derivation components.
- Access to SIT within its retention windows: about 90 days for its telemetry (the configured retention
  was not read) and about 91 days for `EXT_` keys. Through it come the `EXT_` values, the query-API
  fallback form, and SIT's telemetry (the logged switch readings, payload sources and outcomes). The
  service's owning team captures each chosen day's payloads and logged outcomes together, before
  either expires, and holds them under the corpus controls until they are committed (H31; §6.1;
  §6.4). The SIT week chosen for the replay in slice 006, whose logged bodies and payloads are exported
  at once (A3-78), may supply corpus days.
- The data owner (data controls, §6.4).
- The design owner (FR-001-020; FR-001-021).

## Out of Scope

- **Everything around the redaction**, which is slice 002's: the payload fetch (H2, D5); the switch
  read, and its JavaScript-truthiness decision over `permissions[0].active` (P17, DV-24, and the
  processing of DV-25); the leg's statuses, reasons and outcome rows; body serialisation, `body_digest`
  and `service_version`; the send; the stored body; metrics, logs and alerts, including the
  `shape="application-only"` count (H33).
- **The STE replay** (slice 006): the one-week SIT → STE replay, its mechanics (A3-78, not
  established) and its report, and the SJP parity check read from SIT's telemetry (row A2-9; §9.2 row
  20).
- **Any change to what is sent** (D10): P21 and P35 stay carried, and DV-11 and DV-12 stay
  unallocated.
- **Withdrawn and parked design**: the VEP rules and any VEP corpus dimension (H5); outbound schema
  validation and gate G1 (H13); the shadow profile and `shadow_capture` (H21).
- **Production data**: data from PRD or PRP in any form, as an input, an expectation or an example.
- **Defect dispositions**: no disposition of design §7.1 is re-decided.
- **Running the legacy inside this repository's build.**

## Design traceability

| Design id | What it fixes here | FR ids |
|---|---|---|
| D1 (lift and shift) | rules copied verbatim; every intended difference a numbered, tested deviation | FR-001-003, FR-001-004, FR-001-033 |
| D10 (no payload change) | quirks carried; expectations never edited to suit the port | FR-001-009, FR-001-023 |
| D16 (a), A3-109 | application-only hearings redacted and sent as today | FR-001-009 |
| H5 | no VEP projection, rules or corpus dimension; VEP code not run | FR-001-010, FR-001-022 |
| H7, A3-17 | R3/R4 verbatim, only when the switch is `ACTIVE`, after R1 and before R5 | FR-001-004, FR-001-006 |
| H8, DV-25, A3-28, P16 | a failed read takes the configured default; case 13 derived for every payload | FR-001-006, FR-001-038 |
| H13 | no schema validation in the redaction | FR-001-010 |
| H21, A3-67 | SIT's logged body for `INACTIVE`; executed legacy for `ACTIVE` and synthetic cases | FR-001-017, FR-001-019, FR-001-022, FR-001-026 |
| A3-18 | every case recorded in both switch states | FR-001-018, FR-001-022, FR-001-024 |
| H28 | the source of the 32,768-character trace limit only (a production capacity measurement, not an oracle rule) | FR-001-021 |
| A3-78 | the replay week's export, taken within retention, may supply corpus days | Dependencies (External) |
| §4.4 (`HmppsRedaction`), §5 | pure function; deep-copy boundary; JavaScript semantics | FR-001-001, FR-001-002, FR-001-005 |
| §5.1 | R0–R11 in the legacy order; R12 not ported; Jest twins; proving cases | FR-001-003, FR-001-004, FR-001-011, FR-001-012, FR-001-025 |
| §5.3 | carried guards; the fallback form | FR-001-008, FR-001-027, FR-001-035 |
| §6.1 | the oracle, both forms, both states; what transfers from the informant pack | FR-001-014, FR-001-017 – FR-001-022 |
| §6.1 / A3-67 gap (DIGEST §16) | `INACTIVE` expectation for the form SIT's legacy did not read | FR-001-020 |
| §6.1 / A3-67, cut-short bodies (not covered by the design) | `INACTIVE` expectation of a share whose SIT logged body is cut short | FR-001-021 |
| §6.2 | comparator; derivation for registered deviations; unregistered differences fail | FR-001-013, FR-001-023, FR-001-026, FR-001-037 |
| §6.3 | blind-spot cases 1–5, 8, 9, 11–13; whole-corpus checks | FR-001-024, FR-001-027, FR-001-038 |
| §6.4 | custody, provenance, digests; never in logs or reports | FR-001-015, FR-001-028 – FR-001-032 |
| §6.5, A3-68 | the same comparator for the STE replay | FR-001-016 |
| §7.0 | standing rule (throw and report); DV numbering, never reused | FR-001-008, FR-001-033 |
| §7.1 (P17–P19, P21, P34, P35, P07, P36) | carried and pending-external defects pinned; no disposition re-decided | FR-001-008, FR-001-009, FR-001-012, FR-001-035 |
| §7.2 | the register seed DV-01 … DV-26; next free DV-27; row content | FR-001-033, FR-001-034, FR-001-036 |
| DV-09 (R12) | the redaction logs nothing | FR-001-001, FR-001-003, FR-001-036 |
| Q13, §9.2 row 12 | `hearing.id` in every body: a harness check | FR-001-010, FR-001-027, FR-001-039 |
| §9.2 row 4 | SIT-data unit tests green, with only registered deviations | FR-001-026, FR-001-039 |
| Q19 | the fallback-form body has no `cpsProsecutorIds` | FR-001-009 |
| A2-5, A2-16 | the two payload forms; fallback-form cases recorded for HMPPS | FR-001-017 |
| A2-8 | case 1 is synthetic | FR-001-024 |
