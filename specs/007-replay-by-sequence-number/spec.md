# Feature Specification: Replay a parked request by broker sequence number

**Feature Branch**: `007-replay-by-sequence-number`
**Created**: 2026-11-19
**Status**: Draft
**Input**: User description: "Port the informant register's 'replay a parked request by broker
sequence number' change to this service, adapted to how this service differs. Here the guard
already gives a same-`messageId` dead-letter copy one admission chance on its first delivery
(DV-26, FR-004-001/FR-004-002). Establish the gap that remains, and specify only that: identify the
parking delivery by the broker sequence number, make replay a compare-and-set on the failure
generation, make a re-parked copy say why the request failed, read broker metadata once inside the
settlement boundary, and amend the replay rule in the contract." Written against design rev 3.6 and
`doc/design/DIGEST.md` §4 (DV-26), §7 (state machine: idempotency, settlement and replay admission) and §11 (the
dead-letter runbook), and against slice 004's FR-004-001 … FR-004-005 and FR-004-057. Where they
differ, the design wins, and this slice depends on one agreed design rule being amended (Dependencies).

## Context

When a request's HMPPS leg is still parked on the final permitted delivery, the service records the
request `FAILED(leg-exhausted)`, stores the `messageId` of that delivery as `exhausted_message_id`,
and dead-letters the message `leg-exhausted`. The route operations prefer for getting it back is the
dead-letter "Re-send" in the Azure portal's Service Bus Explorer, which copies the entry back onto
`probation.requests` as it stands — body, application properties and `messageId` (`RESULTS:{requestId}`)
included. Broker duplicate detection is off (H14), so every such copy reaches the guard.

