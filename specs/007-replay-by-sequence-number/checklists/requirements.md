# Specification Quality Checklist: Replay a parked request by broker sequence number

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-19
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs) — this relies on the precedent of
      slices 002 and 004, whose checklists accept the design's fixed vocabulary as contract: the
      feature *is* a change to message-transport identity, so broker concepts (sequence number, delivery count, enqueue time,
      `messageId`, dead-letter reason and description) and the design's fixed vocabulary (statuses,
      reasons, `audit_note` lines, metric names) are domain terms, as in slices 002 and 004. The
      "Documents the implementation must update" list names files, not designs; no statement, column
      type or class design appears
- [x] Focused on user value and business needs — support recovers a parked request with the portal's
      Re-send even when a store blip hands the copy back, and the dead-letter queue says why a copy
      was re-parked
- [x] Written for non-technical stakeholders — the Context and user stories explain the gap and why
      this service cannot take the informant register's rule unchanged in plain language
- [x] All mandatory sections completed — plus Context, Dependencies, Out of Scope and the
      documents list

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain — the amendment of A3-30 / FR-004-002, agreed by the
      design owner, is a gating dependency rather than a specification ambiguity
- [x] Requirements are testable and unambiguous — each limb of the decision (FR-007-003 … FR-007-005)
      has a boundary case in FR-007-016, including "exactly at the margin" and "no enqueue time"
- [x] Success criteria are measurable — counts and zero outcomes over the test matrix
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined — five stories with 4, 2, 2, 2 and 3 scenarios
- [x] Edge cases are identified — 13, including a re-send during a resend run, message expiry, two re-sends, a stale
      decision, a re-sent service replay message, submit-hearing, rolled-back rows, unreadable
      metadata, queue recreation, clock skew and a rolling deployment
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified — each code assumption cites file:line; the broker
      behaviours (sequence number, enqueue time on re-send) are taken from the broker documentation
      and pinned by FR-007-016's emulator test

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows — recovery after a hand-back, the parking delivery, duplicate
      copies from before the failure, legacy rows, and the re-park description
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- **Gap established before writing.** The informant register's defect (every unchanged Re-send is
  re-parked) does not exist here: DV-26 already admits a same-id copy on its first delivery
  (`IdempotencyGuard.java:137-143`). What remains is that the first delivery can be handed back before
  the guard judges it — store precondition (`ProbationMessageListener.java:116-120`) or guard-read
  failure (`121-141`) — after which the copy is re-parked `exhausted`, with no description. Deferral
  and a lost replay race also hand back but are not gaps: FR-004-002 intends the first, and after the
  second the copy meets the claim rules, a no-op or a newer failure it predates. The parking delivery is already re-parked correctly; the replay statement has no
  generation check, which only matters once the decision depends on the parking time.
- **Deliberate differences from the informant register's specification.**
  - The sequence-number rule *complements* DV-26 instead of replacing it: duplicate detection is off
    here, so a pre-parking duplicate copy with its own sequence number must not replay the request.
    The admitting test for a handed-back copy is "enqueued after parking plus a margin", for every
    row, not only legacy rows.
  - No separate legacy heuristic: the same rule serves rows without a parking sequence number; only
    the exact parking-delivery check is lost for them.
  - No parking category column: this service parks under one category (`leg-exhausted`), and the
    re-park keeps the contract's `exhausted` reason (closed set, metric label, alert catalogue),
    adding a description instead of changing the reason.
  - A lost compare-and-set is handed back as a lost race is today; no new reason code is coined.
  - The margin has a 1-minute floor and absorbs clock skew only; no lock-duration bound.
  - No duplicate-detection-window caveat in the contract change: there is no window to wait out.
- **Review pass, 2026-11-19.** Applied: deferral and lost race removed from the gap; the resend-run
  edge case corrected (post-margin copies now deferred to their final delivery); message expiry
  added; the column overwritten by every `FAILED` write and read only on `FAILED`, with no status
  constraint, for rolling deployment; SC-007-003 scoped to the exhausted `messageId`; FR-004-004
  listed as amended; the unreachable unlisted-reason branch marked defensive; "parking normally at
  count ≥ 4" because of the reclaim path; DIGEST section references corrected.
- **Design precedence.** FR-004-002's "one admission chance" is the design's rule (A3-30), so the
  specification is gated on that amendment being made (the design owner has agreed it); the
  constitution says the design wins.
