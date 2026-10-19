<!--
SYNC IMPACT REPORT
==================
Version change: (none) → 1.0.0
Bump rationale: Initial ratification. Every principle and section is new; there
                were no prior principles to remove or redefine. The principles
                are carried from the two sibling services built to the same
                pattern - service-cp-crime-informant-register (constitution
                1.0.2, parity-first) and service-cp-crime-yot-results-distribution
                (constitution 5.2.0, fix-first) - and re-grounded in the design
                of this service, "Probation Results Distribution Service", rev
                3.1 (2026-10-19). Neither sibling's stance is taken whole: the
                design's D1 ("lift and shift") ports the rules and what is sent
                verbatim and fixes the delivery defects around them, each as a
                registered deviation (design §1.2, §7.0). Principle I is written
                to that middle line.

Design citations: "design §n" in this document means a section of the design
rev 3.1. Decision ids (H5, H7, D1, D4a, D10, Q15, ...), deviation ids (DV-n),
defect ids (P01-P36), rule ids (R0-R12) and row ids (A2-n, A3-n) are the
design's and are never renumbered here.

Added sections:
  - Core Principles
      I.    Behaviour Parity with Registered Deviations
      II.   Test-Driven Development
      III.  Message-Contract First
      IV.   Canonical JSON In, Typed Models Out
      V.    SOLID with Ports and Adapters
      VI.   Explicit Failure - Nothing Is Ever Swallowed
      VII.  Privacy in Telemetry
      VIII. Estate Conventions
      IX.   Scope - the HMPPS Leg Only
  - Technology Stack & Deployment
  - Development Workflow & Quality Gates
  - Governance

Removed sections: None.

