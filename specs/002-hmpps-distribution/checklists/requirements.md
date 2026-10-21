# Specification Quality Checklist: HMPPS distribution — from a queued share to a recorded HMPPS outcome

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs). The user stories and the success
      criteria name no language, framework or library. The functional requirements name the
      elements the design freezes as contracts, because this message-driven service's behaviour
      cannot be stated without them: the queue, the inbound schema, the tables, columns, CHECKs
      and view of V1, the statuses and reasons, the metric names and labels, the `distribution.*`
      properties, and the consumed HTTP paths and media types. Code-level names appear in three
      places only: the four ports and the vocabulary type names the shared core owns (FR-002-071,
      FR-002-072), and the deviation register's pinning tests (FR-002-074), which the constitution
      requires each DV row to name. They are marked as the design's proposals.
- [x] Focused on user value and business needs. Every story opens with the operational or
      business outcome: a hearing reaches HMPPS once, no duplicate reaches HMPPS, a missing payload
      is retried and not lost, a switch outage does not stop a hearing, an unknown gateway outcome
      is not doubled, and operators see what happened without seeing anyone's data.
- [ ] Written for non-technical stakeholders. **Partly.** The context, the user stories and the
      success criteria are in plain language. The functional requirements are written for the
      delivery team and its reviewers, because the brief for this spec asks for the design's frozen
      contract elements to be named. This is an accepted exception, not a gap to fix before
      planning.
- [x] All mandatory sections completed. User Scenarios & Testing, Requirements and Success Criteria
      are filled in. The optional sections are Context, Clarifications, Key Entities, Assumptions,
      Dependencies, Out of Scope and Design traceability.

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain. **Three remain**, each a design gap that digest §16
      or the design's own answer tables leave open. None can be resolved from the design:
      - FR-002-024: which unreadable cause labels a 2xx users-groups answer whose `permissions[0]`
        is `null` (design gap §4.4 / A2-28);
      - FR-002-038: how a gateway answer outside 2xx, 4xx and 5xx (in practice a 3xx) maps to a
        leg outcome and an `http` bucket;
      - FR-002-061: how a crash during the re-run of a `PARKED` row is counted (A3-115).

      The questions and their options are below, in the form slices 001, 003–006 use.
- [x] Requirements are testable and unambiguous, apart from the three marked above. Every
      requirement states an observable outcome: a row value, a settlement, a count, a log level or
      a startup refusal. Twenty-two ambiguities were resolved and recorded under Clarifications:
      twenty-one from the design, and one, the reading of A2-67's "no settlement", from the
      constitution's settlement rule.
- [x] Success criteria are measurable. Each has a count, a percentage or a time bound: 100% of runs,
      0 extra POSTs, 1 or at most 3 POSTs, 215 s, 10 s, 14 of 14 deviations.
- [x] Success criteria are technology-agnostic. They speak of shares, hearings, deliveries, POSTs,
      stored bodies and the processed log. SC-002-012 refers to the repository's build and quality
      gates by name only.
- [x] All acceptance scenarios are defined. Six stories carry 59 Given/When/Then scenarios.
- [x] Edge cases are identified. There are 16, including the cached `{}`, a payload with no
      `hearing`, a body without `hearing.id`, a re-run that removes an earlier body, a lost lock, a
      database failure between the POST and the record, a `max-delivery-count` mismatch, and bodies
      outside the three pre-created months.
- [x] Scope is clearly bounded. A slice table appears in the Context, and the Out of Scope section
      names the slice that owns each excluded item. The one boundary this slice draws itself —
      the interim handling of a `FAILED` request until slice 004 (FR-002-008) — is stated as a
      requirement, an assumption and a dependency.
- [x] Dependencies and assumptions identified. The dependencies are slices 001 and 003–006, the
      results producer increment, platform asks 1, 2, 5, 6, 6a, 9 and 16, and the unchanged
      services. There are fifteen assumptions, each tied to a design row, to digest §16, to the
      slicing plan or to another slice's open question.

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria, apart from FR-002-038, whose
      outcome is open. Each story lists the requirements it exercises. The rest are covered as the
      User Scenarios preamble states:
      - the foundational requirements, which are V1, the vocabularies, the ports, and the
        configuration with its timing rule (FR-002-014, FR-002-050 to FR-002-056, FR-002-066 to
        FR-002-072), by schema-vocabulary assertions and startup-refusal tests on every build;
      - the negative and structural FR-002-031 and FR-002-073, by the spec-validator review and
        workflow gate 6, with the schema-vocabulary assertions pinning `leg` and the reserved
        `contract-invalid`;
      - the deviation register, FR-002-074, by SC-002-010.
- [x] User scenarios cover primary flows. They cover the send end to end, admission and
      duplicates, a missing payload, an unreadable switch, gateway failures, and observability with
      privacy.
- [x] Feature meets measurable outcomes defined in Success Criteria. SC-002-001 to SC-002-012 map to
      the stories and to the foundational requirements.
- [x] No implementation details leak into specification, with the same qualification as the first
      item: only the design's frozen contract elements, the ports and vocabulary type names, and the
      pinning-test names appear.

