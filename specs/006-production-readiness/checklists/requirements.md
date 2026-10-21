# Specification Quality Checklist: Production readiness and cutover inputs

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-21
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs). *Qualified pass.* The spec names
      only what the design fixes as a contract or a vocabulary: the metric names and bounded
      labels of design §10.1, the leg statuses and reasons, the latest-attempt view, the
      `distribution.*` properties, the producer chain's names and the `TOGGLED-OFF` filter value.
      It prescribes no language, framework, file layout or query text. The alert-rule format, the
      layout of the operations reference and the packaging of the comparison are left to the plan
      (FR-006-052; Assumption 3).
- [x] Focused on user value and business needs. Each story is a decision that the owning team,
      operations or DevOps must take on evidence (go/no-go, cutover, rollback, decommissioning), or
      a page they must act on.
- [x] Written for non-technical stakeholders. *Qualified pass.* The stories, outcomes and success
      criteria are in plain language. The requirements use the design's frozen vocabulary, because
      this slice's stakeholders (the owning team, operations, DevOps and the design owner) work in
      it.
- [x] All mandatory sections completed: User Scenarios & Testing, Requirements and Success
      Criteria. The spec also has Clarifications, Assumptions, Dependencies, Out of Scope, Open
      items and Design traceability.

## Requirement Completeness

- [ ] No [NEEDS CLARIFICATION] markers remain. **Three remain, deliberately.** Each is a genuine
      design gap the spec cannot close on its own authority: FR-006-010 (A3-78, the replay
      mechanics), FR-006-024 (§10.4, the source of results' daily share count and its day boundary)
      and FR-006-032 (§9.2 row 9: what the test call sends where the legacy still sends). The
      questions and their options are below.
- [x] Requirements are testable and unambiguous. The only exceptions are the open parts of the
      three marked requirements. Every other FR names its observable outcome, and the
      build-supported ones are pinned by tests (FR-006-019, FR-006-030, FR-006-032, FR-006-046).
- [x] Success criteria are measurable. SC-006-001 to SC-006-011 each state a count, a percentage or
      a time bound.
- [x] Success criteria are technology-agnostic (no implementation details). They are stated as
      classes, verdicts, pages, rows, steps and counts. No framework, store or tool is named.
- [x] All acceptance scenarios are defined: 40 Given/When/Then scenarios across 7 stories.
- [x] Edge cases are identified: 20, covering the replay, cutover, rollback, the soak and the
      dependencies on other slices.
- [x] Scope is clearly bounded. Out of Scope and FR-006-051 bound it. The runbook itself,
      decommissioning, the producer change and the platform asks are dependencies, not work in this
      slice.
- [x] Dependencies and assumptions identified. The spec lists slices 001–005 by what each supplies,
      with their requirement ids where another slice owns the capability. It lists the external
      preconditions by owner (results team, platform team, operations and DevOps, HMPPS, the design
      owner, the business, the data owner), 10 assumptions, and 16 open items carried from the
      design and from the other slices' open questions.

## Feature Readiness

- [ ] All functional requirements have clear acceptance criteria. 49 of 52 have them.
      FR-006-010 and FR-006-024 cannot have complete criteria until their markers are answered, and
      FR-006-032 lacks only its row-9 entry's content. Their decided parts are covered: the
      comparison independent of the mechanics (US1, scenarios 1–9), the service side of the
      reconciliation (US5, scenarios 1–5), and every other evidence row (US2, scenarios 1–6). The
      evidence query for row 9 reads the call's attempt row whatever the answer.
- [x] User scenarios cover primary flows: the pre-cutover proof, the go/no-go, cutover, the planned
      and urgent rollback and the roll-forward, the paging alerts and the counts, the daily
      reconciliation, the detection of a "both", and decommissioning.
- [x] Feature meets measurable outcomes defined in Success Criteria. Each SC traces to its FRs:
      SC-006-001–SC-006-003 to FR-006-001–FR-006-012; SC-006-004 and SC-006-005 to
      FR-006-014–FR-006-022; SC-006-006 and SC-006-010 to FR-006-031–FR-006-036; SC-006-007 to
      FR-006-037–FR-006-046; SC-006-008 to FR-006-050; SC-006-009 to FR-006-023–FR-006-030;
      SC-006-011 to FR-006-020 and FR-006-027.
- [x] No implementation details leak into specification. This passes with the same qualification as
      the first Content Quality item.

## Notes

- **Clarify-style pass, 2026-10-21.** 21 ambiguities were resolved from the DIGEST, the design rev
  3.6 and slice 001. Each is recorded in the spec's Clarifications session with its design
  reference. They include: the runbook and decommissioning being outside the repository; the
  build-supported rows; the alert set and its origins; DV-25 in the replay; the latest-attempt
  body; pairing by share; the cut-short SIT body; https for the stub; the exports not committed;
  the soak length; the switch owners and the `TOGGLED-OFF` value; the 10-minute wait; the urgent
  rollback before T; intake resuming only after roll-forward; and no service-side read of the
  Results switch.
- **Open design items carried, not decided.** These are Q28, P36's alert-on-failure ticket,
  A3-75's query and environment coverage, the §9.4 step 4 execution path (the design owner places
  it; 004 raises the question as FR-004-049), the alert routing (open, owned by the owning team,
  A3-111), 003's open question 2 (how alert 9 combines the pods' readings; 003's AS-15), 001's
  FR-001-021 (the expected body of a cut-short SIT body), Q20/D9, §11.3, A3-89, H5, where the
  platform keeps alert-rule definitions, and Confluence lag. The spec's "Open items" section lists
  them, with the three marked questions.
