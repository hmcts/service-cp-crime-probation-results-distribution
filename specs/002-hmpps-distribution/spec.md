# Feature Specification: HMPPS distribution — from a queued share to a recorded HMPPS outcome

**Feature Branch**: `002-hmpps-distribution`  
**Created**: 2026-10-21  
**Status**: Draft  
**Input**: Design rev 3.6 (draft of 2026-10-20; decisions H1–H34), as restated in
`doc/design/DIGEST.md` §2 (scope), §3 (decisions), §4 (deviations), §5 (inbound contract), §6
(pipeline order and time budgets), §7 (state machine), §8 (V1 data model), §9 (outbound send
rules), §10 (payload source, youth switch, redaction), §12 (configuration) and §13
(observability). It is slice 002 of the approved slicing plan: the vertical HMPPS distribution
feature, and owner of the shared core. Its scope there is queue intake with the closed schema and
settlement, the idempotency guard, Flyway V1, the payload source, the youth switch, redaction by
slice 001, the gateway send, the stored body, outcomes, settlement and recovery, and the slice's own
metrics and logs.

## Context

The legacy function app sends each resulted hearing to HMPPS and keeps no record of it. A failed
send is reported as a success; a share whose youth-switch read or Redis read fails is not sent, and
nothing says so; whole payloads are logged. This slice builds the service's one business path end
to end. It takes a share's command from the queue `probation.requests`, reads the hearing payload,
reads the youth switch, redacts the payload with the legacy HMPPS rules that slice 001 ports, and
POSTs the result to the HMPPS route on the gateway. It records every attempt, with the exact bytes
it sent, and it settles the broker message only after that record is written.

The port is lift and shift (D1). What is sent does not change (D10). The delivery and reliability
defects around the send are fixed, and each fix is a numbered deviation with its own pinning test
(digest §4). The guard, the processed log, the settlement discipline and the inbound classes are
copied from the sibling services: the informant register service (IR below) and the YOT results
distribution service. The departures from that code are the ones the design records (digest §4).

**What this slice owns for the whole service.** Slice 002 owns the shared core that slices 003–006
build on:

- the V1 schema: three tables and one view;
- every closed vocabulary: statuses, reasons, origins and label value sets;
- the processing state machine;
- the ports of the application core;
- the metric and label registry;
- the validated configuration for the properties its own path uses.

Later slices extend this core. They do not redefine it.

| Concern | Slice |
|---|---|
| HMPPS redaction R0–R11 (pure; R12 not ported, DV-09), the SIT corpus, the comparator, the seed of `doc/DEVIATIONS.md` | 001 (a dependency) |
| Queue intake, the guard, payload, switch, send, record and settle; V1; vocabularies; metrics; logs | **002 (this spec)** |
| Daily partition maintenance and purge of `outbound_body` | 003 |
| Replay by either route (DV-26 included), resend-leg, forced resend, operator runners, dead-letter runbook | 004 |
| HTTP exposure of the operator actions | 005 |
| Alert rules, dashboards, reconciliation, go-live evidence, cutover and rollback inputs | 006 |

## Clarifications

### Session 2026-10-21

The ambiguities found while writing this spec were resolved from the digest and the design; one,
the reading of row A2-67's "no settlement", rests on the constitution's settlement rule. Three
remain open as design gaps (FR-002-024, FR-002-038, FR-002-061). Each answer below is applied in the
requirement it names.

- Q: Which deliveries does this slice admit, given that replay belongs to slice 004? → A: Two kinds:
  a new share, and a reclaim of an expired claim on a `RECEIVED` or `RETRYING` request. Slice 004
  adds replay admission. Until it does, a delivery that meets a `FAILED` request is dead-lettered
  `exhausted` at once and the request is left untouched (FR-002-008). For the only copy a producer
  can send for a failed share, that is the outcome the guard inherits from IR. (Approved slicing
  plan; A2-34, A3-53)
- Q: Is a Redis outage, a timed-out read or an unparseable cached value a failure of the run? →
  A: No. Each is a miss for that key, and the next source is asked. The leg parks only when every
  source misses. (DV-07, DV-13; digest §10; FR-002-016)
- Q: Does a query-API answer that is not a 2xx park the leg at once? → A: No. The results retry
  block runs first: it retries 408, 429, 5xx, and connect and read failures, and no other 4xx. When
  it stops, the query has failed. If the cache also missed, the leg is `payload-unavailable`.
  (DV-23; §4.5, "missed or failed"; FR-002-017)
- Q: Which user does each internal read act as? → A: The users-groups read always acts as the
  service's own system user. The results fallback acts as the command's `userId` when there is
  one, and as that system user otherwise. (H30, DV-17; FR-002-017, FR-002-021)
- Q: When is the switch read? → A: Once per message, on every kind of run, as the HMPPS leg's first
  step: after the payload fetch and before the redaction. It is never cached. A run whose payload
  is unavailable never reads it. (H7, A3-11; presence matrix; FR-002-020)
- Q: Is a 2xx users-groups answer with an unusable body retried? → A: No. It is `UNREADABLE` at
  once (`EMPTY_PERMISSIONS` or `UNPARSEABLE`). Only an error with no response, or a status above
  429, is retried. (A3-13; FR-002-023)
- Q: Can an unreadable switch on its own park, fail or dead-letter a message? → A: No. The leg
  applies the configured default, sends, and settles by the outcome of the send. (H8, A3-23;
  FR-002-025)
- Q: Which recorded switch values run the youth rules R3/R4? → A: `ACTIVE` and
  `UNREADABLE_DEFAULTED_ACTIVE`. (A3-25; FR-002-025)
- Q: Is a 2xx other than 200 a success, given that the HMPPS ICD names only 200? → A: Yes. Any 2xx
  is `SENT`. A status other than 200 is logged at WARN and counted, and is never retried. (H12,
  A3-45; FR-002-036)
- Q: Which gateway failures are retried within the run? → A: Only two: a connection failure (the
  request was never sent; a connect timeout counts) and a 429. The outcome of a 5xx, a 408, a read
  timeout after the request was written, or an I/O failure once writing began is unknown, and each
  of them parks at once. (H11, H15, A3-54; FR-002-035)
- Q: Is a 401 retried or parked? → A: Neither. It is `FAILED(receiver-rejected)`, terminal at once.
  The `http` label tells it apart from a 400. (§9; A3-115, A3-116; FR-002-036, FR-002-059)
- Q: Which attempts store their body, and when? → A: Every attempt that serialised a body: `SENT`,
  `receiver-rejected` and `gateway-transient`. The body is inserted after the outcome update, in
  the same transaction. A re-run in place replaces it. (A2-61, H18, H20, A3-63; FR-002-042)
- Q: Does this slice validate the outbound body against a schema? → A: No. `contract-invalid` and
  `contract=invalid` stay reserved and unused. (H13; FR-002-031)
- Q: Which completion reason does a request get, with one leg? → A: `both-legs-terminal`; the name
  is kept although there is one leg. The reason is `recovered` instead when `audit_note` holds a
  replay line, which slice 004's replay writes. (A3-7, A2-41; FR-002-044)
- Q: What is the `failure_reason` of a `RETRYING` request? → A: NULL. The per-leg reasons are on
  the attempt row. (A2-35; FR-002-044)
- Q: What happens to a database outage, or an unexpected run exception, on the final permitted
  delivery? → A: The message is dead-lettered `exhausted`, not abandoned, so the service's own
  reason is counted. (A2-34; FR-002-007)
- Q: Row A2-67 says that a stale-runner rejection has "no settlement". Does the listener then leave
  the lock to expire? → A: No. "No settlement" is read as "no completion by that run": the run
  writes nothing more, and the listener abandons the delivery, or dead-letters it `exhausted` on
  the final permitted delivery. Letting the lock expire would break constitution Principle VI and
  workflow gate 2. The wording is a point for the design owner to confirm. (A2-67; digest §7;
  FR-002-007, FR-002-040)
- Q: When is `probation_processed_total` incremented? → A: When a run settles a request to a final
  status, `COMPLETED` or `FAILED`; the label is that status. An unreadable switch never makes a
  request `FAILED`; §10.1's rev 3.1 note is read by H8. (§10.1; H8; digest §16; FR-002-058)
- Q: Which `shape` does an outcome take when it sent no body, or sent a body with a prosecution
  case? → A: `case`. The label has two values and is defined by one condition on the body sent:
  `application-only` when that body carries court applications and no prosecution case. No alert or
  count reads the label outside `receiver-rejected` rows. (A3-114; FR-002-059)
- Q: Does the service read the Results switch, or any App Configuration? → A: No. (H26, Q15, A3-92;
  FR-002-027)
- Q: Is `gateway_request_id` captured? → A: Only if a response header carries one. Which header
  does is not established, so the column stays NULL until the go-live test call finds it. (§11.3;
  digest §16; Assumptions)
- Q: What does a database outage do to validation? → A: A delivery during the outage is handed back
  before its body is read, as the copied listener does. It is validated on a later delivery. (IR
  failure behaviour; digest §6 Intake; FR-002-009)

## User Scenarios & Testing *(mandatory)*

Every story stands on the same foundations: the V1 schema (FR-002-050 to FR-002-056), the closed
vocabularies (FR-002-072), the ports (FR-002-071), and the validated configuration with its timing
rule (FR-002-014, FR-002-066 to FR-002-070). These have no story of their own. Schema-vocabulary
assertions and startup-refusal tests verify them on every build. The privacy rules (FR-002-065)
apply to every story from its first line of code.

Three more requirements have no story of their own:

- FR-002-031 (no outbound validation) and FR-002-073 (the leg as the extension point) are negative
  and structural. The spec-validator review and workflow gate 6 verify them. The schema-vocabulary
  assertions pin what a test can: `leg` holds only `HMPPS`, and `contract-invalid` and
  `contract=invalid` stay in the vocabulary, reserved.
- FR-002-074, the deviation register, is verified by SC-002-010. Each row's pinning test sits with
  the story that exercises its behaviour.

FR-002-038 is left out until its marker is resolved.

### User Story 1 — A resulted hearing reaches HMPPS once, and the record says exactly what was sent (Priority: P1)

Results publishes one command for each regular hearing share. The service admits the command, reads
the hearing's payload from the cache, and reads the youth switch for that message. It redacts the
payload by the legacy HMPPS rules, then posts what is left to the HMPPS route through the gateway,
with the key the legacy uses. It then records the attempt:

- the outcome, and the gateway's status;
- the switch value it applied, and where the payload came from;
- the digest and build version of the body;
- the exact bytes it sent.

Only after that does it tell the broker the message is done. A body the rules empty is not sent; it
is recorded as not applicable. A body the receiver refuses is recorded as rejected, and stays
rejected.

**Why this priority**: this is the service's reason to exist, and the smallest thing that replaces
the legacy send. Every other story refines a branch of this path.

**Independent Test**: set up one valid command on the queue, its payload under the dated cache key,
the switch reading active, and a stub gateway that answers 200. Then:

- exactly one POST arrives, and its body equals slice 001's redaction output for that payload with
  the switch active;
- the request is `COMPLETED(both-legs-terminal)`;
- its one HMPPS attempt row is `SENT`, with status 200, `payload_source = cache-day`,
  `youth_switch = ACTIVE`, a body digest, the build version and a send time;
- one stored body is byte-identical to what the stub received, and hashes to the digest;
- the message is completed after those writes.

**Requirements exercised**: FR-002-005 to FR-002-007, FR-002-011, FR-002-015, FR-002-019 to
FR-002-022, FR-002-028 to FR-002-030, FR-002-032 to FR-002-037, FR-002-039, FR-002-040, FR-002-042
to FR-002-047, FR-002-053, FR-002-054, FR-002-058, FR-002-059, FR-002-063.

**Acceptance Scenarios**:

1. **Given** a valid command whose payload is under the dated `EXT_` key, a switch reading `ACTIVE`
   and a gateway answering 200, **When** the message is delivered, **Then**:
   - exactly one POST is made to the configured HMPPS route, with the subscription-key header and
     `Content-Type: application/json`;
   - its body is the redaction output, youth defendants removed;
   - the attempt row is `SENT` with `http_status = 200`;
   - the request is `COMPLETED` with `completion_reason = both-legs-terminal`;
   - the message is completed only after those writes.
2. **Given** the same command with the switch reading `INACTIVE`, **When** it is processed,
   **Then** the youth rules do not run, youth defendants are sent as the legacy sends them, and
   the row records `youth_switch = INACTIVE`.
3. **Given** a payload that the rules empty — a hearing flagged SJP, box or virtual box (R1), or one
   with no case or application left (R11) — **When** it is processed, **Then**:
   - nothing is POSTed;
   - the row is `NOT_APPLICABLE`, with reason `sjp-or-box-hearing` or
     `nothing-left-after-redaction` respectively;
   - it carries the payload source and the switch value, but no digest and no stored body;
   - the request is `COMPLETED`.