Templates requiring updates:
  - .specify/templates/plan-template.md       ✅ compatible - the "Constitution
      Check" block is filled per feature by `/speckit-plan`; no structural
      change required. Plan authors MUST gate on Principles I-IX and MUST
      state the status of pre-build gate G1 (Principle III).
  - .specify/templates/spec-template.md       ✅ compatible - no
      constitution-specific content; spec authors MUST express behaviour in
      terms of the queue message in and the HMPPS send out (Principle III),
      not in terms of REST endpoints.
  - .specify/templates/tasks-template.md      ✅ compatible - test tasks are
      already mandatory and ordered before the implementation tasks they
      guard (Principle II).
  - .specify/templates/checklist-template.md  ✅ compatible - no changes.
  - CLAUDE.md                                 ✅ aligned - written in the same
      change: the message-contract rule in place of an API-first rule.
  - .claude/rules/*.md, .claude/agents/*.md   ✅ aligned - written in the same
      change; retained as quick-reference, and this constitution is
      authoritative where they disagree.

Follow-up TODOs (open at ratification; each is open in the design, not here):
  - Pre-build gate G1 (design §6.6, §9.2): the interim HMPPS outbound schema
    is not yet derived or approved.
  - O-1 (design §4.8, §11.1): whether the operator actions get an HTTP front
    (the operations API). Until decided, actuator is the only HTTP surface.
  - D13 (design §11.1): what is stored about each outbound message; this
    constitution applies the design's recommendation (digest-only) until the
    data owner decides.
  - Corpus location (design §6.4): RESOLVED before the first push
    (2026-10-19). SIT holds no production data, so the recorded corpus of SIT
    payloads is committed to this repository under
    `src/test/resources/corpus/` (Principle VII).
  - `doc/DEVIATIONS.md` is seeded from design §7.2 by the first feature that
    lands behaviour; it does not exist at ratification.
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
attempt in its own processed log (design §1.1, §4.1).

The legacy app reports success even when a send fails, keeps no record, logs
whole payloads at info, and sends nothing — silently — for a share whose
switch read or Redis read fails (design §1.1, §7.1). This service exists to
end the silence, not to change what is sent. Management's instruction is "lift
and shift" (D1): the rules and the body are copied as they are; the delivery
and reliability defects around them are fixed, and every intended difference
is a numbered, tested deviation.

## Core Principles

### I. Behaviour Parity with Registered Deviations (NON-NEGOTIABLE)

The legacy HMPPS code at the baseline commit `122e2152` of
`cpp-context-azure-legalaidagency` — the HMPPS filter (`PF/functions.js`), the
HMPPS publisher's body construction, and the orchestration around them — is
the **oracle for every observable behaviour** that is not a registered
deviation. Its Jest suite is not: some of its tests are vacuous or
order-dependent (P34), so the reference output is **recorded by executing the
legacy Node code** over a corpus of real hearing payloads (design §6.1).

- **The rules are ported verbatim** (D1; design §5): R0–R12 in the legacy
  order, one Java method per JavaScript rule, rule ids stable (row A3-2). R3
  and R4 (the youth rules) run only when the switch reads `ACTIVE`, in the
  legacy's place (H7; row A3-17). Where a rule tests "truthy", the port
  reproduces JavaScript truthiness, including for the switch's own
  `permissions[0].active` (design §5).
- **What is sent does not change in this delivery** (D10, decided
  2026-10-15). Known quirks are carried and reported, not fixed: P16 (an
  unreadable switch sends nothing and the run completes), P17 (the first
  permission decides), P18 (with the switch on, R4 drops a court application
  with no `masterDefendant`), P35 (`plea.delegatedPowers` not removed; pending
  D10). A reviewer who finds one of these "obviously wrong" is looking at a
  carried defect, not a bug in the port.
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
  reserved; the next free id is **DV-25** (design §7.0).
- **Standing rule** (design §7.0): where the legacy throws and swallows, the
  port throws and reports. A carried crash still sends nothing for its leg,
  but it becomes `FAILED(rules-exception)`, logged at ERROR and counted. It
  needs no DV row of its own.
- **The harness** (design §6): the corpus is recorded **in both switch
  states** (row A3-18), and covers the fallback form of the same hearings as
  well as the cache form. The comparator is semantic JSON equality after
  canonicalisation, BigDecimal throughout; a registered deviation that
  changes output is asserted **by derivation** from the oracle and the stated
  rule, never by editing the golden; **any unregistered difference fails the
  build**. The exact bytes sent are pinned separately, by `body_digest` and
  fixed test vectors.
- **The `shadow` profile** (design §6.5) is the run-time half of the same
  discipline: the HMPPS publisher replaced by a capture sink, gateway egress
  denied, and a per-hearing comparison against the deployed SIT artefact.
  Which days and how many is D8 (open).

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
  (design §4.2, Appendix B.1): `source` (`RESULTS`), `requestId`, `hearingId`,
  `hearingDay`, `sharedTime`, `eventType` (`Hearing_Resulted` only) and an
  optional `userId` (absent allowed, `null` refused), with
  `additionalProperties: false`. It is the informant contract with only the
  `$id`, title and descriptions changed. Message properties: `messageId =
  RESULTS:{requestId}`, `contentType = application/json`, `correlationId =
  hearingId`. The operator actions mark their messages with **broker
  application properties** (`origin = REPLAY`; `origin = SUPPORT` with
  `operator`, `runId`, `reason`), never with body fields, so the closed schema
  is unchanged by them. Agreed with `cpp-context-results`, the publisher.
- **Outbound** — the redacted HMPPS body, POSTed to the HMPPS route on the APIM
  gateway. HMPPS owns what it accepts; its ICD (Q6) has not been obtained.
  Until it is, the body is validated before every POST against an **interim
  closed schema** vendored under `src/main/resources/contracts/outbound/`,
  derived from the recorded corpus by the rule of design §6.6 (objects closed
  at every level, enums open, `hearing.id` required). A body that fails it is
  not sent: the leg is `FAILED(contract-invalid)` and alerted.
- **Pre-build gate G1** (design §6.6, §9.2): the interim HMPPS schema is
  derived from the recorded corpus and **approved before the build starts**.
  G1 is open at ratification. When the ICD arrives it replaces the interim
  schema; Q6 and Q13 remain build gates for enabling the HMPPS leg in
  production.
- **Consumed contracts** — this service adapts to them and never redefines
  them: the Redis `EXT_` claim-check payload written by results, consumed
  as-is (dated key, then undated key); the results query API
  `hearingDetails/{hearingId}?hearingDate=` fallback with its vendor media
  type; the users-groups permission read by feature key with its vendor media
  type (H7); the APIM gateway's HMPPS route, called with the subscription key
  the function app uses today (D4a: no gateway change).

Rules:

- The inbound schema is versioned with the repo, and contract tests assert
  against it. Both contracts MUST be documented in this repository, and the
  documentation updated **before** any code change that affects either.
- A change to either contract is a **cross-team event**: it requires a spec,
  an agreed change with the other side, and a compatibility plan (producers
  and consumers deploy independently — assume the old shape is in flight). A
  change to what is sent to HMPPS is also a change to D10 and is out of this
  delivery's scope.
- The only HTTP this service exposes is Spring Boot Actuator. The operator
  actions of design §4.8 — replay, resend-leg, pause and resume intake,
  submit-hearing, purge-queue — are decided capabilities; whether they get an
  HTTP front under `/operations/**` is **open (O-1)**. Until O-1 is decided
  they run as command-line runners in the image, and adding any HTTP endpoint
  requires a constitution amendment, not just a spec.

**Rationale**: the queue command and the HMPPS body are the whole external
surface. Treating them with the discipline other services give an OpenAPI
document is what keeps a redeploy on either side from silently dropping a
hearing.

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
  therefore not bound to a type; it is checked against the closed outbound
  schema (Principle III) before every POST, and the bytes sent are pinned by
  `body_digest` and `serializer_version` (design §4.6).
- Everything this service *originates* — the inbound command, decisions,
  outcomes, processed-log rows, operator commands, metric and log fields —
  MUST be typed Java records and enums with bounded value sets.

**Rationale**: fidelity in, contract-checking out. `JsonNode` guarantees that
nothing the legacy passed through is lost; the closed schema and the digest
guarantee that nothing is sent that was not meant to be.

### V. SOLID with Ports and Adapters (NON-NEGOTIABLE)

The pipeline is an application core surrounded by adapters (design §4.4):

```
ASB listener (inbound adapter)
    → IdempotencyGuard          (processed-log claim; copied from informant)
    → DistributionRunner        (application core; LegResender for resend-leg)
        → HearingPayloadSource  (Redis EXT_ → results query API fallback)
        → YouthFilterSource     (users-groups EMYouthFilter permission, per message)
        → HmppsRedaction        (pure: R0–R12, R3/R4 when ACTIVE)
        → OutboundContractValidator + body digest
        → HmppsSender           (APIM gateway, HMPPS route)
        → OutboundLog           (outbound_message rows, one per leg per attempt)
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
2. **Dead-letter** — a message that cannot be admitted (`validation`,
   `collision`, or `exhausted` on an inherited final-delivery path; row A2-55),
   or an admitted message whose final permitted delivery still leaves a
   `PARKED` leg (`leg-exhausted`, item 1). The reason set is closed:
   `{validation, collision, exhausted, leg-exhausted}` (row A2-34).
3. **Record a terminal failure and complete** — `receiver-rejected`,
   `contract-invalid` and `rules-exception` are terminal at once; and an
   unreadable youth switch is `FAILED(youth-switch-unreadable)` at once with
   nothing sent and the message completed, as the legacy completes today
   (P16 carried), while the request is settled `FAILED` with no exhausted
   message id, replayable and resend-eligible (rows A3-12, A3-21). A completed
   message is not a silent one: what was not sent is on a row.

**Alerting is required in addition, never instead.** Whichever outcome is
taken, a failure MUST also produce a sanitised ERROR log line and increment a
bounded metric an alert reads (design §10). An alert is not a way of settling
a message.

Specific rules:

- The HMPPS publisher **never throws past its outcome**: it returns `SENT`,
  `FAILED_NON_TRANSIENT` or `TRANSIENT_EXHAUSTED`, and the runner maps each to
  the leg's (status, reason) (design §4.4).
- An ambiguous POST (timeout, unknown outcome) is **retried**. The guarantee is
  stated honestly: no re-send in normal operation, redeliveries and replays
  included; across a crash between a 2xx and recording it, at-least-once. A
  duplicate is preferred to a silent loss. Whether HMPPS tolerates a duplicate
  is Q5 (open). Do not write code, or a comment, that promises more.
- No statement of this service moves a `COMPLETED` request (row A2-27); request
  completion is historical, and a leg's truth is its latest row (row A2-49).
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
§1.1). A loud, recorded, counted failure is a success of this design; a quiet
one is the only true outage.

### VII. Privacy in Telemetry (NON-NEGOTIABLE)

Logs, metrics, traces, and exception messages MUST NOT carry defendant
personal data at `INFO` level or above — no names, dates of birth, addresses,
NINOs, contact details, or free text that may contain them. The legacy logs
whole payloads at info (P14) and serialises errors to nothing (P15); this
service does neither (DV-09).

- The permitted correlation set at `INFO` is: `requestId`, `hearingId`,
  `hearingDay`, `source`, the broker `sequenceNumber` and `deliveryCount`,
  `leg`, status and bounded reason, HTTP status, `body_digest`, the run's
  `runOrigin`, the `youth_switch` state read and its bounded cause, counts and
  timings (design §10.2). Every log line about processing MUST carry
  `requestId` and `hearingId`.
- Whole payloads and outbound bodies MUST NOT be logged at any level in a
  deployed environment. Where a payload dump is genuinely needed for local
  diagnosis it goes behind `DEBUG` **and** an explicit local-only profile
  guard.
- Metric labels and dimensions are logs too: never label a metric with
  anything identifying a person. `reason`, `cause`, `status` and `origin` are
  bounded enums — never raw exception text, never a fragment of a body or a
  far end's words.
- What is stored about each outbound message is **digest-only** — status,
  response code, digests and reproduction identifiers, no body — unless the
  data owner approves a retention policy (D13, open; design §4.6). The shadow
  profile's capture table is the design's one exception, scoped to shadow
  runs (design §6.5).
- **This repository is public.** Hearing data from production environments
  (PRD, PRP, or any environment holding production data) MUST NOT be
  committed to it. SIT holds no production data, so the recorded parity
  corpus of SIT payloads MAY be committed as test fixtures, under
  `src/test/resources/corpus/` only (decision 2026-10-19; design §6.4). Other
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
  its finite timeouts are the one difference (DV-24). The service follows the
  live toggle, so cutover carries no youth behaviour change; whether the
  toggle changes (CHD-2701) is the business's choice, made in users-groups
  data, not in this service (design §1.4).
- **The service does not read the cutover flag** `ProbationResultsDistributionService`
  (Q15): the producer and the legacy triggers do. Which implementation is live
  is decided there, and nothing in this service — no property, endpoint or
  Helm value — is a second lever. The App Configuration reader exists only if
  the operations API's optional flag-check action is adopted (O-1; row A2-17).
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
  - Broker **duplicate detection on, set at queue creation**, window PT5M;
    `messageId = RESULTS:{requestId}`.
  - Lock duration 5 min; claim lease PT4M30S; processing deadline 4 min. The
    lease is strictly shorter than the lock (Q17 — the informant rule, not
    YOT's).
  - Concurrency 2 to start (a starting point, not a measurement; O-2).
    Sessions off.
  - **Replay never reuses a `messageId`**: the replay action re-sends the
    command stored at admission (`processed_request.command`) byte for byte
    under a fresh `messageId` (row A2-48). The dead-letter queue is kept for
    forensics, not as the replay source.
- **Processed log**: PostgreSQL, service-owned, **Flyway** (design §4.6,
  Appendix B.2) — `processed_request` keyed `(source, request_id)`, with the
  informant's claim triple and request fingerprint, and `outbound_message`,
  one row per leg per attempt, unique `(source, request_id, leg, attempt)`;
  `shadow_capture` for the shadow profile. The copied informant DDL stays
  byte-identical; this service's additions sit in a separate ALTER section.
  Hearing payloads are never persisted.
- **Payload source**: Redis `EXT_` (dated key, then undated key) through
  Lettuce with TLS peer verification (DV-13, DV-14), then the results query
  API fallback, which runs with the command's `userId` or, when it is absent,
  the configured system user (DV-17). A miss everywhere is transient.
- **Youth switch**: the users-groups permission read per message, legacy retry,
  connect 2 s / read 5 s (DV-24).
- **Outbound**: Spring `RestClient` POST to the HMPPS route, with
  `Ocp-Apim-Subscription-Key` from configuration (the existing key, D4a) and
  connect/read timeouts; `RetryPolicy` copied from YOT (408, 429, 5xx and
  connect/IO failures retried, `Retry-After` honoured and capped, bounded
  attempts). No client certificate (D4b, an assumption).
- **Time budget per message** (design §4.7): payload 60 s + switch 25 s + send
  130 s = **215 s < 240 s**, enforced by the attempt-fits-before-deadline rule
  and validated at startup.
- **HTTP surface**: Spring Boot Actuator only — health, readiness/liveness,
  metrics (Principle III). The estate's authorisation and audit filter
  starters are on the classpath and configured for the operations API in case
  O-1 adopts it; they serve no endpoint until then.
- **Observability**: Micrometer → Prometheus with the `probation_` metric
  family of design §10.1, plus the App Insights Java agent; structured JSON
  logs (Principle VIII); DLQ depth from Azure Monitor. Alerts per design §10.3.
- **Secrets/identity**: workload identity for Service Bus
  (`AzureServiceBusDataReceiver`, and `AzureServiceBusDataSender` on
  `probation.requests` for submit-hearing); Key Vault through the Secrets
  Store CSI driver for the gateway key, the Redis password, the datasource
  credentials, the App Insights connection string and the system user ids. No
  secret in a Helm value, a committed file or an environment default.
- **Test stack**: JUnit Jupiter (the Boot 4.1 test starter) + Mockito +
  AssertJ (unit); golden and harness tests for the ported redaction;
  **Testcontainers** — the Service Bus emulator, PostgreSQL and Redis, with
  Toxiproxy for outage cases — for integration tests (suffix `*IT`); **WireMock** for the results query
  API, the users-groups read and the gateway's HMPPS route.
- **Deployment**: GitHub Actions → ADO pipeline 460 → ACR → Flux with the
  shared `springboot-app` chart (design §8.3). Charts, values, queue
  provisioning, identity and network policy live in the sibling
  infrastructure repositories, not here.

### Increments

None at ratification. The repository is a buildable skeleton — package
modules, configuration, CI and this constitution — with no business code and
no Flyway migration. The first feature starts with `/speckit-specify`; its
plan records the status of gate G1.

## Development Workflow & Quality Gates

- The contract artefacts (the inbound schema, the interim outbound schema, the
  contract documentation) MUST be updated **before** any code change that
  affects a contract (Principle III).
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
  an inbound payload (Principle IV), PII or a payload in a log line
  (Principle VII), production code with no preceding failing test
  (Principle II), **a behaviour change with no DV row or no pinning test, and a
  carried defect "fixed" in passing** (Principle I), and anything VEP-shaped or
  a second cutover lever (Principle IX).

## Governance

This constitution supersedes the informal conventions in `.claude/rules/` and
the template-derived guidance in `CLAUDE.md` — including the template's
"API-First Rule", which does not apply to a service with no business REST API.
Where this document and those files disagree, this document wins; they are
retained as quick-reference material and MUST be kept in sync.

The design — the Confluence page "Probation Results Distribution Service" in
the CRA space
(https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service),
rev 3.1 at ratification — is the source of truth for names and
behaviour. This constitution states the principles the design's decisions
imply; it does not take decisions the design leaves open. A later design
revision that changes a decision cited here is adopted by amendment.

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

**Version**: 1.0.0 | **Ratified**: 2026-10-19 | **Last Amended**: 2026-10-19