## Notes

- Validated 2026-10-21 in two passes. The first pass found a broken clarification marker, wrapped
  across a line, and five wording faults: the digest path, the overlap between the `HTTP_FAILURE`
  and `TIMEOUT` causes, the `RETRYING` → `RETRYING` transition, `FR-002-004`'s structure, and an
  edge case that overstated what the crash counter covers. The second pass found none.
- A review against the digest and the design, on the same day, led to these changes:
  - FR-002-052 now states App. B.2's full presence matrix and pairings, and `note` NULL or 1 to 256
    characters; it also gains `attempts ≥ 0` and the (leg, reason) rule;
  - FR-002-043 lists only this slice's cells, and gives `http_status` on a `gateway-transient`
    row as "either";
  - P07 is re-classed as pending external;
  - A2-67's reading is recorded under Clarifications;
  - FR-002-008 is titled interim;
  - the foundational set is corrected, here and in the User Scenarios preamble;
  - User Story 6's Independent Test runs on its own fixtures;
  - FR-002-006 cites A2-50 and R1-23, not R1-7, which is the resend-leg claim;
  - A3-60's access constraint is added to Dependencies, and its encryption and DPIA note to
    Assumptions.
- **For the cross-slice review (closed in the cross-spec pass).** Replay belongs to slice 004 in the
  approved plan. This slice therefore dead-letters `exhausted` any delivery that meets a `FAILED`
  request (FR-002-008, titled interim). For the producer's own copies that is IR's outcome, and it
  holds only until slice 004 replaces it. FR-002-008 is not a deviation and takes no DV id; its
  test is removed when slice 004 lands. Slice 004's replay-admission requirement (FR-004-001) now
  says that it replaces FR-002-008. The later slices cite the shared-core registry and label sets
  instead of defining rival types: slice 003 cites FR-002-057 and FR-002-072 for the
  `probation_body_*` series and `BodyMaintenanceStep`.
- **`rolled-back` rows (closed in the cross-spec pass).** V1's CHECKs carry the `rolled-back` cells
  (FR-002-052). The statement that writes those rows is now slice 004's FR-004-049a, pinned by
  `RollbackFencingIT`; its executor stays open (FR-004-049). FR-002-043 and Out of Scope cite it,
  and FR-002-040 cites `RollbackFencingIT`'s late leg-outcome UPDATE case for the request-claim
  guard.
- **Cross-spec pass, 2026-10-21.** The cross-slice findings were checked against the DIGEST, the
  design and the legacy code at `122e2152`, then applied:
  - The redaction is R0–R11, R12 not ported (DV-09), as 001 states it, in the Context table,
    FR-002-028 and Dependencies. FR-002-028 calls it with the effective state, and FR-002-029 and
    FR-002-030 name 001's result kinds (FR-001-007).
  - 001's hand-off for a parseable payload with no `hearing` is stated in the Edge Cases, the
    Assumptions, Dependencies and the traceability table: the redaction fails in R1, FR-002-030
    records `FAILED(rules-exception)`, nothing is sent, and the outcome is an open item for the
    design owner, not a marker. The cached-`{}` edge case now follows it: a cached `{}` has no
    `hearing`, so R1 fails on it (`PF/functions.js:27`); the earlier text gave it
    `NOT_APPLICABLE(nothing-left-after-redaction)`, which 001's redaction cannot produce for it.
  - FR-002-054 states V1's partition names, UTC bounds and create-if-absent rule (App. B.2), and
    carries 003's open question 1: it is put to the design owner before V1 is written, and a late
    answer is a V2 migration. (Answered since, before V1 is written: option A, H35, 2026-10-21 —
    V1 unchanged, the DEFAULT partition kept.)
  - The registry holds 005's proposed `probation_operations_failures_total` and its two label sets
    (FR-002-057, FR-002-072), so that the registry keeps one owner. FR-002-072 also says which
    vocabularies serve the replay action's refusals and which codes are slice 004's own.
  - FR-002-074 adds P36's service-side pin (`FailureSignalIT`), and SC-002-010 counts five defects.
    The DV-07 and DV-17 rows hand their resend halves to slice 004 (FR-004-050a).
  - Out of Scope adds "no Event Grid input" (H3). The Key Entity "Latest leg state" is renamed
    "Latest attempt", as slices 004 and 006 name it.
- **For the design owner.** Row A2-67's "no settlement" on a stale-runner rejection is read as "no
  completion by that run": the listener abandons, or dead-letters `exhausted` on the final
  permitted delivery (Clarifications; FR-002-007, FR-002-040). The wording is a point to confirm.
  One open item, not a marker, goes with it: the outcome of a parseable payload with no `hearing`
  (Edge Cases; Assumptions). 003's open question 1, which had to be answered before V1 is written,
  is answered: option A (H35, 2026-10-21), V1 unchanged and the DEFAULT partition kept (FR-002-054;
  Assumptions).
- Of the design's "recovery", this slice takes crash recovery: reclaiming an expired claim and
  re-running in place. It also takes the completion-reason rule that writes `recovered`. Recovery
  of a `FAILED` request after a resend (A2-38) is slice 004's.