4. **Given** an application-only hearing (court applications, no prosecution case) that the gateway
   answers with 400, **When** it is processed, **Then**:
   - there is one POST and no retry;
   - the row is `FAILED(receiver-rejected)` with `http_status = 400`;
   - the body and its digest are stored;
   - the request is `COMPLETED`;
   - the outcome is counted with `shape = application-only` and `http = 400`;
   - what is sent was not changed to avoid the rejection (D16 (a)).
5. **Given** a gateway answering 401, **When** the hearing is processed, **Then** the row is
   `FAILED(receiver-rejected)` at once, with no retry, the outcome is counted with `http = 401`, and
   the request is `COMPLETED`.
6. **Given** a gateway answering 202, **When** the hearing is processed, **Then** the row is `SENT`
   with `http_status = 202`, a WARN line records the non-200 success,
   `probation_gateway_non200_success_total{status="202"}` is incremented, and nothing is retried.
7. **Given** a payload on which a rule throws — a missing null guard that the legacy also lacks
   (P19) — **When** it is processed, **Then**:
   - nothing is POSTed;
   - the row is `FAILED(rules-exception)`, with the switch value but no digest;
   - an ERROR line and a counter record it;
   - the request is `COMPLETED`.
8. **Given** a share whose leg is recorded, **When** support looks up the hearing by hearing id and
   hearing day, **Then** support finds:
   - the request;
   - the leg's latest attempt, from the latest-attempt view;
   - for an attempt that sent a body, the exact bytes sent, which hash to the recorded digest.

---

### User Story 2 — Only valid new work is admitted, and no copy of a share is sent twice (Priority: P1)

The queue has no broker duplicate detection. Every copy of a message therefore reaches the service:
a producer republish, a redelivery, a concurrent copy. The processed log is the only thing that
stops a second send. A message that breaks the inbound contract is never admitted: it is
dead-lettered with a bounded reason, and it leaves no record. While the service's own database is
unavailable, intake pauses instead of burning deliveries.

**Why this priority**: the results POST is not idempotent, so every duplicate is a genuine
duplicate at HMPPS (H12). Without this story, the first story is unsafe to run.

**Independent Test**: keep the downstream stubs answering normally, and deliver:

- each kind of invalid message;
- a republish of a completed share;
- two concurrent first copies;
- a copy with the same `requestId` but a different hearing day;
- a delivery during a database outage.

Then assert the dead-letter reasons and counts, exactly one POST per share, rows left untouched
where the table says so, and intake suspended and resumed around the outage.

**Requirements exercised**: FR-002-001 to FR-002-010, FR-002-040, FR-002-044, FR-002-046 to
FR-002-049, FR-002-058.

**Acceptance Scenarios**:

1. **Given** a message that has any of these faults:
   - an unknown field;
   - a missing required field;
   - an `eventType` other than `Hearing_Resulted`;
   - a `null` `userId`;
   - a value of the wrong type or format;
   - a body that is not JSON,

   **When** it is delivered, **Then**:
   - it is dead-lettered `validation` on that delivery;
   - no processed-log row is written;
   - `probation_deadlettered_total{reason="validation"}` is incremented;
   - the ERROR line carries the reason code, and neither the offending field's name nor any value
     from the message.
2. **Given** a valid command with no `userId` field, **When** it is delivered, **Then** it is
   admitted and processed like any other.
3. **Given** a share whose request is `COMPLETED`, **When** another copy of its command arrives (a
   producer republish under the same `messageId`), **Then** the copy is completed with no run and
   no POST, and the request and attempt rows are unchanged.
4. **Given** two copies of a new share delivered at the same moment, **When** both reach the guard,
   **Then**:
   - exactly one admission, one run and one POST happen;
   - the other copy is abandoned without delay and without an ERROR line;
   - on a later delivery, that copy is completed as a no-op.
5. **Given** a copy that meets a request whose claim is held by a live run, **When** it is delivered,
   **Then** it is abandoned without delay. If that was its final permitted delivery, it is
   dead-lettered `exhausted`. The live run completes unaffected.
6. **Given** a known `(source, requestId)` that arrives with a different hearing id, hearing day,
   shared time or event type, **When** it is delivered, **Then** it is dead-lettered `collision`,
   and the existing rows are untouched.
7. **Given** the service's database is unavailable, **When** a message is delivered, **Then**:
   - the message is abandoned;
   - intake is suspended, with `probation_intake_suspended{cause="database"} = 1`;
   - readiness reports down;
   - on its final permitted delivery, the message is dead-lettered `exhausted`;
   - when the database answers again, intake resumes and redelivered messages are processed.
8. **Given** an unexpected exception during a run, **When** it happens on an earlier delivery,
   **Then** the message is abandoned. **When** it happens on the final permitted delivery, **Then**
   the message is dead-lettered `exhausted`.
9. **Given** a run whose request claim another delivery took over after the lease expired, **When**
   the run tries to write its outcome, **Then** the write updates nothing,
   `probation_stale_runner_rejections_total` is incremented, and the run does not complete the
   message: the delivery is abandoned, or dead-lettered `exhausted` on its final permitted
   delivery.
10. **Given** a request that is `FAILED`, **When** any copy of its command is delivered, **Then** the
    copy is dead-lettered `exhausted` at once, with no run and no POST, and the request is untouched.
    This is the interim rule of FR-002-008, which holds until slice 004 adds replay admission.

---

### User Story 3 — A hearing whose payload cannot be read yet is retried by redelivery, and failed visibly if it never appears (Priority: P2)

The command is a claim check: the payload is read separately. The service reads it from the cache
by the dated key, then by the undated key, then from the results query API. A cache outage, an
unparseable cached value or an empty query answer is a miss, not a reason to give up. When every
source misses, the leg parks and the broker redelivers the message. The next delivery re-runs the
same attempt in place. When the final permitted delivery still finds nothing, the leg and the
request fail, and the message is dead-lettered with a reason the operators act on.

**Why this priority**: the legacy skips its fallback on a Redis connection error, and then sends
nothing, silently (P09). A missing payload is where a hearing is most at risk of being lost.

**Independent Test**: run these cases in turn:

- the dated key absent and the undated key present;
- both keys absent, with the query API answering in turn 200, an empty body, `{}`, non-JSON, and
  503 followed by 200;
- nothing anywhere, across five deliveries.

Assert the source recorded, the system user used when the command has no `userId`, the park and
in-place re-run, and the final dead-letter.

**Requirements exercised**: FR-002-012, FR-002-013, FR-002-015 to FR-002-019, FR-002-041,
FR-002-043, FR-002-044, FR-002-047, FR-002-048, FR-002-058.

**Acceptance Scenarios**:

1. **Given** the dated key is absent and the undated key `EXT_{hearingId}_result_` holds the
   payload, **When** the message is processed, **Then** that payload is used and the row records
   `payload_source = cache-nullday`.
2. **Given** the cache cannot be reached, **When** the message is processed, **Then** the read
   counts as a miss and the query API is asked. The hearing is sent with `payload_source = query`
   (DV-07). The service neither failed to start nor refused the message.
3. **Given** a cached value that is blank or does not parse, **When** the message is processed,
   **Then** that key counts as a miss.
4. **Given** the query API answers 2xx with an empty body, `{}` or a body that is not JSON, **When**
   the message is processed, **Then** the answer is a miss (DV-10).
5. **Given** both keys miss and the command has no `userId`, **When** the query API is called,
   **Then** it carries `CJSCPPUID` set to the service's own system user (DV-17, H30). With a
   `userId`, it carries that user's id.
6. **Given** the query API answers 503 and then 200, **When** the message is processed, **Then**:
   - the read is retried with a doubling back-off, honouring a delta-seconds `Retry-After` up to the
     cap, within the payload budget;
   - the hearing is sent (DV-23).

   **Given** the query API answers 404, **Then** it is not retried; with the cache also missing,
   the payload is unavailable.
7. **Given** the query API answers with the fallback form `{hearing, sharedTime}`, **When** the
   message is processed, **Then** the redacted fallback body is sent as it comes, without
   `cpsProsecutorIds`, and the row records `payload_source = query`.
8. **Given** every source misses on a delivery that is not the final one, **When** the message is
   processed, **Then**:
   - the leg is `PARKED(payload-unavailable)`;
   - the switch is not read, and no body exists;
   - the request is `RETRYING`, with no failure reason;
   - the message is abandoned without delay;
   - `probation_parked_total` is incremented, and an ERROR line is written.
9. **Given** a parked leg whose payload is now available, **When** the message is redelivered,
   **Then** the same attempt-1 row is re-run in place:
   - no `PENDING` is written first;
   - `attempts` is incremented;
   - `note` reads `prev=PARKED/payload-unavailable`;
   - the outcome is `SENT`, and the request is `COMPLETED`.
10. **Given** every source still misses on the final permitted delivery, **When** the message is
    processed, **Then**:
    - the leg is `FAILED(payload-unavailable)`;
    - the request is `FAILED(leg-exhausted)`, with `exhausted_message_id` set to that delivery's
      `messageId`;
    - the message is dead-lettered `leg-exhausted`, and the dead-letter is counted.
11. **Given** a query attempt whose worst case, with its wait, no longer fits the 60 s payload
    budget, **When** the stage would start it, **Then** it is not started, and the stage ends as
    `payload-unavailable`.
12. **Given** a payload whose `sharedTime` does not parse as an instant, **When** it is processed,
    **Then** `payload_shared_time` is left empty and the leg carries on. A `sharedTime` that parses,
    with any offset, is recorded as that instant.

---

### User Story 4 — An unreadable youth switch never stops a hearing; the configured default applies (Priority: P2)

For every message, the HMPPS leg reads the users-groups `EMYouthFilter` permission, as the legacy
does, and decides by the first permission's `active` flag with JavaScript semantics. The read keeps
the legacy's retry rule, and gains finite timeouts. When the switch cannot be read, the leg assumes
the configured default. The default is `ACTIVE`, which removes youth defendants, unless it is set to
`INACTIVE`. The leg then sends the hearing, and it records, logs and counts the assumption.

**Why this priority**: today the legacy sends nothing for such a hearing, and says nothing (P16).
Sending on a stated, recorded assumption fixes that (DV-25). The switch decides which defendants
HMPPS receives, so the assumption must be visible.

**Independent Test**: with a stub users-groups query, drive each decision and each unreadable
cause, first with the default `ACTIVE` and then with `INACTIVE`. Assert the body sent, the recorded
value, the WARN line and the counter labels. Assert also that nothing parks, fails or dead-letters
because of the switch alone.

**Requirements exercised**: FR-002-020 to FR-002-027, FR-002-043, FR-002-058, FR-002-063.

**Acceptance Scenarios**:

1. **Given** `permissions[0].active` is `true`, or any other JavaScript-truthy value, **When** the
   switch is read, **Then** the decision is `ACTIVE`, the youth rules run, and the row records
   `youth_switch = ACTIVE`.
2. **Given** a present `permissions[0]` whose `active` is `false`, `0`, `""` or absent, or a
   `permissions[0]` that is not an object, **When** the switch is read, **Then** the decision is
   `INACTIVE`.
3. **Given** two permissions in the answer, **When** the switch is read, **Then** the first decides,
   `probation_youth_switch_ambiguous_total` is incremented, and nothing else changes.
4. **Given** users-groups answers 500 three times, **When** the switch is read, **Then**:
   - three attempts are made, 1 s apart, and the read ends `UNREADABLE(HTTP_FAILURE)`;
   - the default `ACTIVE` applies, and the `ACTIVE`-redacted body is sent;
   - the row is `SENT`, with `youth_switch = UNREADABLE_DEFAULTED_ACTIVE`;
   - a WARN line names the cause;
   - `probation_youth_switch_unreadable_total{cause="HTTP_FAILURE", defaulted="ACTIVE"}` is
     incremented.
5. **Given** users-groups answers 404, or any other non-2xx status at or below 429, **When** the
   switch is read, **Then** the read is not retried, and it is `UNREADABLE(HTTP_FAILURE)` at once.
6. **Given** users-groups never answers, **When** the switch is read, **Then** each attempt is bounded
   by 2 s to connect and 5 s to read. After three attempts, within 23 s, the read is
   `UNREADABLE(TIMEOUT)`. The hearing is still sent.
7. **Given** a 2xx with an empty or missing `permissions` list, **Then** the read is
   `UNREADABLE(EMPTY_PERMISSIONS)`. **Given** a 2xx whose body does not parse, **Then** it is
   `UNREADABLE(UNPARSEABLE)`. Neither is retried.
8. **Given** the default is configured `INACTIVE` and the switch is unreadable, **When** the hearing
   is processed, **Then** the `INACTIVE` body is sent, the row records
   `UNREADABLE_DEFAULTED_INACTIVE`, and the counter is labelled `defaulted="INACTIVE"`.
