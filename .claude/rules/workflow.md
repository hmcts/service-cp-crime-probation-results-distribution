# Workflow: Mandatory Build Loop

Every non-trivial code change MUST follow this cycle:

```
Contract → Failing test → Write → Code Review (agent) → QA (agent) → Contract Validate (agent) → Fix → Ship
```

- **Contract:** this service has two contracts of its own and four it consumes (constitution
  Principle III):
  1. the **inbound ASB message schema** — `src/main/resources/contracts/distribution-command.schema.json`,
     closed (`additionalProperties: false`), the informant contract with only `$id`, title and
     descriptions changed (design Appendix B.1);
  2. the **outbound HMPPS body** — the legacy-identical redacted body. There is **no outbound schema
     in this release** (H13): no validation before the POST and no pre-build gate G1. The HMPPS ICD
     v0.8 is information only — its schema is stale (H12);
  3. consumed, never redefined: the Redis `EXT_` claim-check payload, the results query API
     `hearingDetails` fallback, the users-groups permission read, and the APIM gateway's HMPPS route
     with the existing subscription key (D4a).

  The operations API's OpenAPI document becomes an owned contract once built (H9). Update the
  contract documentation BEFORE writing code that changes any of these shapes. A change to what is
  sent to HMPPS is a change to decision D10 (no payload change in this delivery) and is a cross-team
  event, not a spec. The operations API's **replay** and **resend-leg** endpoints are in scope (H9);
  exposing any other operator action over HTTP is a **constitution amendment** while O-1 is open.
- **Contract Validate:** run the `spec-validator` agent to check the code against the message
  contract, the legacy-parity body and the deviation register, and the gates below.

Loop repeats until ALL agents return PASS / COMPLIANT.

## What Requires the Loop

| Must Go Through Loop                            | Exempt                         |
|-------------------------------------------------|--------------------------------|
| New / modified Java class                       | Markdown / docs only           |
| New / modified test class or golden fixture     | Whitespace / import only       |
| Message-contract or operations-API change       | CLAUDE.md and rule updates     |
| `doc/DEVIATIONS.md` row added or changed        |                                |
| Flyway migration                                | README changes                 |
| ASB consumer / settlement configuration         |                                |
| Dockerfile changes                              |                                |
| CI/CD pipeline config                           |                                |

## TDD is Non-Negotiable

- Write the failing test first; confirm it fails for the *correct* reason (assertion failure, not a
  compilation error)
- Then write the minimum production code to make it pass
- Then refactor with the test still green
- Commit history MUST show the failing test was authored at or before the production code —
  **every commit**, no exceptions, no "tests to follow"

## Gates (replace the API-first gate)

A change ships only when all applicable gates are green:

1. **Message-contract gate.** The inbound message is parsed and validated against the closed
   schema. Unknown fields, missing required fields, a `null` `userId`, an `eventType` other than
   `Hearing_Resulted` and unparseable messages are dead-lettered `validation` at once, with no
   `processed_request` row — never a silent drop (DV-16). The offending field's name is never quoted
   back (producer-chosen text); the reason code is.
2. **Settlement gate.** Every path through the message listener performs exactly one explicit
   `complete()` / `abandon()` / `deadLetter()`, after the state that justifies it is written. Tests
   cover the success, park-and-redeliver, final-delivery and dead-letter paths. An unreadable youth
   switch is not a path of its own: the leg applies the configured default and settles by its send
   (H8).
   A path that can return unsettled fails review.
3. **Idempotency gate.** A redelivery or republish of the same `(source, requestId)` never sends a
   terminal-success leg a second time; a terminal-success leg is never touched again. The
   processed-log guard is the **only** duplicate protection — broker duplicate detection is off
   (H14) — so every copy must reach it and be handled (`DuplicateDeliveryIT`: a republish of a
   completed share is a no-op; two concurrent copies give one run). Proven by test, not by
   inspection.
4. **Parity gate.** Every ported rule R0–R12 has its JUnit twin of the legacy Jest case where one
   exists, and the SIT corpus passes the comparator against SIT's own logged HMPPS bodies (switch
   off) and the executed legacy (switch on, synthetic cases) (H21). A registered deviation that
   changes output is asserted by derivation; a golden changes only in the same commit as a
   `doc/DEVIATIONS.md` entry; **any unregistered difference fails the build**. The one-week SIT → STE
   replay is the pre-cutover proof, not a build gate.
