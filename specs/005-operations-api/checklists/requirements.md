# Specification Quality Checklist: Operations API — replay and resend-leg over HTTP

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

Validation run 2, 2026-10-21, after the review findings were applied. One entry per item, with
what was checked.

- **No implementation details; none leaks** — passes, with a stated exception. This is an
  operators' API over a message-driven service with frozen contracts, so the spec names what the
  design fixes: the HTTP surface (paths and action names marked *proposed*), the status codes and
  the closed reason set, the request fields, the OpenAPI document's location, the two estate
  libraries the design and the constitution name (`cp-auth-rules-filter`,
  `cp-audit-filter-springboot`), the `probation_` metric family and one proposed metric. FR-005-027
  also says that the audit library's default publisher, which discards its own publishing
  failures, cannot be relied on, because without that the requirement could not be met. It
  prescribes no language, class, package or code structure; how the filters are wired and where the
  result types live is the plan's.
- **Focused on user value** — passes. Each story is an operator outcome (replay, resend, forced
  resend, admission and audit, readable refusals, a described API, one behaviour behind two front
  doors) with its reason for the priority.
- **Written for non-technical stakeholders** — passes in part, by design. Context and the user
  stories are plain English; the requirements address the owning team and the operators, the only
  audience an operators' API has, in the design's contract vocabulary.
- **All mandatory sections** — complete: User Scenarios & Testing, Requirements with Key Entities,
  Success Criteria, Assumptions. Added: Context, Clarifications (22 resolved), Dependencies, Out of
  Scope, Design traceability.
- **No clarification markers remain** — does not pass, deliberately. Two markers remain (the limit
  is three), each a gap the design does not decide; see the questions below.
- **Testable and unambiguous** — passes, except FR-005-015 and FR-005-020, which become testable
  once answered. The scenarios that depend on them (story 2's report, story 4's admitted group)
  are written so that either answer fits.
- **Success criteria measurable** — passes: each is a count, a percentage, a zero, or a
  same-answer comparison between the API and the runner.
- **Success criteria technology-agnostic** — passes: they speak of calls, answers, audit events,
  log lines, attempts and the queue, and name no library, framework or language. SC-005-010 restates
  the existing coverage thresholds as numbers.
- **Acceptance scenarios** — 7 stories, 43 Given/When/Then scenarios. Where a scenario touches what
  slice 004 decides or records (stories 2 and 3), it checks what this slice does — the call reaching
  004 unchanged and the answer rendering 004's result — and the story's Independent Test names
  004's tests as the ones that pin the rule or the recording.
- **Edge cases** — 19, including the duplicate risk of a repeated forced resend, replay while
  intake is paused, the races slice 004 fences, a run cut short by the store or by a pause,
  dependency outages and an audit transport left off.
- **Scope bounded** — passes: FR-005-001, FR-005-002 and Out of Scope. The six O-1 actions, the
  rollback's close-in-flight step and every dead-letter-queue operation are excluded, each with its
  design id.
- **Dependencies and assumptions** — passes: slices 004 (required first, and the owner of the
  whole-call input rules the API renders), 002 and 006 (downstream); platform asks 2, 12 and 14;
  users-groups; the audit context; the owning team, including its sign-off on the lost
  response-event shortfall; twelve assumptions, of which 4, 6, 11 and 12 mark this spec's own
  readings where the design is silent.