9. **Given** an unreadable switch and a gateway answering 503, **When** the hearing is processed,
   **Then** the leg parks as `gateway-transient`: it parks for the send's reason. The switch reading
   alone never parks, fails or dead-letters a message.
10. **Given** the service is starting, **When** it reads its configuration, **Then** it logs the
    default at INFO with its value. A value other than `ACTIVE` or `INACTIVE` stops startup, and the
    refusal names the property.
11. **Given** any run, **When** the leg executes, **Then** it makes no read of the
    `ProbationResultsDistributionService` switch and no App Configuration read.

---

### User Story 5 — A gateway failure is parked or exhausted, never retried into a duplicate (Priority: P2)

The gateway forwards the POST to HMPPS and retries a backend 5xx on its own, and the results POST is
not idempotent. The service therefore retries within the run only what cannot have reached HMPPS: a
connection that never carried the request, and a 429 that the gateway's rate limit returned before
forwarding. When the gateway's answer leaves the outcome unknown — a 5xx, a 408, a read timeout
after the request was written — the leg parks at once, and the broker redelivers the message. When
the final permitted delivery still fails, the leg and the request fail, visibly.

**Why this priority**: the legacy has no timeout and no retry, and reports success whatever happened
(P04, P05). Retrying an outcome-unknown answer in-run would turn a gateway slowdown into duplicate
results at HMPPS.

**Independent Test**: with a stub gateway, run each answer class in turn: a refused connection, a
429 with `Retry-After`, a 503, a 408, a stalled response, a 5xx answer followed by 200 on
redelivery, and a 5xx on every delivery. Count the POSTs per delivery, and check the outcomes, the
stored bodies, the recovery counts and the final dead-letter.

**Requirements exercised**: FR-002-012, FR-002-013, FR-002-032 to FR-002-037, FR-002-039 to
FR-002-042, FR-002-047, FR-002-049, FR-002-058, FR-002-060, FR-002-061.

**Acceptance Scenarios**:

1. **Given** a refused connection, then a 200, **When** the message is processed, **Then** the POST
   is retried within the run after the back-off, the row is `SENT`, and one body is stored.
2. **Given** a 429 with `Retry-After: 2`, then a 200, **When** the message is processed, **Then**:
   - the retry waits 2 s, within the 10 s cap;
   - the row is `SENT`;
   - `probation_gateway_recovered_total{after="429-in-run"}` is incremented.
3. **Given** a 503, **When** the message is processed, **Then**:
   - exactly one POST is made;
   - the row is `PARKED(gateway-transient)` with `http_status = 503`, and the body and its digest
     are stored;
   - the request is `RETRYING`, and the message is abandoned.
4. **Given** a 408, **When** the message is processed, **Then** exactly one POST is made and the row
   is `PARKED(gateway-transient)`.
5. **Given** no answer within the 60 s read timeout after the request was written, **When** the
   message is processed, **Then** exactly one POST is made, and the row is
   `PARKED(gateway-transient)` with no `http_status`.
6. **Given** every connection attempt fails, **When** the message is processed, **Then** at most
   three attempts are made, and the row is `PARKED(gateway-transient)` with no `http_status`.
7. **Given** a leg parked after a 5xx, **When** the redelivered message meets a 200, **Then**:
   - the same row is re-run in place and ends `SENT`;
   - `note` reads `prev=PARKED/gateway-transient`;
   - the earlier body row is replaced, so exactly one body row remains;
   - `probation_gateway_recovered_total{after="5xx-redelivery"}` is incremented.
8. **Given** the send is still transiently failing on the final permitted delivery, **When** the
   message is processed, **Then**:
   - the leg is `FAILED(gateway-transient)`;
   - the request is `FAILED(leg-exhausted)`;
   - the message is dead-lettered `leg-exhausted`;
   - an ERROR line is written, and the outcome is counted.
9. **Given** a slow 429 that leaves less than the wait plus 65 s of the 130 s send stage, **When**
   the run considers a retry, **Then** no further attempt is made, and the leg is
   `PARKED(gateway-transient)` within the stage budget.
10. **Given** a run that received a 2xx and died before writing its outcome, **When** the message is
    redelivered after the claim lease expired, **Then**:
    - the delivery reclaims the request and re-runs the `PENDING` attempt-1 row in place, with
      `note` reading `prev=PENDING/-`;
    - the run POSTs again, which is a genuine duplicate at HMPPS;
    - `probation_crash_reruns_total` is incremented.
11. **Given** a `Retry-After` that is not a number of seconds, **When** a 429 is retried, **Then**
    the computed back-off is used.

---

### User Story 6 — Operations see every outcome, are paged only for genuine issues, and see no personal data (Priority: P3)

The operators answer two questions: is the service healthy, and what happened to one particular
hearing. They answer both from metrics and structured logs, with nobody's personal data in either.
Every transition of the state machine is counted with closed labels, and every leg outcome is one
log line. The signals let the alert rules, which slice 006 writes, page only for genuine issues and
count the known ones.

**Why this priority**: each earlier story already emits its own signals. This story completes the
registry, proves its privacy, and makes the page-or-count split of H34 possible. The privacy rules
themselves bind every story from the start.

**Independent Test**: with the stubs, drive one message per outcome class directly, without the
other stories' suites:

- a 200, a 202, an R1 `NOT_APPLICABLE`, an application-only 400, a case-hearing 400 and a 401;
- a 503 that parks, then a 200 on its redelivery; and a 429, then a 200, in one run;
- a payload-unavailable park, carried to its final permitted delivery;
- a defaulted switch read;
- a message that breaks the inbound schema;
- a spell with the broker unreachable.

Then assert, over this story's own runs: one log line per outcome, the closed label sets, that each
H34 signal can be selected by its labels, the gauges, and the privacy sweeps.

**Requirements exercised**: FR-002-009, FR-002-057 to FR-002-065.

**Acceptance Scenarios**:

1. **Given** any leg outcome, **When** it is written, **Then** exactly one structured log line
   records it, carrying:
   - the status, reason, HTTP status and body digest;
   - the run origin;
   - the recorded `youth_switch`, and the cause when it was defaulted;
   - the MDC fields `requestId`, `hearingId`, `hearingDay`, `source`, `sequenceNumber`,
     `deliveryCount` and `leg`.

   It never carries a `userId`, a payload or a body.
2. **Given** every run this story drives (and, once the other stories exist, every run of the full
   suite), **When** the emitted label values are checked, **Then** every one belongs to its closed
   set, and none is a hearing id, a user id, a payload value or free text.
3. **Given** every log statement in the code, **When** the privacy sweeps run, **Then** none at
   INFO or above carries payload or defendant data. None at any level carries an outbound body, raw
   exception text, a far end's words or a secret.
4. **Given** an application-only 400, a case-hearing 400, a 401, a 2xx other than 200 and a
   recovered send, **When** their outcomes are counted, **Then** each one can be selected on its
   own by its labels, so a rule can count it or page on it as H34 says.
5. **Given** a request that is still not terminal, or a leg whose latest row is `PARKED`, **When**
   time passes, **Then** `probation_oldest_non_terminal_age` or `probation_oldest_parked_age` reports
   its age.
6. **Given** the broker is unreachable, **When** health is read, **Then**
   `probation_servicebus_up = 0`, and readiness stays up while the database is up.
7. **Given** each dead-letter the service writes, and each final request status, **When** they
   happen, **Then** `probation_deadlettered_total{reason}` and `probation_processed_total{status}`
   count them.

---

### Edge Cases

- **A cached `{}`** is a found value, so the cache adapter treats it as a hit, as the legacy does.
  It has no `hearing`, so it is handled as the next case states. A `{}` from the query API is a
  miss instead (DV-10).