5. **Deviation gate.** Every intended difference from the legacy has a DV row naming its pinning
   test, and every carried defect stays carried — P17, P18 and P19 (no null guard added inside a
   rule), and P21 and P35 while pending external (D10); design §7.1. "It looked wrong so I fixed
   it" is a failed gate. P16 is fixed under DV-25 (H8), not carried. DV ids are never reused; the
   next free one is DV-27.
6. **Outbound-send gate.** No outbound schema validator and no `contract-invalid` write in this
   release (H13). The gateway retries in-run only a connection failure and 429; a 5xx, a 408 and a
   read timeout park with no in-run retry (H11, H15). Nothing VEP-shaped is added (H5).
7. **Stored-body gate.** The exact bytes POSTed go to `outbound_body` after the outcome UPDATE, in
   the same transaction, and the digest trigger accepts them (H20); the body is never logged at any
   level and never a metric label (row A3-60).
8. **No-swallowed-exception gate.** No empty catch, no catch-and-continue, no success returned from
   a catch block. Reviewers reject on sight.
9. **No-PII gate.** No defendant PII (names, addresses, DOB, ASN, URN) at `info` level or above, no
   payload at any level in a deployed environment (DV-09), no outbound body at any level, and no
   production hearing data (PRD/PRP) committed to this public repository; recorded SIT payloads and
   SIT's logged HMPPS bodies are allowed as test fixtures.

## Agent Definitions

### code-reviewer (Read only)
- Spawned as sub-agent with Read-only tools
- Analyses code for: logic errors, null safety, ports-and-adapters violations (infrastructure types
  leaking into the application layer), swallowed exceptions, unsettled message paths, secrets, PII in
  logs, `System.out` usage
- Returns: **PASS** or **NEEDS CHANGES** with severity-rated findings
- NEVER modifies code — reports only

### qa (Read only)
- Spawned as sub-agent with read-only tools; `Bash` for inspection and for running the existing
  suite only
- Judges the tests that exist against the test matrix in `.claude/agents/qa.md` (JUnit Jupiter +
  Mockito + AssertJ; Testcontainers Service Bus emulator, Postgres and Redis; WireMock for HTTP
  stubs) and names every gap
- Verifies TDD discipline (test tasks ordered before implementation tasks; a red run recorded before
  the green run)
- Runs `./gradlew test`
- Returns: **PASS** or **FAIL** with test results and missing-coverage findings
- NEVER writes tests and NEVER fixes production code — reports only

### software-engineer (Full access)
- For full feature implementation tasks
- **Writes every test**, test-first, under the TDD rule above — the `qa` agent reviews them, it does
  not author them
- Follows all rules in `technical-rules.md` and `design_rules.md`
- Runs `./gradlew build` after changes

### spec-validator (Read only)
- Spawned as sub-agent with Read-only tools
- Checks:
  - the inbound message record and its validation against the closed JSON schema
  - the outbound HMPPS body sent as the legacy sends it — no schema validation in this release
    (H13) — with its digest recorded and its bytes stored (H20)
  - the deviation register consistent with the code (every DV row's pinning test exists and passes;
    no behaviour change without a row; carried defects still carried)
  - settlement discipline: one explicit settlement on every listener path
  - state-machine completeness: every admitted share ends with a recorded outcome per leg, persisted
    before settling
  - the absence of any business REST endpoint, any VEP code path and any second cutover lever;
    the `/operations/**` replay and resend-leg endpoints are allowed (H9)
- Returns: **COMPLIANT** or **DRIFT DETECTED** with severity-rated findings
- NEVER modifies code — reports only

### research (Read, Glob, Grep, WebSearch)
- For deep codebase investigation, including the legacy Node source in
  `cpp-context-azure-legalaidagency` when establishing parity
- Cross-references the design
- Returns structured findings with citations

## Critical Principle

**Agents are reporters, not fixers.** The parent agent (or developer) reads agent reports and applies
all fixes. This prevents conflicting changes and keeps the team in control.