- **Clear acceptance criteria per requirement** — passes, with one FR verified by review rather
  than by a test. Every other FR is exercised by a story scenario, an edge case or a success
  criterion; FR-005-009 (replay takes no operator or reason) by story 1 scenario 8;
  FR-005-039 and FR-005-040 (no new property; no App Configuration or Results-switch read) by
  SC-005-011. FR-005-038 (the data-sender role for replay's enqueue) is a platform grant (ask 2):
  it is verified by the deployment review of the environment's identity, and exercised there by
  SC-005-008's replay; no test in this repository can show it. Every FR appears in the design
  traceability table.

### Open questions for the owning team (H31)

#### Question 1: How does a resend-leg call answer? (FR-005-015)

**Context**: the design fixes the report's content (§4.4 Output row; row A3-56) but not whether one
HTTP call waits for up to `distribution.resend.max-ids-per-run` IDs, each bounded only by the
215 s message budget (§4.7).

**What we need to know**: does the call answer synchronously with the whole report, or accept the
run and leave the outcomes to the run report line and the attempt rows?

| Option | Answer | Implications |
|--------|--------|--------------|
| A | Synchronous: the call returns the whole report once every ID has run | The same output as the runner, and the simplest. A long run can outlive the route's timeout, and a caller who repeats a timed-out forced resend sends a further genuine duplicate. Operators keep lists short |
| B | Accepted run: `202` with the `resendRunId`; the outcomes go to slice 004's run report line and the attempt rows | No timeout exposure; the sibling service's shape for a long action. No HTTP read-back while the list action is open (O-1): operators read the log index or use the list runner |
| C | Synchronous checks, background sends: the call runs the whole-call and per-ID checks and claims, answers the refusals and the claimed IDs at once, and the sends carry on | Refusals come back at once, and a repeat is fenced while the sends are in flight. One run's result is read in two places |
| Custom | Provide your own answer | — |

#### Question 2: Who may call? (FR-005-020)

**Context**: the design copies the API from the sibling YOT service (§4.8), whose allow rules admit
one estate group, but names no group for this service. A forced resend re-sends defendants'
personal data to HMPPS as a genuine duplicate (H12), youth defendants included wherever the youth
switch does not remove them (H7).

**What we need to know**: which users-groups group or groups each allow rule admits.

| Option | Answer | Implications |
|--------|--------|--------------|
| A | "Second Line Support" for both actions, as the sibling service | Copies a reviewed estate decision. An estate-wide group, wider than the holders of exec rights on the namespace who can run the runners |
| B | A narrower, service-specific group for both actions | A tighter population; the group has to exist in users-groups in every environment |
| C | "Second Line Support" for replay; a narrower group for resend-leg, or for the forced override only | Matches the risk. A rule for the forced override alone makes it an action of its own, which changes FR-005-019 |
| Custom | Provide your own answer | — |

### Cross-slice items for the consistency review

All six items below were closed in the cross-spec pass of 2026-10-21:

- the result shapes and the whole-call refusals are slice 004's, fixed in its Key Entities and
  Assumption 3, and this spec renders the same set;
- 004's Clarifications and Assumption 6 now carry the distinct-ID cap as 004's own reading, shared
  with this spec's assumption 12, without attributing an order to DIGEST §11;
- the vocabulary names are identical across 002, 004 and 005; 002's FR-002-072 says which
  vocabularies serve the replay action's refusals and which codes are 004's own;
- every slice now keeps its open questions, with their option tables, in its checklist;
- the proposed counter is registered in 002's shared registry with its two label sets
  (FR-002-057, FR-002-072); this slice only increments it (FR-005-043; assumption 8), and 006
  neither alerts on it nor counts it until the design owner adds it to §10.1 (FR-006-022).

The items as raised:

- Slice 004 has to return its results in the shape FR-005-007 and FR-005-014 render: replay
  `enqueued` (with the message identity used), `no-op`, or a refusal (`resend-in-progress`, and
  `unknown-request` or `not-eligible` for a missing or not-`FAILED` request); resend-leg's
  whole-run refusals; and the report with its `resendRunId`, per-ID outcomes (with `not-run`) and
  counts.
- Slice 004 returns the whole-call refusals the API renders — `missing-argument` for an absent or
  empty list or a missing operator or reason, `too-many-request-ids` carrying the cap applied,
  `argument-too-long`, `intake-paused` — and is the one place that collapses duplicates and applies
  the cap (FR-004-019, FR-004-020; FR-005-011). The API parses each entry as a UUID and passes the
  list as sent. The 200-character bound and the refuse-not-truncate rule are recorded under
  Dependencies → 004.
- Slice 004's Clarifications and its assumption 6 attribute "the cap counts distinct IDs" to
  DIGEST §11 "stating the two rules in that order"; neither DIGEST §11 nor row A3-56 fixes that
  order. This spec carries the reading as its own assumption 12; 004's wording should match.
- The OpenAPI document mirrors as closed enums slice 002's leg, leg status, leg reason and resend
  refusal vocabularies and slice 004's `not-run`, `enqueued` and `no-op` (FR-005-035); the names
  must stay identical across 002, 004 and 005.
- Where open questions live: this slice keeps its two questions, with their option tables, in this
  checklist, as slices 001 and 006 do; slice 003 carries an "Open questions" section in its
  spec.md. The cross-spec pass should pick one form for all six slices.
- The proposed counter `probation_operations_failures_total{action, reason}` is not in the
  design's §10.1 catalogue and needs adding there.