- **A parseable payload with no `hearing`, or `hearing: null`** (a cached `{}` included). The
  redaction reports a rules failure in R1 (001's FR-001-007 (d)), and FR-002-030 records
  `FAILED(rules-exception)`: the request is `COMPLETED` and nothing is sent. That is the same
  non-send as the legacy, whose handler drops such a payload before the switch read, logging one
  line (`PF/index.js:9-11` at `122e2152`); here it is recorded, logged at ERROR and counted rather
  than only logged, and the switch is read first, as for every payload (FR-002-020). Raised with
  the design owner (001's Dependencies); carried as an open item, not a marker (Assumptions).
- **A body without `hearing.id`** is sent as it is, because there is no outbound validation (H13).
  If the gateway refuses it, the row is `FAILED(receiver-rejected)`. Whether `hearing.id` survives
  the redaction across the corpus is slice 001's harness check (Q13).
- **A re-run in place that ends with no body deletes the attempt's earlier body row** (A3-63). This
  happens, for example, to a leg parked as `gateway-transient` whose payload has since gone. The
  earlier outcome then survives only as `prev=<STATUS>/<reason>` in `note`. Its digest is
  overwritten by the new outcome, which has none.
- **The switch stage runs out mid-attempt.** The read ends `UNREADABLE(TIMEOUT)`, and the default
  applies; the other stages keep their own budgets.
- **A 2xx whose response body cannot be read** is still `SENT`, because the status is known. The
  receiver's response body is never stored.
- **The broker lock is lost during a run.** The settlement fails and is counted; the redelivery
  meets the durable state; a late write by the first run is fenced by the claim token.
- **A database failure after the POST, before the record.** The outcome cannot be written, so the
  message is handed back. The redelivery reclaims the request once the lease expires, re-runs the
  row, and POSTs again: this is the at-least-once window. The re-run of a `PENDING` row is counted
  as a crash re-run; the re-run of a `PARKED` row is the open question of FR-002-061.
- **A `sharedTime` with an offset other than `Z`** is admitted. The service takes `requestId` from
  the wire, and in this slice never recomputes it.
- **A message carrying operator application properties** (`origin = REPLAY` or `SUPPORT`) is
  processed like any other delivery here. The properties never change behaviour, and recording them
  is slice 004's.
- **A share older than the `EXT_` key's life** (about 91 days) misses the cache, and is read in its
  fallback form, as in User Story 3.
- **A configured `max-delivery-count` that differs from the queue's** is refused at startup, when
  the difference is known. Otherwise the final delivery would be misidentified, so the queue
  setting is a platform precondition.
- **A body whose `created_at` falls outside the three months V1 creates** lands in the DEFAULT
  partition, until slice 003 maintains the partitions.
- **A users-groups 2xx that is an HTML error page** is `UNREADABLE(UNPARSEABLE)`.
- **More than one permission, with conflicting values.** The first decides (P17, carried), and the
  ambiguity is counted.
- **A gateway status outside 2xx, 4xx and 5xx** is not classified by the design; see FR-002-038.

## Requirements *(mandatory)*

### Functional Requirements

Each requirement is tagged with the design rows and sections it implements. "Digest §n" is a
section of `doc/design/DIGEST.md`. A bare "§n" is a section of design rev 3.6.

**Intake and the inbound contract**

- **FR-002-001**: The service MUST consume `probation.requests` (stack-prefixed in deployed
  environments) with these settings:
  - peek-lock receipt, with auto-complete off and sessions off;
  - at most `distribution.servicebus.max-concurrent-calls` messages in flight per pod (default 2);
  - a 5-minute lock, with auto-renewal up to 5 minutes;
  - `maxDeliveryCount` 5, mirrored in configuration.

  Broker duplicate detection is off on the queue, and no behaviour of the service may rely on it.
  (H3, H14, Q17, O-2; digest §5)
- **FR-002-002**: This slice MUST vendor design Appendix B.1's closed inbound schema at
  `src/main/resources/contracts/distribution-command.schema.json`. It is the informant contract with
  only `$id`, title and descriptions changed: draft-07, `additionalProperties: false`. The vendored
  file is the build's authority for the inbound body. (App. B.1; DV-16; digest §5)
- **FR-002-003**: The listener MUST dead-letter a message `validation` on the delivery that carries
  it when its body:
  - is not JSON;
  - lacks a required field;
  - carries an unknown field;
  - carries a value of the wrong type or format;
  - carries `null` for `userId`;
  - has an `eventType` other than `Hearing_Resulted`.

  The dead-letter writes no `processed_request` row, is not retried, writes a sanitised ERROR line,
  and increments `probation_deadlettered_total{reason="validation"}`. The dead-letter description,
  the log line and the metrics carry the bounded reason code, at most with a bounded violation
  kind. None of them carries the offending field's name, which is text the producer chose, or any
  value from the message. An absent `userId` is valid. (DV-16, A2-55; digest §5; workflow gate 1)
- **FR-002-004**: Broker properties and operator application properties MUST NOT change what a
  run does:
  - `messageId` serves only as the delivery's identity, recorded as `exhausted_message_id` when
    the final delivery fails;
  - `correlationId` and `contentType` change nothing;
  - the operator application properties `origin`, `operator`, `runId` and `reason` are audit
    labels only (A2-68).

  Recording the replay route and submit-hearing's operator fields at admission is slice 004's.
  (Digest §5; A2-68, A3-34)

**The guard and the processed log**

- **FR-002-005**: The idempotency key MUST be `(source, requestId)`. The request fingerprint MUST be
  a SHA-256 over `hearingId`, `hearingDay`, `sharedTime` and `eventType`, in IR's canonical form.
  `userId` is not part of it. (Digest §5; §4.4)
- **FR-002-006**: On first sight of a key, the guard MUST insert two rows in one transaction:
  - the `processed_request` row: `RECEIVED`, its identity fields, the fingerprint, the request claim
    (owner, token, and an expiry one lease ahead), `attempts` counted, and `command` holding the
    admitted body exactly as received;
  - the attempt-1 HMPPS row: `PENDING`, attempt 1, `attempt_origin = queue`, no leg claim.

  (Digest §6 step 1; A2-48, A2-50, R1-23)
- **FR-002-007**: Every delivery of a valid command MUST be handled by the decision tree inherited
  from IR, with the departures that digest §4 records:

  | The delivery meets | Outcome |
  |---|---|
  | no row for the key | admit it (FR-002-006) and run |
  | a lost insert race with a concurrent first copy | handled as a live claim (below), quietly: no ERROR line |
  | a `COMPLETED` request | complete the message; no run and no write |
  | a known key with a different fingerprint | dead-letter `collision`; the rows are untouched |
  | a `RECEIVED` or `RETRYING` request whose claim is live | abandon without delay; on the final permitted delivery, dead-letter `exhausted` |
  | a `RECEIVED` or `RETRYING` request whose claim is absent or expired | reclaim it atomically (new owner, token and expiry; `attempts` + 1) and run |
  | a `FAILED` request | FR-002-008 |
  | a database outage | abandon and suspend intake (FR-002-009); on the final permitted delivery, dead-letter `exhausted` |
  | an unexpected exception in the run | abandon; on the final permitted delivery, dead-letter `exhausted` (A2-34; a recorded departure from YOT's listener) |
  | a run that lost its claim before writing (a stale runner) | write nothing more; abandon; on the final permitted delivery, dead-letter `exhausted` (A2-67's "no settlement", as read in Clarifications) |

  The guard dead-letters with `validation`, `collision` or `exhausted`. Only settlement writes
  `leg-exhausted` (FR-002-047). (A3-53, A2-34, A2-55; §4.5; DV-08)
- **FR-002-008** (interim; superseded by slice 004's replay admission): Replay admission is slice
  004's (approved slicing plan). Until slice 004 is in place, a delivery that meets a `FAILED`
  request MUST NOT be admitted or run. It is dead-lettered `exhausted` on that delivery and counted;
  the request and its rows are untouched. A producer can send only one kind of copy of a failed
  share: a copy under `messageId = RESULTS:{requestId}`, which is the exhausted identity. For that
  copy, this rule gives the outcome IR's guard gives, and it leaves a dead-letter entry.
  Resubmitting that entry before slice 004 lands only dead-letters it `exhausted` again; once slice
  004's replay admission is in place, a resubmitted copy is admitted as a replay (DV-26). This rule
  is not a deviation from the legacy and takes no DV id; its test is removed when slice 004 lands.
  Slice 004 replaces this rule with replay admission: IR's fresh-identity rule, DV-26, the route
  record, the replay column of the eligibility table, and `REPLAY_DEFERRED`. (A2-34, A3-30, A3-53)
- **FR-002-009**: While the service's database is unavailable, intake MUST be suspended:
  `probation_intake_suspended{cause="database"}` is 1, and `probation_intake_suspensions_total` is
  incremented. A delivery that meets the outage is handed back before its body is read. Intake
  resumes when the store probe, run every `distribution.store.probe-interval` (default 10 s), finds
  the database again. Readiness MUST report the database and nothing else, and Service Bus health
  MUST never be part of readiness. An outage of Redis, users-groups or the gateway MUST NOT suspend
  intake or affect readiness. (Digest §6 Intake; §10.1; digest §13)
- **FR-002-010**: A run MUST capture its `runOrigin` once, at admission, and keep it for the whole
  run. In this slice it is `queue` for every delivery, including a submit-hearing command
  (`origin = SUPPORT`). Every `origin` metric label, every log line, and every attempt row the run
  creates take the value from there, never from a column read later. Slice 004's runs set `replay`
  and `resend`. (A2-71, A2-57)

**Pipeline order, eligibility and time budgets**

- **FR-002-011**: A run MUST proceed in this order, and in no other:
  1. admit;
  2. fetch the payload, the only stage before the leg;
  3. run the HMPPS leg: read the switch, redact, digest the body, send;
  4. record the outcome;
  5. settle the message.

  There is no validation stage (H13). The fetch's outcome is written only to a leg that this kind of
  run may touch (FR-002-012). (Digest §6; R2-7)
- **FR-002-012**: For a delivery whose request is not being replayed (`replayed_at` is NULL), the leg
  MUST follow the delivery column of the eligibility table, for the rows this slice creates:

  | The leg's latest row | The run |
  |---|---|
  | attempt-1 `PENDING`, inserted by this delivery's admission | run it |
  | attempt-1 `PENDING`, its request claim reclaimed after expiry | re-run it in place (FR-002-041); count it as a crash re-run (FR-002-061) |
  | attempt-1 `PARKED` | re-run it in place, on every delivery |
  | `SENT` or `NOT_APPLICABLE` | skip it; it is never sent again |
  | attempt-1 `FAILED` | skip it |

  The run selects its column from `processed_request.replayed_at`. The replay column, and the cells
  for resend and replay rows, are slice 004's. (Digest §7 eligibility; A2-39, A2-60, A2-64; R2-7)
- **FR-002-013**: Each stage MUST run within its own budget, measured from the stage's start:
  payload 60 s, switch 25 s, send 130 s (`distribution.budget.*`). An attempt, or a retry, may start
  only if the wait before it plus the client's connect and read timeouts fits the stage's remaining
  time; this attempt-fits rule is checked at the end of the wait. A stage that cannot start another
  attempt ends as follows:
  - the payload stage, as `payload-unavailable`: parked, or failed on the final permitted delivery;
  - the send stage, as `gateway-transient`: parked, or failed on the final permitted delivery;
  - the switch stage, as `UNREADABLE(TIMEOUT)`, which then defaults (FR-002-025).

  With the defaults, the worst case is 60 + 25 + 130 = 215 s. (Digest §6; A2-69, A3-96, R1-30)
- **FR-002-014**: The processing deadline (4 min) MUST be shorter than the claim lease (`PT4M30S`),
  and the lease shorter than the lock (5 min). Lock auto-renewal MUST cover at least the deadline
  plus 30 s. Startup refuses a configuration that breaks either rule (FR-002-066). (Q17; digest §6,
  §12)

**Payload source**

- **FR-002-015**: The payload MUST be read from these sources, in this order:
  1. the cache key `EXT_{hearingId}_{hearingDay}_result_`;
  2. the undated key `EXT_{hearingId}_result_`;
  3. the results query API.

  The first source that yields a payload ends the fetch. The attempt row records
  `payload_source = cache-day | cache-nullday | query`, and
  `probation_payload_source_total{source}` counts it. The fetch sits behind the separable
  payload-source port (D5). (H2, D5; digest §10)
- **FR-002-016**: Cache reads MUST use TLS with the peer verified: both `ssl` and `verify-peer` are
  true outside the `local` profile. The client MUST reconnect on its own, with connect and command
  timeouts of 5 s each. Each of the following is a miss for that key, never a failure of the run:
  - any failure to read the key: unreachable, timed out, refused;
  - a cached value that is blank, JSON `null`, or not parseable.

  A cache outage MUST NOT stop the service from starting. (DV-07, DV-13, DV-14; digest §10)
- **FR-002-017**: The query-API fallback MUST be
  `GET {results base}/results-query-api/query/api/rest/results/hearingDetails/{hearingId}?hearingDate={hearingDay}`
  with `Accept: application/vnd.results.hearing-information-details+json`. `CJSCPPUID` is the
  command's `userId` when present, and the service's own system user otherwise. The timeouts are
  5 s to connect and 30 s to read. The results retry block applies (DV-23):
  - at most `distribution.results.max-attempts` attempts (default 4);
  - a back-off from 500 ms, doubling to 20 s;
  - 408, 429, every status from 500, and connect and read failures are retried;
  - a delta-seconds `Retry-After` is honoured, up to the maximum back-off;
  - the retries are cut short by the payload budget.

  A 2xx with an empty body, `{}`, or a body that is not JSON MUST be a miss. So is any other answer
  once the retries are spent. (Digest §10; DV-10, DV-17, DV-23, H30; §4.5)
- **FR-002-018**: When every source misses, the leg MUST be recorded `PARKED(payload-unavailable)`,
  or `FAILED(payload-unavailable)` on the final permitted delivery. The switch is not read, no body
  exists, and the row carries no payload source. (Digest §10, §7; presence matrix)
- **FR-002-019**: A payload MUST reach the leg as it was read, in either form:
  - the cache form, `{hearing, cpsProsecutorIds, policeCases, sharedTime}`;
  - the fallback form, `{hearing, sharedTime}`, with its own field set.

  Nothing is added, normalised or reordered beyond what the redaction does (D10; Q19 closed). The
  payload MUST never be stored, and never logged in a deployed environment. When its `sharedTime`
  parses as an ISO-8601 instant (offset or zone honoured), the row records it as
  `payload_shared_time`. Otherwise the column stays NULL, and the leg carries on. (Digest §10 Forms;
  H17, A3-58; DV-09)

**Youth switch**

- **FR-002-020**: The switch MUST be read once per message, on every kind of run: a queue run here,
  replay and resend runs in slice 004. It is the HMPPS leg's first step, after the payload fetch and
  before the redaction. A reading MUST NOT be cached, or reused across messages. (H7; A3-11)
- **FR-002-021**: The read MUST be
  `GET {users-groups base}/usersgroups-query-api/query/api/rest/usersgroups/permissions?key={distribution.usersgroups.youth-filter-key}`,
  where the key defaults to `EMYouthFilter`, with
  `Accept: application/vnd.usersgroups.get-permissions-by-feature-key+json` and `CJSCPPUID` set to
  the service's own system user. (H7, H30; A3-11, A3-106)
- **FR-002-022**: The decision MUST follow the legacy's JavaScript semantics:

  | The answer | Decision |
  |---|---|
  | `permissions[0].active` is truthy | `ACTIVE` (youth defendants removed) |
  | `permissions[0]` is non-null, and its `active` is falsy or absent; this includes a `permissions[0]` that is not an object | `INACTIVE` |
  | anything else | `UNREADABLE(cause)` |

  Only `permissions[0]` decides (P17, carried). A response with more than one permission increments
  `probation_youth_switch_ambiguous_total` and changes nothing else. (A3-11, P17, A2-57)
- **FR-002-023**: The read MUST keep the legacy's retry rule:
  - at most `distribution.usersgroups.max-attempts` attempts (3);
  - a fixed `retry-wait` between them (1 s);
  - a retry only for an error with no response, or a status above 429;
  - no `Retry-After`.

  It MUST gain finite timeouts: 2 s to connect and 5 s to read (DV-24). A 2xx whose body is
  unusable MUST NOT be retried. The attempt-fits rule applies within the 25 s switch stage.
  (A3-13, A3-14, A3-22; DV-24)
- **FR-002-024**: An `UNREADABLE` result MUST carry exactly one bounded cause:

  | Cause | When |
  |---|---|
  | `HTTP_FAILURE` | a non-2xx answer, or a failure with no response other than a timeout, once the retry rule stops |
  | `TIMEOUT` | the last attempt timed out, or the 25 s stage ran out |
  | `EMPTY_PERMISSIONS` | a 2xx whose `permissions` list is empty or missing |
  | `UNPARSEABLE` | a 2xx body that does not parse |

  H8 makes a 2xx whose `permissions[0]` is `null` an `UNREADABLE` read, with cause
  [NEEDS CLARIFICATION: which cause labels an unreadable read whose 2xx body parses and whose
  non-empty `permissions` list has `null` as its first element? None of the four causes is mapped
  to it (design gap §4.4 / A2-28; digest §16). The cause is the `cause` label on
  `probation_youth_switch_unreadable_total` and a field of the outcome log line]. The cause is never
  stored in a column. (A2-28; H8)
- **FR-002-025**: An `UNREADABLE` switch MUST resolve to
  `distribution.youth-switch.default-when-unreadable` (default `ACTIVE`):
  - the row records `UNREADABLE_DEFAULTED_ACTIVE` or `UNREADABLE_DEFAULTED_INACTIVE`;
  - the youth rules R3/R4 run when the recorded value is `ACTIVE` or `UNREADABLE_DEFAULTED_ACTIVE`;
  - the leg carries on to the redaction, the digest and the send, on every kind of run, and settles
    by the outcome of the send;
  - the outcome line is logged at WARN, with the cause;
  - `probation_youth_switch_unreadable_total{cause, defaulted}` is incremented.

  An unreadable switch on its own MUST NOT park, fail or dead-letter anything.
  `youth-switch-unreadable` exists in no vocabulary. (H8, DV-25; A3-23, A3-25, A3-27)
- **FR-002-026**: Startup MUST validate the default as `ACTIVE` or `INACTIVE`, and refuse any other
  value, naming the property. The service logs the value at INFO. The default is configuration, not
  a reading: checking that it equals the environment's live switch is a go-live gate of slice 006.
  (A3-24, A3-29)
- **FR-002-027**: The service MUST NOT read the `ProbationResultsDistributionService` switch, MUST
  NOT read App Configuration, and MUST NOT carry any property, endpoint or value that acts as a
  cutover lever. (H26, Q15; A3-92)

**Redaction**

- **FR-002-028**: The leg MUST apply slice 001's HMPPS redaction to the payload: R0–R11 verbatim, in
  the legacy order (R12 is not ported, DV-09), called with the effective state — `ACTIVE` for a
  recorded `ACTIVE` or `UNREADABLE_DEFAULTED_ACTIVE`, `INACTIVE` otherwise (001's FR-001-001,
  FR-001-006). The leg MUST add no rule, no guard and no field handling of its own. The redaction
  deep-copies its input, so the payload that was read is not modified. (D1, D10; digest §10
  Redaction; A3-17; DV-09)
- **FR-002-029**: A redaction result emptied by R1 or emptied by R11 (001's FR-001-007 (b), (c))
  MUST be recorded `NOT_APPLICABLE`, with `sjp-or-box-hearing` for R1, or
  `nothing-left-after-redaction` for R11. Nothing is sent. (Digest §10, §7; R1, R11)
- **FR-002-030**: A redaction result of kind rules failure (001's FR-001-007 (d)) MUST be recorded
  `FAILED(rules-exception)`, which is terminal at once, with an ERROR line and a counter. Nothing is
  sent. No null guard is added to a rule to avoid the failure: P19 is carried, under the standing
  rule "where the legacy throws and swallows, the port throws and reports". (Digest §7; §7.0; P19)
- **FR-002-031**: The body MUST NOT be validated against any outbound schema. There is no validator,
  no validation-mode property, no violation metric and no `contract-invalid` write.
  `contract-invalid` and the note form `contract=invalid` stay in the vocabulary, reserved and
  unused. (H13; A3-50)

**Gateway send**

- **FR-002-032**: The send MUST be one HTTPS POST to `distribution.hmpps.url`, the route the legacy
  uses, with the same subscription key value (D4a). It carries `Ocp-Apim-Subscription-Key` from
  configuration and `Content-Type: application/json`, and no client certificate (D4b). The
  gateway's internal certificate authority comes from the platform's truststore (digest §15,
  ask 9). (D4a, D4b; digest §9)
- **FR-002-033**: The body MUST be serialised once, from the redacted tree. Those exact UTF-8 bytes
  are the bytes POSTed, the bytes digested (`body_digest`, the lower-case hex SHA-256) and the bytes
  stored (FR-002-042), and the publisher hands them to the runner. `service_version` is the
  service's build version, read once at startup, and it is written on every row that carries a
  `body_digest`. (H18, H19; A3-59, A3-61; R1-10)
- **FR-002-034**: The gateway client MUST use a 5 s connect timeout and a 60 s read timeout. (H27;
  A3-95)
- **FR-002-035**: In-run retries of the POST MUST be limited to two cases: a connection failure, in
  which the request was never sent (a connect timeout counts), and a 429. The rule is fixed in
  code, never a property. Within it:
  - at most `distribution.gateway.max-attempts` attempts are made (default 3);
  - the wait is 500 ms, doubling up to 10 s;
  - a delta-seconds `Retry-After` is honoured up to the maximum back-off, and any other form is
    ignored in favour of the computed wait;
  - each retry starts only under the attempt-fits rule.

  (H11, H15; A3-43, A3-54, A2-69; DV-04)
- **FR-002-036**: On a queue run, the gateway's answer MUST map to the leg's outcome as follows. A
  resend's column, `FAILED` at once in place of `PARKED`, is slice 004's.

  | Gateway answer | Leg outcome |
  |---|---|
  | any 2xx | `SENT`, with the status recorded. A status other than 200 is also logged at WARN and counted in `probation_gateway_non200_success_total{status}` (`201`, `202`, `204`, `other`) |
  | a 4xx other than 408 and 429 (400 and 401 included) | `FAILED(receiver-rejected)`, terminal; no retry |
  | an outcome-unknown answer: a 5xx, a 408, a read timeout after the request was written, or an I/O failure once writing began | `PARKED(gateway-transient)` at once, with no in-run retry; `FAILED(gateway-transient)` on the final permitted delivery |
  | a connection failure or a 429, still failing when the retries or the stage run out | `PARKED(gateway-transient)`; `FAILED(gateway-transient)` on the final permitted delivery |

  (Digest §9; H11, H12, H15; A3-45)
- **FR-002-037**: The publisher MUST never throw past its outcome. It returns `SENT(status)`,
  `FAILED_NON_TRANSIENT(status)` or `TRANSIENT_EXHAUSTED`, with the bytes it sent. Only the runner
  maps these to a (status, reason). (Digest §9)
- **FR-002-038**: A gateway answer outside 2xx, 4xx and 5xx MUST map to [NEEDS CLARIFICATION: which
  leg outcome and which `http` label bucket does such an answer take, in practice a 3xx? The
  design's answer table (§9, §4.4) and the closed `http` label (A3-116) cover only 2xx, 4xx, 5xx
  and no answer, and the publisher's outcome set is closed].
- **FR-002-039**: `SENT` MUST mean only that the gateway answered 2xx. Nothing the service writes —
  code, comment, log or document — may promise more. The results POST is not idempotent, so a
  crash-window re-run, and a redelivery after an outcome-unknown answer, are genuine duplicates at
  HMPPS. The guarantee is this: a leg recorded `SENT` or `NOT_APPLICABLE` is never sent again by a
  redelivery; delivery is at-least-once across a crash between a 2xx and its record. (H12; A3-47;
  digest §9; §4.5 crash window)

**Recording outcomes and the stored body**

- **FR-002-040**: A run's leg outcome MUST be written by one UPDATE of its attempt row. The UPDATE
  sets `status`, `reason`, `youth_switch`, `http_status`, `gateway_request_id`, `payload_source`,
  `payload_shared_time`, `body_digest`, `sent_at` (from the database clock, when `SENT`),
  `service_version` and `note`, and increments `attempts` by one. It is guarded by the row's
  expected status and by the request's claim owner and token. It commits in the same transaction
  as the request's transition and the body row (FR-002-042). An UPDATE that changes no row is a
  stale-runner rejection: it is counted in `probation_stale_runner_rejections_total`, and the run
  writes nothing more and does not complete the message. The listener abandons the delivery, or
  dead-letters it `exhausted` on the final permitted delivery (FR-002-007). That is how this spec
  reads A2-67's "no settlement" (Clarifications). Besides this slice's own case (a claim taken over
  after the lease expired, User Story 2 scenario 9), the request-claim guard is pinned by
  `RollbackFencingIT`'s late leg-outcome UPDATE case once slice 004's close-in-flight statement,
  which clears the request claim, is in place (004's FR-004-049a). (Digest §6 step 4; A2-67, A2-39,
  A2-51)
- **FR-002-041**: A re-run in place — of a `PARKED` attempt-1 row, or of a `PENDING` one whose claim
  was reclaimed — MUST write no `PENDING` first. It re-reads the payload and the switch, and
  re-derives every outcome column in the one UPDATE of FR-002-040. It rewrites `note` whole, as
  `prev=<STATUS>/<reason>`, or as `prev=PENDING/-` for a `PENDING` row, so a note never grows across
  re-runs. (A2-39, A2-68, A2-58)
- **FR-002-042**: Every attempt that serialised a body MUST store the exact bytes POSTed in
  `outbound_body`. These are the `SENT`, `FAILED(receiver-rejected)`, `PARKED(gateway-transient)` and
  `FAILED(gateway-transient)` attempts. The body is inserted as text, never as a normalised JSON
  type, after the outcome UPDATE and in the same transaction, so that the digest trigger accepts it.
  A re-run in place MUST delete the attempt's earlier body row, and insert the new one if there is
  one, in the same transaction, leaving at most one body row per attempt. No other attempt has a
  body row. The receiver's response body is never stored. (H18, H20; A2-61, A3-59, A3-63)
- **FR-002-043**: Every row this slice writes MUST satisfy design §4.6's column-presence matrix and
  pairings:
  - `payload_source` is set on every row except a `PENDING` or `payload-unavailable` one;
  - `body_digest`, with `service_version`, is set exactly on `SENT`, `receiver-rejected` and
    `gateway-transient` rows;
  - `http_status` is required on `SENT` and `receiver-rejected` rows. On a `gateway-transient` row
    it holds the last status the gateway answered, and is NULL after a connect or read failure;
  - `youth_switch` is NULL on `PENDING` and `payload-unavailable` rows, and set on every other;
  - `sent_at` is set exactly on `SENT` rows;
  - `payload_shared_time` ⇒ `payload_source`; `body_digest` ⇒ `payload_source`;
    `youth_switch` ⇒ `payload_source`; `http_status` ⇒ `body_digest`.

  These are the cells of the rows this slice writes. The CHECKs carry the full matrix of
  FR-002-052, including the cells of the `payload-changed` rows that slice 004's resend writes and
  of the `rolled-back` rows that the rollback's close-in-flight statement writes (§9.4 step 4;
  004's FR-004-049a). (A2-54, A2-61, A2-66)
- **FR-002-044**: This slice MUST write only these request transitions:
  - `RECEIVED` or `RETRYING` → `RETRYING`, with `failure_reason` NULL (A2-35);
  - `RECEIVED` or `RETRYING` → `COMPLETED`, with `completion_reason = recovered` when `audit_note`
    holds a `REPLAYED_AFTER_FAILURE` line, and `both-legs-terminal` otherwise (A2-41, A3-7);
  - `RECEIVED` or `RETRYING` → `FAILED`, with `failure_reason = leg-exhausted` and
    `exhausted_message_id` set to the final delivery's `messageId`.

  The completion and final-failure statements clear `replayed_at`; the retrying statement leaves it
  as it is. No statement of this slice moves a `COMPLETED` or `FAILED` row. (Digest §7; A2-27,
  A2-35, A2-41, A2-60, A3-7)
- **FR-002-045**: `audit_note` MUST be append-only: one ISO-8601-stamped line per event, never
  overwritten or truncated. This slice writes no audit line of its own, and reads the column only
  for FR-002-044. (Digest §8; A2-47)

**Settlement**

- **FR-002-046**: Every delivery MUST be settled exactly once — complete, abandon or dead-letter —
  after the state that justifies it is durably written. No path may let the lock expire, settle
  twice, or return without settling. The message listener is the only component that turns a run's
  result into a settlement, and it holds no business logic. (Digest §5, §6 step 5; workflow gate 2;
  constitution Principle VI)
- **FR-002-047**: A run MUST settle by its leg's latest row:

  | After the run, the leg's latest row is | Request | Message |
  |---|---|---|
  | `SENT`, `NOT_APPLICABLE`, or `FAILED` with `receiver-rejected` or `rules-exception` | `COMPLETED` (FR-002-044) | complete |
  | `PARKED` (`payload-unavailable`, `gateway-transient`), on a delivery that is not the final permitted one | `RETRYING`, `failure_reason` NULL | abandon |
  | on the final permitted delivery, the outcome that would have been `PARKED`: the leg is written `FAILED(<same reason>)` | `FAILED(leg-exhausted)`, with `exhausted_message_id` | dead-letter `leg-exhausted` |

  A `FAILED` leg with `payload-unavailable` or `gateway-transient` never completes a request.
  (Digest §7 Settlement; §4.4; A2-34)
- **FR-002-048**: The final permitted delivery MUST be recognised when the broker's delivery count
  reaches `distribution.servicebus.max-delivery-count`. That value MUST equal the queue's
  `maxDeliveryCount` (5). (Digest §5, §12)
- **FR-002-049**: An abandon MUST hand the message back with no delay. A copy abandoned on a live
  claim may be dead-lettered `exhausted` within seconds; that noise is accepted, and slice 004's
  runbook removes such an entry. (H15; A3-55)

**The V1 data model**

- **FR-002-050**: The V1 migration MUST be design Appendix B.2's script: three tables and one view,
  and nothing else (there is no shadow table, H21). Its objects are:
  - `processed_request`: IR's block byte-identical, followed by this service's changes as `ALTER`s
    — the replaced exhausted-identity CHECK, the completion- and failure-reason CHECKs,
    `command text NOT NULL` and `replayed_at`;
  - `outbound_message`;
  - the view `outbound_message_latest`;
  - the partitioned `outbound_body`, with its trigger.

  (Digest §8; App. B.2; A2-48, A2-60, R1-11, H21)
- **FR-002-051**: `processed_request` MUST enforce:
  - primary key `(source, request_id)`;
  - `status` ∈ {`RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`}, and `attempts ≥ 0`;
  - the claim triple, all or none;
  - `COMPLETED` ⇒ `completion_reason` ∈ {`both-legs-terminal`, `recovered`}, and any non-null value
    in that set;
  - `FAILED` ⇒ `failure_reason` ∈ {`leg-exhausted`, `rolled-back`}, and `failure_reason` NULL on
    every other status;
  - `exhausted_message_id` required on a `FAILED` row with `leg-exhausted`;
  - an index on `(hearing_id, hearing_day)`.

  (Digest §8; A2-35, R1-26, R1-28b)
- **FR-002-052**: `outbound_message` MUST enforce the vocabularies and CHECKs of design §4.6 and
  Appendix B.2, over every (status, reason) cell, including the cells this slice never writes:
  - `leg IN ('HMPPS')`;
  - `attempt ≥ 1` and `attempts ≥ 0`; unique `(source, request_id, leg, attempt)`;
  - `attempt_origin` ∈ {`queue`, `resend`, `replay`}, with `attempt = 1` ⇔ `attempt_origin = queue`;
  - `status` ∈ {`PENDING`, `SENT`, `NOT_APPLICABLE`, `PARKED`, `FAILED`}, with `reason` NULL
    exactly on `PENDING` and `SENT`, and the (status, reason) pairs of digest §7. Every reasoned
    branch states `reason IS NOT NULL`;
  - the (leg, reason) rule: `nothing-left-after-redaction` only on an `HMPPS` row;
  - `youth_switch` ∈ the four recorded values, and `payload_source` ∈ {`cache-day`,
    `cache-nullday`, `query`};
  - `sent_at` exactly on `SENT`, and `SENT` ⇒ `http_status` and `body_digest`;
  - the leg-claim triple and `resend_run_id` present exactly on `resend` rows;
  - `note` NULL, or 1 to 256 characters;
  - the five presence CHECKs:
    - `payload_source` is required on every row except `PENDING`, `payload-unavailable` and
      `rolled-back` rows;
    - `body_digest` is present exactly on `SENT`, `receiver-rejected` and `gateway-transient` rows,
      with `rolled-back` exempt;
    - `service_version` is present exactly when `body_digest` is, with no exemption;
    - `http_status` is required on `SENT` and `receiver-rejected` rows;
    - on an `HMPPS` row, `youth_switch` is NULL on `PENDING`, `payload-unavailable` and
      `payload-changed` rows and set on every other, with `rolled-back` exempt;
  - the four pairings of A2-66, on every row, `rolled-back` included: `payload_shared_time` ⇒
    `payload_source`; `body_digest` ⇒ `payload_source`; `youth_switch` ⇒ `payload_source`;
    `http_status` ⇒ `body_digest`;
  - a foreign key to `processed_request`, `ON DELETE RESTRICT`;
  - an index on `(leg, status, updated_at)`.

  An applied migration is never edited, so V1 carries the whole matrix from the start; FR-002-043
  lists only the cells this slice writes. (Digest §8; App. B.2; A2-50, A2-54, A2-58, A2-61, A2-66,
  R2c-2; technical rules, Migrations)
- **FR-002-053**: The view `outbound_message_latest` MUST return the highest attempt per
  `(source, request_id, leg)`. Every read of a leg's state MUST come from it, and never from
  `processed_request.status`. That covers support lookups and gauges here, and the dashboards,
  reconciliation and resend selection of later slices. (Digest §8; A2-49)
- **FR-002-054**: `outbound_body` MUST be range-partitioned by calendar month (UTC) on `created_at`,
  and MUST have:
  - monthly partitions named `outbound_body_YYYY_MM`, on UTC month bounds — from the first instant
    of the month (inclusive) to the first instant of the next (exclusive) — each created only if no
    table of that name exists;
  - the DEFAULT partition, `outbound_body_default`;
  - primary key `(outbound_message_id, created_at)`;
  - a foreign key to `outbound_message(id)`, `ON DELETE RESTRICT`;
  - the body as text;
  - the row trigger `outbound_body_digest_trg`, which refuses a body whose attempt's `body_digest`
    is NULL or differs from the SHA-256 of the body's UTF-8 bytes. A missing parent is left to the
    foreign key.

  V1 MUST create the current month's partition and the next two, by that rule. The service's
  database role creates the partitions, and so owns them. Maintaining them after that, and purging,
  is slice 003's, which creates later months by the same name, bounds and rule (003's FR-003-006,
  FR-003-007). V1 keeps `outbound_body_default` (A3-63) unless 003's open question 1 (FR-003-011)
  is answered with its option C, which removes it; that question goes to the design owner before
  V1 is written (Assumptions; Dependencies). (H20; A3-63, A3-64, A3-105; digest §8; App. B.2;
  FR-003-011)
- **FR-002-055**: A schema test MUST assert on every build:
  - that the database's value sets equal the code's closed vocabularies: leg status, leg reason,
    leg, attempt origin, payload source, youth switch, completion reason and failure reason;
  - that the note forms are the seven of digest §8: `prev=<STATUS>/<reason>`, `prev=PENDING/-`,
    `rerun-by=<messageId>`, `override=allow-changed-payload` and `override=force-resend` in use;
    `baseline=none` and `contract=invalid` reserved;
  - that `youth-switch-unreadable` is refused everywhere;
  - that each presence, pairing, origin and claim CHECK accepts its cells and refuses their
    negation, across the full matrix of FR-002-052, the `payload-changed` and `rolled-back` cells
    included.

  (R1-26; A2-50, A2-54, A2-58, A2-66, A3-25)
- **FR-002-056**: The database clock MUST be the only time authority for the log.
  `processed_request` and `outbound_message` rows are never deleted. Hearing payloads are never
  stored. (Digest §8; §4.6)

**Observability**

- **FR-002-057**: The metric registry of digest §13 MUST be defined once, in the shared core: every
  name, type and closed label set. That includes the series slices 003 and 004 increment:
  `probation_replays_total`, `probation_replay_deferred_total`, `probation_resend_refusals_total`
  and `probation_body_*`. No label may carry a hearing id, a payload value, a user id or free text.
  `probation_contract_violations_total` is not built (H13). `probation_flag_read_failures_total`
  is the results producer's metric, not this service's. The registry also holds one series that
  is in neither digest §13 nor design §10.1: `probation_operations_failures_total{action, reason}`,
  a counter that slice 005 proposes and alone increments (005's FR-005-043). It is marked
  *proposed*, a design addition raised with the design owner, and is registered here so that the
  registry keeps one owner. No alert or dashboard reads it until the design owner adds it (006's
  FR-006-022). (Digest §13; A2-57; H26; constitution Principle VII)
- **FR-002-058**: Each event this slice handles MUST increment its series:

  | Event | Series |
  |---|---|
  | a leg outcome written | `probation_leg_outcomes_total{leg, status, reason, origin, forced, shape, http}` |
  | a leg parked | `probation_parked_total{leg, reason}` |
  | a message dead-lettered by the service | `probation_deadlettered_total{reason}` |
  | a request reaching `COMPLETED` or `FAILED` | `probation_processed_total{status}` |
  | a payload found | `probation_payload_source_total{source}` |
  | a switch read | `probation_youth_switch_state{state}` (the last recorded value); and, when unreadable, `probation_youth_switch_unreadable_total{cause, defaulted}`; when ambiguous, `probation_youth_switch_ambiguous_total` |
  | a POST attempt | `probation_gateway_request_duration{leg}` |
  | a 2xx other than 200 | `probation_gateway_non200_success_total{status}` |
  | a recovery | `probation_gateway_recovered_total{after}` (FR-002-060) |
  | a crash re-run | `probation_crash_reruns_total{leg}` (FR-002-061) |
  | an unexpected run failure, a failed settlement, a lost lock, a stale runner, an intake suspension | `probation_{processing_failures, settlement_failures, lock_loss, stale_runner_rejections, intake_suspensions}_total` |

  (Digest §13)
- **FR-002-059**: The labels of `probation_leg_outcomes_total` MUST be set as follows:

  | Label | Value |
  |---|---|
  | `leg` | `HMPPS` |
  | `status`, `reason` | the row's; a status with no reason takes a fixed placeholder from the registry |
  | `origin` | the run's `runOrigin` |
  | `forced` | `false` (`true` only on slice 004's forced resend) |
  | `shape` | `application-only` when the body sent carries court applications and no prosecution case; `case` otherwise |
  | `http` | the row's `http_status`, bucketed into `200`, `2xx-other`, `400`, `401`, `408`, `429`, `4xx-other`, `5xx`, or `none` (no answer) |

  (A3-36, A3-114, A3-116, A2-71, R1-31)
- **FR-002-060**: `probation_gateway_recovered_total` MUST count each recovery when it happens:
  - `after="429-in-run"` when a run's send ends `SENT` after at least one 429 retried in that run;
  - `after="5xx-redelivery"` when a re-run of a row `PARKED(gateway-transient)`, whose recorded
    `http_status` is a 5xx, ends `SENT`.

  The re-run overwrites the row, so the count cannot be read back later. (A3-116; A2-39)
- **FR-002-061**: `probation_crash_reruns_total{leg}` MUST count each delivery that re-runs a
  `PENDING` attempt-1 row whose request claim had expired, because the earlier run may have POSTed
  before it died. A crash during the re-run of a `PARKED` row leaves the row `PARKED`, and is not
  told apart from an ordinary re-run: [NEEDS CLARIFICATION: how is a crash during the re-run of a
  `PARKED` row counted, given that the re-run writes no `PENDING` first and so leaves nothing a
  redelivery can detect? Should it be counted, and on what evidence, or accepted as uncounted? The
  design counts only `PENDING` attempt-1 re-runs and leaves the rest not established (A3-115;
  digest §16); the replay's `FAILED` re-run case is slice 004's]. (A3-115; §4.5 crash window)
- **FR-002-062**: The gauges MUST report:

  | Gauge | What it reports |
  |---|---|
  | `probation_oldest_non_terminal_age` | the age of the oldest request in `RECEIVED` or `RETRYING` |
  | `probation_oldest_parked_age` | the age of the oldest leg whose latest row is `PARKED` |
  | `probation_servicebus_up` | broker health, judged stale after `distribution.servicebus.health-staleness` (default 60 s); never part of readiness |
  | `probation_intake_suspended{cause}` | 1 while intake is suspended; `cause` ∈ {`database`, `operator`}, the `operator` value set by slice 004's pause |

  (Digest §13; A2-57; R1-18)
- **FR-002-063**: Logs MUST be structured JSON. Every processing line carries the MDC fields
  `requestId`, `hearingId`, `hearingDay`, `source`, `sequenceNumber`, `deliveryCount` and `leg`;
  they are cleared after each message, and `userId` is never among them. Each leg outcome MUST
  produce exactly one line, carrying:
  - the status, reason, HTTP status and body digest;
  - the `runOrigin`;
  - the recorded `youth_switch`, and, for a defaulted read, its cause.

  Slice 004's runs add the replay `route` and `forced=true`. The level of the line:
  - INFO for `SENT` and `NOT_APPLICABLE`;
  - raised to WARN when the switch was defaulted, or the 2xx was not 200;
  - ERROR for `PARKED` and `FAILED`.

  Every failure path — a parked or failed leg, a dead-letter, an unexpected exception, a failed
  settlement — MUST write a sanitised ERROR line and increment a metric (DV-06). (Digest §13 Logs;
  DV-06; A3-23, A3-45)
- **FR-002-064**: The signals MUST let slice 006's alert rules page only for genuine issues, and
  count the known ones (H34):

  | Paged (the rule is slice 006's) | Backing series from this slice |
  |---|---|
  | a leg parked for more than 30 min | `probation_oldest_parked_age` |
  | a request non-terminal for more than 30 min (silent drop) | `probation_oldest_non_terminal_age` |
  | a send still failing after its last delivery, or a rules exception | `probation_leg_outcomes_total{status="FAILED", reason=~"gateway-transient\|payload-unavailable\|rules-exception"}`; slice 004's `payload-changed` and `rolled-back` join it until Q28 is decided |
  | repeated 401s | `probation_leg_outcomes_total{reason="receiver-rejected", http="401"}` |
  | a hearing sent on an assumed switch value | the rate of `probation_youth_switch_unreadable_total` |
  | a send re-run after a crash | `probation_crash_reruns_total` |
  | a spike in case-hearing 400s | the daily `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` |

  | Counted, not paged | Series |
  |---|---|
  | application-only 400s | `probation_leg_outcomes_total{reason="receiver-rejected", shape="application-only"}` |
  | case-hearing 400s | `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` |
  | 500s recovered by a later delivery; 429s recovered in-run | `probation_gateway_recovered_total{after}` |
  | 2xx answers other than 200 | `probation_gateway_non200_success_total{status}` |

  The DLQ-depth alert reads the broker's own dead-lettered-message metric, not a series of this
  service. (H33, H34; A3-114, A3-115, A3-116; digest §13)
- **FR-002-065**: These privacy rules MUST hold:
  - no log line at INFO or above, and no metric, trace or exception message, carries payload or
    defendant data;
  - no payload is logged at any level in a deployed environment. A local-only DEBUG dump, behind an
    explicit local-profile guard, is the sole exception;
  - the outbound body, sent or stored, is never logged at any level in any profile, and is never a
    metric label;
  - raw exception text, a far end's words and secrets (the subscription key, the cache key,
    credentials, connection strings) are never logged.

  The privacy tests copied from the sibling services, a telemetry privacy test and a log-statement
  sweep, MUST pass over every log statement. (DV-09; A3-60; constitution Principle VII)

**Configuration**

- **FR-002-066**: The service MUST validate at startup every digest §12 property that this slice
  uses, with the checks digest §12 gives, and refuse to start, naming the property, when one fails.
  That covers:
  - the `results`, `usersgroups`, `youth-switch`, `hmpps`, `gateway`, `budget`, `redis`,
    `servicebus`, `store` and `claim` groups;
  - the workload identity, outside `local`, and the datasource;
  - the scaffold additions: `distribution.servicebus.connection-string` (exactly one of it and
    `namespace` is set), `distribution.consumer.enabled` and `distribution.redis.key-prefix`.

  Slice 004 validates the resend cap, and slice 003 the body-retention properties. (Digest §12;
  A2-56; R1-16)
- **FR-002-067**: Startup MUST also refuse a configuration whose composed stages do not fit. The
  figures in brackets are the defaults:

  | Check | Must fit |
  |---|---|
  | each client's connect + read timeout | its stage (results 35 s ≤ 60 s; users-groups 7 s ≤ 25 s; gateway 65 s ≤ 130 s) |
  | the payload stage: two cache reads at connect + command timeout, plus one query attempt | the payload budget (55 s ≤ 60 s) |
  | every retry block's initial and maximum back-off | its stage |
  | the send stage: (attempts − 1) × (connect + maximum back-off) + connect + read | the leg budget (95 s ≤ 130 s) |
  | the switch stage: attempts × (connect + read) + (attempts − 1) × wait | the switch budget (23 s ≤ 25 s) |
  | payload + switch + leg budgets | below the processing deadline (215 s < 240 s) |

  (A2-56, A2-69, A3-96, A3-15, A3-22)
- **FR-002-068**: One system-user id, the service's own, from the platform's secret store, MUST
  supply both `distribution.results.system-user-id` and `distribution.usersgroups.system-user-id`.
  (H30; A3-106)
- **FR-002-069**: The properties the design leaves out of this release MUST NOT exist: no
  contract-validation mode (H13), no App Configuration connection string or label (H22, H26), no
  `distribution.vep.*` (H5). (Digest §12)
- **FR-002-070**: No secret, connection string, key or environment-specific value may be committed.
  Deployed values arrive as environment variables from the platform's secret store, and committed
  configuration carries placeholders only. (Constitution Principle VII; the repository is public)

**The shared core**

- **FR-002-071**: The application core MUST depend only on ports it owns — the payload source, the
  youth-filter source, the HMPPS sender and the outbound log (the design's `HearingPayloadSource`,
  `YouthFilterSource`, `HmppsSender` and `OutboundLog`). It depends on no broker, cache, HTTP-client
  or database type. The one permitted exception is the hearing payload, which crosses the core as a
  JSON tree. (§4.4; D5; constitution Principle V)
- **FR-002-072**: Each closed vocabulary MUST be defined once, in the shared core, for every slice
  to use:
  - leg, leg status, leg reason, attempt origin (which also serves as the run origin), payload
    source, youth-filter decision with the four recorded switch values, unreadable cause, publish
    outcome, completion reason, failure reason, dead-letter reason, and the note forms;
  - the label sets rev 3.6 adds: HTTP bucket, body shape, intake-suspension cause, non-200 status,
    recovery `after` (`429-in-run`, `5xx-redelivery`), replay route (`service`, `support`, `copy`),
    resend refusal (`unknown-request`, `not-eligible`, `resend-in-progress`, `request-claimed`) and
    maintenance step (`lock`, `create`, `drop`).

  The type names for the rev 3.6 sets are chosen here: `HttpStatusBucket`, `BodyShape`,
  `IntakeSuspensionCause`, `Non200SuccessStatus`, `GatewayRecovery`, `ReplayRoute`,
  `ResendRefusal` and `BodyMaintenanceStep`. For the proposed series of FR-002-057, two more sets
  are defined here, with the values slice 005 lists: `OperationsAction` (`replay`, `resend-leg`)
  and `OperationsFailure` (`audit-unavailable`, `audit-response-unpublished`, `store-unavailable`,
  `broker-unavailable`, `unexpected`). Three of the four `ResendRefusal` values
  (`unknown-request`, `not-eligible`, `resend-in-progress`) also serve the replay action's
  refusals (004's Assumption 2). The whole-run and action outcome codes — `not-run`,
  `intake-paused`, `intake-not-paused`, `too-many-request-ids`, `missing-argument`,
  `unreadable-argument`, `argument-too-long`, `store-unavailable`, `broker-unavailable`,
  submit-hearing's `payload-unavailable`, `enqueued` and `no-op` — are slice 004's own closed set
  (004's Key Entities), never row values; slice 005 renders the ones its two endpoints can give,
  in its API description's closed enums and reason set. Parsing a wire string into any vocabulary
  MUST fail explicitly on an unknown value. (Digest §8, §13; technical rules, Enums)
- **FR-002-073**: The leg structure MUST stay the extension point. `Leg` has one value, `HMPPS`; the
  runner iterates the legs the eligibility table allows, and never branches on whether a leg is
  HMPPS. Nothing VEP-shaped exists. (A3-1; H5)

**Deviation register**

- **FR-002-074**: Each deviation this slice lands MUST be registered in `doc/DEVIATIONS.md`, which
  slice 001 seeds, with a pinning test that fails against the legacy behaviour and passes here. The
  carried, pending-external and moot defects this slice touches MUST keep the class the design gives
  them:

  | Id | What this slice does | Pinning evidence (the design's proposed names, fixed at plan time) |
  |---|---|---|
  | DV-04 | gateway timeouts; in-run retries for a connection failure and a 429 only | `GatewayRetryPolicyTest`; `ReadTimeoutParksIT`, `Http408ParksIT`, `ConnectFailureRetriedIT`, `RateLimited429RetriedIT` |
  | DV-05 | the leg's outcome reflects the real answer | `GatewayPublisherOutcomeTest`; `LegOutcomeIT` |
  | DV-06 | failures logged at ERROR and counted | `FailureSignalIT` |
  | DV-07 | a Redis connection failure is a miss, then the query, then a park | `RedisDownFallbackIT`; the resend half is slice 004's (004's FR-004-050a) |
  | DV-08 | duplicates and republishes are not re-sent, by the guard alone | `DuplicateDeliveryIT`; `RequestDedupeIT` |
  | DV-09 | no payload or defendant data at INFO or above, service-wide | `TelemetryPrivacyTest`; `LogStatementSweepTest` |
  | DV-10 | a 2xx query answer with an empty body, `{}` or non-JSON is a miss | `ResultsQueryExternalHearingClientTest` (empty body, empty object) |
  | DV-13 | a reconnecting cache client, with its timeouts applied | `RedisDownFallbackIT` (recovery); `LivePayloadConfigTest` (timeouts) |
  | DV-14 | the cache's TLS peer is verified | `LivePayloadConfigTest` (peer verification) |
  | DV-16 | a closed schema; an invalid message is dead-lettered `validation` | `ContractValidationDeadLetterIT` |
  | DV-17 | the fallback runs without `userId`, as the system user | `QueryFallbackWithoutUserIdIT`; the resend half is slice 004's (004's FR-004-050a) |
  | DV-23 | the results read's retry block and timeouts | `ResultsQueryExternalHearingClientTest` (retry, status matrix, timeout) |
  | DV-24 | users-groups timeouts of 2 s and 5 s | `FeaturePermissionClientTest` (timeout) |
  | DV-25 | an unreadable switch defaults, and the hearing is sent | `YouthSwitchUnreadableIT`, for every cause and for the `INACTIVE` default. Corpus case 13's derivation is slice 001's |
  | P17 (carried) | the first permission decides; ambiguity is counted | `FeaturePermissionClientTest` (first permission decides; ambiguity counted) |
  | P19 (carried) | a rule that throws is `FAILED(rules-exception)` | `RulesExceptionIT` |
  | P07 (pending external; D4a keeps the shared key) | the same subscription key value, from the service's own setting | `GatewayConfigTest` |
  | P36 (pending external) | failures logged at ERROR and counted: the signals the alerting reads exist | `FailureSignalIT` (the service-side half); the deployed alert rules are slice 006's (006's FR-006-021) |
  | P01 (moot) | no client certificate | `GatewayPublisherTest` (sends no client certificate) |

  (Digest §4; design §7.1, §7.2; D1)

### Key Entities *(include if feature involves data)*

- **Distribution command**: the queue message for one share. Its fields are `source`, `requestId`,
  `hearingId`, `hearingDay`, `sharedTime`, `eventType` and an optional `userId`. The schema is
  closed. The command is a claim check: the payload is read separately. Its identity is
  `(source, requestId)`, and its fingerprint covers the four immutable fields.
- **Processed request**: one per identity. It holds:
  - the share's identity and fingerprint;
  - its status, with the matching reason;
  - a lifetime count of runs;
  - the request claim;
  - the admitted command, verbatim;
  - an append-only audit note;
  - the replay marker, which only slice 004's replay sets.

  It answers the question "was this share processed?".
- **Leg attempt**: one row per leg per attempt; this slice writes attempt 1. It holds:
  - its status and reason, and the gateway's status;
  - the switch value applied;
  - the payload source, and the `sharedTime` read;
  - the body digest, the build version and the send time;
  - a bounded note.

  It also holds resend fields, which slice 004 uses.
- **Latest attempt**: the highest attempt per leg. It is the leg's truth.
- **Stored body**: the exact bytes POSTed by an attempt that sent a body. The database checks them
  against the digest. They are kept by month, for 6–7 months.
- **Hearing payload**: results' external view of the hearing, in the cache form or the fallback
  form. It is read and redacted, and never stored or logged.
- **Youth-switch reading**: `ACTIVE`, `INACTIVE` or `UNREADABLE(cause)`, recorded as one of four
  values. `ACTIVE` removes youth defendants.
- **Publish outcome**: `SENT(status)`, `FAILED_NON_TRANSIENT(status)` or `TRANSIENT_EXHAUSTED`,
  with the bytes sent.
- **Dead-letter entry**: the message-level outcome of a delivery the service settles by
  dead-lettering it, with reason `validation`, `collision`, `exhausted` or `leg-exhausted`.
- **Run origin**: `queue`, `replay` or `resend`. It is fixed at admission, and labels a run's signals
  and rows.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-002-001**: In the end-to-end suite, every valid command for a new share ends with exactly one
  HMPPS attempt row in a recorded outcome, and every delivery is settled exactly once: 100% of runs,
  0 unsettled deliveries, 0 lock expiries.
- **SC-002-002**: For a share whose leg is `SENT` or `NOT_APPLICABLE`, sequential, concurrent and
  republished copies produce 0 extra POSTs. The documented crash window is the one exception, and
  it is counted.
- **SC-002-003**: 100% of contract-invalid messages are dead-lettered on their first delivery, with
  reason `validation`, and leave no processed-log record.
- **SC-002-004**: For 100% of the attempts that sent a body, the stored body is byte-identical to
  the bytes the receiver stub received, and hashes to the attempt's digest. No attempt that sent
  nothing has a body row, and no attempt has more than one after any number of re-runs.
- **SC-002-005**: With the default settings, no stage outlasts its budget (60 s, 25 s, 130 s), and no
  message's stages together exceed 215 s, below the 4-minute deadline. Each stage, driven past its
  budget, ends in its defined outcome.
- **SC-002-006**: An outcome-unknown gateway answer (5xx, 408, read timeout) produces exactly 1 POST
  per delivery. A connection failure or a 429 produces at most 3.
- **SC-002-007**: For each unreadable cause, 100% of hearings are still sent. The body equals the
  default-state body, and the assumption is recorded on the row and counted.
- **SC-002-008**: Support can answer "was this hearing sent to HMPPS on this day, when, with what
  answer, and with exactly which bytes" with one lookup by hearing id and day, for any send within
  the retention window.
- **SC-002-009**: The privacy sweeps find 0 log statements at INFO or above carrying payload or
  defendant data, 0 statements at any level carrying an outbound body, and 0 metric label values
  outside the closed sets.
- **SC-002-010**: All 14 deviations this slice lands (FR-002-074), and all 5 carried,
  pending-external or moot defects it touches (P17, P19, P07, P36, P01), each name a pinning test
  that passes.
- **SC-002-011**: During a database outage, 0 messages are dead-lettered before their final
  permitted delivery. Intake resumes within one probe interval (10 s by default) of the database
  answering.
- **SC-002-012**: There is 0 drift between the database's value sets and the code's vocabularies,
  asserted on every build. The repository's full build and quality gates pass, with coverage at or
  above the constitution's thresholds.

## Assumptions

- Names the design marks *proposed* are kept, unless the plan records a change: environment
  variables, metric names, test names. The technical rules fix them at build time.
- The platform's database is PostgreSQL 15 (A3-105), which supports the digest trigger on a
  partitioned table.
- `gateway_request_id` stays NULL until the go-live test call identifies which response header, if
  any, carries a gateway request id (§11.3; digest §16). Capturing it then is a small change to the
  publisher.
- Whether the legacy passed on a non-JSON 2xx query body is not established (digest §16). Treating
  it as a miss stands. If the legacy did pass it on, the difference is registered as DV-27, and
  behaviour does not change.
- Volume is not measured. Concurrency 2 per pod and 1–2 replicas are starting points (O-2). Nothing
  here depends on a measured rate.
- These are plan-level choices within the closed registry:
  - the placeholder value of the `reason` label for a status with no reason;
  - the JSON paths that `shape` reads (`hearing.courtApplications` and `hearing.prosecutionCases`,
    read as present and non-empty).
- `probation_gateway_request_duration` times each POST attempt, answered or not. The age gauges are
  computed from the database, from a request's `created_at` and a leg row's `updated_at`, on a
  refresh schedule the plan sets.
- The results producer sends one command per non-SJP `Hearing_Resulted` share. Normally the command
  carries a `userId`, with `messageId = RESULTS:{requestId}` and `correlationId = hearingId` (digest
  §14). The service takes `requestId` from the wire.
- The payload's shape belongs to results, and is consumed as it is: there is no typed model, and
  unknown fields survive. `EXT_` keys live about 91 days.
- The default `ACTIVE` reflects the live switch setting the design assumes at cutover. Operations
  change the property if the live switch changes (A3-24, A3-29).
- The scaffold already provides the local stack — the broker emulator, the database, the cache and
  the HTTP stubs — and the configuration. This slice adds behaviour, not tooling.
- The interim rule of FR-002-008 holds only from this slice's merge to slice 004's. No environment
  beyond development runs in that interval.
- Stored bodies are not column-encrypted (A3-60). Informing the data owner and the DPIA is a note
  that slice 006 carries, not a gate here.
- A parseable payload with no `hearing`, or `hearing: null`, is recorded `FAILED(rules-exception)`,
  the request `COMPLETED`, nothing sent (Edge Cases): the redaction reports a rules failure in R1
  (001's FR-001-007 (d)) and FR-002-030 records it. The legacy drops such a payload before the
  switch read and only logs it (`PF/index.js:9-11`), and the closed reason vocabulary has no reason
  for such a skip, so the outcome is raised with the design owner, as 001's Dependencies hand it
  over. It is an open item, not a marker: the outcome stands until the design owner says
  otherwise. Like any `rules-exception`, it reaches slice 006's leg-failed alert.
- 003's open question 1 (FR-003-011: how an expired month is removed; its option C drops the
  DEFAULT partition) is put to the design owner before V1 is written. V1 keeps
  `outbound_body_default` (A3-63) unless the answer is C. An answer that arrives after V1 has been
  applied is a V2 migration, never an edit of V1 (technical rules, Migrations).

## Dependencies

**Slices**

- **001-parity-kernel** (required by User Story 1, and by everything after it): the HMPPS redaction
  as a pure function, R0–R11 (R12 not ported, DV-09), with the effective switch state as an input
  and four result kinds (001's FR-001-007); the comparator and corpus used by the parity tests; and
  `doc/DEVIATIONS.md`, seeded with DV-01 to DV-26. 001 also hands this slice the outcome of a
  parseable payload with no `hearing` (`PF/index.js:9-11`): recorded `FAILED(rules-exception)`
  here, and raised with the design owner (Edge Cases; Assumptions).
- **003-body-retention** builds on V1's `outbound_body`. It keeps the months ahead, purges after
  `P6M`, and clears DEFAULT rows. Its open question 1 (FR-003-011: how an expired month is removed;
  its option C drops the DEFAULT partition) is put to the design owner before V1 is written. V1
  keeps `outbound_body_default` (A3-63) unless the answer is C; an answer that arrives after V1 has
  been applied is a V2 migration, never an edit of V1 (FR-002-054; Assumptions).
- **004-recovery** builds on the guard, the runner, V1, the vocabularies and the registry. It
  replaces FR-002-008 with replay admission, and adds the replay and resend columns, their
  statements, and recovery after a resend (A2-38).
- **005-operations-api** builds on slice 004's actions.
- **006-production-readiness** builds on the signals of FR-002-057 to FR-002-064: alert rules,
  dashboards and reconciliation. It also proves this slice at go-live (§9.2 rows 1, 3, 7, 9 and 21).

**External**

- The results producer increment (H26): a flag-gated publish to `probation.requests`. Until it
  lands, tests build commands by hand.
- These platform asks (digest §15):
  - ask 1: the queue and its DLQ, with duplicate detection off, a 5-minute lock and
    `maxDeliveryCount` 5;
  - ask 2: the identity's receiver role (the sender role serves slice 004's enqueuing actions);
  - ask 5: the database, owned by the service role. Access to it, and so to `outbound_body`, is
    limited to the service role and the security-cleared operations team (A3-60);
  - ask 6: the secrets — the gateway key, the cache key, the datasource credentials, the system
    user id and the telemetry connection string;
  - ask 6a: the path to users-groups, with the system user permitted;
  - ask 9: the path to the gateway, with its certificate authority in the truststore;
  - ask 16: the service's own system user.
- These services, all unchanged: users-groups and its `EMYouthFilter` data; the results query API;
  the gateway's HMPPS route; the HMPPS receiver.

## Out of Scope

- A VEP leg in any form (H5).
- Any Event Grid input or subscription: the queue is the only trigger (H3).
- Outbound schema validation (H13).
- Reading `ProbationResultsDistributionService`, or any App Configuration (H26).
- Any change to what is sent (D10, D16 (a)), including P21 and P35, which are pending external.
- These belong to slice 004:
  - replay by either route, DV-26 and the route record;
  - resend-leg, the forced resend and the `sharedTime` resend check;
  - recovery after a resend;
  - operator pause and resume, submit-hearing, purge-queue and the runners;
  - the dead-letter runbook.

  The rollback close-in-flight statement is slice 004's too (004's FR-004-049a); its executor is
  open (004's FR-004-049; §9.4 step 4).
- Partition maintenance and purge (slice 003).
- The HTTP operations API (slice 005).
- These belong to slice 006: alert rules and routing, dashboards, reconciliation, the SIT → STE
  replay, cutover, rollback and decommissioning.
- The parity kernel itself (slice 001).
- The producer increment, the LAA feed, the Results Store, SJP hearings, the NOWs app, any gateway
  change, and the receivers.
- Measuring volume, and tuning concurrency or thresholds.

## Design traceability

| Design id | Where it is specified |
|---|---|
| H2, D5 | FR-002-015, FR-002-071 |
| H3 | FR-002-001; Out of Scope (no Event Grid input) |
| H5, A3-1 | FR-002-069, FR-002-073 |
| H7, A3-11, D3a | FR-002-020, FR-002-021, FR-002-022 |
| H8, DV-25, A3-23, A3-25, A3-27, P16 | FR-002-024, FR-002-025, FR-002-058, FR-002-063, FR-002-074; User Story 4 |
| A3-24, A3-29 | FR-002-026 |
| A3-13, A3-14, A3-22, DV-24 | FR-002-023, FR-002-074 |
| A2-28 (gap) | FR-002-024 (open) |
| H11, H15, A3-43, A3-54, DV-04 | FR-002-035, FR-002-036, FR-002-074; User Story 5 |
| H12, A3-45, A3-47 | FR-002-036, FR-002-039 |
| H13, A3-50 | FR-002-031, FR-002-057, FR-002-069 |
| H14, A3-53, DV-08 | FR-002-001, FR-002-007, FR-002-074; User Story 2 |
| A3-55 (the noise part of H15) | FR-002-049 |
| H17 (the recorded part), A3-58 | FR-002-019, FR-002-040, FR-002-043 |
| H18, A3-59, A3-60 | FR-002-033, FR-002-042, FR-002-065; Assumptions; Dependencies (ask 5) |
| H19, A3-61, R1-10 | FR-002-033, FR-002-040, FR-002-043 |
| H20 (V1), A3-63, A3-64 (V1 part), A3-105 | FR-002-042, FR-002-054 |
| H21 | FR-002-050 (no shadow table) |
| H26, Q15, A3-92 | FR-002-027 |
| H27, A3-95, A3-96 | FR-002-034, FR-002-067 |
| H30, A3-106, DV-17, Q14 | FR-002-017, FR-002-021, FR-002-068, FR-002-074 |
| H33, H34, A3-114, A3-115, A3-116 | FR-002-059, FR-002-060, FR-002-061, FR-002-064 |
| D1, D10, D16 (a) | FR-002-019, FR-002-028; User Story 1, scenario 4 |
| D4a, D4b, P07, P01 | FR-002-032, FR-002-074, SC-002-010 |
| Q17 | FR-002-001, FR-002-014 |
| O-2 | FR-002-001; Assumptions |
| A2-50, R1-23 | FR-002-006, FR-002-052 |
| R1-11, R1-26, R1-28b | FR-002-050, FR-002-051, FR-002-055 |
| R1-16, A2-56 | FR-002-066, FR-002-067 |
| R1-18, A2-57 | FR-002-010, FR-002-022, FR-002-062 |
| R1-30, A2-69 | FR-002-013, FR-002-067 |
| R1-31 | FR-002-059 |
| R2-7 | FR-002-011, FR-002-012 |
| A2-27 | FR-002-044 |
| A2-34 | FR-002-007, FR-002-008, FR-002-047 |
| A2-35 | FR-002-044, FR-002-051 |
| A2-39, A2-58, A2-68 | FR-002-040, FR-002-041 |
| A2-41, A3-7 | FR-002-044 |
| A2-47 | FR-002-045 |
| A2-48 | FR-002-006, FR-002-050 |
| A2-49 | FR-002-053 |
| A2-54, A2-61, A2-66, R2c-2 | FR-002-043, FR-002-052 |
| A2-55, DV-16 | FR-002-002, FR-002-003, FR-002-007 |
| A2-60 | FR-002-012, FR-002-044, FR-002-050 |
| A2-64 | FR-002-012 |
| A2-67; A2-51 (`RollbackFencingIT`'s late leg-UPDATE case, with slice 004) | FR-002-007, FR-002-040; Clarifications |
| A2-71 | FR-002-010, FR-002-059 |
| A3-30, A3-34, DV-26 | FR-002-004, FR-002-008 (deferred to slice 004) |
| DV-05, DV-06 | FR-002-036, FR-002-037, FR-002-063, FR-002-074 |
| DV-07, DV-13, DV-14 | FR-002-016, FR-002-074 |
| DV-09 | FR-002-019, FR-002-028 (R12 not ported), FR-002-065, FR-002-074 |
| DV-10, DV-23 | FR-002-017, FR-002-074 |
| P17 | FR-002-022, FR-002-074 |
| P19 | FR-002-030, FR-002-074 |
| P36 (the service-side half; the deployed alerting is slice 006's) | FR-002-063, FR-002-074, SC-002-010 |
| 001 hand-off (`PF/index.js:9-11`): a parseable payload with no `hearing` | FR-002-030; Edge Cases; Assumptions; Dependencies (001) |
| 003's open question 1 (FR-003-011): the DEFAULT partition, decided before V1 is written | FR-002-054; Assumptions; Dependencies (003) |
| (not in the design; proposed by slice 005) — `probation_operations_failures_total` and its two label sets | FR-002-057, FR-002-072 |
| §4.4 claim contract (f) | FR-002-040 |
| §4.5 eligibility table, delivery column | FR-002-012 |
| §4.7 time budget | FR-002-013, FR-002-014, FR-002-067 |
| App. B.1 | FR-002-002 |
| App. B.2 | FR-002-050 to FR-002-056 |
| Digest §12 | FR-002-066 to FR-002-070 |
| Digest §13 | FR-002-057 to FR-002-065 |
| (gap; no design id) — the gateway answer outside 2xx, 4xx and 5xx | FR-002-038 (open) |