- **Validation iterations.** Iteration 1 found four problems, all fixed:
  - negated requirements written as "no … MUST" (FR-006-013, FR-006-014, FR-006-045, FR-006-050);
  - a hearing with a defaulted switch read that could fall into two classes (FR-006-004);
  - a step mis-attributed in the runbook entry of alert 2;
  - App Configuration's flag wrongly described as re-applied after an STE rebuild (only the
    static-data patch is).

  Iteration 2 found FR-006-046 missing from the traceability table, which was fixed. Iteration 3
  was clean. Every FR (52) appears in the traceability table, and there are no duplicate or missing
  FR ids.
- **Review pass, 2026-10-21.** The review's findings were checked against the DIGEST, the design
  and slices 001–005, then applied:
  - **Cross-spec consistency with 001 (FR-006-007).** The marker on cut-short SIT logged bodies is
    removed. The replay now carries 001's open question (FR-001-021) unchanged, and the design
    owner's answer stays with 001's existing question rather than a second one. The class "SIT body
    unusable" blocks cutover until it is explained. The freed marker is spent on the §9.2 row-9
    test call (FR-006-032), which had been only an open item.
  - **One vocabulary with 001.** Pairing is by share (hearing id, hearing day and `sharedTime`
    instant). "Unpaired" is one class with the sub-reasons SIT-only, STE-only and ambiguous.
    Numbers are compared in 001's words. FR-006-006 and FR-006-011 now say how an unpaired or
    unusable share is explained, so the verdict rule matches US1's scenarios.
  - **No decision taken for another owner.** Rollback step 4's execution path is the design
    owner's to place (FR-006-040), not 004's to deliver; the US3 rehearsal is qualified to match.
    Alert routing is shown as open (FR-006-014, US4).
  - **Fidelity fixes.** The urgent rollback forbids restoring the subscription before intake is
    paused, which is the order the design rejects (FR-006-042; US3 scenario 3 now follows the
    design's step order). Pre-run check (c) names the service's system user and the
    `EMYouthFilter` permission, not the Results switch's UI patch (FR-006-008). Replay is a repair
    route once the service is re-enabled (FR-006-041). The evidence for rows 1 and 3 adds results'
    healthcheck and the config review (FR-006-032). Alert 9 shows its dependency on 003's open
    question 2.
  - **Adjusted.** The finding asking to hedge the 004 dependency ("to be confirmed in the
    cross-spec pass") rested on 004 not listing the runners. 004 does list them (FR-004-042,
    FR-004-043, FR-004-051, FR-004-053, FR-004-054), so the dependency now cites those ids
    instead, and the cross-spec pass can check them directly.
- **Cross-spec pass, 2026-10-21.** The cross-slice findings were checked against the DIGEST and the
  design, then applied:
  - FR-006-007 no longer decides 001's FR-001-021. A cut-short or non-JSON SIT body has no expected
    body while that question is open; the executed legacy is not used; the share is SIT body
    unusable and blocks until an explanation entry covers it. The Clarifications bullet, US1's
    Independent Test and scenario 9, the Edge Cases, FR-006-004, FR-006-005, the Key Entities,
    Assumption 1, Dependencies and Open items follow.
  - The success criteria are renumbered SC-006-001 … SC-006-011, so that they cannot collide with
    001's.
  - 003's open question 2 is cited as 003's assumption AS-15: 003 withdrew the marker FR-003-029,
    because the design's default stands. FR-003-022 is cited only for the gauge itself.
  - FR-006-040 and Dependencies cite 004's close-in-flight statement (FR-004-049a); its executor
    stays open (FR-004-049).
  - 005's proposed operations-failure counter is neither alerted nor counted by this slice
    (FR-006-014, FR-006-022), so FR-006-019 stays true.
  - FR-006-049 adds keeping the LAA feed's owner informed (D12).
  - **Not applied: the urgent-rollback order.** The finding asked to move "then planned steps 5–7"
    after step 6's purge, as the DIGEST's H32 row reads. The design's own text places it inside
    step 5 (§9.4, urgent rollback step 5; row A3-117 (iii); row A3-113's amended step 5), and the
    design wins over the digest. FR-006-042 and US3 scenario 3 therefore keep the design's order,
    and the digest's wording is raised for correction.
- **Public-repository hygiene.** A grep over `spec.md` and `checklists/requirements.md` for
  personal names, hostnames, URLs, cloud resource names, environment labels, production figures
  and attribution found none. The environment-specific names of the cutover (the subscription, its
  topic, the configuration store and the labels) are placeholders by FR-006-037.
- Items marked incomplete require spec updates before `/speckit.clarify` or `/speckit.plan`. The two
  open boxes above are the three markers, and they need the owners' answers, not spec edits.

### Question 1: How is the SIT week fed into STE? (FR-006-010; A3-78)

**Context**: "The replay MUST feed the week's SIT `Hearing_Resulted` shares … to the service in STE
by …". The design's Inputs row has results' flag ON in the STE stack, which assumes a replay fed
through the producer.

**What we need to know**: what is injected into STE, and where STE's results and the service's
Redis read and query-API fallback get the `EXT_` payloads for SIT hearings.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | Inject results' `Hearing_Resulted` handling (a results command or event) with results' flag ON in STE, so results writes `EXT_` and publishes | Exercises the producer and the service together. STE's own reference data shapes the payload, so differences rooted in it are expected and must be explained |
| B | Inject one `probation.requests` message per share, built from the week's shares, with STE's Redis loaded from the exported `EXT_` payloads | Exercises the service alone, on the payloads SIT had. It does not exercise the producer in STE, and it needs a loader for Redis |
| C | Inject upstream Event Grid events and let STE's whole chain run | The broadest proof, with the most moving parts and the most differences that are not the service's |
| Custom | Provide your own answer | State the injection point and the payload source |

**Your choice**: _awaiting the owning team (owner of A3-78)_

### Question 2: Where does results' daily share count come from? (FR-006-024; §10.4)

**Context**: "The service-side counts MUST be compared with results' daily share count for the same
day, obtained from …".

**What we need to know**: the source of results' daily share count, and the day boundary that both
sides count on.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | A results viewstore query per day | Counts what was shared, independent of the flag; needs a query the results team owns |
| B | The `Hearing_Resulted` Event Grid publish count | A count already produced per share; covers the regular path only |
| C | A counter on the producer side of the probation publish | Counts what the producer sent, so it misses shares lost to a failed flag read (the "neither" case the reconciliation is meant to find) |
| Custom | Provide your own answer | State the source and its day boundary |

**Your choice**: _awaiting the results team_

### Question 3: What does the row-9 test call send where the legacy still sends? (FR-006-032; §9.2 row 9)

**Context**: "The row-9 entry MUST also state what the test call sends, and by which action, in an
environment where the legacy still sends." Row 9 asks for a test call from the service's pod,
accepted on the HMPPS route and shown by a `SENT` attempt row, and A3-108 makes it the production
reachability proof. Before cutover the legacy still sends every share, and the HMPPS POST is not
idempotent (H12).

**What we need to know**: what the call sends, and through which action, so that it proves
reachability without an unagreed duplicate at HMPPS.

**Suggested Answers**:

| Option | Answer | Implications |
|--------|--------|--------------|
| A | A real hearing, sent by submit-hearing, after HMPPS agrees to receive that hearing twice | Uses an existing action, and the attempt row is the evidence. In production it is a deliberate duplicate, so it needs HMPPS's agreement for the named hearing |
| B | A real hearing the legacy did not deliver, sent by submit-hearing | No duplicate: the call is also a repair. It depends on such a hearing existing while its `EXT_` payload is still held, and on showing that the legacy did not deliver it |
| C | A real SIT hearing in SIT; in production, row 9 is shown by the first shares after the flip | No duplicate in production. But row 9 is then not a production precondition, which departs from §9.2 (every row holds before the Results switch is turned on) and leaves reachability unproven at the flip |
| Custom | Provide your own answer | State what is sent, by which action, and who agrees to it |

**Your choice**: _awaiting the design owner, with HMPPS_