The guard tells "a deliberate resubmission" from "the delivery that parked the request coming round
again" by DV-26 (FR-004-001; design A2-62, A3-30): a delivery whose `messageId` equals
`exhausted_message_id` is admitted as a replay only on its first delivery (broker delivery count 1 in
the design, `0` in this listener's zero-based convention), and is otherwise dead-lettered
`exhausted`. FR-004-002 makes this deliberate: a same-id copy has **exactly one admission chance**.
That rule already avoids the informant register's defect — an unchanged Re-send is not refused on
sight — but it leaves three gaps:

- **The one chance is spent by faults that have nothing to do with the copy.** Two paths hand a
  delivery back *before* the guard can judge it, and each hand-back raises the delivery count: the
  store precondition failing (the listener abandons the delivery unexamined and asks intake to stop),
  and an unexpected failure while the guard reads the processed log. The redelivery then carries
  count 1 or more and the exhausted `messageId`, so it is dead-lettered `exhausted` without the
  request ever being re-run. (A deferral over a `PENDING` resend row is not part of this gap: FR-004-002
  orders the identity test first precisely so that a deferred copy is re-dead-lettered on its next
  delivery. A lost replay race is not either: after it the request is `RECEIVED`/`RETRYING`,
  `COMPLETED`, or `FAILED` again by a newer parking that the copy predates.)
  Support is told to "clear the cause and resend once more" (`doc/runbooks/dead-letter-recovery.md`),
  although for a store outage the cause was never the copy's. The test the guard really wants —
  *was this message sent after the request was parked?* — is not the delivery count.
- **The parking delivery is recognised only indirectly.** What the count test is protecting against
  is the parking delivery itself coming round again (released by a closed connection while its
  dead-letter operation was in flight). It is caught today only because, with the delivery budget
  pinned to 5, a parking delivery normally carries a count of 4 or more. The broker offers an exact
  identity for "the same stored message": the sequence number it assigns on accept, unchanged by
  redelivery and new for every accepted send — a portal Re-send included.
- **A re-parked copy cannot be told apart on the dead-letter queue.** It is dead-lettered `exhausted`
  with no description: the same entry a duplicate copy produces when a live claim bounced it until its
  budget ran out. Support has to query the processed log to find out that the request is
  `FAILED(leg-exhausted)` and the copy merely missed its chance.

This slice keeps DV-26's first-delivery chance exactly as it is and adds a second, narrower way in: a
same-id copy that the broker **enqueued after the request was parked** stays a resubmission however
many times it was handed back, while the delivery that parked the request is recognised exactly by its
sequence number. Because the new limb depends on the request's parking time, the replay statement
becomes a compare-and-set on the failure generation the delivery observed.

This is a change to **transport recovery**, the sanctioned area of behavioural change. The HMPPS
rules, the redaction, the body and every successful path are untouched. It widens an existing
registered departure (DV-26) rather than adding a new one.

### Why the new limb complements DV-26 rather than replacing it

The informant register replaced its `messageId` test outright with a sequence-number test: any
accepted send other than the parking delivery is a resubmission. This service MUST NOT do that,
because here duplicate detection is off and duplicate copies are ordinary traffic — a producer
republish or an owed event redelivered on the producer side is enqueued while the original run is
still in progress, meets its live claim, and is abandoned again and again (constitution, "accepted
noise", H15, A3-55). Under a pure sequence-number rule such a copy, coming round after the request
had parked, would carry a different sequence number and replay the request on its own, with nobody
having asked — and a `FAILED(gateway-transient)` leg is one the runbook says must be confirmed with
HMPPS before it is sent again. Today a copy under the exhausted `messageId` is re-parked when its
count is above 0. The new limb keeps it re-parked, since it was enqueued *before* the request was
parked; for copies under the exhausted `messageId` it admits only what was sent afterwards, which is
what a deliberate resubmission is. (Copies under any other `messageId` keep FR-004-001's
new-identity limb at any count, unchanged: when a service replay has re-parked a request under
`REPLAY:<uuid>`, a pre-parking `RESULTS:` copy still replays it, as it does today.)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A re-sent copy survives a hand-back that was not its fault (Priority: P1)

Support re-sends a `leg-exhausted` dead-letter entry unchanged, some time after the request was
parked. Its first delivery is handed back before the guard can admit it — the processed log is
briefly unreachable, or the guard's read fails. When the copy is
delivered again, the service still recognises it as a resubmission and replays the request.

**Why this priority**: This is the gap. A store blip during a recovery session currently turns every
copy in flight into a fresh `exhausted` dead-letter entry that has to be found and re-sent again.

**Independent Test**: Park a request, wait longer than the safety margin, re-send its dead-letter
entry unchanged, make the store precondition fail for its first delivery only, and observe that the
redelivery (count 1) is admitted as a replay with route `copy`.

**Acceptance Scenarios**:

1. **Given** a request `FAILED(leg-exhausted)` with exhausted `messageId` X, parked at time P, **When**
   a delivery arrives under X with a sequence number other than the parking one, a delivery count of
   1 or more, and a broker enqueue time later than P plus the safety margin, **Then** it is admitted as
   a replay exactly as FR-004-004 describes: `FAILED` → `RECEIVED`, `replayed_at` set, the
   `REPLAYED_AFTER_FAILURE prior=leg-exhausted` and route lines appended, `attempts` incremented, and
   `probation_replays_total{route="copy"}` raised by one.
2. **Given** the same request, **When** that copy's first delivery met a store outage and was
   abandoned unexamined, **Then** its redelivery after the store recovers is admitted as in scenario 1.
3. **Given** the same request, **When** a delivery under X arrives at count 0, **Then** it is admitted
   as a replay whatever its enqueue time — DV-26's first-delivery chance is unchanged.
4. **Given** a replay admitted by either limb, **When** support searches the logs for the request,
   **Then** one line records the admission with the parking sequence number (or `none`), the
   delivery's sequence number, delivery count and enqueue time, and which limb admitted it —
   identifiers only.

---

### User Story 2 - The delivery that parked the request is never re-run (Priority: P1)

The protection DV-26's count test gives must survive, and become exact. If the parking delivery's
dead-letter operation was not accepted and the same stored message returns, it is recognised by its
sequence number and dead-lettered again without running.

**Why this priority**: Re-running it would take a deliberately parked request past its delivery
budget with nobody having asked, and could send to HMPPS a leg that must first be confirmed with them.

**Independent Test**: Park a request, deliver the same stored message again (same sequence number) at
any delivery count, and observe that nothing runs, no row changes, and the message is dead-lettered.

**Acceptance Scenarios**:

1. **Given** a request `FAILED(leg-exhausted)` that recorded the sequence number S of its parking
   delivery, **When** a delivery carrying S arrives, at any delivery count and with any enqueue time,
   **Then** it is dead-lettered `exhausted`, nothing is run and the request row is unchanged.
2. **Given** the same situation, **When** it is re-parked, **Then** exactly one explicit settlement
   attempt is made for that delivery.

---

### User Story 3 - A duplicate copy from before the failure does not replay it (Priority: P1)

A producer republish or owed-event copy that was already on the queue while the original run held the
claim, and was handed back for that reason, does not replay the request when it comes round after the
request has parked.

**Why this priority**: With duplicate detection off this is the common case, and it is the reason this
service cannot adopt the informant register's rule unchanged.

**Independent Test**: Enqueue a copy under X while the request is `RETRYING`, let it be abandoned on
the live claim, let the request park, and deliver the copy again; observe a re-park.

**Acceptance Scenarios**:

1. **Given** a request `FAILED(leg-exhausted)` parked at P, **When** a delivery under X arrives with a
   sequence number other than the parking one, a count of 1 or more, and an enqueue time not later
   than P plus the margin, **Then** it is dead-lettered `exhausted` and the request row is unchanged.
2. **Given** the same request, **When** such a delivery has no enqueue time, **Then** it is
   re-parked; only the count-0 limb could have admitted it.

---

### User Story 4 - Requests parked before this change keep working (Priority: P2)

A request parked before this change has no recorded parking sequence number. Its copies are judged by
the same rule, without the exact sequence-number check: count 0 admits, a post-parking enqueue admits,
anything else re-parks. The parking delivery of such a request was enqueued before it parked and
carries a count of 4 or more, so it is still re-parked.

**Why this priority**: Requests already on the dead-letter queue at go-live of this change are the
first ones support will want to recover.

**Independent Test**: Seed a `FAILED(leg-exhausted)` row with no parking sequence number; deliver a
copy under X at count 1 enqueued after the margin (admitted), one at count 1 enqueued before parking
(re-parked), and one at count 0 (admitted).

**Acceptance Scenarios**:

1. **Given** a `FAILED(leg-exhausted)` request with no recorded parking sequence number, **When** a
   delivery under X arrives at count 1 or more with an enqueue time later than the parking time plus
   the margin, **Then** it is admitted as a replay.
2. **Given** the same request, **When** a delivery under X arrives at count 1 or more with an enqueue
   time not later than the parking time plus the margin, or none, **Then** it is re-parked.

---

### User Story 5 - A re-parked copy says why the request failed (Priority: P3)

When a delivery is dead-lettered because its request is already `FAILED`, the dead-letter entry
carries a description naming the request's recorded failure reason, so support can tell a re-parked
copy from a delivery whose budget a live claim used up, without querying the store.

**Why this priority**: It does not block recovery, but it is the first thing support reads.

**Independent Test**: Trigger a re-park of a `FAILED(leg-exhausted)` request and read the dead-letter
reason and description on the entry.

**Acceptance Scenarios**:

1. **Given** a request `FAILED(leg-exhausted)`, **When** a delivery for it is re-parked, **Then** the
   dead-letter reason is `exhausted` and the description is `leg-exhausted`.
2. **Given** a delivery dead-lettered `exhausted` on its final permitted delivery by a hand-back (a
   live claim, a store outage, an unexpected failure, a stale runner, a deferral), **When** it is
   read, **Then** it carries no description, exactly as today.
3. *(Defensive, unit-tested only; unreachable under the schema's CHECK.)* **Given** a stored failure
   reason the contract does not list, **When** a delivery is re-parked, **Then** it is dead-lettered
   `exhausted` with no description, a sanitised error records the anomaly without quoting the stored
   text, and nothing throws.

---

### Edge Cases

- **Re-send during a resend run.** Today the copy is deferred `REPLAY_DEFERRED` once, on its first
  delivery, and dead-lettered `exhausted` at count 1 by the identity test (FR-004-002, intended). A
  copy enqueued within the margin of parking behaves exactly so. A copy enqueued after the margin now
  passes the identity test on every delivery, so it is deferred on each and dead-lettered `exhausted`
  on its final permitted one (FR-004-003's final-delivery rule); `probation_replay_deferred_total`
  rises once per delivery instead of once. Abandon redelivers at once, so the copy still does not
  wait for the resend to finish; support re-sends it once the resend run has ended, or replays
  through the API.
- **Message expiry.** The queue's time-to-live is one hour with dead-lettering on expiry
  (`docker/servicebus-emulator/config.json:10-11`). A store outage suspends intake
  (`ProbationMessageListener.java:158-160`); a copy held on the queue for longer than an hour expires
  to the dead-letter queue without reaching the service, and nothing is recorded. Support re-sends it
  once intake has resumed.
- **Two re-sends of one entry** (a double click). Both arrive at count 0; the replay statement admits
  one, the other is handed back. On its redelivery it is judged afresh: a `RECEIVED`/`RETRYING`
  request follows the claim rules, a `COMPLETED` one is a no-op, and a request that has failed again
  is re-parked, because that copy was enqueued before the newer failure.
- **A stale admission decision.** A delivery that read the row before another replay ran and the
  request parked again must not replay the newer failure on what it read. The replay statement
  succeeds only against the failure generation the delivery observed (FR-007-007).
- **Re-send soon after parking.** Within the safety margin of the parking time only the count-0 limb
  is available, which is today's behaviour; a hand-back then costs the copy its chance, as now.
- **Service replay and portal Re-send racing.** The service's replay message has a fresh `messageId`
  and is judged by the `messageId ≠ exhausted_message_id` limb at any count, unchanged. Whichever
  admission commits first wins; the other is handed back and judged afresh.
- **A dead-lettered service replay message re-sent from the portal.** It keeps its own `REPLAY:…`
  `messageId` and `origin = REPLAY`. If that replay was what parked the request, the id equals
  `exhausted_message_id` and the new rule applies to it exactly as to a producer-id copy; route
  `service` is recorded on admission.
- **Submit-hearing for a parked share.** It is published under `RESULTS:{requestId}` with
  `origin = SUPPORT`, so it meets the same rule; it is enqueued after parking, so it now also survives
  a hand-back. Its audit markers are written exactly as FR-004-057 says.
- **`FAILED(rolled-back)`.** It has no `exhausted_message_id` and no parking delivery, so every copy is
  admitted (FR-004-001, unchanged) and the new fields stay empty.
- **Broker metadata cannot be read.** Retrieving the message, or reading its sequence number,
  delivery count or `messageId`, throws. The delivery still receives exactly one explicit settlement
  attempt, an abandon. A missing enqueue time is not a failure: it is captured as absent, logged as
  `none`, and disables only the post-parking limb.
- **Queue deleted and recreated.** Sequence numbers restart, so a new message could carry a recorded
  parking sequence number. That can only cause a false re-park — visible and recoverable by re-sending
  again — never a false replay.
- **Broker clock ahead of the database clock by more than the margin.** A pre-parking duplicate copy
  could pass the post-parking limb. The margin (default 5 minutes) is chosen to make that implausible;
  even then the replay re-runs only `FAILED` and `PARKED` legs, as DV-26 already accepts at count 0.
- **Rolling deployment.** An older consumer can still park requests without recording a sequence
  number; those rows are handled as User Story 4 describes. An older consumer's replay admission or
  recovery does not clear the new column, so a non-`FAILED` row can carry a stale value; the guard
  reads it only on a `FAILED` row, and every `FAILED` write of this version overwrites it (NULL
  included). A `FAILED` row written by an older consumer over a stale value can at worst re-park a
  copy carrying that old sequence number — a false re-park, never a false replay.

## Requirements *(mandatory)*

### Functional Requirements

**Recording the parking delivery**

- **FR-007-001**: When a delivery moves a request to `FAILED(leg-exhausted)` — the post-run write that
  records the leg's exhausted outcome and the request's transition together, and the reclaim path that
  owes only the request transition — the service MUST record the broker sequence number of that
  delivery in the same write that sets `FAILED` and `exhausted_message_id`. `exhausted_message_id`
  keeps its meaning and stays required with `leg-exhausted`.
- **FR-007-002**: The parking sequence number MUST be written only under the request claim, as the
  `FAILED` transition already is, so a stale runner cannot write it. Every statement of this version
  that writes `FAILED` MUST overwrite it — with the parking sequence number for `leg-exhausted`, and
  with NULL for the rollback close-in-flight's `FAILED(rolled-back)`, which no delivery parks — and
  every statement that moves a request out of `FAILED` (replay admission, recovery by resend) MUST
  clear it. The guard MUST read it only when the row is `FAILED`.

**Deciding a delivery of a `FAILED` request** — replaces FR-004-001's predicate; FR-004-002's order
is kept

- **FR-007-003**: A delivery whose sequence number equals the request's recorded parking sequence
  number MUST be dead-lettered `exhausted`, whatever its `messageId`, delivery count or enqueue time,
  with no state change and no run. This check runs first.
- **FR-007-004**: A delivery whose `messageId` differs from `exhausted_message_id`, or of a request
  whose `exhausted_message_id` is NULL, MUST pass the identity test at any delivery count, as today.
- **FR-007-005**: A delivery whose `messageId` equals `exhausted_message_id` (and whose sequence
  number is not the recorded parking one, or none is recorded) MUST pass the identity test when
  **either** its delivery count is 0 (DV-26's first-delivery chance, unchanged) **or** its broker
  enqueue time is later than the time the request was parked by more than a configured safety margin
  (default 5 minutes). Otherwise — including when the enqueue time is absent and the count is not 0 —
  it MUST be dead-lettered `exhausted` with no state change. The parking time is the request's
  `updated_at` as written by the `FAILED` transition, on the database clock.
- **FR-007-006**: Passing the identity test MUST remain necessary, not sufficient: the deferral check
  of FR-004-003 then runs as today, and only then the replay statement. The identity test MUST still
  run before the deferral check (FR-004-002's order). FR-004-002's consequence — "if its first
  delivery is not admitted, its redelivery is dead-lettered `exhausted`" — is narrowed to copies
  enqueued no later than the parking time plus the margin.
- **FR-007-007**: The replay statement MUST be a compare-and-set on the failure generation the
  delivery observed: besides its existing conditions (status `FAILED`, no `PENDING` resend row as the
  leg's latest, under the request-row lock), it succeeds only if `attempts` and the parking sequence
  number (compared null-safely) are still those the guard read. If it does not succeed, the delivery
  MUST be handed back exactly as a lost race is today — abandoned, or dead-lettered `exhausted` on its
  final permitted delivery.
- **FR-007-008**: An admitted replay MUST keep FR-004-004 (as amended by FR-007-005 and FR-007-007,
  whose predicate is now its statement's condition), FR-004-005 and FR-004-057 otherwise unchanged:
  the same transition and columns, the parking sequence number cleared alongside `failure_reason` and
  `exhausted_message_id`, the appended audit lines, the route label read only for audit, and the
  submit-hearing markers in the same transaction.
- **FR-007-009**: Each admission and each re-park of a `FAILED` request MUST be logged with the
  request identifiers, the parking sequence number (or `none`), the delivery's sequence number,
  delivery count and enqueue time (or `none`), and the deciding rule (`parking-delivery`,
  `first-delivery`, `enqueued-after-parking`, `new-identity`, `not-after-parking`). No PII.

**Re-park description**

- **FR-007-010**: A re-park under FR-007-003 or FR-007-005 MUST keep the dead-letter reason `exhausted`
  and its count on `probation_deadlettered_total{reason="exhausted"}` (the reason set stays closed,
  A2-34), and MUST set the dead-letter description to the request's stored failure reason when it is
  one the contract lists (`leg-exhausted`). Hand-back dead-letters keep no description. (A stored
  reason outside the list cannot occur — the `failure_reason` CHECK admits only `leg-exhausted` and
  `rolled-back`, and `rolled-back` has no exhausted id — so the defensive branch, no description, a
  sanitised error log and no exception, is proven by a unit test only.)

**Broker metadata**

- **FR-007-011**: The received message and its broker metadata — sequence number, delivery count,
  `messageId`, enqueue time and application properties — MUST be read once per delivery, inside the
  boundary that guarantees a settlement attempt, and every later use (log context, route, support
  markers, the guard, the final-delivery check) MUST use the captured values. Today the message, its
  delivery count and its sequence number are read before that boundary, and the application
  properties are read more than once.
- **FR-007-012**: A failure to read the message or any required item (sequence number, delivery count,
  `messageId`) MUST still produce exactly one explicit settlement attempt, an abandon, since the final
  delivery cannot be recognised without the count. An absent enqueue time MUST be captured as absent
  and is not a failure.

**Contract, deviation and schema**

- **FR-007-013**: The replay rule in `doc/API_CONTRACTS.md`, the dead-letter reasons table there, and
  the DV-26 row of `doc/DEVIATIONS.md` (its predicate and pinning test) MUST be amended **before** the
  code, and only once the agreed design amendment has been made (Dependencies). No new DV id is
  allocated: this widens DV-26, the same departure. Normal publishing keeps the deterministic
  `messageId`, and the service replay keeps minting a fresh one.
- **FR-007-014**: The schema change MUST be additive and forward-only: one nullable column for the
  parking sequence number, valid for every existing row. A `FAILED(leg-exhausted)` row without it MUST
  stay valid (rows parked before the change, and rows written by an older consumer during a rolling
  deployment); the service MUST NOT assume a migration prevents one. No constraint may tie the column
  to the request's status, because an older consumer's replay admission and recovery do not clear it.
- **FR-007-015**: The safety margin MUST be configuration, validated at startup against a floor of
  1 minute, with a default of 5 minutes. It absorbs clock skew between the broker and the database
  only; the informant register's bound by the lock duration does not apply here, because no limb of
  this rule depends on lock expiry.

**Quality gates**

- **FR-007-016**: Every requirement above MUST be proven by a test written before the code, covering
  at least:
  - the parking sequence number re-parks at count 0, at count ≥ 1 and with a post-parking enqueue time;
  - same `messageId`, other sequence number: count 0 admits; count ≥ 1 enqueued after parking plus the
    margin admits; enqueued at exactly the margin, before it, or with no enqueue time re-parks;
  - a copy abandoned on a store outage and on a failing guard read is admitted on its redelivery when
    enqueued after the margin; one enqueued before parking is re-parked; a copy that lost the replay
    race meets the claim rules, a no-op, or a re-park against the newer failure;
  - a post-margin copy deferred over a `PENDING` resend row is deferred on each delivery and
    dead-lettered on its final one, and a within-margin copy is dead-lettered at count 1, as today;
  - a duplicate copy enqueued while the request was `RETRYING` is re-parked after the request parks;
  - a legacy row with no parking sequence number, for each limb;
  - a new `messageId` and a `rolled-back` request are admitted at any count (unchanged);
  - two concurrent copies admit one replay; a decision read before a replay–park cycle cannot replay
    the newer failure; the compare-and-set matches a null parking sequence number;
  - a stale runner cannot write the parking sequence number, and every exit from `FAILED` clears it;
  - submit-hearing (`origin = SUPPORT`) and service-replay (`origin = REPLAY`) copies record their route
    and markers unchanged;
  - a re-park carries reason `exhausted` and description `leg-exhausted`; an unlisted stored reason
    gives no description, no thrown exception and no quoted text; hand-backs carry no description;
  - each broker accessor is read once; a failing accessor still gives one settlement attempt;
  - the migration leaves existing rows valid, and a margin below the floor is refused at startup;
  - a stale column value on a non-`FAILED` row (an older consumer's replay) is ignored, and the
    rollback close-in-flight writes NULL;
  - against the Service Bus emulator: the sequence number is unchanged across an abandon, a copy sent
    to the queue again gets a new sequence number and a new, populated enqueue time, and a first
    delivery reports delivery count 0
    through the client library this service uses.

### Key Entities

- **Processed request** (`processed_request`): gains the **parking sequence number** — the broker
  sequence number of the delivery that moved it to `FAILED(leg-exhausted)`. Set with that transition,
  absent on rows parked before this change and on `FAILED(rolled-back)`; overwritten by every
  `FAILED` write and cleared on replay and on recovery by this version, and read only on a `FAILED`
  row, since an older consumer may leave a stale value on a row it moves out of `FAILED`.
  `exhausted_message_id`, `failure_reason`, `attempts`,
  `audit_note`, `replayed_at` and `updated_at` keep their meaning; for a `FAILED` row `updated_at` is
  the time it was parked.
- **Delivery facts**: what the guard decides on for one delivery — `messageId`, sequence number,
  delivery count, optional enqueue time and the replay route — captured once by the listener.
- **Dead-letter reason**: unchanged closed set (`validation`, `collision`, `exhausted`,
  `leg-exhausted`); a re-park now also carries a description.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-007-001**: Every unchanged dead-letter re-send of a `leg-exhausted` entry, made more than the
  safety margin after the request parked, is admitted as a replay even when one or more of its
  deliveries were handed back before admission.
- **SC-007-002**: No run is started by a delivery carrying the parking sequence number recorded for the
  current `FAILED` generation.
- **SC-007-003**: No copy under the exhausted `messageId` that was enqueued before the request parked
  replays it on any delivery other than its first (zero across the test matrix), so this change admits
  nothing DV-26 refuses today except same-id copies enqueued after parking.
- **SC-007-004**: No leg recorded `SENT` or `NOT_APPLICABLE` is sent again as a result of a replay, a
  re-send, or a race between them (SC-004-002 continues to hold).
- **SC-007-005**: Every re-parked copy of a `FAILED(leg-exhausted)` request shows the description
  `leg-exhausted` on the dead-letter queue, and every hand-back dead-letter shows none.
- **SC-007-006**: Every delivery, including one whose broker metadata cannot be read, receives exactly
  one explicit settlement attempt.

## Assumptions

Each was checked against the code, the queue definition or the existing specifications.

- **First-delivery convention.** The listener treats a first delivery as count 0
  (`IdempotencyGuard.java:138-140`; `ProbationMessageListener.java:343-355`), and DV-26's "count 1" is
  that 0. A copy re-sent from the dead-letter queue is a new message, so its count starts again. The
  emulator test of FR-007-016 confirms this against the client library rather than assuming it.
- **Parking normally happens late.** `distribution.servicebus.max-delivery-count` must equal 5
  (`application.yaml:74`; `StartupValidator.java:286`), and the run parks a request only on the final
  permitted delivery (count ≥ 4). The reclaim path (`IdempotencyGuard.java:210-211` →
  `ProbationMessageListener.java:258-259`) has no final-delivery condition, so a parking delivery is
  only normally at count ≥ 4; it is always enqueued before the request parks. FR-007-003's
  sequence-number check does not rely on either.
- **The parking delivery returns only on release.** If the final delivery's lock is lost, the broker's
  count reaches the budget and it dead-letters the message itself; the parking delivery reaches the
  service again only when released without an increment (a closed connection). Today it is then
  re-parked by the count test (`IdempotencyGuard.java:142-143`), and FR-007-003 keeps that.
- **Hand-backs before admission.** The store precondition abandons unexamined
  (`ProbationMessageListener.java:116-120`, `173-184`), and intake is then suspended (`158-160`); a
  guard-read failure is handed back (`121-141`). Each raises the delivery count. A lost replay race
  (`IdempotencyGuard.java:155`) and a deferral (`ProbationMessageListener.java:233-238`) also hand
  back, but are not gaps (Context).
- **Duplicate detection is off** (H14; `docker/servicebus-emulator/config.json:16`; API contract
  "Every copy reaches the guard"), so duplicate copies are enqueued while a run holds the claim, and
  the broker never discards a re-send.
- **Sequence numbers.** The broker assigns one on accept; it is unique within the queue, unchanged by
  redelivery, and new for every accepted send, a portal re-send included (broker documentation, not
  code; FR-007-016's emulator test pins it). The queue has no sessions
  (`docker/servicebus-emulator/config.json:17`); the rule uses equality only, never ordering. The
  listener already reads it for logging (`ProbationMessageListener.java:106`).
- **Enqueue time.** A re-sent copy gets a new enqueue time (broker documentation; pinned by the
  emulator test). The client exposes it, as `DeadLetterQueuePeeker.java:49` already reads it. The
  comparison is broker clock against the database clock; the margin absorbs the difference.
- **Generation.** While a row is `FAILED` nothing changes its `attempts` or `updated_at`: the
  audit-note appends of a refused or forced resend touch neither
  (`OutboundMessageRepository.java:589-592`; `JdbcProcessedRequestRepository.java:314-317`), and the
  other updates match only `RECEIVED`/`RETRYING` (`JdbcProcessedRequestRepository.java:40`). Every
  path out of `FAILED` and back in increments `attempts` (replay admission, `:147`; reclaim, `:440`). That makes `attempts` with the parking sequence number a sufficient
  generation, and `updated_at` the parking time.
- **Replay statement today.** It is guarded by `status = 'FAILED'` and the `PENDING` check under a
  row lock, but not by any observed value (`JdbcProcessedRequestRepository.java:133-163`). With the
  count-only rule a stale read changes nothing, because the decision does not depend on the row's
  history; with FR-007-005 it does, hence FR-007-007.
- **Service replay is unaffected.** It mints `REPLAY:<uuid>` (`ReplayAction.java:96`), which never equals
  the exhausted id of a producer-parked request.

## Dependencies

- **Design amendment (gating).** FR-004-002 and the dead-letter runbook's "one admission chance" are
  the design's own rule (A3-30; DIGEST §11 "Same-id copies"). The design owner has agreed the change;
  A3-30, A2-62's predicate and the H9/DV-26 wording on the Confluence design page, and their digest
  in `doc/design/DIGEST.md`, MUST be amended before `/speckit.plan`. This specification does not
  edit the design.
- Slice 002's settlement and hand-back rules (FR-002-007, FR-002-009) and slice 004's replay
  admission, deferral and support-origin rules (FR-004-001 … FR-004-005, FR-004-057) are the base this
  slice changes.

## Out of Scope

- Turning broker duplicate detection on, or any change to producer publishing.
- Changing the closed dead-letter reason set, the replay action, resend-leg, or `close-expired-resend`.
- Letting a copy wait out a `PENDING` resend row instead of being dead-lettered after its budget.
- Backfilling parking sequence numbers for requests parked before this change.
- Removing re-parked copies or replayed originals from the dead-letter queue.
- Any change to the HMPPS rules, the redaction, the body or the outbound send.

## Documents the implementation must update

Not edited in this specification's PR, except the constitution (noted below). The implementation
updates them, contract first:

- `doc/API_CONTRACTS.md` — the replay rule (the "Resubmission from the dead-letter queue" bullet and
  "What happens on replay"), and the `exhausted` row of the dead-letter reasons table (the re-park
  limb and the description).
- `doc/DEVIATIONS.md` — the DV-26 row: predicate and pinning test.
- `doc/runbooks/dead-letter-recovery.md` — "A copy under the exhausted `messageId` has one admission
  chance", and the `exhausted` row's description.
- `doc/design/DIGEST.md` — DV-26 and its guard predicate (§4), replay admission (§7), "Same-id
  copies" (§11), after the design amendment.
- `specs/004-recovery/spec.md` — FR-004-001, FR-004-002, FR-004-004 (amended by FR-007-007), US2
  scenarios 2 and 4, SC-004-003, and the
  2026-10-21 DV-26 clarification; `specs/004-recovery/data-model.md` for the new column.
- `.specify/memory/constitution.md` — **amended in this specification's PR** (2.0.1 → 2.1.0): the
  Principle I DV-26 clause, the dead-letter item and the two-replay-routes item, ratified by the
  design owner. Listed here so the implementation does not amend it again.
- `.claude/rules/design_rules.md`, `.claude/rules/technical-rules.md` and the `qa`, `code-reviewer`,
  `spec-validator` and `software-engineer` agent definitions where they state the DV-26 rule.
- `doc/CHANGELOG.md`.
- Code: `application/IdempotencyGuard.java`; `inbound/ProbationMessageListener.java`;
  `persistence/ProcessedRequestRepository.java` (`RequestRecord`, `markFailed`, `admitReplay`) and
  `JdbcProcessedRequestRepository.java` (`find`, `lockedAdmitReplay`, `markFailed`);
  `persistence/OutboundMessageRepository.java` (`markRequestFailedExhausted`, `recoverFailedRequest`);
  `persistence/JdbcRollbackFencingRepository.java` (the `FAILED(rolled-back)` write, lines 102-107);
  the parking delivery's identity threaded through `application/DistributionRunner.java`,
  `application/OutboundLog.java` and `adapter/persistence/OutboundLogAdapter.java` as
  `deliveryMessageId` is today; `config/DistributionProperties.java` and
  `config/StartupValidator.java` for the margin; a new Flyway migration after
  `V4__close_null_reason_hole.sql`.
- Tests that pin the count-only rule: `IdempotencyGuardTest`, `ProbationMessageListenerTest`,
  `DlqResubmissionReplayIT`, `RequestReplayStatementTest`, `DeadLetterTestSupport`,
  `RunbookCrossReferenceIT`.
- The Confluence design page — raised with its owner, not edited as part of this work.
