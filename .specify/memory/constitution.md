<!--
SYNC IMPACT REPORT
==================
Version change: 1.1.0 → 2.0.0
Bump rationale: MAJOR. Design rev 3.6 (2026-10-20; decisions H1–H34) is
                adopted whole. Its decisions since rev 3.3 remove or redefine
                principles and invalidate practice the 1.x text required:
                - H13: outbound schema validation is parked; the interim
                  outbound schema and pre-build gate G1 are gone
                  (Principle III);
                - H21: the `shadow` profile and `shadow_capture` are gone; the
                  pre-cutover proof is SIT-data unit tests plus a one-week
                  SIT → STE replay (Principle I);
                - H14: broker duplicate detection is off, reversing "on, set
                  at queue creation"; the processed-log guard is the only
                  duplicate protection (stack section);
                - H9: the operations API is in scope for replay and
                  resend-leg, and a resubmission from the dead-letter queue is
                  a supported replay route, DV-26 (Principles III, VI; stack
                  section);
                - H18, H20: the exact outbound body is stored, in
                  `outbound_body`, and purged after 6 months, reversing
                  digest-only storage (Principles IV, VII; stack section);
                - H11, H15: an outcome-unknown POST is no longer retried
                  in-run; it parks for broker redelivery (Principle VI).
                H9 had awaited its own amendment since 1.1.0. H10 (warn-only
                validation) was never adopted; H13 parks it.

Design citations: "design §n" in this document means a section of the design
rev 3.6. Decision ids (H1–H34, D1, D4a, D10, D16, Q15, ...), deviation ids
(DV-n), defect ids (P01-P36), rule ids (R0-R12) and row ids (A2-n, A3-n) are
the design's and are never renumbered here.

Modified principles (2.0.0):
  - I.   Behaviour Parity with Registered Deviations - expected output for
         SIT cases is SIT's own logged HMPPS body; the legacy JavaScript is
         executed only for youth-switch-ACTIVE and synthetic cases (H21); the
         `shadow` profile is replaced by the one-week SIT → STE replay (H21;
         D8 closed); DV-26 allocated (H9), next free id DV-27; DV-04 narrowed
         (H11, H15); DV-08 rests on the guard alone (H14); the carried
         quirks follow design §7.1's classes - P17, P18 and P19 carried, P21
         and P35 pending external (D10); application-only hearings are a
         known class, sent as today (D16 (a); H33).
  - III. Message-Contract First - the ICD v0.8 recorded (H12); no outbound
         schema validation and no pre-build gate G1 (H13); Q6 closed, Q13
         narrowed to its harness check; the replay route recorded for a
         message with no `origin` (H9); the users-groups read, and the
         results fallback when the command carries no `userId` (every resend
         included), use the service's own system user (H30); the operations
         API in scope for replay and resend-leg (H9), the rest of O-1 still
         open.
  - IV.  Canonical JSON In, Typed Models Out - the HMPPS body is no longer
         schema-checked; its exact bytes are stored and checked against
         `body_digest` by a trigger (H18, H20); `service_version` replaces
         `rules_version` and `serializer_version` (H19).
  - V.   SOLID with Ports and Adapters - the validator stage leaves the
         pipeline (H13); OutboundLog also writes `outbound_body` (H20).
  - VI.  Explicit Failure - an outcome-unknown POST (5xx, 408, read timeout
         after the write) parks with no in-run retry (H11, H15);
         `contract-invalid` reserved and unreachable (H13); alerts page only
         for genuine issues and known issues are counted (H34); Q5 closed:
         the POST is not idempotent, so every duplicate is genuine (H12).
  - VII. Privacy in Telemetry - the stored outbound body is never logged at
         any level nor used as a metric label; bodies are kept 6-7 months in
         `outbound_body` (H18, H20); new bounded labels (`forced`, `shape`,
         `http`, `route`, `after`, `step`, the resend refusal `reason`);
         SIT's logged bodies follow the corpus rule (H21).
  - IX.  Scope - Results reads the switch with FeatureControlGuard (H26); the
         legacy is switched off at its Event Grid subscription (H22); the
         service reads no App Configuration and does not check the switch
         (row A3-92).
  - Technology Stack & Deployment - duplicate detection off (H14); two
         replay routes (H9); V1 is three tables and one view on PostgreSQL 15
         (H17, H19, H20, H21; row A3-105); the service's own system user
         (H30); gateway timeouts 5 s / 60 s and the in-run retry predicate
         (H11, H15, H27); the composed send-stage check (row A3-96); the
         operations API (H9); the alert split (H34); new bullets for the
         operator actions (H9, H16, H17) and the body-retention job (H20).
  - Increments; Development Workflow & Quality Gates - gate G1 and the
         interim outbound schema removed (H13); new reviewer checks (H13,
         H14, H15; row A3-60).
  - Preamble and Governance - design rev 3.6 referenced; the design digest
         `doc/design/DIGEST.md` named.

Removed: the `shadow` profile bullet (Principle I); the interim outbound
schema and the pre-build gate G1 bullet (Principle III); digest-only storage
and the shadow capture-table exception (Principle VII). Reversed: broker
duplicate detection, now off (stack section). Added sections: None.