- The Spec Kit hooks were not run: neither `before_specify`, which creates a branch, nor
  `after_specify`, which commits. `.specify/feature.json` was not set either, because six slices
  were specified in parallel. The developer who starts this feature branches `002-hmpps-distribution`
  from `main` and points the Spec Kit scripts at this folder.

### Question 1: Which cause labels an unreadable read whose first permission is `null`? (FR-002-024)

**Context**: FR-002-024: "H8 makes a 2xx whose `permissions[0]` is `null` an `UNREADABLE` read,
with cause [NEEDS CLARIFICATION …]". The design lists a `null` `permissions[0]` among the
`UNREADABLE` cases, and names four causes (`HTTP_FAILURE`, `TIMEOUT`, `EMPTY_PERMISSIONS`,
`UNPARSEABLE`; A2-28), but maps none of them to that case (design gap §4.4 / A2-28; digest §16).
The cause is the `cause` label of `probation_youth_switch_unreadable_total` and a field of the
outcome log line. It is never stored, and it changes nothing the leg does: the default applies
and the hearing is sent (H8).

**What we need to know**: which cause such a read carries.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | `EMPTY_PERMISSIONS`: a list whose first element is `null` holds no usable permission, like an empty list | The closed set stays at four values. The dashboard cannot tell a `null` first element from an empty or missing list |
| B | `UNPARSEABLE`: the body parses, but not into a usable permission | The closed set stays at four values. "Unparseable" then also covers a body that parses as JSON |
| C | A fifth cause, for example `NULL_PERMISSION` | The most precise label, but the cause vocabulary and the closed label set of design §10.1 grow by one value: a design addition |
| Custom | Provide your own answer | Name the cause, and say whether the label set changes |

**Your choice**: _for the design owner_

### Question 2: How does a gateway answer outside 2xx, 4xx and 5xx map? (FR-002-038)

**Context**: FR-002-038: "A gateway answer outside 2xx, 4xx and 5xx MUST map to [NEEDS
CLARIFICATION …]". The design's answer table (§9; §4.4) maps any 2xx, a 4xx other than 408 and
429, a 5xx, a 408, a 429, a read timeout after the write, an I/O failure once writing began and a
connection failure. The `http` label's buckets are `200`, `2xx-other`, `400`, `401`, `408`,
`429`, `4xx-other`, `5xx` and `none` (A3-116), and the publisher's outcomes are `SENT(status)`,
`FAILED_NON_TRANSIENT(status)` and `TRANSIENT_EXHAUSTED`. An answer outside those classes — in
practice a 3xx that the client does not follow — is mapped by none of them.

**What we need to know**: which leg outcome and which `http` bucket such an answer takes.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | Outcome unknown, as a 5xx: `PARKED(gateway-transient)`, then `FAILED(gateway-transient)` on the final delivery, or at once on a resend; `http` bucket `5xx` | No retry within the run and no silent loss: an answer that persists ends `FAILED` and reaches the leg-failed alert. The `5xx` bucket then also counts statuses that are not 5xx |
| B | A rejection, as a 4xx: `FAILED(receiver-rejected)`, terminal at once; `http` bucket `4xx-other` | The request completes at once and nothing is re-sent. A persistent misrouting that answers with a redirect is only counted, never paged, because `receiver-rejected` is outside the leg-failed alert |
| C | Either outcome above, with a new `http` bucket, for example `other` | The label tells the case apart, but the closed label set of A3-116 grows by one value: a design addition |
| Custom | Provide your own answer | Name the outcome and the bucket |

**Your choice**: _for the design owner_

### Question 3: How is a crash during the re-run of a `PARKED` row counted? (FR-002-061)

**Context**: FR-002-061. `probation_crash_reruns_total{leg}` counts a delivery that re-runs a
`PENDING` attempt-1 row whose request claim expired, because the earlier run may have POSTed before
it died (§4.5 crash window; A3-115). An in-place re-run of a `PARKED` row writes no `PENDING` first
(A2-39), so a run that dies after its POST leaves the row `PARKED`, and the next delivery re-runs it
like an ordinary re-run. The design counts only `PENDING` attempt-1 re-runs and leaves the rest not
established (§10.1; digest §16). The replay's `FAILED` re-run case is slice 004's.

**What we need to know**: whether such a crash is counted, and on what evidence.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | Not counted, as designed: only `PENDING` attempt-1 re-runs count | No change. A possible duplicate at HMPPS after a crash during a `PARKED` re-run is not alerted; it stays part of the documented at-least-once window |
| B | Count every delivery that reclaims a request claim which expired, rather than one released at settlement, whatever the leg's latest row | No schema change, and every re-run after a dead run is counted as a possible duplicate, as the `PENDING` case is. It also counts runs that died before their POST, and it widens the metric's definition in §10.1: a design change |
| C | Mark the row before the POST (a `PENDING` write, or a note form) so that a redelivery can tell | Exact, but it reverses A2-39 (an in-place re-run writes no `PENDING` first) and adds a write to every re-run: a design change |
| Custom | Provide your own answer | Say what is counted, and on what evidence |

**Your choice**: _for the design owner_