Templates requiring updates:
  - .specify/templates/*.md                   ✅ compatible - no
      constitution-specific content changed.
  - CLAUDE.md, README.md, doc/API_CONTRACTS.md, doc/CHANGELOG.md
                                              ✅ aligned in the same change.
  - .claude/rules/*.md, .claude/agents/*.md, .claude/context/*.md,
    .claude/skills/project-docs.md, .claude/workflows/phase-gate.js
    (prompt text)                             ✅ aligned in the same change;
      retained as quick-reference, and this constitution is authoritative
      where they disagree.
  - doc/design/DIGEST.md                      ✅ added in the same change:
      rev 3.6's rules with their design row ids, no rationale.
  - src/main/resources/application.yaml, docker/servicebus-emulator/
    config.json, docker-compose.yml, build.gradle
                                              ✅ aligned by the scaffold
      update in the same change: gateway read timeout 60 s, the in-run
      retry rule, the service's own system user, the resend-list cap and
      the body-retention properties, no App Configuration, duplicate
      detection off, PostgreSQL 15 in the local stack, and the JSON Schema
      library moved to test scope.

Follow-up TODOs (each is open in the design, not here):
  - O-1 (design §4.8, §11.1): HTTP exposure of the other operator actions -
    list, pause and resume intake, submit-hearing, purge-queue, the optional
    flag check and the recommended `dlq-completed` listing. Until decided
    they run as command-line runners in the image.
  - Q13 (narrowed): the harness check that `hearing.id` survives redaction
    over the corpus (design §6.3, §9.2 row 12). Q20: the landing order of
    the results branch chains (results team). Q28: whether
    `FAILED(payload-changed)` and `FAILED(rolled-back)` page; until placed
    they stay on the leg-failed alert.
  - The one-week SIT → STE replay's mechanics are not established (row
    A3-78).
  - Not established in the design: where the body-retention job sits in
    the package tree (design §4.4; the scaffold puts the `outbound_body`
    writes in `persistence` behind `OutboundLog`); which action or runner
    closes the in-flight rows at rollback (§9.4 step 4); how crash re-runs
    beyond the PENDING attempt-1 case are counted (§10.1); where the
    expected output of a fallback-form corpus case comes from, since SIT's
    logged body reflects the form the legacy read at the time (§6.1).
  - Environment variable names proposed, not confirmed:
    `YOUTH_SWITCH_DEFAULT_WHEN_UNREADABLE` (row A3-24) and
    `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID` (row A3-106). Whether
    `distribution.resend.max-ids-per-run`, `distribution.body-retention` and
    `distribution.body-maintenance.cron` bind any is open: design §8.1 gives
    none, Appendix A proposes `RESEND_MAX_IDS_PER_RUN`, `BODY_RETENTION` and
    `BODY_MAINTENANCE_CRON`.
  - Whether counting 400s rather than paging meets DD-34870 (P36; design
    §10.3) is for that ticket's owner.
  - Rev 3.6 text that still contradicts its own decisions, read here by the
    decision: §1.1 "every failure is counted and alerted" (H34); the §4.1
    identity table's "operations API recommended, open" (H9); the §10.1
    `probation_processed_total` note (rev 3.1); §8.2 gives the sender role
    to submit-hearing only, though the replay action enqueues too.
  - `doc/DEVIATIONS.md` is seeded from design §7.2 (DV-01 … DV-26) by the
    first feature that lands behaviour; it does not exist yet.

History:
  - 1.0.0 (2026-10-19) - initial ratification against design rev 3.1, from
    the sibling constitutions of service-cp-crime-informant-register (1.0.2,
    parity-first) and service-cp-crime-yot-results-distribution (5.2.0,
    fix-first), re-grounded in D1 ("lift and shift"). Corpus location
    resolved before the first push: SIT payloads committed under
    `src/test/resources/corpus/` (Principle VII).
  - 1.1.0 (2026-10-19) - H8 adopted from design rev 3.3: an unreadable youth
    switch defaults (default `ACTIVE`) and the hearing is sent; P16 fixed
    under DV-25; next free DV id DV-26.
  - 2.0.0 (2026-10-21) - design rev 3.6 (H1–H34) adopted: G1, the interim
    outbound schema and the `shadow` profile removed; broker duplicate
    detection off; the operations API in scope for replay and resend-leg,
    and a dead-letter resubmission a replay route (DV-26); the exact
    outbound body stored in `outbound_body` for 6-7 months; outcome-unknown
    POSTs park with no in-run retry; alerts only for genuine issues; next
    free DV id DV-27.
-->

# service-cp-crime-probation-results-distribution Constitution

This service replaces the `hmpps` Azure Function App (the "probation" app in
`cpp-context-azure-legalaidagency`) with a Spring Boot service on AKS. For each
resulted regular hearing share it takes one thin command from its own Azure
Service Bus queue, `probation.requests`, published by a flag-gated increment in
`cpp-context-results`; reads the hearing's `EXT_` payload from Redis, with the
results query API as fallback; reads the users-groups `EMYouthFilter`
permission; redacts the payload by the legacy HMPPS rules; and POSTs what is
left to the HMPPS route on the APIM gateway. It records one outcome per leg per
attempt in its own processed log, with the exact body it POSTed, kept for six
months (design §1.1, §4.1, §4.6).

The legacy app reports success even when a send fails, keeps no record, logs
whole payloads at info, and sends nothing — silently — for a share whose
switch read or Redis read fails (design §1.1, §7.1). This service exists to
end the silence, not to change what is sent: the one send it makes where the
legacy makes none — a hearing whose switch cannot be read, sent with the
configured default applied — is a registered deviation (H8, DV-25).
Management's instruction is "lift and shift" (D1): the rules and the body are
copied as they are; the delivery and reliability defects around them are
fixed, and every intended difference is a numbered, tested deviation.

## Core Principles

### I. Behaviour Parity with Registered Deviations (NON-NEGOTIABLE)

The legacy HMPPS code at the baseline commit `122e2152` of
`cpp-context-azure-legalaidagency` — the HMPPS filter (`PF/functions.js`), the
HMPPS publisher's body construction, and the orchestration around them — is
the **oracle for every observable behaviour** that is not a registered
deviation. Its Jest suite is not: some of its tests are vacuous or
order-dependent (P34). The expected output is what the legacy actually sends
(design §6.1; H21, row A3-67): for SIT cases, with the switch `INACTIVE`, it
is **SIT's own logged HMPPS body** for the same hearing; the legacy Node code
is **executed** only for the youth-switch-`ACTIVE` expectations (the same
payloads with the switch forced on, which SIT does not run) and for synthetic
cases.

- **The rules are ported verbatim** (D1; design §5): R0–R12 in the legacy
  order, one Java method per JavaScript rule, rule ids stable (row A3-2). R3
  and R4 (the youth rules) run only when the switch reads `ACTIVE`, or cannot
  be read and the configured default is `ACTIVE`, in the legacy's place (H7,
  H8; rows A3-17, A3-25). Where a rule tests "truthy", the port
  reproduces JavaScript truthiness, including for the switch's own
  `permissions[0].active` (design §5).
- **What is sent does not change in this delivery** (D10, decided
  2026-10-15). Known quirks are carried and reported, not fixed (design
  §7.1): P17 (the first permission decides), P18 (with the switch on, R4
  drops a court application with no `masterDefendant`) and P19 (no null
  guards: a rule that throws is `FAILED(rules-exception)`, never guarded)
  are carried; P21 (associated persons and delegated powers kept inside
  applications) and P35 (`plea.delegatedPowers` not removed) are pending
  external and stay carried until HMPPS agrees (D10). A reviewer who finds
  one of these "obviously wrong" is looking at a carried defect, not a bug
  in the port. P16 (an unreadable switch sends nothing) is no longer among
  them: it is fixed under **DV-25** (H8, decided 2026-10-19; row A3-26) —
  the body sent is the one the legacy sends for the same payload with the
  switch in the configured default state, so no payload rule changes.
  Application-only hearings are sent as today and HMPPS rejects them with
  400 (D16 (a); row A3-109): a known class, counted rather than paged (H33,
  H34), not a defect to fix.
- **Every catalogued defect P01–P36 has exactly one disposition** from the
  classification of design §7.0: fixed by construction (a DV-n), carried
  (pinned by the harness), pending external, moot, or out of scope (H5). The
  dispositions are the design's §7.1; this repository does not re-decide them.
- **Every intended difference from the legacy is a numbered deviation** (DV-n)
  in `doc/DEVIATIONS.md`, seeded from design §7.2, carrying: the legacy
  behaviour with its `file:line` citation, the new behaviour, why, who
  approved it, whether it changes a message body, and **the test that pins
  it**. An undocumented behaviour change is a defect, whether or not it looks
  like an improvement. DV ids are never reused: DV-01, DV-02, DV-03 and DV-15
  are withdrawn; DV-11 and DV-12 are unallocated until D10; DV-18 to DV-22 are
  reserved; DV-25 is the unreadable-switch default (H8); DV-26 admits a copy
  resubmitted under the exhausted `messageId` at broker delivery count 1 as a
  replay (H9; row A3-30) — a departure from the informant guard, registered
  like any other; the next free id is **DV-27** (design §7.0). Two rows
  changed meaning without a new id: DV-04 now retries in-run only a
  connection failure and 429 (H11, H15; rows A3-43, A3-54), and DV-08 rests
  on the processed-log guard alone (H14; row A3-53).
- **Standing rule** (design §7.0): where the legacy throws and swallows, the
  port throws and reports. A carried crash still sends nothing for its leg,
  but it becomes `FAILED(rules-exception)`, logged at ERROR and counted. It
  needs no DV row of its own.
- **The harness** (design §6): the corpus is real SIT hearing payloads, in the
  cache form and the fallback form of the same hearings, with expected output
  **in both switch states** (row A3-18): SIT's own logged body for a SIT case
  with the switch `INACTIVE`, the executed legacy for the `ACTIVE`
  expectations and for synthetic cases (row A3-67). SIT's logged bodies are
  privacy-sensitive extracts like the payloads (Principle VII). The comparator
  is semantic JSON equality after canonicalisation, BigDecimal throughout; a
  registered deviation that changes output is asserted **by derivation** from
  the expected output and the stated rule, never by editing the golden; **any
  unregistered difference fails the build**. Corpus case 13 (the switch read
  fails) is such a derived case: its expected output is the `ACTIVE` output
  for the same payload, where the legacy gives "no message", registered under
  DV-25 and derived for every corpus payload, not recorded (row A3-28). The
  exact bytes sent are pinned separately, by `body_digest` and fixed test
  vectors, and stored (Principle IV).
- **The one-week SIT → STE replay** (design §6.5; H21, rows A3-66, A3-68) is
  the run-time half of the same discipline and the **pre-cutover proof**, not
  a build gate. One week of SIT `Hearing_Resulted` events is replayed into an
  STE stack running the service against a stub gateway that answers 200 and
  forwards nothing; the bodies in STE's `outbound_body` are compared hearing
  by hearing with SIT's logged bodies by the §6.2 comparator. The report lists
  matches, registered deviations (DV-25 for a defaulted switch read) and
  unexplained differences, and **any unexplained difference blocks cutover**
  (§9.2 row 5). Its mechanics are not established (row A3-78). There is no
  `shadow` profile, capture sink or `shadow_capture` table (H21; D8 closed).

**Rationale**: HMPPS, the receiver behind the gateway, is unchanged and has
not been asked to accept anything new. The only safe migration is one that is
indistinguishable at that boundary, except where a difference has a number, a
reason and a test.

### II. Test-Driven Development (NON-NEGOTIABLE)

Red → Green → Refactor for every behaviour change, without exception.

1. Write the failing test first. It MUST run and fail for the *correct* reason
   — the assertion, not a missing class or a compile error.
2. Write the minimum production code to make it pass.
3. Refactor with the test still green.

Because "it really did fail first" cannot be proved from a commit graph, the
evidence is a convention that a reviewer can audit:

- Test tasks precede implementation tasks in every task list, and each test
  task is closed before the implementation task it guards is opened. A test
  task lands its compile-safe seams so that the recorded red run is a failing
  assertion, never a compile error.
- Each task's commit narrative records the observed red run before the green
  run — the failing assertion, quoted.
- Reviewers reject implementation commits whose tests could not have failed
  first: assertions that are tautologically true, tests that assert only that
  no exception was thrown, coverage added in the same breath as the code with
  no red run recorded.
- **Every DV row names a test that would fail against the legacy behaviour and
  passes against the deviation**, and every carried defect is pinned by a
  harness case or a named test (design §7.1 names them). A row with no
  pinning test is incomplete and blocks merge.

The `qa` reviewer agent gates on that convention. Production code arriving
without an accompanying failing-then-passing test is a FAIL, not a style
comment.

Exempt: pure mechanical refactors (rename, move, extract with no behaviour
change), formatting, and comment-only edits.

**Rationale**: parity (Principle I) is only meaningful if it is executable. A
test written after the code encodes what the code does; a test written from
the oracle or the deviation register encodes what the legacy did, or what the
register says should differ. Only the second protects the boundary.

### III. Message-Contract First (NON-NEGOTIABLE)

This service has **no business REST API**. Its contracts are:

- **Inbound** — the command on `probation.requests`, defined by the closed
  JSON schema `src/main/resources/contracts/distribution-command.schema.json`
  (design §4.2, Appendix B.1; vendored with the first feature): `source`
  (`RESULTS`), `requestId`, `hearingId`, `hearingDay`, `sharedTime`,
  `eventType` (`Hearing_Resulted` only) and an optional `userId` (absent
  allowed, `null` refused), with `additionalProperties: false`. It is the
  informant contract with only the
  `$id`, title and descriptions changed. Message properties: `messageId =
  RESULTS:{requestId}`, `contentType = application/json`, `correlationId =
  hearingId`. The operator actions mark their messages with **broker
  application properties** (`origin = REPLAY`; `origin = SUPPORT` with
  `operator`, `runId`, `reason`), never with body fields, so the closed schema
  is unchanged by them. A resubmission from the dead-letter queue or a
  producer republish carries no `origin`; the replay route — `service`
  (`origin = REPLAY`), `support` (`origin = SUPPORT`) or `copy` (no
  `origin`) — is recorded at admission in `processed_request.audit_note`, as a
  log field and on `probation_replays_total{route}`, with no schema change
  (H9; row A3-34). Agreed with `cpp-context-results`, the publisher.
- **Outbound** — the redacted HMPPS body, POSTed to the HMPPS route on the APIM
  gateway. HMPPS owns what it accepts. Its ICD v0.8 is obtained (H12; rows
  A3-45 … A3-49): success is 200 only, the results POST is **not
  idempotent**, and its schema is stale — it rejects bodies the live receiver
  accepts — so it is information, not a schema this service validates
  against. **There is no outbound schema validation in this release** (H13;
  row A3-50): the body is redacted, digested and sent. H10's validator stage —
  `OutboundContractValidator`, its mode property, its metric and its alert —
  is the parked design and is not built. The `contract-invalid` reason and
  the `contract=invalid` note form stay in V1 **reserved and unused**, so
  un-parking is a code change, not a schema change.
- **No pre-build gate** (H13; row A3-51): gate G1 is parked with the
  validator. Q6 is closed as information for HMPPS (row A3-109); Q13 is
  narrowed to the harness check that `hearing.id` survives redaction over the
  corpus (design §6.3, §9.2 row 12), because APIM builds the backend path
  from it.
- **Consumed contracts** — this service adapts to them and never redefines
  them: the Redis `EXT_` claim-check payload written by results, consumed
  as-is (dated key, then undated key); the results query API
  `hearingDetails/{hearingId}?hearingDate=` fallback with its vendor media
  type; the users-groups permission read by feature key with its vendor media
  type (H7); the APIM gateway's HMPPS route, called with the subscription key
  the function app uses today (D4a: no gateway change). `CJSCPPUID` on the
  users-groups read, and on the results fallback whenever the command carries
  no `userId` (DV-17, every resend included), is the service's **own system
  user** (H30; row A3-106).

Rules:

- The inbound schema is versioned with the repo, and contract tests assert
  against it. Both contracts MUST be documented in this repository, and the
  documentation updated **before** any code change that affects either.
- A change to either contract is a **cross-team event**: it requires a spec,
  an agreed change with the other side, and a compatibility plan (producers
  and consumers deploy independently — assume the old shape is in flight). A
  change to what is sent to HMPPS is also a change to D10 and is out of this
  delivery's scope.
- **The operations API** (design §4.8; H9, row A3-35). The HTTP surface is
  Spring Boot Actuator and `/operations/**`, which exposes **replay** (at any
  time) and **resend-leg** (the forced-resend override included), behind
  YOT's five deployment gates (platform ask 14); the command-line runners in
  the image stay as the fallback. Its OpenAPI document is owned and versioned
  in this repository and updated before the code that changes it. The other
  operator actions — list, pause and resume intake, submit-hearing,
  purge-queue, the optional flag check and the recommended `dlq-completed`
  listing — are decided capabilities that run as command-line runners; their
  HTTP exposure is **open (O-1)**, and adding one requires a constitution
  amendment, not just a spec. The operations API is operators' tooling, not a
  business API: no hearing is submitted to this service over HTTP and no
  consumer reads anything out of it.

**Rationale**: the queue command and the HMPPS body are the whole business
surface, and the operations API is the operators'. Treating them with the
discipline other services give an OpenAPI document is what keeps a redeploy on
either side from silently dropping a hearing.

### IV. Canonical JSON In, Typed Models Out (NON-NEGOTIABLE)

Inbound hearing payloads (from Redis, or the results query API fallback) are
large, sparsely populated, and owned elsewhere. They MUST be handled
end-to-end as the JSON tree model (`JsonNode`) of the Jackson generation the
platform (Spring Boot) provides — currently Jackson 3
(`tools.jackson.databind.JsonNode`) under Spring Boot 4.1:

- No POJO/record mapping of the inbound hearing payload. Unknown fields MUST
  survive untouched; binding to a typed model silently discards what it does
  not know, and this service is not the owner of that shape.
- Jackson MUST be configured with the platform equivalent of
  `USE_BIG_DECIMAL_FOR_FLOATS`, so numeric values round-trip exactly (design
  §5, §6.2).
- Inbound trees are **immutable in practice**: never mutate a `JsonNode` you
  did not construct. The legacy relied on the Durable Functions serialisation
  boundary to give each activity its own copy; the port keeps that boundary
  by construction — the redaction deep-copies its input once on entry and
  applies its rules to that copy (design §5).
- **The HMPPS body is the redacted tree**, not a typed model: the legacy sends
  the filtered JSON, and the receiver's shape is owned elsewhere. It is
  therefore not bound to a type, and in this release it is not checked
  against any schema (H13). What is recorded is the exact UTF-8 bytes POSTed:
  `body_digest` (SHA-256 over those bytes) on the attempt row, and the bytes
  themselves in `outbound_body`, where a trigger refuses a body whose hash
  differs from its attempt's `body_digest` (H18, H20; row A3-63). The attempt
  row carries the build's `service_version`, which replaces `rules_version`
  and `serializer_version` (H19; row A3-61).
- Everything this service *originates* — the inbound command, decisions,
  outcomes, processed-log rows, operator commands, metric and log fields —
  MUST be typed Java records and enums with bounded value sets.

**Rationale**: fidelity in, a faithful record out. `JsonNode` guarantees that
nothing the legacy passed through is lost; the parity harness guarantees that
what is sent is what the legacy sends; the digest and the stored body make
"what did we send?" a select, not a reconstruction.

### V. SOLID with Ports and Adapters (NON-NEGOTIABLE)

The pipeline is an application core surrounded by adapters (design §4.4):

```
ASB listener (inbound adapter)
    → IdempotencyGuard          (processed-log claim; copied from informant)
    → DistributionRunner        (application core; LegResender for resend-leg)
        → HearingPayloadSource  (Redis EXT_ → results query API fallback)
        → YouthFilterSource     (users-groups EMYouthFilter permission, per message)
        → HmppsRedaction        (pure: R0–R12, R3/R4 when ACTIVE)
        → body digest           (no schema validation in this release, H13)
        → HmppsSender           (APIM gateway, HMPPS route)
        → OutboundLog           (outbound_message rows, one per leg per attempt;
                                 outbound_body, the exact bytes POSTed)
```

- Transport, payload source, the switch read, the send and the processed log
  MUST each sit behind an interface owned by the core. The core MUST NOT
  import Azure Service Bus, Redis, HTTP-client or JDBC types. Jackson's tree
  type is the one qualified exception, by Principle IV.
- The redaction MUST be pure: JSON in, JSON out, no I/O, no clock, no
  randomness. It is the parity-critical code and MUST be testable with golden
  files alone.
- **The per-leg structure is the extension point** (row A3-1): the `Leg` value
  set and the `outbound_message.leg` column are kept with one value, `HMPPS`.
  A further leg is added as a new leg value, projection and sender — never as
  a branch inside the runner or the redaction.
- Dependency injection MUST be constructor parameters with `private final`
  fields (explicit constructor or Lombok `@RequiredArgsConstructor`).
  Field-level `@Autowired` is forbidden, including in tests.
- Stubbed adapters are a legitimate, temporary state. A stub MUST implement
  the real port interface, MUST log at a level that makes its no-op-ness
  obvious, and MUST NOT be reachable in a production profile once the real
  adapter lands.

**Rationale**: the parity work is in the redaction; the risk is in the
adapters. Separating them lets the golden suite run in milliseconds with no
broker, no cache, no gateway and no users-groups, and lets each adapter be
replaced — the payload source in particular (D5) — without reopening ported
logic.

### VI. Explicit Failure — Nothing Is Ever Swallowed (NON-NEGOTIABLE)

**No exception is ever caught and ignored.** Not "for robustness", not "to
keep the consumer alive", not in a `finally`, not in a stub.

**Every delivery is settled exactly once.** For every delivery the listener
tells the broker exactly once what happened — `complete`, `abandon` or
`deadLetter` — after the state that justifies it is durably written. It never
lets the lock expire and never answers twice (design §4.2, §4.5). Auto-complete
is forbidden.

**Every admitted share ends with a recorded outcome per leg** (design §1.1,
§4.5): `SENT`, `NOT_APPLICABLE` with a bounded reason, `PARKED` with a bounded
reason, or `FAILED` with a bounded reason. A message that is not admitted —
dead-lettered `validation`, `collision` or `exhausted` — has a message-level
outcome instead: its dead-letter entry and its count (row A2-55). Every
failure path therefore ends in exactly one of:

1. **Park and redeliver** — a transient failure (`payload-unavailable`,
   `gateway-transient`) parks the leg and the message is abandoned for
   redelivery; on the final permitted delivery the leg is `FAILED` and the
   message is dead-lettered `leg-exhausted`. On a resend there is no delivery
   to park against, so the same failure is `FAILED` at once (row R2-8).
   `gateway-transient` is a connection failure or a 429 still failing after
   the in-run retries, or — at once, with **no in-run retry** — a 5xx, a 408,
   or a read timeout or I/O failure after the request was written, all
   **outcome-unknown** (H11, H15; rows A3-43, A3-54).
2. **Dead-letter** — a message that cannot be admitted (`validation`,
   `collision`, or `exhausted` on an inherited final-delivery path; row A2-55),
   or an admitted message whose final permitted delivery still leaves a
   `PARKED` leg (`leg-exhausted`, item 1). The reason set is closed:
   `{validation, collision, exhausted, leg-exhausted}` (row A2-34). A copy of
   a `FAILED` request under its exhausted `messageId` is re-dead-lettered
   `exhausted` only above broker delivery count 1; at count 1 it is admitted
   as a replay (DV-26).
3. **Record a terminal failure and complete** — `receiver-rejected` (a 4xx
   other than 408 and 429, a 401 included) and `rules-exception` are terminal
   at once. `contract-invalid` is reserved and unreachable while validation is
   parked (H13). A completed message is not a silent one: what was not sent
   is on a row.

**An unreadable youth switch is not a failure path** (H8; rows A3-23,
A3-25). An HTTP failure after the legacy 3 × 1 s retry, a timeout, an empty
or missing `permissions` list, a `null` `permissions[0]`, an unparseable
body, or the switch stage passing its 25 s budget resolves to
`distribution.youth-switch.default-when-unreadable` (`ACTIVE` unless set to
`INACTIVE`), and the leg continues — redact → body digest → send — on a
queue delivery, a replay and a resend alike. Its outcome is whatever those
stages give, settled like any other run; nothing about the read itself
parks, fails or dead-letters. The row records
`youth_switch = UNREADABLE_DEFAULTED_ACTIVE` (or `_INACTIVE`); the read is
logged at WARN with its cause, counted and alerted (design §4.4, §10). It is
not silent: the assumption is on the row, in the metric and behind an alert.
`youth-switch-unreadable` is no longer a leg or request reason: it is absent
from the (status, reason) and (leg, reason) vocabularies, the `LegReason`
and `FailureReason` enums and their CHECKs; the request failure reasons are
`leg-exhausted` and `rolled-back` (design §4.5, §4.6).

**Signals are required in addition, never instead.** Whichever outcome is
taken, a failure MUST also produce a sanitised ERROR log line and increment a
bounded metric (design §10). **Alerts page only for genuine issues** (H34;
rows A3-115, A3-116): the dead-letter queue not empty; a send parked, or a
request non-terminal, for more than 30 minutes; a send still failing after its
last delivery or on a resend, or a rules exception; repeated 401s; a hearing
sent on an assumed youth-switch value; a send re-run after a crash; a
body-retention failure; the legacy function app sending after cutover; and,
on the results side, a failed read of the Results switch. Known issues are
**counted, not paged** — on the dashboard and in the daily reconciliation:
application-only 400s; other 400s (with a spike alert); 500s recovered by a
later delivery; 429s recovered by an in-run retry; a 2xx other than 200.
Thresholds are proposals, tuned after go-live. `payload-changed` and
`rolled-back` stay on the leg-failed alert until the design places them
(Q28). An alert is not a way of settling a message.

Specific rules:

- The HMPPS publisher **never throws past its outcome**: it returns `SENT`,
  `FAILED_NON_TRANSIENT` or `TRANSIENT_EXHAUSTED`, and the runner maps each to
  the leg's (status, reason) (design §4.4). Any 2xx is `SENT`; a 2xx other
  than 200 is also logged at WARN and counted as an anomaly (H12; row A3-45).
- An outcome-unknown POST — a 5xx after APIM's own retries, a 408, a read
  timeout or I/O failure after the request was written — is **not retried
  in-run**: the leg parks and the broker redelivers (H15; row A3-54). Each
  redelivery is a new POST, which APIM may itself retry; the exposure is
  bounded by `maxDeliveryCount` 5 (row A3-43). The guarantee is stated
  honestly: a leg recorded `SENT` or `NOT_APPLICABLE` is never sent again by
  a redelivery or a replay; across a crash between a 2xx and recording it,
  at-least-once. A duplicate is preferred to a silent loss. **The results
  POST is not idempotent** (ICD v0.8; Q5 closed, row A3-109), so every
  duplicate — the crash window, a forced resend, a redelivery after an
  outcome-unknown answer — is a genuine duplicate at HMPPS, and a
  `FAILED(gateway-transient)` leg is confirmed with HMPPS before any manual
  re-send (design §9.4 step 7). Do not write code, or a comment, that
  promises more.
- No statement of this service moves a `COMPLETED` request (row A2-27); request
  completion is historical, and a leg's truth is its latest row (row A2-49).
  A forced resend appends its own attempt row and leaves its request
  `COMPLETED` (row A3-36).
- `catch` blocks MUST rethrow, wrap-and-rethrow, or perform one of the
  outcomes above. An empty `catch`, a `catch` whose body is only a `debug`/
  `trace` log, and a swallowed `InterruptedException` (without restoring the
  interrupt flag) are all build-blocking.
- **Service Bus health MUST NOT gate readiness.** A broker blip must not roll
  the pods; queue health is an alerting concern, surfaced as its own metric and
  a non-readiness health group.
- `System.out`, `System.err` and `printStackTrace()` are forbidden in
  production code and tests; diagnostics go through SLF4J.

**Rationale**: the legacy reports success when a send fails, keeps no record,
and leaves an info line where a hearing went unsent (P05, P06, P16; design
§1.1). Where a hearing can still be sent safely — an unreadable switch, with
its default stated, recorded and alerted (H8) — sending it beats a recorded
non-send. A loud, recorded, counted failure is a success of this design; a quiet
one is the only true outage. Paging only on what someone must act on keeps the
pages meaningful; the counts keep the known issues visible.

### VII. Privacy in Telemetry (NON-NEGOTIABLE)

Logs, metrics, traces, and exception messages MUST NOT carry defendant
personal data at `INFO` level or above — no names, dates of birth, addresses,
NINOs, contact details, or free text that may contain them. The legacy logs
whole payloads at info (P14) and serialises errors to nothing (P15); this
service does neither (DV-09).

- The permitted correlation set at `INFO` is: `requestId`, `hearingId`,
  `hearingDay`, `source`, the broker `sequenceNumber` and `deliveryCount`,
  `leg`, status and bounded reason, HTTP status, `body_digest`, the run's
  `runOrigin`, for a replay its `route` (`service`, `support`, `copy`), for a
  forced resend `forced=true`, the `youth_switch` value recorded (`ACTIVE`,
  `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`, `UNREADABLE_DEFAULTED_INACTIVE`)
  and, for a defaulted read, its bounded cause, counts and timings (design
  §10.2). Every log line about processing MUST carry `requestId` and
  `hearingId`.
- Whole payloads MUST NOT be logged at any level in a deployed environment.
  Where a payload dump is genuinely needed for local diagnosis it goes behind
  `DEBUG` **and** an explicit local-only profile guard. **The outbound body —
  the bytes POSTed and the stored `outbound_body` — is never logged at any
  level, in any profile, and never put in a metric label** (row A3-60; design
  §10.2).
- Metric labels and dimensions are logs too: never label a metric with
  anything identifying a person. Every label is a bounded enum: `reason`,
  `cause`, `defaulted`, `status` and `origin`;
  `probation_youth_switch_unreadable_total` carries `cause` and `defaulted`
  (`ACTIVE` | `INACTIVE`) and nothing more (row A3-27); `forced` ∈ {`false`,
  `true`} (row A3-36); `shape` ∈ {`application-only`, `case`} (row A3-114);
  `http` ∈ {`200`, `2xx-other`, `400`, `401`, `408`, `429`, `4xx-other`,
  `5xx`, `none`} (row A3-116); `route` ∈ {`service`, `support`, `copy`} (row
  A3-34); `after` ∈ {`429-in-run`, `5xx-redelivery`} and `step` ∈ {`lock`,
  `create`, `drop`} (rows A3-116, A3-64; design §10.1); the resend refusal
  `reason` ∈
  {`unknown-request`, `not-eligible`, `resend-in-progress`,
  `request-claimed`} (row A3-56). Never raw exception text, never a fragment
  of a body or a far end's words.
- **The exact outbound body is stored** (H18, H20; rows A3-59 … A3-65): the
  UTF-8 bytes POSTed, one `outbound_body` row per attempt that serialised a
  body, partitioned by month on `created_at` (UTC) and purged after
  `distribution.body-retention` (`P6M`; whole months are dropped, so 6–7
  months in effect). It is personal data, youth defendants included: not
  column-encrypted, as the platform's other service databases; access limited
  to the service role and the security-cleared operations team; never logged
  and never in a metric; the data owner and the DPIA are informed (a note, not
  a gate). After the purge the attempt row's `body_digest`, `service_version`
  and metadata remain. Hearing payloads are never persisted. D13 is closed;
  there is no shadow capture table (H21).
- **This repository is public.** Hearing data from production environments
  (PRD, PRP, or any environment holding production data) MUST NOT be
  committed to it. SIT holds no production data, so the recorded parity
  corpus of SIT payloads MAY be committed as test fixtures, under
  `src/test/resources/corpus/` only (decision 2026-10-19; design §6.4).
  **SIT's logged HMPPS bodies, taken as expected output, are the same kind of
  extract and follow the same rule and location** (H21; row A3-67); SIT data
  replayed into STE is held under the same controls (design §6.5). Other
  fixtures are synthetic or copied legacy test fixtures. Corpus content is
  never logged in a deployed profile and never quoted in docs, PRs or
  findings.
- Secrets, connection strings, subscription keys and tokens MUST NOT appear
  anywhere in output.

**Rationale**: this pipeline carries criminal-court results for named
individuals, youth defendants among them, across the estate's log shipping.
Correlation ids are enough to debug it; personal data in a log index, or in a
public repository, is an incident.

### VIII. Estate Conventions (NON-NEGOTIABLE)

- **Build**: Gradle (wrapper committed). Maven is forbidden. The repository is
  derived from the crime Spring Boot template by way of the sibling services;
  it is never scaffolded from scratch.
- **Static analysis**: every analysis runs in `check`, and therefore in
  `build`; none of them has to be remembered on a command line. **PMD**, pinned
  (`toolVersion` in `gradle/pmd.gradle`) so a toolchain bump cannot change a
  verdict without a commit saying so, with `ignoreFailures = false`:
  `pmdMain` against `.github/pmd-ruleset.xml` and `pmdTest` against
  `.github/pmd-test-ruleset.xml`. **Checkstyle**: `google_checks` via
  `config/checkstyle/google_checks.xml`, `maxWarnings = 0`, over main **and**
  test sources, with `config/checkstyle/checkstyle-suppressions.xml`
  suppressing `MethodName` over `src/test` only. **Coverage gate**:
  `jacocoTestCoverageVerification` in `check` — LINE ≥ 0.88, BRANCH ≥ 0.85,
  excluding the application entry point and `config/**` (design §8.3). It
  `mustRunAfter` `jacocoTestReport`, so a failing gate still leaves a report.
  Thresholds are a ratchet, never loosened in passing; CI's coverage ratchet
  guard fails a pull request that lowers or deletes a `minimum`. Warnings are
  not tolerated as normal (`-Werror`). Suppressions MUST be inline (or, where
  the tool has no inline form, in the named ruleset or suppressions file),
  narrow, and carry a reason.
- **Package root**: `uk.gov.hmcts.cp`; this service's code lives under
  `uk.gov.hmcts.cp.probationresultsdistribution`. Explicit imports only —
  wildcard imports are forbidden.
- **Commits**: Conventional Commits (`feat:`, `fix:`, `chore:`, `docs:`,
  `refactor:`, `test:`, `build:`, `ci:`, `style:`).
- **Branches**: features use Spec Kit's sequential `NNN-slug` naming and land
  on `main` through a pull request; the `main` ruleset requires an approval,
  resolved threads and the CI status checks. There is no Jira ticket for this
  work at ratification; if a ticketed workflow arrives, branch naming is
  revisited by PATCH amendment.
- **No AI attribution anywhere** — not in commit messages, branch names, PR
  titles or descriptions, code comments, or documentation. No
  `Co-Authored-By` trailers naming a tool, no generated-with footers. All
  output reads as developer-authored work.
- **Logging** is SLF4J + Logback. `src/main/resources/logback.xml` configures a
  single console appender using `LoggingEventCompositeJsonEncoder`
  (`net.logstash.logback`), so JSON is emitted **unconditionally** — there is
  no `json` Spring profile and no plain-text alternative. MDC is a declared
  provider, which is what puts `requestId` and `hearingId` on every line
  (Principle VII).

**Rationale**: this repository is one of some seventy in the CPP estate and is
operated by people who did not write it. Uniform build, analysis, naming and
history let them read it the way they read everything else.

### IX. Scope — the HMPPS Leg Only (NON-NEGOTIABLE)

- **HMPPS only** (H5, a working assumption of 2026-10-15, revisited if the
  business answers otherwise). The legacy's second send, to VEP, is not built:
  no VEP projection, sender, schema, key, stub or enabled flag. VEP is
  decommissioned with the function app after business confirmation (design
  §9.6). Re-adding it is a new leg value, projection and sender (Principle V)
  and a new spec — never a flag in this one.
- **The youth switch is retained at legacy parity** (H7, decided 2026-10-19).
  Per message, inside the HMPPS leg after the payload fetch, the service reads
  the users-groups `EMYouthFilter` permission as the legacy does and decides
  by `permissions[0].active`; `ACTIVE` applies R3 and R4. The read keeps the
  legacy's retry (3 attempts, fixed 1 s, the legacy's stop rule — row A3-13);
  its finite timeouts are one difference (DV-24). The service follows the
  live toggle, so cutover carries no youth behaviour change; whether the
  toggle changes (CHD-2701) is the business's choice, made in users-groups
  data, not in this service (design §1.4).
- **An unreadable switch defaults and sends** (H8, decided 2026-10-19;
  DV-25, the other difference). The default,
  `distribution.youth-switch.default-when-unreadable` (`ACTIVE` | `INACTIVE`,
  default `ACTIVE` — production's live state today; env
  `YOUTH_SWITCH_DEFAULT_WHEN_UNREADABLE`, name proposed), is validated and
  logged at startup (row A3-24). It is configuration, not a read of
  users-groups, so it does not follow the toggle by itself, and a mismatch
  fails either way: `ACTIVE` against a live `INACTIVE` removes, in an outage,
  youth defendants the live setting would send; `INACTIVE` against a live
  `ACTIVE` sends youth defendants production removes — the data-protection
  direction, and the more serious. **The property MUST equal the
  environment's live `EMYouthFilter` reading**, checked at the go/no-go and
  again after any CHD-2701 change (design §9.2 row 7, §9.5 row 5; row
  A3-29). It is the one coupling to the toggle; it is not a cutover lever.
- **The service does not read the cutover switch**
  `ProbationResultsDistributionService` (Q15; H26, row A3-92). Cutover uses two
  switches, neither of them in this service: Results gates the probation
  publish with `FeatureControlGuard`, reading the App Configuration flag
  directly, fail-closed (H26; row A3-89); the legacy function app reads no
  switch and is switched off at its Event Grid subscription (H22; row A3-70).
  Nothing in this service — no property, endpoint or Helm value — is a second
  lever, and **the service reads no App Configuration at all** (row A3-69). A
  service-side check of the switch was considered and left out for now (row
  A3-92): pause-intake is the service-side stop. The optional diagnostic flag
  check (design §4.8 action 7), if adopted, would read users-groups' synced
  copy through the users-groups client the service already has, and is not a
  gate.
- **Out of scope by construction**: the LAA feed (coordination only, D12); the
  Results Store (on hold, H1); SJP hearings (the producer sends only on the
  non-SJP branch); the NOWs function app; any change on the APIM gateway (D4a);
  the receiver itself; the producer increment, which is built in
  `cpp-context-results`, not here.

**Rationale**: the design narrowed the service deliberately, decision by
decision. Scope that creeps back in through a flag, a stub or a "while we are
here" change re-opens decisions the business has taken.

## Technology Stack & Deployment

- **Java**: 25. **Framework**: Spring Boot 4.1, from
  `hmcts/service-hmcts-crime-springboot-template` by way of the sibling
  services.
- **Ports**: local `8082`; Kubernetes `4550`. Release name
  `probation-results-distribution`.
- **Messaging**: Azure Service Bus queue `probation.requests` (stack-prefixed
  in deployed environments) + its dead-letter queue, owned by this service,
  consumed via `azure-messaging-servicebus` `ServiceBusProcessorClient`
  (design §4.2).
  - Peek-lock, explicit settlement, **`maxDeliveryCount` 5**, mirrored in
    configuration so the listener can spot the final delivery.
  - Broker **duplicate detection off** (H14; row A3-53); `messageId =
    RESULTS:{requestId}`. The processed-log guard on `(source, requestId)` is
    the only duplicate protection, and every copy reaches it: a `COMPLETED`
    request → no-op, completed; a live claim → abandoned (back-off); a
    `FAILED` request → a replay by the DV-26 rule; two concurrent copies of a
    new request → one wins, the other backs off. A copy abandoned on a live
    claim is redelivered at once and can be dead-lettered `exhausted` in
    seconds — accepted noise, with no abandon delay (H15; row A3-55).
    Enabling duplicate detection later means recreating the queue.
  - Lock duration 5 min; claim lease PT4M30S; processing deadline 4 min. The
    lease is strictly shorter than the lock (Q17 — the informant rule, not
    YOT's).
  - Concurrency 2 to start (a starting point, not a measurement; O-2).
    Sessions off.
  - **Two replay routes** (H9; rows A3-30, A3-53): (a) a **resubmission from
    the dead-letter queue** with the broker tools — the route operations
    prefer; a copy under the exhausted `messageId` is admitted as a replay at
    broker delivery count 1 and re-dead-lettered `exhausted` above it
    (DV-26); (b) the service's **replay action** re-sends the command stored
    at admission (`processed_request.command`) byte for byte under a fresh
    `messageId` with `origin = REPLAY` (row A2-48) — the only route after a
    rollback purge (design §9.4 step 6a). The fresh id keeps the replay
    distinct from the exhausted identity and labels it for audit; it is not
    needed to pass the broker. A replay of a `COMPLETED` request is a no-op
    by either route.
- **Processed log**: PostgreSQL, service-owned, **Flyway** (design §4.6,
  Appendix B.2). V1 is **three tables and one view** (H17, H19, H20, H21;
  rows A3-58, A3-61, A3-63, A3-66):
  - `processed_request` keyed `(source, request_id)`, with the informant's
    claim triple and request fingerprint; the copied informant DDL stays
    byte-identical, and this service's additions (`command`, `replayed_at`,
    the CHECKs) sit in a separate ALTER section;
  - `outbound_message`, one row per leg per attempt, unique `(source,
    request_id, leg, attempt)`, carrying `payload_shared_time`,
    `body_digest`, `service_version` and `youth_switch` — no
    `payload_digest`, `rules_version` or `serializer_version`;
  - the view `outbound_message_latest`, the latest attempt per leg;
  - `outbound_body`, the exact bytes POSTed: partitioned by month on
    `created_at` (UTC) with a DEFAULT partition, primary key
    `(outbound_message_id, created_at)`, foreign key to `outbound_message`
    `ON DELETE RESTRICT`, and a trigger that refuses a body whose hash differs
    from its attempt's `body_digest`. The outcome UPDATE (with `body_digest`)
    is written first, then the body INSERT, in the same transaction; an
    in-place re-run replaces the attempt's body row.

  **PostgreSQL 15**, production's version (row A3-105): a trigger on a
  partitioned table needs ≥ 13 and `DETACH PARTITION … CONCURRENTLY` ≥ 14.
  The database is set up as the Informant Register's, on the environment's
  Results server and owned by the service role, which creates — and so owns —
  the partitions. Hearing payloads are never persisted; outbound bodies are,
  for 6–7 months (Principle VII).
- **Payload source**: Redis `EXT_` (dated key, then undated key) through
  Lettuce with TLS peer verification (DV-13, DV-14), then the results query
  API fallback, which runs with the command's `userId` or, when it is absent
  and on every resend, the service's own system user (DV-17; H30). A miss
  everywhere is transient.
- **Youth switch**: the users-groups permission read per message, legacy retry,
  connect 2 s / read 5 s (DV-24); an unreadable read resolves to
  `distribution.youth-switch.default-when-unreadable`, default `ACTIVE`
  (DV-25).
- **Outbound**: Spring `RestClient` POST to the HMPPS route, with
  `Ocp-Apim-Subscription-Key` from configuration (the existing key, D4a) and
  timeouts connect 5 s / read **60 s** (H27; row A3-95). YOT's `RetryPolicy`
  is used with a predicate this service passes, **fixed in code, not a
  property**: in-run retries only for a connection failure (the request never
  sent; a connect timeout included) and 429, honouring `Retry-After` capped by
  `max-backoff`, with bounded attempts (H11, H15; rows A3-43, A3-54). A 5xx, a
  408 and a read timeout or I/O failure after the request was written park
  (Principle VI). Any 2xx is `SENT`; a non-200 2xx is counted as an anomaly
  (H12). No client certificate (D4b).
- **Time budget per message** (design §4.7): payload 60 s + switch 25 s + send
  130 s = **215 s < 240 s**, enforced by the attempt-fits-before-deadline rule
  and validated at startup. The send stage's composed startup check is
  (attempts − 1) × (connect + `max-backoff`) + (connect + read) ≤ the leg
  budget — with the defaults 2 × (5 s + 10 s) + 65 s = **95 s ≤ 130 s** —
  because only the last attempt can run to the read timeout (H27; row
  A3-96).
- **HTTP surface**: Spring Boot Actuator — health, readiness/liveness,
  metrics — and the operations API under `/operations/**` for replay and
  resend-leg (H9; Principle III), behind YOT's five gates: an internal ingress
  or APIM route, the gateway stripping and injecting `CJSCPPUID`, an Istio
  AuthorizationPolicy and NetworkPolicy, users-groups reachable for the auth
  filter, and an Artemis audit connection (platform ask 14). The estate's
  authorisation and audit filter starters are on the classpath for it.
- **Operator actions** (design §4.8):
  - **Resend-leg takes an explicit `requestId` list** (H16; row A3-56): 1 …
    `distribution.resend.max-ids-per-run` (default 100, startup-validated
    ≥ 1; a longer list is refused whole), duplicates collapsed. Each ID is
    taken in its own transaction — the request row `FOR UPDATE`, then the leg
    row `FOR UPDATE SKIP LOCKED` — and proceeds or is refused on its own with
    one bounded code: `unknown-request`, `not-eligible`,
    `resend-in-progress` or `request-claimed`, an API and run-report code,
    not a row value. The run report lists every ID's outcome, and refusals
    are counted on `probation_resend_refusals_total{reason}`. The whole run
    is refused while intake is paused (row A2-40). Choosing the IDs is a
    separate, visible step: the list action, or a documented query on
    `outbound_message_latest`.
  - **The resend "changed?" check** (H17; row A3-57) runs directly after the
    fetch, before any leg stage: the payload's `sharedTime` is compared with
    the request's stored `shared_time` **as instants** — the same instant in
    another rendering proceeds; a different, absent or unparseable one is
    `FAILED(payload-changed)` unless `allow-changed-payload` is given. There
    is no payload digest.
  - **Forced resend** (H9 (c); row A3-36): the override `allowTerminalSuccess`
    (name proposed) lets resend-leg select a leg whose latest row is `SENT` or
    `NOT_APPLICABLE`. It requires an operator and a reason — sanitised, at
    most 200 characters, appended to `processed_request.audit_note` — and
    writes a new `resend` attempt row with `override=force-resend`. The
    request stays `COMPLETED`; the send is a genuine duplicate at HMPPS and
    is counted with `forced=true`.
  - **Dead-letter runbook** (rows A3-32, A3-33, A3-53): resend `exhausted` and
    `leg-exhausted`; never resend `validation` or `collision`. Once a
    replayed request is `COMPLETED`, remove the original — and any
    re-dead-lettered copy — from the dead-letter queue. An `exhausted` copy
    whose request is already `COMPLETED` is accepted noise: remove it, do not
    resend it.
  - **Rollback constraints** (design §9.3, §9.4; rows A2-40, A2-52, A3-117):
    resend-leg is refused while intake is paused, and purge-queue is allowed
    only while it is paused. After a rollback intake stays paused until a
    full new cutover: purge, reconcile, cutover, and only then resume.
- **Body-retention job** (H20; row A3-64): the service's one scheduled task;
  there is no batch job. Daily at `distribution.body-maintenance.cron`
  (default `0 15 2 * * *`), the scheduler in UTC. One runner across pods, by a
  transaction-scoped advisory lock; a pod that does not get it skips the run.
  Each run pre-creates the current month's partition and the next two; drops
  each partition whose whole month ends on or before the UTC cut-off,
  `date_trunc('month', (now() AT TIME ZONE 'UTC') - retention)`, read from the
  partition's bounds in the catalogue and never from its name (`DETACH
  PARTITION … CONCURRENTLY`, then `DROP`, each under a `lock_timeout` and
  retried on the next run); deletes DEFAULT-partition rows older than the
  cut-off; and counts creations, drops and failures by step, with a gauge of
  DEFAULT rows. A failure, or a non-empty DEFAULT partition, alerts.
  `distribution.body-retention` is whole months, ≥ `P1M`, startup-validated.
- **Observability**: Micrometer → Prometheus with the `probation_` metric
  family of design §10.1, plus the App Insights Java agent; structured JSON
  logs (Principle VIII); DLQ depth from Azure Monitor. Alerts per design
  §10.3: genuine issues page; known issues are counted on the dashboard and
  in the daily reconciliation (H34; Principle VI).
- **Secrets/identity**: workload identity for Service Bus
  (`AzureServiceBusDataReceiver`, and `AzureServiceBusDataSender` on
  `probation.requests` for the actions that enqueue — submit-hearing and the
  replay action); no App Configuration role. Key Vault through the Secrets
  Store CSI driver for the gateway key, the Redis password, the datasource
  credentials, the App Insights connection string and the service's **own
  system user id** (H30; row A3-106): one id, created per environment by a
  static-data patch with the system-users role, which both
  `distribution.results.system-user-id` and
  `distribution.usersgroups.system-user-id` take (env
  `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID`, name proposed). No secret
  in a Helm value, a committed file or an environment default.
- **Test stack**: JUnit Jupiter (the Boot 4.1 test starter) + Mockito +
  AssertJ (unit); golden and harness tests for the ported redaction;
  **Testcontainers** — the Service Bus emulator, PostgreSQL (15, production's
  version; row A3-105) and Redis, with Toxiproxy for outage cases — for
  integration tests (suffix `*IT`); **WireMock** for the results query
  API, the users-groups read and the gateway's HMPPS route.
- **Deployment**: GitHub Actions → ADO pipeline 460 → ACR → Flux with the
  shared `springboot-app` chart (design §8.3). Charts, values, queue
  provisioning, identity and network policy live in the sibling
  infrastructure repositories, not here.

### Increments

None at ratification. The repository is a buildable skeleton — package
modules, configuration, CI and this constitution — with no business code and
no Flyway migration. The first feature starts with `/speckit-specify`. V1
stays documentation until a feature needs the store — design Appendix B.2,
summarised in `doc/design/DIGEST.md` — and that feature turns it into the
Flyway migration.

## Development Workflow & Quality Gates

- The contract artefacts (the inbound schema, the operations API's OpenAPI
  document once it exists, the contract documentation) MUST be updated
  **before** any code change that affects a contract (Principle III).
- Every feature built via Spec Kit lives under `specs/NNN-slug/` containing at
  least `spec.md`, `plan.md` and `tasks.md`. Flow:
  `/speckit-specify → /speckit-plan → /speckit-tasks → /speckit-implement
  → /speckit-analyze`.
- Non-trivial changes flow through `Spec → Write → Code Review → QA →
  Spec-Validate → Fix → Ship`. The reviewer agents (`code-reviewer`, `qa`,
  `spec-validator`) report findings only; they MUST NOT modify code. The
  primary agent or a human applies fixes and re-runs until all three return
  PASS / COMPLIANT. Exempt: markdown-only edits, whitespace/import-only edits,
  and `.claude/rules/*` or `CLAUDE.md` updates.
- Required to run cleanly before merge:
  - `./gradlew build` — compilation, the full test suite, **PMD** (main and
    test), **Checkstyle** (main and test) and the **JaCoCo coverage
    verification**. Every analysis runs in `check`.
  - `./gradlew test` — the whole suite; there is no separate
    `integrationTest` task, so the Testcontainers suites run here and need
    Docker only when those tests are in the selection.
  - `./gradlew jacocoTestReport check` — the order CI uses, so the coverage
    report exists to be read whichever way the gate goes.
- Any change to ported logic MUST run the golden and harness suites; **a golden
  file is only updated in the same commit as a `doc/DEVIATIONS.md` entry** (new
  or amended), and a registered deviation is asserted by derivation rather than
  by editing the golden (Principle I).
- Pull requests: the description MUST state which principle(s) the change
  touches. Any deviation requires explicit written justification in the PR
  description and MUST be flagged in the plan's "Complexity Tracking" section.
- Reviewers MUST specifically look for: a swallowed exception or an unsettled
  delivery (Principle VI), an auto-completed message, a typed model bound over
  an inbound payload (Principle IV), PII or a payload in a log line, and an
  outbound body in a log line or a metric label at any level (Principle VII),
  production code with no preceding failing test (Principle II), **a behaviour
  change with no DV row or no pinning test, and a carried defect "fixed" in
  passing** (Principle I), anything VEP-shaped or a second cutover lever
  (Principle IX), an outbound schema validator or a `contract-invalid` write
  in this release (H13), reliance on broker duplicate detection (H14), and an
  in-run retry of a 5xx, a 408 or a read timeout (H15).

## Governance

This constitution supersedes the informal conventions in `.claude/rules/` and
the template-derived guidance in `CLAUDE.md` — including the template's
"API-First Rule", which does not apply to a service with no business REST API.
Where this document and those files disagree, this document wins; they are
retained as quick-reference material and MUST be kept in sync.

The design — the Confluence page "Probation Results Distribution Service" in
the CRA space
(https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service),
rev 3.1 at ratification, rev 3.3 at amendment 1.1.0 (H8 only) and rev 3.6
(2026-10-20; decisions H1–H34) at amendment 2.0.0 — is the source of truth for
names and behaviour. Where a section of the page and rev 3.6 differ, the rev
3.6 decision cited here applies and the difference is raised with the design
owner. `doc/design/DIGEST.md` restates rev 3.6's rules, each with its design
row id and without rationale, for spec authors and implementers; where the
digest and the design differ, the design wins. This constitution states the
principles the design's decisions imply; it does not take decisions the
design leaves open. A later design revision that changes a decision cited here
is adopted by amendment.

**Amendment procedure**:

1. Propose the change in a feature spec under `specs/`.
2. Bump `Version` per semantic versioning:
   - **MAJOR** — a breaking principle change, removal, or redefinition that
     invalidates existing practice.
   - **MINOR** — a new principle, new section, or materially expanded
     guidance.
   - **PATCH** — clarifications, wording, typo fixes, or non-semantic
     refinements.
3. Re-run `/speckit-analyze` on every in-flight feature spec to verify it still
   aligns with the amended principles; update or waive as required.

**Compliance expectations**:

- All PRs MUST honour these principles.
- Deviations MUST be explicitly justified in the PR description and, where
  relevant, in the plan's "Complexity Tracking" table.
- Reviewers MUST block merges that silently violate a NON-NEGOTIABLE principle
  without a written waiver.
- Behaviour relative to the legacy function app is governed by the
  **deviation register** (`doc/DEVIATIONS.md`, Principle I), not by PR
  discussion alone: a behaviour change merged without a register entry MUST be
  reverted or registered retrospectively with named approval. DV ids are
  stable: renumber never, reuse never, append only.

**Version**: 2.0.0 | **Ratified**: 2026-10-19 | **Last Amended**: 2026-10-21
