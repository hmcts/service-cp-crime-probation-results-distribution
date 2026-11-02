# Feature Specification: Operator recovery — replay, resend-leg and the operator actions

**Feature Branch**: `004-recovery`  
**Created**: 2026-10-21  
**Status**: Draft  
**Input**: Design rev 3.6 (2026-10-20; decisions H1–H34), whose named source of truth is the
Confluence page "Probation Results Distribution Service". Rules taken from `doc/design/DIGEST.md`
§3 (H9, H14–H17), §4 (DV-26 and the recorded guard and statement departures), §5 (application
properties; idempotency and settlement), §6 (the resend run's order; time budgets), §7 (eligibility
by run kind, replay admission, re-runs, claims and fencing, recovery), §8 (`command`, `replayed_at`,
the `audit_note` events, the resend columns, the `note` forms), §11 (operator actions 1–6, the
resend payload check, the dead-letter runbook, the `dlq-completed` listing), §13 (the replay and
resend metrics and log fields), §14 (the `requestId` recipe and its pinned vector) and §16 (open
items), each checked against the design. Slice 004 of the approved slicing; it depends on 002.

## Context

A send to HMPPS can fail, and HMPPS can need a hearing again. Design rev 3.6 gives operations three
ways back (H9), each bounded so that a leg recorded `SENT` or `NOT_APPLICABLE` is never sent a
second time by accident:

1. **Dead-letter resubmission** — the route operations prefer. A dead-lettered message is
   resubmitted with the broker's tools, and the guard admits the copy as a replay of its `FAILED`
   request — even under the `messageId` that exhausted it, provided the broker is delivering that
   copy for the first time (DV-26, a registered departure from the informant guard).
2. **Service replay** — the service puts the command it stored at admission back on the queue,
   byte for byte, under a fresh `messageId`. It needs no dead-letter copy, so it is the only route
   back after a rollback has purged the queue.
3. **Resend-leg** — the HMPPS leg is re-run for an explicit list of `requestId`s (H16), each checked
   on its own, after confirming by `sharedTime` that the hearing has not been re-shared since (H17).
   It is the only route for a leg of a request whose completion is already recorded. Its
   forced-resend override sends a leg already `SENT` or `NOT_APPLICABLE` again, on the operator's
   name and reason: a deliberate, genuine duplicate at HMPPS (H9 (c), H12).

A replay re-runs only what failed or is still waiting. A resend never moves a completed request,
and one that brings a `FAILED` request's leg to a terminal outcome records the request
`COMPLETED(recovered)`.

Around these sit the other operator actions of design §4.8 that recovery and rollback use:
**pausing and resuming intake** — the service's only stop, step 1 of every rollback; **purging** the
paused queue; and **submit-hearing**, which enqueues a normal command, marked as support, for a
share the service never admitted. Two read-only aids serve the procedures: the request listing and
the listing of dead-letter entries whose request has completed. Every action is a command-line
runner in the image, running as the pod; the optional flag check is not built. The HTTP exposure of
replay and resend-leg is slice 005, and the other actions stay runners while O-1 is open.

The pipeline the replayed, resent and submitted runs reuse — the guard's other paths, the payload
source, the youth switch, the redaction, the send, the stored body and the settlement statements —
is slice 002's, the redaction coming from 001. This slice adds no deviation: it pins DV-26, already
in the design's register, and the resend halves of DV-07 and DV-17 (FR-004-050a). It also states
the rules of rollback step 4's close-in-flight statement (FR-004-049a), whose executor is still
open (FR-004-049).

## Clarifications

### Session 2026-10-21

- Q: Does resend-leg select requests by date range, status or a maximum count, as earlier
  revisions had it? → A: No. H16 withdrew that selector with its arguments. Resend-leg takes an
  explicit `requestId` list only, capped by `distribution.resend.max-ids-per-run`; filtering by
  status, reason or `updated_at` belongs to the separate, read-only selection step — the request
  listing or the documented query on the latest attempts (H16; A3-56; §4.8 action 1).
- Q: Must a dead-letter resubmission wait out a duplicate-detection window, or have its
  `messageId` edited? → A: No. Broker duplicate detection is off, so A3-31's five-minute caution is
  superseded: a copy is never dropped by the broker, and DV-26 admits a same-id copy at delivery
  count 1 (H14; A3-53; §4.8 runbook).
- Q: Which deliveries does DV-26 admit under the exhausted `messageId`? → A: Any at broker delivery
  count 1 — an operator's resubmission, a producer republish of the same share, an owed event
  redelivered on the producer side, or a submit-hearing command. All are admitted as replays,
  accepted explicitly by the design because a replay re-runs only `FAILED` and `PARKED` legs (A3-30;
  DV-26).
- Q: Does the replay route change what a replay does? → A: No. `service`, `support` and `copy` are
  audit labels; behaviour keys on `replayed_at` (A3-34; A2-60; A2-68).
- Q: What does a replay of a `COMPLETED` request do? → A: Nothing, by either route. The service's
  action enqueues nothing and reports a no-op; a copy that reaches the guard is completed with
  nothing re-run (H9; A3-36; §4.8 action 2).
- Q: Where does the service's replay take its body from? → A: `processed_request.command`, byte for
  byte, with `userId` exactly as the original carried it — never from the dead-letter queue and
  never rebuilt (A2-48; §4.2 replay rule).
- Q: Why must the replay `messageId` still be fresh, now that no broker window exists? → A: To keep
  the replay distinct from the exhausted identity, so its own redeliveries are admitted at any
  delivery count, and for audit and route labelling (A3-53; §4.2 replay rule).
- Q: Is the service's replay refused while intake is paused? → A: No; it is available at any time
  (A3-35). After a rollback it is the route back once the service is re-enabled (§9.4 step 6a). A
  replay enqueued while intake is paused waits, and a roll-forward purge may remove it (Edge
  Cases).
- Q: What does the replay action answer for a request it cannot replay? → A: The design names one
  code, `resend-in-progress` (A2-70, A2-72), and replays "`FAILED` requests only" (§4.8 action 2).
  For a missing request and for one still `RECEIVED` or `RETRYING` this spec reuses the design's
  resend codes `unknown-request` and `not-eligible` (A3-56) instead of coining new ones — the same
  choice slice 005 renders.
- Q: Which latest rows are resend-eligible? → A: `FAILED` with any reason except an attempt-1
  `FAILED(rolled-back)`; `SENT` or `NOT_APPLICABLE` only with `allowTerminalSuccess`; anything else
  is `not-eligible` (§4.5 table; A3-56; A2-40; R1-28b).
- Q: Which refusals stop a whole resend run and which a single ID? → A: The design refuses the
  whole run for a list over the cap and while intake is paused (H16; A2-40). `unknown-request`,
  `not-eligible`, `resend-in-progress` and `request-claimed` are per ID, decided request first and
  leg second (A2-65; A3-56).
- Q: How does a resend decide that the payload is "the same share"? → A: It compares the payload's
  `sharedTime` with the stored `shared_time` as instants, at the stored precision, directly after
  the fetch; different, absent or unparseable is `FAILED(payload-changed)` unless
  `allow-changed-payload` is given (H17; A3-57; A2-24).
- Q: Does a resend made after the cache entry expired need `allow-changed-payload`? → A: No. The
  query API's form carries the same instant for the same share, so it proceeds; the post-expiry
  override rule is withdrawn (H17; A3-57).
- Q: Can a resend park? → A: No. There is no delivery to park against, so a payload miss and a
  transient send failure are `FAILED` at once (R2-8; DV-07; H15).
- Q: Which user does a resend's query-API read run as? → A: Always the service's own system user,
  because the request row holds no `userId` (DV-17; H30).
- Q: Does `allowTerminalSuccess` act on the whole run or per ID? → A: Per ID. It only lets through
  an ID whose latest row is `SENT` or `NOT_APPLICABLE`; `override = true`, the `override=force-resend`
  form, `forced = true` and the reason line are recorded for those IDs only, because the
  `override` column means "an override was used" (A3-36; §4.6; §10.1).
- Q: Does a forced resend change its request? → A: No. Completion is historical: the request stays
  `COMPLETED`, and a failed forced resend leaves the latest row `FAILED`, alerted and resend-eligible
  again (A2-27; A2-49; A3-36).
- Q: A3-36 says a forced resend "validates" the body. Is there a validation stage? → A: No.
  Outbound validation is parked: the stages are fetch, `sharedTime` check, switch, redaction, body
  digest and send (H13; A3-50).
- Q: When does a resend recover a `FAILED` request? → A: When the leg's latest row is `SENT`,
  `NOT_APPLICABLE` or `FAILED` with a class-1 reason (`receiver-rejected`, `rules-exception`);
  class-2 outcomes leave it `FAILED` (A2-38; R1-8).
- Q: When is a replayed request completed `recovered` rather than `both-legs-terminal`? → A:
  Whenever `audit_note` holds the `REPLAYED_AFTER_FAILURE` line — also when the replay's first run
  parks and a later redelivery settles it (A2-41; A2-47).
- Q: Does the replay action need the broker's sender role, though design §8.2 grants it to
  submit-hearing only? → A: Yes. §8.2 is read by H9: the workload identity's sender role on
  `probation.requests` (platform ask 2) serves both actions that enqueue (DIGEST §16).
- Q: Where is a normal (unforced) resend's reason recorded? → A: The design does not say (A3-56,
  listed in DIGEST §16 as "not established — a default stands"). Default taken here: the reason is
  required on every resend but recorded only for a forced resend, whose reason the design appends to
  `audit_note`; a normal resend's reason is not persisted and never logged (Assumption 4). Flagged
  for the design owner.
- Q: What becomes of a replay deferred over a resend? → A: The broker redelivers it at once, with
  no abandon delay (H15), so it normally uses up its deliveries within seconds and is dead-lettered
  `exhausted`; the operator replays again once the resend has finished, which is a no-op if the
  resend recovered the request (A2-70; A2-72; A3-55).
- Q: Does the cap count the IDs as listed or the distinct IDs? → A: The distinct IDs. This is this
  slice's reading, shared with 005 (its assumption 12): duplicates are collapsed first and the cap
  applies to the distinct IDs. The design states both rules without fixing their order (DIGEST §11;
  A3-56).
- Q: What happens to an attempt's stored body when a replay re-runs the attempt in place? → A: The
  earlier body row is deleted and, when the re-run serialised a body, the new one is stored, in the
  same transaction as the outcome; a body row exists only for an attempt that has a `body_digest`
  (H20; A3-63; DIGEST §8).
- Q: Which `exhausted_message_id` does a replayed run record if it is exhausted again? → A: The
  `messageId` of the delivery that exhausted it — a service replay's fresh id, or the copy's own id
  for a dead-letter resubmission — so a later replay or resubmission is judged against that id
  (§4.4 settlement; DV-26).
- Q: Which actions besides replay and resend-leg does this slice own? → A: Pause and resume intake,
  purge-queue, submit-hearing, the request listing and the `dlq-completed` listing (§4.8 actions 1
  and 4–6; A3-33), each as a command-line runner. Slice 002 hands it the operator pause,
  submit-hearing, purge-queue and the runners; slice 006's rollback inputs use them. The optional
  flag check (action 7) is not built (H26; A3-92).
- Q: Where does submit-hearing read the hearing from? → A: From Redis only — the `EXT_` dated key,
  then the undated key — because the command's `sharedTime` must be the raw string the producer
  hashed, never parsed, re-rendered or read back from `shared_time`; with neither key present it is
  refused `payload-unavailable` and the hearing must be re-shared (§4.8 action 5; A2-4).
- Q: How does submit-hearing build the `requestId`? → A: With its own `RequestIdRecipe` — the
  name-based UUID of `hearingId|hearingDay|sharedTime` over the raw strings — pinned by the vector
  of results' own helper test, with no build dependency on a results artefact; the vector is
  re-taken when Q20 lands (A2-25; DIGEST §14).
- Q: Is a submit-hearing command a queue run or a replay? → A: A queue delivery — `origin = queue`
  in the metrics — unless it meets a `FAILED` request, when it is a replay with route `support`
  (A2-57; A2-71; DV-26). A `COMPLETED` request makes it a no-op.
- Q: When may purge-queue run? → A: Only while intake is paused; otherwise it is refused (R1-28b;
  §4.8 action 6).
- Q: When is intake resumed after a rollback? → A: Only at the end of the roll-forward: purge,
  reconcile every message received since the purge's cut-off, a full new cutover, then resume
  (A2-52; A3-117). The resume action cannot know where the roll-forward stands and does not check
  it; slice 006's roll-forward inputs carry the order (006's FR-006-043), and this slice's runbook
  does not restate it.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Replay a `FAILED` request from its stored command (Priority: P1)

An operator asks for a request that ended `FAILED` — exhausted on its final delivery, or closed by
a rollback — to be replayed. Through the replay runner in the image (or slice 005's endpoint), the
service checks the request and, if it may be replayed, puts the command it stored at admission back
on the queue under a fresh message identity marked as a replay. The replayed run re-runs only the
legs that failed or are still waiting, and when it ends terminally the request is recorded
`COMPLETED(recovered)`.

**Why this priority**: It is the only way back for a request whose queue messages a rollback purged
(§9.4 step 6a), and the general-use route (H9 (b)). Without it a `FAILED` request can be recovered
only from the dead-letter queue, and only while a copy is still there.

**Independent Test**: Seed a `FAILED(leg-exhausted)` request whose stored command carries a
`userId` and whose HMPPS attempt-1 row is `FAILED(gateway-transient)`; run the replay runner for it
with the gateway stub answering 200. Observe one enqueued message whose body equals the stored
command byte for byte, under a new `messageId`, marked `origin = REPLAY`; one POST; the attempt-1
row re-run in place to `SENT`; and the request `COMPLETED(recovered)`.

**Acceptance Scenarios**:

1. **Given** a `FAILED` request whose leg's latest row is not a `PENDING` resend row, **When** the
   operator replays it, **Then** exactly one message is enqueued whose body is the stored command
   byte for byte (`userId` present exactly when the original carried it), whose `messageId` has
   never been used for that request, with `correlationId` = the `hearingId` and the application
   property `origin = REPLAY`; the outcome `enqueued` names that `messageId`; and the action writes
   nothing to the store.
2. **Given** that message is delivered, **When** the guard admits it, **Then** the request moves
   `FAILED` → `RECEIVED`, `failure_reason` and `exhausted_message_id` are cleared, `replayed_at` is
   set, `audit_note` keeps every earlier line and gains `REPLAYED_AFTER_FAILURE prior=leg-exhausted`
   and a route line naming `service`, and `probation_replays_total{route="service"}` rises by one.
3. **Given** the leg's latest row is attempt 1 `FAILED(gateway-transient)`, **When** the replayed
   run sends and the gateway answers 200, **Then** that same row becomes `SENT` in one update with no
   `PENDING` written first, `attempts` is incremented, `note` holds `prev=FAILED/gateway-transient`
   and `rerun-by=<the replay messageId>`, the row's stored body is replaced by the bytes just sent,
   and the request is `COMPLETED` with `completion_reason = recovered` and `replayed_at` cleared.
4. **Given** the leg's latest row is a resend attempt (attempt 2) `FAILED(gateway-transient)`,
   **When** the replayed run reaches the leg, **Then** a new attempt 3 with
   `attempt_origin = replay` and no leg claim is appended and run, and attempt 2 is unchanged.
5. **Given** the replayed run finds no payload anywhere, **When** a later redelivery of the replay
   message finds it, **Then** each delivery re-runs the `PARKED` row, the request is `RETRYING`
   between deliveries with `replayed_at` kept, and the run that sends settles the request
   `COMPLETED(recovered)`; **Given** instead that the final permitted delivery still finds none,
   **Then** the leg is `FAILED(payload-unavailable)`, the request `FAILED(leg-exhausted)` with
   `exhausted_message_id` = the replay message's `messageId`, and the message is dead-lettered
   `leg-exhausted`.
6. **Given** a redelivery of a replay message whose leg's latest row is `FAILED` with a `note` that
   already names that message in `rerun-by`, **When** it is processed, **Then** the row is skipped:
   a `FAILED` row is re-run at most once per replay message.
7. **Given** the outcomes of a replayed run, **When** they are counted and logged, **Then** they
   carry `origin = replay`, `forced = false` and the `route`, also after settlement has cleared
   `replayed_at`.
8. **Given** a `COMPLETED` request, **When** the operator replays it, **Then** nothing is enqueued
   and the outcome is `no-op`.
9. **Given** a `FAILED` request whose leg's latest row is a `PENDING` resend row, live or expired,
   **When** the operator replays it, **Then** the action refuses `resend-in-progress` and nothing is
   enqueued.
10. **Given** an unknown `requestId`, or a request still `RECEIVED` or `RETRYING`, **When** the
    operator replays it, **Then** the action refuses `unknown-request` or `not-eligible`
    respectively, and nothing is enqueued.
11. **Given** a request closed `FAILED(rolled-back)` by the rollback protocol (no
    `exhausted_message_id`, no dead-letter copy), **When** the service is re-enabled and the operator
    replays it, **Then** the replay is admitted and the attempt-1 `FAILED(rolled-back)` row is re-run
    in place.
12. **Given** any run of the replay runner, **When** it ends, **Then** its result says whether it
    was done, refused with nothing changed, or failed; and the runner started no message intake and
    no scheduled task.
13. **Given** a replayed run still in flight when the rollback protocol clears the request's claim,
    **When** it writes its leg outcome, **Then** the write changes nothing,
    `probation_stale_runner_rejections_total` rises by one, and the message is not settled by that
    run.

---

### User Story 2 - A copy resubmitted from the dead-letter queue is admitted as a replay (Priority: P1)

Operations resubmit a dead-lettered message with the broker's tools. The tool may keep the original
`messageId` or give the copy a new one. The guard admits a fresh copy as a replay of its `FAILED`
request, records how it arrived, and is not fooled by the exhausted message coming round again.

**Why this priority**: It is the route operations prefer (H9 (a)) and a go-live precondition: SIT
negative testing must show a dead-letter resend admitted and processed (§9.2 row 21; H25). DV-26 is
a registered deviation and needs its pinning test (constitution Principles I and II).

**Independent Test**: Seed three `FAILED(leg-exhausted)` requests, each with its own exhausted
`messageId` X. To the first deliver a copy under its X at delivery count 1; to the second a delivery
of its X at count 2; to the third a copy under a new id. Observe: admitted; dead-lettered
`exhausted` with the request unchanged; admitted — each admission recording route `copy`.

**Acceptance Scenarios**:

1. **Given** a `FAILED` request exhausted by `messageId` X, **When** a copy with `messageId` X and no
   `origin` arrives at broker delivery count 1, **Then** it is admitted as a replay, route `copy` is
   recorded (an `audit_note` line, a log field, `probation_replays_total{route="copy"}`), and only
   the leg's `FAILED` or `PARKED` latest row is re-run.
2. **Given** the same request, **When** a delivery with `messageId` X arrives at delivery count 2 or
   more, **Then** it is dead-lettered `exhausted` and the request row is unchanged.
3. **Given** the same request, **When** a copy under a `messageId` other than X arrives, at any
   delivery count, **Then** it is admitted as a replay.
4. **Given** a copy under X whose first delivery is not admitted — deferred over a `PENDING` resend
   row, meeting a live claim, or hitting a store outage — **When** it is redelivered at count 2,
   **Then** it is dead-lettered `exhausted`, because the exhausted-identity test runs before the
   deferral check; the dead-letter queue then holds both the original and the copy.
5. **Given** a `FAILED` request whose leg's latest row is a `PENDING` resend row, live or expired,
   **When** a fresh copy is delivered, **Then** it is abandoned `REPLAY_DEFERRED`: the request stays
   `FAILED`, `replayed_at` stays NULL and `probation_replay_deferred_total` rises by one; on its
   final permitted delivery it is dead-lettered `exhausted`.
6. **Given** a copy carrying `origin = SUPPORT` under X at count 1 (a submit-hearing command for the
   same share), **Then** it is admitted with route `support`; **Given** a dead-letter copy of a
   service replay message, which keeps `origin = REPLAY`, **Then** it is admitted with route
   `service`.
7. **Given** the producer republishes the share of a `FAILED` request (same `messageId` X, count 1),
   **Then** it is admitted as a replay with route `copy`, and no leg whose latest row is `SENT` or
   `NOT_APPLICABLE` is sent again.
8. **Given** a `COMPLETED` request, **When** any copy arrives — a resubmission, a republish, a
   submit-hearing command or a service replay message — **Then** it is completed as a no-op: no leg
   is re-run and no row changes.
9. **Given** a request `FAILED(rolled-back)` (NULL `exhausted_message_id`), **When** any copy
   arrives at any delivery count, **Then** it is admitted as a replay.

---

### User Story 3 - Resend the HMPPS leg for an explicit list of requests (Priority: P2)

An operator who has chosen the requests to resend passes their `requestId`s, with their name and a
reason, to the resend-leg runner (or slice 005's endpoint). Each ID is checked on its own: the
eligible ones are re-run on a new attempt row and the others are refused with a code that says why;
the report lists every ID. A resend first confirms, by `sharedTime`, that the hearing has not been
re-shared since; it never parks; and when it brings a `FAILED` request's leg to a terminal outcome
the request is recorded `COMPLETED(recovered)`.

**Why this priority**: Resend-leg is part of the design (Q16) and the only way to re-run a leg of a
request whose completion is already recorded (§4.5, consequence 2). Replay (stories 1 and 2) covers
`FAILED` requests first, so resend-leg is the second route to deliver.

**Independent Test**: Seed two requests whose latest HMPPS row is `FAILED`, one request whose latest
row is `SENT`, one request under a live claim, and keep one ID unknown; run resend-leg on the five
IDs with the gateway stub answering 200. Observe two new `resend` attempt rows, refusals
`unknown-request`, `not-eligible` and `request-claimed`, five report entries, and the refusal
counter by code.

**Acceptance Scenarios**:

1. **Given** the mixed list above, **When** resend-leg runs with an operator and a reason, **Then**
   only the two eligible IDs get new attempt rows; the report lists all five IDs once each — the two
   with their resulting status and reason, the others with `unknown-request`, `not-eligible` and
   `request-claimed`; and `probation_resend_refusals_total` rises by one for each of those three
   codes.
2. **Given** a list with more distinct IDs than `distribution.resend.max-ids-per-run`, **When** it is
   submitted, **Then** the whole run is refused `too-many-request-ids` before anything is read or
   changed, and nothing is sent.
3. **Given** an ID listed twice, **When** the run is taken, **Then** it is run once and reported
   once.
4. **Given** intake paused by an operator, **When** a run is submitted, **Then** it is refused whole,
   `intake-paused`, and no ID is read or changed; **Given** a pause that begins while a run is in
   progress, **Then** the run claims no further ID and reports the IDs it had not reached as
   `not-run`.
5. **Given** an eligible ID, **When** the run claims it, **Then** a new attempt row — the previous
   attempt plus one, `attempt_origin = resend`, `PENDING`, with its leg claim, the run's
   `resend_run_id` and the operator — is committed before any POST, and no earlier row of the leg
   changes.
6. **Given** the cached payload has expired and the query API returns the same share with its
   `sharedTime` rendered differently, **When** the resend runs, **Then** it proceeds and sends, with
   `payload_source = query` and `payload_shared_time` recorded.
7. **Given** the payload's `sharedTime` is later than the request's stored `shared_time`, **When**
   the resend runs without `allow-changed-payload`, **Then** the attempt is `FAILED(payload-changed)`
   before any switch read (`youth_switch` NULL, no `body_digest`), nothing is sent, and a refusal
   line is appended to `audit_note`; **When** it runs with `allow-changed-payload`, **Then** it is
   sent and the row records `override = true` and `override=allow-changed-payload`.
8. **Given** a payload with no `sharedTime`, or one that does not parse, **When** the resend runs
   without the override, **Then** the attempt is `FAILED(payload-changed)` in the same way.
9. **Given** neither Redis nor the query API has the payload — Redis unreachable included — **When**
   the resend runs, **Then** the attempt is `FAILED(payload-unavailable)` at once — never `PARKED` —
   and the ID can be resent later.
10. **Given** the gateway answers 503, 408, or times out reading after the request was written,
    **When** the resend sends, **Then** there is one POST and the attempt is
    `FAILED(gateway-transient)` at once; **Given** a 429 and then a 200, **Then** the attempt is
    `SENT` after the in-run retry; **Given** a 400, **Then** it is `FAILED(receiver-rejected)`.
11. **Given** the youth switch cannot be read, **When** the resend runs, **Then** the configured
    default is applied, the leg carries on, and the row records `UNREADABLE_DEFAULTED_<value>`.
12. **Given** the payload comes from the query API, **When** it is read, **Then** the read carries
    the service's own system user as `CJSCPPUID`.
13. **Given** a `FAILED(leg-exhausted)` request, **When** its resend ends `SENT` — or
    `NOT_APPLICABLE`, `FAILED(receiver-rejected)` or `FAILED(rules-exception)` — **Then**, in the same
    transaction as the outcome, the request becomes `COMPLETED(recovered)`, `failure_reason` and
    `exhausted_message_id` are cleared, and both are appended to `audit_note`; **When** it ends
    `FAILED` with `gateway-transient`, `payload-unavailable` or `payload-changed`, **Then** the
    request stays `FAILED`, unchanged.
14. **Given** a `COMPLETED` request whose latest row is `FAILED(receiver-rejected)`, **When** it is
    resent and fails again, **Then** the request stays `COMPLETED` and the leg's latest row is the new
    `FAILED` row, resend-eligible again.
15. **Given** the latest row is attempt-1 `FAILED(rolled-back)`, **Then** the ID is `not-eligible`;
    **Given** the latest row is a closed resend row (attempt ≥ 2) `FAILED(rolled-back)`, **Then** it
    is eligible.
16. **Given** the latest row is `PARKED`, or a `PENDING` attempt 1 or a `PENDING` replay row under an
    expired request claim, **Then** the ID is `not-eligible`; **Given** either `PENDING` row under a
    live request claim, **Then** the ID is `request-claimed`; **Given** it is a `PENDING` resend row
    whose lease is live, **Then** it is `resend-in-progress`.
17. **Given** two resend runs and a late queue delivery competing for one leg, or a replay and a
    resend racing in either order, **When** they run, **Then** exactly one of them runs the leg and
    there is exactly one POST for it.
18. **Given** the rollback protocol rotated a resend attempt's claim token while the resend was in
    flight, **When** the resend completes, **Then** nothing changes and
    `probation_stale_runner_rejections_total` rises by one.

---

### User Story 4 - Work the dead-letter queue by a runbook the service makes true (Priority: P2)

Operations meet dead-letter entries with four reasons. A runbook published with the service tells
them which entries to resend, which never to resend, when to remove entries, and how to choose and
read a resend. The service's behaviour makes each rule of it true.

**Why this priority**: The "DLQ not empty" alert pages until entries are removed (§10.3). Resending
the wrong entry fails again (`validation`), hides a fault (`collision`) or is noise, and rollback
recovery depends on choosing the right route (A3-32, A3-33, §9.4).

**Independent Test**: Review the published runbook against FR-004-046 and FR-004-047, then run its
cases: an `exhausted` entry for a `COMPLETED` request resent → a no-op; an `exhausted` entry with
no `processed_request` row resent → processed as a first delivery; a `validation` entry resent →
dead-lettered `validation` again; after a replay completes, the original entry is still in the
dead-letter queue.

**Acceptance Scenarios**:

1. **Given** a `leg-exhausted` entry, **When** it is resubmitted, **Then** it is replayed as in
   story 2, and the runbook says so.
2. **Given** an `exhausted` entry whose request is already `COMPLETED`, **When** it is resubmitted,
   **Then** it is a no-op; the runbook says to remove it, not to resend it.
3. **Given** an `exhausted` entry with no `processed_request` row, **When** it is resubmitted,
   **Then** it is processed as a first delivery.
4. **Given** a `validation` entry, **When** it is resubmitted, **Then** it is dead-lettered
   `validation` again with no row written; **Given** a `collision` entry, **Then** it is dead-lettered
   `collision` again with the existing row untouched; the runbook says never to resend either.
5. **Given** a replay that completed, **When** the dead-letter queue is read, **Then** the original
   entry is still there until the operator removes it.
6. **Given** the runbook, **When** an operator prepares a resend, **Then** it tells them how to
   choose the IDs, how to read each report entry, that an outcome-unknown
   `FAILED(gateway-transient)` is confirmed with HMPPS before any re-send, and that stored bodies can
   be read for an investigation only within their retention window.

---

### User Story 5 - Send an already-sent hearing again, deliberately (Priority: P3)

When HMPPS needs again a hearing the service has already sent — or one the rules found nothing to
send for — an operator lists it for resend-leg with the forced-resend override, their name and a
reason. The leg is re-run on a new attempt row, the request's recorded completion is untouched, and
the duplicate is recorded as deliberate.

**Why this priority**: It is the rarest recovery and a genuine duplicate at HMPPS (H12). It is
decided (H9 (c)) but not needed for the first recoveries.

**Independent Test**: Seed a `COMPLETED` request whose latest HMPPS row is `SENT`; resend it with and
without the override.

**Acceptance Scenarios**:

1. **Given** a latest row `SENT`, **When** it is listed without the override, **Then** it is
   `not-eligible` and nothing is sent.
2. **Given** the same row, **When** it is listed with `allowTerminalSuccess`, an operator and a
   reason, **Then** a new `resend` attempt row is created and run like any resend — re-fetched,
   `sharedTime`-checked, the switch re-read, re-redacted, digested and sent — and the row records the
   operator, `override = true` and `override=force-resend`; the reason, sanitised to one line of at
   most 200 characters, is appended to `audit_note`; the request stays `COMPLETED`; the outcome is
   counted with `origin = resend` and `forced = true` and logged with `forced=true`.
3. **Given** the payload's `sharedTime` is later than the stored one, **When** the forced resend runs
   without `allow-changed-payload`, **Then** it is `FAILED(payload-changed)`; **When** it runs with
   it, **Then** it is sent and the row's `note` holds both override forms.
4. **Given** a forced resend that fails, **Then** the request stays `COMPLETED`, the latest row is
   `FAILED`, the failure reaches the leg-failed signals, and the leg is resend-eligible again without
   the override.
5. **Given** the override for an ID whose latest row is `FAILED`, **Then** that ID is resent as a
   normal resend: `override = false`, no `override=force-resend`, no reason line and `forced = false`.
6. **Given** a latest row `NOT_APPLICABLE` and the override, **Then** the rules are run again and
   the outcome is whatever they now give, labelled `forced = true`.

---

### User Story 6 - Find what to recover: list requests and completed dead-letter entries (Priority: P3)

Before a resend an operator needs the IDs, and after a replay they need to know which dead-letter
entries can now be removed. Two read-only runners answer: the request listing (§4.8 action 1) and
the listing of dead-letter entries whose request is now `COMPLETED` (A3-33).

**Why this priority**: Both procedures can be done without them — the documented query and the
broker's tools — but the listings make them visible, repeatable steps (H16; A3-33). Slice 005
already assumes the list runner as the way IDs are chosen.

**Independent Test**: Seed requests in several latest-row states and a dead-letter queue with
entries for completed, failed and unknown requests; run both runners and compare their output with
the seeded state.

**Acceptance Scenarios**:

1. **Given** a hearing and day, **When** the request listing is run for them, **Then** it shows each
   request for that hearing and day with its status and reasons and every attempt of its leg
   (attempt, origin, status, reason, HTTP status, times), and nothing else.
2. **Given** a latest HMPPS status, a reason and an `updated_at` range, **When** the listing is run,
   **Then** it shows the requests whose latest row matches, read from the latest attempts, ready to
   pass to resend-leg.
3. **Given** a dead-letter queue holding entries whose requests are `COMPLETED`, `FAILED` or unknown,
   **When** the completed-entry listing is run, **Then** it lists only the entries whose request is
   `COMPLETED`, with their dead-letter reason, and removes nothing.
4. **Given** either listing, **When** it prints, **Then** no stored command, body, payload,
   `audit_note` text, operator name or `userId` appears in its output or its logs.

---

### User Story 7 - Stop intake for a rollback, empty the paused queue, and resume only when re-enabled (Priority: P2)

When a rollback starts, the operator pauses intake — the service's only stop — so that nothing more
is taken from the queue while the legacy function app sends again. Once the live period has been
reconciled, they purge the paused queue and record how many messages went and up to when. Intake is
resumed only as the last step of a new cutover.

**Why this priority**: Rollback steps 1 and 6, the urgent rollback and the roll-forward all depend
on it (§9.4; H23; H32; A2-52), and resend-leg's refusal and purge-queue's permission both read the
pause. It is needed before go-live, but not for the first recoveries.

**Independent Test**: With messages on the queue, pause intake and enqueue more; observe that no
delivery is taken, `probation_intake_suspended{cause="operator"}` reads 1, resend-leg is refused
`intake-paused` and purge-queue is allowed; purge; observe the count and the cut-off reported and
the messages from before the cut-off gone; resume; observe intake taking deliveries again.
Separately, run the close-in-flight statement of FR-004-049a over seeded in-flight rows and check
its outcome; who runs it in a rollback is open (FR-004-049).

**Acceptance Scenarios**:

1. **Given** intake running, **When** the operator pauses it, **Then** no further delivery is taken
   from `probation.requests`, `probation_intake_suspended{cause="operator"}` reads 1, and the outcome
   says intake is paused.
2. **Given** an operator pause, **When** the store becomes unavailable and then answers again,
   **Then** intake stays paused.
3. **Given** an operator pause, **When** the operator resumes intake, **Then** the gauge reads 0 and
   intake takes deliveries again as soon as nothing else — a store outage — holds it.
4. **Given** intake already paused (or already running), **When** the operator pauses (or resumes)
   it, **Then** nothing changes and the outcome is `no-op`.
5. **Given** intake running, **When** the operator runs purge-queue, **Then** it is refused
   `intake-not-paused` and nothing is removed.
6. **Given** intake paused and messages on the queue, **When** the operator runs purge-queue,
   **Then** every message enqueued before its cut-off instant is removed, the dead-letter queue is
   untouched, and the report and its log line give the number removed and the cut-off; a message
   enqueued after the cut-off stays, for the reconciliation that follows.
7. **Given** a `RECEIVED` request with a `PENDING` attempt-1 row, a `RETRYING` request with a
   `PARKED` row, a `FAILED(leg-exhausted)` request with a `PENDING` resend row and a `COMPLETED`
   request with a `PENDING` resend row, **When** the close-in-flight statement runs over them
   (FR-004-049a), **Then** each of those rows is `FAILED(rolled-back)`, its `note` holding
   `prev=PENDING/-` or `prev=PARKED/<reason>`, with nothing changed but `status`, `reason`, `note`
   and `updated_at` and, on each resend row, `claim_token`; the `RECEIVED` and `RETRYING` requests
   are `FAILED(rolled-back)`, with no claim and no `exhausted_message_id`; the `FAILED` request
   keeps `leg-exhausted` and its exhausted id; the `COMPLETED` request is unchanged; and a late
   runner's write on any of them changes nothing and is counted as a stale-runner rejection.

---

### User Story 8 - Submit a hearing the service never admitted (Priority: P3)

A share reached neither path, or its message was never admitted — a dead-letter entry with no
request row. The operator submits the hearing and its day; the service reads the cached payload,
rebuilds the producer's `requestId` from the raw strings, and enqueues a normal command marked as
support, carrying the operator, a run id and the reason. It is processed like any delivery.

**Why this priority**: It is the repair route once a roll-forward has re-enabled the service (§9.4
step 7) and for shares never admitted (A2-55). Replay cannot serve those shares, but they are rare,
and the route works only while the cache still holds the payload.

**Independent Test**: Put an `EXT_` payload for a hearing and day in Redis; run submit-hearing for
them; observe one enqueued command whose `requestId` is the recipe over the raw strings — the
recipe also reproducing the pinned vector — with no `userId` and `origin = SUPPORT`; let it be
processed; observe a new request admitted with the operator, run id and reason on its `audit_note`,
and its outcomes labelled `origin = queue`.

**Acceptance Scenarios**:

1. **Given** a payload under the dated `EXT_` key, **When** the operator submits the hearing and
   day with an operator and a reason, **Then** one command is enqueued with `source = RESULTS`, the
   recipe's `requestId`, the `hearingId` and `hearingDay` as given, the payload's raw `sharedTime`,
   `eventType = Hearing_Resulted` and no `userId`; its `messageId` is `RESULTS:{requestId}`, its
   `correlationId` the `hearingId`; it carries `origin = SUPPORT`, the operator, a run id and the
   reason; and the outcome `enqueued` gives the `requestId` and the run id.
2. **Given** only the undated `EXT_` key holds the payload, **When** the hearing is submitted,
   **Then** that payload is used.
3. **Given** neither key holds a payload — even if the results query API would answer — **When** the
   hearing is submitted, **Then** it is refused `payload-unavailable` and nothing is enqueued.
4. **Given** the vector of results' helper test, **When** `RequestIdRecipe` is applied to its
   inputs, **Then** it gives the vector's `requestId`.
5. **Given** the command is admitted as a first delivery, **Then** `audit_note` gains the operator,
   run id and reason, the outcomes are labelled `origin = queue`, and any query-API read runs as the
   service's own system user.
6. **Given** the share's request already exists, **Then** the command is a no-op if the request is
   `COMPLETED`, is abandoned while a live claim holds it, and is a replay with route `support` if it
   is `FAILED`.
7. **Given** a `hearingDay` that is not a `YYYY-MM-DD` date, or a `hearingId` that is not a UUID,
   **When** the hearing is submitted, **Then** it is refused `unreadable-argument` without echoing
   the value, nothing is read from the cache and nothing is enqueued.

---

### Edge Cases

- **Two replays of one request.** The second message waits behind the first run's claim; it is a
  no-op once the first completes, or a replay of its own if the first ends `FAILED` again. Abandoned
  on a live claim with no delay, it can be dead-lettered `exhausted` within seconds: accepted noise
  that the runbook removes once the request is `COMPLETED` (A3-53, A3-55).
- **A replay deferred over a resend.** It burns its deliveries at once and is normally dead-lettered
  `exhausted` within seconds; replay again after the resend, which is a no-op if the resend
  recovered the request (A2-70, A2-72).
- **A replay enqueued while intake is paused.** It waits on the queue; the roll-forward after a
  rollback purges the queue before intake resumes, so it may be removed. The request stays `FAILED`
  and is replayed again once intake has resumed (A3-35; §9.4 step 6a; A2-52).
- **The broker cannot take a replay's message.** The action fails with nothing changed in the store
  (`broker-unavailable`). An enqueue whose outcome is unknown may have landed; a repeat then gives a
  second replay message, harmless as above.
- **A request left `RECEIVED` or `RETRYING` after a final-delivery hand-back.** When the store was
  down, or the run threw, on the final delivery, the message is dead-lettered `exhausted` without the
  request reaching `FAILED`. The replay action refuses it (`not-eligible`); a resubmission of the
  entry is admitted by the guard once no live claim remains and runs the redelivery column, not as a
  replay (§4.5 table; A2-34).
- **A message that was never admitted cannot be replayed.** The replay action re-sends a stored
  command, so a share with no request row — dead-lettered `validation`, `collision` or `exhausted`
  before admission, or left on a paused queue in an urgent rollback — is repaired by a resubmission
  (for `exhausted`), by submit-hearing or by a court re-share, never by replay (A2-55; A3-117).
- **A crash after a 2xx during a replay's re-run of a `FAILED` attempt-1 row.** The `rerun-by`
  stamp was not written, so the redelivery re-runs the leg: a genuine duplicate at HMPPS, the same
  at-least-once window as a first delivery (A2-42). It is not told apart from a normal re-run or
  counted; how to count it is open (A3-115).
- **A resend runner that dies after committing its claim.** The `PENDING` resend row's lease
  expires. Until the row is closed, replays of the request are refused or deferred. What closes it is
  the open question of FR-004-024; a reclaim may send again a body the dead runner had already sent.
- **The store fails during a resend run.** The IDs already completed keep their outcomes; the IDs
  not reached are reported `not-run`; an ID claimed but not completed is left as a `PENDING` resend
  row, as above; the run's result is failed. A store that is down before the run starts fails it
  whole, `store-unavailable`, with nothing changed.
- **Two resend runs listing the same ID.** One takes the leg; for the other the ID is
  `resend-in-progress` (the leg row is being claimed) or `request-claimed`.
- **`sharedTime` renderings.** The cache form carries the producer's own string; the query API's
  form renders the instant its own way, possibly with a zone identifier or a different fraction of
  a second. Both are parsed honouring offset or zone and compared as instants at the stored
  precision, so the same share in either form proceeds (A3-57).
- **`allow-changed-payload` with a matching `sharedTime`.** The override is not used and is not
  recorded.
- **A resend after the cache entry expired.** It sends the query API's form — no
  `cpsProsecutorIds`, a different `hearing` field set — as it comes (Q19 closed; D10); the body
  differs in shape from the one first sent, which the runbook tells the operator.
- **An `origin` value other than `REPLAY` or `SUPPORT`.** The route is recorded `copy`; the value
  itself is neither recorded nor logged.
- **A `FAILED(payload-changed)` resend outcome.** Until Q28 is decided it stays on the leg-failed
  alert, so a resend refused for a re-shared hearing pages (slice 006).
- **A dead-letter copy under an exhausted id the recovery has cleared.** Recovery clears
  `exhausted_message_id`, and the request is `COMPLETED`, so a later copy is a no-op.
- **A forced resend of a `NOT_APPLICABLE` leg.** If the switch or the payload has changed since, the
  rules may now leave something to send: the outcome follows the rules as they run now and is still
  labelled `forced`.
- **Submit-hearing after a re-share.** The cache holds the newest share for the hearing and day, so
  the command carries the newest share's `requestId`; if that share was processed, the command is a
  no-op. An older share that was never delivered can only be re-shared.
- **A cached `sharedTime` that is not a valid date-time.** The command is built from the payload as
  it is and is dead-lettered `validation` on arrival by slice 002's closed-schema check, which
  refuses a value of the wrong type or format (002's FR-002-003; DV-16).
- **Submit-hearing while intake is paused.** As for a replay: the command waits on the queue, a
  roll-forward purge may remove it, and the hearing is submitted again once intake has resumed.
- **A pause or a resume while the store is down.** The operator pause and the store outage are
  separate holds; intake takes deliveries only when neither holds it.
- **Messages arriving while intake is paused.** Owed producer events keep arriving after the Results
  switch is off. Those enqueued after a purge's cut-off stay on the queue and are reconciled and
  purged at the roll-forward (A2-52; §9.4).

## Requirements *(mandatory)*

### Functional Requirements

Names the design marks as proposals — `allowTerminalSuccess`, `--force-resend-sent`, the runner
names, the test names — stay proposals here. Codes not taken from the design are marked as such in
Assumptions.

**Replay admission, by either route**

- **FR-004-001**: A delivery of a request whose status is `FAILED` MUST be admitted as a replay only
  if its `messageId` differs from `exhausted_message_id`, or `exhausted_message_id` is NULL, or its
  `messageId` equals `exhausted_message_id` and its broker delivery count is 1 — and, subject to
  FR-004-003 and to slice 002's abandon on a live claim or a database outage (002's FR-002-007),
  whenever that holds.
  A delivery of a `FAILED` request that fails this predicate MUST be dead-lettered `exhausted` and
  counted on `probation_deadlettered_total{reason="exhausted"}`, leaving the request row unchanged.
  (This replaces 002's interim rule FR-002-008 and removes its test; from this slice on, every
  delivery of a `FAILED` request is judged by this predicate.) *(DV-26; H9 (a); A3-30; A2-62;
  A2-34; §4.8 "One chance for a same-id copy"; FR-002-008)*
- **FR-004-002**: The exhausted-identity test of FR-004-001 MUST run before the deferral check of
  FR-004-003, so that a copy under the exhausted `messageId` has exactly one admission chance: if
  its first delivery is not admitted, its redelivery is dead-lettered `exhausted`. *(A3-30; A2-70;
  §4.8 runbook)*
- **FR-004-003**: If, when a delivery of a `FAILED` request is considered for admission, the leg's
  latest row is a `PENDING` row created by resend-leg — whether its lease is live or expired — the
  delivery MUST be abandoned with the reason `REPLAY_DEFERRED`, decided under the request-row lock
  and before the replay statement runs: the request stays `FAILED`, `replayed_at` stays NULL,
  `probation_replay_deferred_total` is incremented and the deferral is logged. On the final permitted
  delivery the same delivery MUST be dead-lettered `exhausted` instead. *(A2-70; A2-72; §4.5;
  A2-34)*
- **FR-004-004**: Admitting a replay MUST, in one statement whose condition is the predicate of
  FR-004-001: move the request `FAILED` → `RECEIVED`; clear `failure_reason` and
  `exhausted_message_id`; set `replayed_at` from the database clock; append — never replace — one
  timestamped line `REPLAYED_AFTER_FAILURE prior=<failure_reason>` to `audit_note`; and take the
  request claim. *(A2-47; A2-60; A2-62; DV-26; §4.5)*
- **FR-004-005**: At admission the service MUST record the replay route as one closed value —
  `service` for the broker application property `origin = REPLAY`, `support` for
  `origin = SUPPORT`, `copy` for no `origin` — as a line appended to `audit_note`, as a field of the
  run's log lines, and by incrementing `probation_replays_total{route}`. The route MUST be an audit
  label only: no eligibility, settlement or sending rule reads `origin`. *(A3-34; A2-68; H9)*
- **FR-004-006**: A delivery of a `COMPLETED` request MUST stay a no-op whichever route it came by
  — a dead-letter resubmission, a producer republish, a submit-hearing command or a service replay
  message: the message is completed, no leg is re-run and no row changes. *(H9; A3-36; A2-27; the
  guard path itself is slice 002's)*

**What a replayed run does**

- **FR-004-007**: While a request's `replayed_at` is set, every delivery for it — the admitting one
  and each redelivery — MUST apply the replay column of the eligibility table: re-run a leg whose
  latest row is `FAILED` (any reason, `rolled-back` included) or `PARKED`, and never touch a leg whose
  latest row is `SENT` or `NOT_APPLICABLE`. The payload fetch's outcome MUST be written only to legs
  the column lets the run touch. The run's stages are the queue run's (slice 002), the query-API
  read carrying the command's `userId` when present and the system user otherwise. *(A2-42; A2-60;
  §4.5 table; R1-5; R2-7; DV-17)*
- **FR-004-008**: A leg whose latest row the replay column re-runs and which is attempt 1 —
  `FAILED`, `PARKED`, or `PENDING` under an expired request claim — or a replay-created row that is
  `PARKED` or `PENDING` under an expired request claim, MUST be re-run in place: no `PENDING` written
  first; one update writing `status`, `reason`, `youth_switch`, `http_status`, `gateway_request_id`,
  `payload_source`, `payload_shared_time`, `body_digest`, `sent_at` and `service_version` together;
  `attempts` incremented; `note` rewritten to `prev=<STATUS>/<reason>` (`prev=PENDING/-` for a
  `PENDING` row) with `rerun-by=<messageId>`; and, in the same transaction, the attempt's earlier
  stored body deleted and the new one stored when the re-run serialised a body. An in-place re-run of
  attempt 1 keeps `attempt_origin = queue`. (A redelivery that re-runs an expired `PENDING`
  replay-created row is not counted on `probation_crash_reruns_total`: the design's metric counts
  only `PENDING` attempt-1 re-runs and leaves the rest not established — A3-115; 002's FR-002-061.)
  *(A2-39; A2-45; A2-63; A2-68; A2-71; H20; A3-63)*
- **FR-004-009**: A leg whose latest row is `FAILED` with attempt ≥ 2 (a resend row, or an earlier
  replay row) MUST get a new appended row — attempt `max(attempt) + 1`, `attempt_origin = replay`,
  no leg claim, `PENDING` until its outcome is written — which is then run under the request claim.
  A replayed run MUST never re-run a resend row in place, and MUST NOT append over a `PENDING` latest
  row. Whether resend-leg reclaims an expired `PENDING` resend row in place is FR-004-024's open
  question. *(A2-46; A2-59; A2-63)*
- **FR-004-010**: On a redelivery of a replay message, a leg whose latest row is `FAILED` and whose
  `note` names that message in `rerun-by` MUST be skipped, so that a `FAILED` row is re-run at most
  once per replay message, except across a crash between a 2xx and the write. A `PARKED` row MUST be
  re-run on every delivery, whatever its `note`. *(A2-45; A2-64)*
- **FR-004-011**: A replayed run MUST label all it produces with `runOrigin = replay`, captured once
  at admission and unchanged when settlement later clears `replayed_at`: the `origin` label of
  `probation_leg_outcomes_total` (with `forced = false`), the run's log lines (with the `route`), and
  the `attempt_origin` of any row it appends. *(A2-71; §10.1; §10.2)*
- **FR-004-012**: A replayed run MUST settle by the normal settlement rules, with these
  consequences: a terminal leg (`SENT`, `NOT_APPLICABLE`, or `FAILED` with `receiver-rejected` or
  `rules-exception`) completes the request with `completion_reason = recovered`, decided by the
  `REPLAYED_AFTER_FAILURE` line anywhere in `audit_note`, so a replayed request whose first replayed
  run parks and that settles on a later redelivery still records `recovered`; a `PARKED` leg sets the
  request `RETRYING` and keeps `replayed_at`; on the final permitted delivery a still-`PARKED` leg
  becomes `FAILED` with the same reason, the request `FAILED(leg-exhausted)` with
  `exhausted_message_id` set to the `messageId` of the delivery that exhausted it, and the message is
  dead-lettered `leg-exhausted`; the completion and final-failure statements clear `replayed_at`.
  *(A2-37; A2-41; A2-47; A2-60; §4.4 settlement)*
- **FR-004-013**: Every leg-outcome write of a replayed run MUST be guarded by the request claim's
  owner and token: a write that matches no row, because the claim was lost or cleared, is a
  stale-runner rejection, counted on `probation_stale_runner_rejections_total`, and the run does not
  settle. *(A2-67; A2-51)*

**The service's replay action**

- **FR-004-014**: The replay action MUST take one request (`requestId`; `source` is `RESULTS`),
  check it as FR-004-020 states, and decide under the request-row lock: no request row → refused
  `unknown-request`; `COMPLETED` → outcome `no-op`, nothing enqueued; `RECEIVED` or `RETRYING` →
  refused `not-eligible`; `FAILED` with the leg's latest row a `PENDING` resend row, live or expired
  → refused `resend-in-progress`; any other `FAILED` request → replayed as FR-004-015 states. The
  action itself MUST write nothing to the store. *(§4.8 action 2; H9; A2-70; A2-72; Assumption 2)*
- **FR-004-015**: The replayed message MUST carry `processed_request.command` byte for byte as its
  body — `userId` included exactly when the original carried it; never rebuilt from columns and
  never taken from the dead-letter queue — under a `messageId` never used before for that request
  (so equal neither to `RESULTS:{requestId}` nor to any earlier replay message's id), with
  `contentType = application/json`, `correlationId` = the `hearingId` and the application property
  `origin = REPLAY`. The fresh `messageId` MUST be short enough that `rerun-by=<messageId>` keeps
  every `note` within its 256 characters. *(A2-48; §4.2 replay rule; A3-53; A2-58)*
- **FR-004-016**: The replay action MUST be available at any time, including while intake is
  paused, and MUST NOT need `RequestIdRecipe`: the stored command carries its `requestId`. *(A3-35;
  §4.8 action 2; §9.4 step 6a)*
- **FR-004-017**: The replay action MUST answer with one bounded outcome — `enqueued` with the new
  `messageId`, `no-op`, or a refusal code — or fail with `store-unavailable` or
  `broker-unavailable`, changing nothing in the store; and MUST write one structured log line with
  the `requestId`, the `hearingId` when the request exists, and that outcome. Refusal codes are
  answer and report codes only, never written to a row. *(DIGEST §11; §10.2; constitution
  Principle VI; Assumption 3)*

**Resend-leg: the run**

- **FR-004-018**: Resend-leg MUST take an explicit list of `requestId`s, the leg (`HMPPS`, its only
  value), an operator and a reason, and optionally `allowTerminalSuccess` (the forced-resend
  override) and `allow-changed-payload`. It MUST NOT select requests by date range, status, reason,
  hearing or count. *(H16; A3-56; Q16; A3-36 as amended)*
- **FR-004-019**: Duplicates in the list MUST be collapsed first. An empty list MUST be refused
  whole (`missing-argument`), and a list of more distinct IDs than
  `distribution.resend.max-ids-per-run` (default 100) MUST be refused whole
  (`too-many-request-ids`), before anything is read or changed. The property MUST be validated at
  startup as at least 1, the refusal naming the property. *(H16; A3-56; §8.1; DIGEST §12;
  Assumption 6)*
- **FR-004-020**: Every action of this slice that takes arguments — replay, resend-leg,
  submit-hearing and the request listing — MUST refuse incomplete or unreadable input whole, before
  any store, cache or broker read and with nothing changed, and the refusal MUST NOT echo the text
  supplied: a missing or blank required argument, an operator or reason where the action takes one
  included, is `missing-argument`; an identifier that is not a UUID (the replay's `requestId`, an
  entry of resend-leg's list, submit-hearing's or the listing's `hearingId`), a `hearingDay` that is
  not a `YYYY-MM-DD` date, a leg other than `HMPPS`, or a listing filter outside its closed values or
  not a valid instant is `unreadable-argument`; an operator or reason longer than 200 characters is
  `argument-too-long`. *(A3-36's bound; constitution Principle VII; Assumptions 5 and 7)*
- **FR-004-021**: A resend MUST NOT claim any ID while the service's intake is paused by an
  operator — the rollback state, in which the legacy is sending again: a run submitted while paused
  is refused whole (`intake-paused`) before any ID is read or changed, and a run during which a
  pause begins claims no further ID and reports the IDs not reached as `not-run`. *(A2-40; §4.4
  Exposure row; §4.8 action 3; §9.4; Assumption 8)*

**Resend-leg: each ID**

- **FR-004-022**: Each distinct ID MUST be checked, claimed and run on its own, so that one ID's
  refusal or failure never stops or changes another's. The check MUST lock the request before its
  leg and refuse in this order: `unknown-request` (no request row); `request-claimed` (the request
  holds a live claim — a queue run or a replay is in progress); `resend-in-progress` (the leg's
  latest row is a `PENDING` resend row whose lease is live, or another resend is claiming the leg at
  that moment); `not-eligible` (FR-004-023). *(H16; A3-56; A2-65; §4.4 claim contract (d))*
- **FR-004-023**: An ID MUST be eligible only if its leg's latest row is `FAILED` with any reason —
  except an attempt-1 `FAILED(rolled-back)` row, which a replay re-runs instead — or, with
  `allowTerminalSuccess`, `SENT` or `NOT_APPLICABLE`. Every other latest row — `PARKED`, and a
  `PENDING` attempt 1 or a `PENDING` replay row under an expired request claim — MUST be refused
  `not-eligible`; under a live request claim FR-004-022 refuses the ID `request-claimed` first.
  *(§4.5 table; A3-56; R1-28b; A2-40; A3-36)*
- **FR-004-024**: When the leg's latest row is a `PENDING` resend row whose lease has expired (its
  runner died or overran), resend-leg MUST [NEEDS CLARIFICATION: the design conflicts. Claim
  contract (c), the §4.5 resend column and A2-72 reclaim the row in place — owner, token and expiry
  rewritten, the attempt number kept, then run — and A2-72's procedure for a deferred replay ("run
  resend-leg, then replay") depends on it; A3-56 and the §4.4 Selects row refuse it
  `resend-in-progress`, "live or expired", which leaves nothing but the rollback's step 4 to close
  such a row, while every replay of its request is deferred. Which applies? A reclaim may also send
  again a body the dead runner had already sent, a duplicate the design does not count (A3-115).]
  *(§4.4 claim contract (c); §4.5; A2-72; A3-56; DIGEST §16)*
- **FR-004-025**: For an eligible ID the run MUST insert, in the transaction that checked it, a new
  attempt row — attempt `max(attempt) + 1`, `attempt_origin = resend`, `PENDING`, its leg claim
  (owner, token, expiry), the run's `resend_run_id` and the `operator` — and commit it before any
  POST. The claim and `resend_run_id` MUST stay on the row once it completes, as the record of who
  ran it, and a resend MUST change no earlier attempt row. *(§4.4 claim contract (b) and (d); §4.4
  Record row; R1-24; A2-50)*
- **FR-004-026**: Each claimed attempt MUST then run: the payload fetch (`EXT_` dated key, undated
  key, then the results query API, which always carries the service's own system user); the
  `sharedTime` check (FR-004-029); the youth-switch read, an unreadable switch taking the configured
  default with the leg carrying on; the redaction; the body digest; the send. Each stage MUST keep its
  configured budget (by default payload 60 s, switch 25 s, send 130 s) and the attempt-fits rule, so
  that the attempt ends within the processing deadline (4 min) and before its leg claim's lease
  (`PT4M30S`) expires.
  *(§4.4 resend run; DV-17; H30; H7; H8; §4.7; A2-69; Q17; H13 — no validation stage)*
- **FR-004-027**: A resend MUST never park. A payload missed everywhere is
  `FAILED(payload-unavailable)`; a send still transient at its end — a connection failure or 429
  after the in-run retries, or at once a 5xx, a 408, or a read timeout or I/O failure after the
  request was written — is `FAILED(gateway-transient)`; a 4xx other than 408 and 429 is
  `FAILED(receiver-rejected)`; any 2xx is `SENT`, a 2xx other than 200 also counted; a rule that
  throws is `FAILED(rules-exception)`; a redaction that leaves `{}` is `NOT_APPLICABLE` with its
  reason (`sjp-or-box-hearing` or `nothing-left-after-redaction`).
  *(R2-8; DV-07; H11; H15; DIGEST §9, §10)*
- **FR-004-028**: An attempt's completion MUST be guarded by its own claim token: a completion
  whose token no longer matches — rotated by the rollback protocol, for example — MUST change
  nothing and be counted on `probation_stale_runner_rejections_total`. The outcome, the stored body
  when one was serialised, and any recovery transition (FR-004-032) MUST be written in one
  transaction, which locks the request row before the leg row. *(§4.4 claim contract (e); A2-51;
  A2-49; A2-65; H20; A3-63)*

**The `sharedTime` check**

- **FR-004-029**: Directly after the payload fetch, before any leg stage — no switch read, no
  redaction, no send — a resend MUST compare the payload's `sharedTime` with the request's stored
  `shared_time` as instants: the ISO-8601 string is parsed honouring its offset or zone (a rendering
  that names a zone included) and compared at the stored precision. Equal → proceed.
  Different, absent or unparseable → `FAILED(payload-changed)`, unless `allow-changed-payload` was
  given. The check MUST apply to normal and forced resends alike. *(H17; A3-57; A2-24)*
- **FR-004-030**: Every resend attempt that read a payload MUST record `payload_source`, and
  `payload_shared_time` when the payload's `sharedTime` parsed. A `FAILED(payload-changed)` row MUST
  carry no `youth_switch`, `body_digest` or `service_version`, and nothing is sent for it; the
  refusal MUST also append to `audit_note` a line that quotes no payload content. *(A3-58; §4.6
  presence matrix; DIGEST §8 `audit_note` events)*
- **FR-004-031**: When `allow-changed-payload` lets through an attempt whose `sharedTime` differs
  (or is absent or unparseable), the attempt row MUST record `override = true` and the note form
  `override=allow-changed-payload`. When the `sharedTime` matched, the override was not used and MUST
  NOT be recorded. *(A3-57; §4.6 `override`, "true when any override was used")*

**Recovery, and completion that stays historical**

- **FR-004-032**: When a resend attempt of a `FAILED` request completes with the leg's latest row
  `SENT`, `NOT_APPLICABLE`, or `FAILED` with a class-1 reason (`receiver-rejected`,
  `rules-exception`), the request MUST move `FAILED` → `COMPLETED` with
  `completion_reason = recovered`, clearing `failure_reason` and `exhausted_message_id` and appending
  both to `audit_note` as one timestamped line. *(A2-38; A2-27; R1-8; A2-49)*
- **FR-004-033**: A resend that ends `FAILED` with `payload-unavailable`, `gateway-transient` or
  `payload-changed` MUST leave its request exactly as it was; so does a resend row the rollback
  closes `FAILED(rolled-back)`. *(A2-38; A2-40; A2-43; §4.4 two classes of `FAILED`)*
- **FR-004-034**: No statement of this slice — replay admission, replayed-run settlement, resend,
  forced resend or recovery — may change a `COMPLETED` request. A resend of a `COMPLETED` request's
  leg that fails MUST leave the request `COMPLETED` and the leg's latest row `FAILED(<reason>)`,
  resend-eligible again. Every report and listing of this slice MUST read a leg's state from its
  latest attempt, never from the request's status. *(A2-27; A2-49; A3-36; §4.6)*
- **FR-004-035**: Replay and resend MUST be mutually fenced: a resend refuses a request under a
  live request claim (FR-004-022), and a replay is not admitted over a `PENDING` resend row
  (FR-004-003, FR-004-014). With two concurrent resend runs and a late queue delivery for one leg,
  or a replay and a resend racing in either order, exactly one of them MUST run the leg and exactly
  one POST MUST result for each attempt. *(A2-65; A2-70; A2-72)*

**The forced resend**

- **FR-004-036**: With `allowTerminalSuccess`, a listed ID whose leg's latest row is `SENT` or
  `NOT_APPLICABLE` MUST get a new resend attempt row and be run exactly as any resend: re-fetched,
  `sharedTime`-checked (with `allow-changed-payload` as for any resend), the switch re-read,
  re-redacted, digested and sent. Without the override such an ID MUST be `not-eligible`. *(H9 (c);
  A3-36; H13)*
- **FR-004-037**: For each attempt run under the forced-resend override, the attempt row MUST record
  the `operator`, `override = true` and the note form `override=force-resend` (joined with
  `override=allow-changed-payload` when that override was used as well), and the operator's reason,
  sanitised to one line of at most 200 characters, MUST be appended to the request's `audit_note`.
  The override applies per ID: for a listed ID whose latest row is `FAILED` it is not used and none of
  this is recorded. *(A3-36; §4.6; DIGEST §11 action 3)*
- **FR-004-038**: A forced resend MUST leave its request `COMPLETED` whatever its outcome, and its
  outcome MUST be counted on `probation_leg_outcomes_total` with `origin = resend` and
  `forced = true` and logged with `forced=true`. Every other outcome — any other resend attempt, and
  every queue or replay outcome — MUST carry `forced = false`. *(A3-36; §10.1; §10.2; A2-27)*

**Report and telemetry**

- **FR-004-039**: Every resend run that is not refused whole MUST produce a report holding the run's
  `resend_run_id`, the leg, whether each override was requested, every distinct listed ID exactly
  once with its outcome — the leg's resulting status and reason, a refusal code, or `not-run` — and
  a summary (IDs taken, `SENT`, `NOT_APPLICABLE` by reason, `FAILED` by reason, refusals by code,
  `not-run`).
  The same report MUST be written as one structured log line, and each refused ID MUST increment
  `probation_resend_refusals_total{reason}`. *(H16; A3-56; §4.4 Output row)*
- **FR-004-040**: Every leg outcome of a replayed or resent run MUST be logged as one line with its
  status, reason, HTTP status, body digest, `runOrigin`, the `route` for a replay, `forced=true` for a
  forced resend, the `youth_switch` value and, for a defaulted read, its cause; and counted on
  `probation_leg_outcomes_total` with its `origin`, so that a failing replay or resend reaches the
  same signals as a failing first send. *(§10.1; §10.2; R1-31; §10.3)*
- **FR-004-041**: No log line, metric label, report, listing or refusal of this slice may carry a
  stored or sent body, a stored command, a payload value, a `userId`, the operator's name or a
  reason's text. An operator's name and a reason's text are written only where this slice's rules
  put them — `operator` on resend attempt rows (FR-004-025); the `audit_note` lines of a forced
  resend (FR-004-037) and of a submit-hearing admission (FR-004-057); and the submit-hearing
  command's application properties (FR-004-056). *(constitution Principle VII; DV-09; A3-60; §10.2;
  §4.8 action 5)*

**Command-line runners**

- **FR-004-042**: The image MUST carry a command-line runner for each action of this slice — replay;
  resend-leg, with the forced-resend override and `allow-changed-payload`; pause intake; resume
  intake; purge-queue; and submit-hearing — taking the same inputs as the action, applying exactly
  the same rules through the same application service, and giving the same outcomes, codes and
  report. For replay and resend-leg the runners are the fallback for slice 005's endpoints and the
  only operator surface until they are deployed; the other actions are runners only while O-1 is
  open. The resend runner's form is
  `resend-leg --leg HMPPS --request-ids <id,…> --operator <name> --reason <text>`, with
  `--force-resend-sent` for the override and `--allow-changed-payload` for the design's
  `allow-changed-payload` (names proposed; slice 005's JSON field is `allowChangedPayload`).
  *(H9; §4.4 Exposure row; §4.8; DIGEST §11; A3-35; O-1)*
- **FR-004-043**: The image MUST also carry two read-only runners: the request listing — by hearing
  and day, or by the latest HMPPS status, reason and `updated_at` range read from the latest
  attempts, its input checked as FR-004-020 states — and the listing of dead-letter entries whose
  request is now `COMPLETED`, which peeks the dead-letter queue and removes nothing. Each prints
  only identifiers, statuses and reasons, attempt numbers and origins, HTTP statuses and times, and
  says when it has shown fewer rows than exist. *(§4.8 action 1; H16; A3-56; A3-33; Assumption 10)*
- **FR-004-044**: A runner MUST run under the pod's own workload identity and network path, with no
  role issued for it alone. *(§4.4 Exposure row)*
- **FR-004-045**: A runner invocation MUST perform its action and end: it MUST NOT start the
  service's message intake — purge-queue's removal of messages is its action, not intake — and MUST
  NOT run the scheduled body maintenance. Its result MUST say,
  in a form a runbook step can test without reading free text, whether the action was done, refused
  with nothing changed, or failed. *(constitution Principle VI; H20 — one scheduled task, one runner;
  §4.4 Output row)*

**The dead-letter runbook and the rollback**

- **FR-004-046**: The operator runbook published with the service MUST state the dead-letter rules:
  resend `leg-exhausted` and `exhausted` entries — a copy of an admitted request replays, and a
  message never admitted is processed as a first delivery; never resend `validation` (fix it at
  source) or `collision` (investigate); once a replayed request is `COMPLETED`, remove the original
  entry and any copy that was itself re-dead-lettered; an `exhausted` entry whose request is already
  `COMPLETED` is duplicate-copy noise, to be removed and not resent; a copy under the exhausted
  `messageId` has one admission chance — if it is re-dead-lettered, resend again once the cause is
  cleared, or replay through the service's action; no wait and no `messageId` edit is needed, there
  being no broker duplicate-detection window; and a dead-letter entry with no request row is
  repaired by a resend (for `exhausted`) or by submit-hearing, never by the replay action.
  *(A3-32; A3-33; A3-53; A3-55; A3-31 as superseded by H14; A2-55; §4.8 runbook)*
- **FR-004-047**: The runbook MUST also state how to choose resend IDs — the request listing, or the
  documented query on the latest attempts by leg, status, reason and `updated_at` range, and by
  `service_version` to find what a release sent; how to read each report entry (`unknown-request`
  and `not-eligible`: a mistake in the list; `resend-in-progress` and `request-claimed`: try that ID
  later); that an outcome-unknown `FAILED(gateway-transient)` is confirmed with HMPPS before any
  re-send; that a `FAILED(receiver-rejected)` re-sent unchanged is rejected again; that a forced
  resend is a genuine duplicate at HMPPS; that a resend after the cache entry has expired sends the
  query API's form, which differs in shape from the body first sent; and that an investigation of
  what was sent reads the stored body within its retention window. When intake is resumed after a
  rollback, and what becomes of a message enqueued while intake is paused, are stated by slice 006's
  rollback inputs, not by this runbook (Dependencies). *(H16; §4.8 "Resending a leg"; A3-61; §9.4
  step 7; A3-115; D16; H12; A3-47; Q19; H20; A3-65)*
- **FR-004-048**: A request moved to `FAILED(rolled-back)` by the rollback protocol MUST be
  replayable (its NULL `exhausted_message_id` admits it, FR-004-001), its attempt-1
  `FAILED(rolled-back)` row being re-run in place; a closed resend row (attempt ≥ 2,
  `FAILED(rolled-back)`) MUST be resend-eligible and, when its request is `FAILED`, re-run by a
  replay on a new replay row. *(A2-40; A2-43; A2-46; A2-62; R1-28b; §9.4 steps 4 and 6a)*
- **FR-004-049**: The rollback's close-in-flight transition (§9.4 step 4; its statement is
  FR-004-049a) MUST be executable by an operator through [NEEDS CLARIFICATION: the transition is
  specified and pinned (`RollbackFencingIT`), but no §4.8 action or runner executes it. Is it a
  runner this slice delivers (refused unless intake is paused), an action another slice owns, or a
  step of the rollback runbook run outside the service? Slice 005 keeps it off HTTP.] *(§9.4 step
  4; A2-51; A2-43; DIGEST §16)*
- **FR-004-049a**: The close-in-flight statement of rollback step 4 MUST close every leg row that
  is `PENDING` or `PARKED`, in one transaction per row, as follows; who runs it is FR-004-049's
  open question.
  - The row becomes `FAILED(rolled-back)`. Its `note` gets `prev=<STATUS>/<reason>`
    (`prev=PENDING/-` for a `PENDING` row), replacing any earlier `prev=` form and keeping the row's
    other note forms.
  - Only `status`, `reason`, `note` and `updated_at` change, plus, on a resend row
    (`attempt_origin = resend`), a fresh random `claim_token` set in the same statement. Every
    outcome and evidence column is kept, and every CHECK holds on the closed row (002's FR-002-052).
  - In the same transaction, the row's request: a `RECEIVED` or `RETRYING` request becomes
    `FAILED` with `failure_reason = rolled-back`, no `exhausted_message_id`, and its claim triple
    (owner, token, expiry) cleared; a `FAILED` request keeps its `failure_reason` and its
    `exhausted_message_id`; a `COMPLETED` request is untouched, even when the closed row is one of
    its resend rows.
  - A late runner's claim-guarded write after the close changes no row and is counted on
    `probation_stale_runner_rejections_total`: a late resend runner's completion by its rotated
    token (FR-004-028), and a late queue or replay runner's statements, its leg-outcome UPDATE
    included, by the cleared request claim (FR-004-013; 002's FR-002-040).

  It is pinned by `RollbackFencingIT` (name proposed) in three cases: a late queue runner, a late
  resend runner, and a late leg-outcome UPDATE of a queue or replay row after the request claim was
  cleared. *(§9.4 step 4; A2-43; A2-51; A2-66; A2-67; A2-27; DIGEST §7, "Rollback step 4")*

**The deviation register**

- **FR-004-050**: The deviation register's DV-26 row MUST name this slice's pinning test — same
  `messageId` at delivery count 1 → replay; same `messageId` above count 1 → dead-lettered
  `exhausted`; a new `messageId` → replay; the route recorded `copy`, and `support` for a
  submit-hearing command — which fails against the informant guard's rule and passes against DV-26.
  *(DV-26; constitution Principles I and II; `DlqResubmissionReplayIT`, name proposed)*
- **FR-004-050a**: This slice MUST pin the resend halves of DV-07 and DV-17, which 001 hands to it
  beside slice 002's queue-run halves (002's FR-002-074), in its resend tests (name proposed:
  `ResendByRequestIdsIT`): on a resend, a Redis connection failure is a miss, the query API is
  asked, and a miss everywhere is `FAILED(payload-unavailable)` at once, never `PARKED` (DV-07;
  FR-004-026, FR-004-027); and every resend's query-API read carries the service's own system user
  as `CJSCPPUID`, whatever the stored command's `userId` (DV-17; H30; FR-004-026). The register's
  DV-07 and DV-17 rows name these cases beside slice 002's tests. *(DV-07; DV-17; H30; R2-8;
  constitution Principles I and II)*

**Intake pause and resume**

- **FR-004-051**: Pausing intake MUST stop the service taking deliveries from `probation.requests`
  and set `probation_intake_suspended{cause="operator"}` to 1; resuming MUST lift the operator pause
  and set that gauge to 0, intake taking deliveries again as soon as no other hold — a store outage —
  remains. Pausing a paused intake, or resuming a running one, MUST change nothing and answer
  `no-op`. *(§4.8 action 4; A2-57; §9.4 step 1; slice 002's intake control)*
- **FR-004-052**: An operator pause MUST stay in force until an operator resumes intake — for the
  whole rolled-back period — so the store's recovery from an outage MUST NOT lift it, and it MUST
  hold on every replica and across a pod restart, by [NEEDS CLARIFICATION: the design makes pause
  "the ops front for `ConsumerLifecycleController`", which holds its state per pod and in memory,
  yet requires intake to stay paused for the whole rolled-back period (A2-52) with one or two
  replicas (O-2), and a runner runs in the pod as a process of its own. How does the pause reach
  every replica and survive a restart: a pause recorded in the store (beyond V1's three tables and
  one view), the deployment's intake switch (`distribution.consumer.enabled`) set off for the
  rolled-back period, or a pause applied to each pod with a restart accepted as resuming intake?]
  *(A2-52; §4.8 action 4; §9.4 step 1; O-2)*

**Purge-queue**

- **FR-004-053**: Purge-queue MUST be refused `intake-not-paused`, removing nothing, unless intake is
  paused by an operator. While intake is paused it MUST remove the messages on `probation.requests`
  enqueued before its cut-off instant — the instant it starts — leave the dead-letter queue
  untouched, and report, and write as one structured log line, the number removed and the cut-off
  instant. A message enqueued after the cut-off stays, for the reconciliation that follows.
  *(§4.8 action 6; R1-28b; §9.4 step 6; A2-52; Assumption 18)*

**Submit-hearing**

- **FR-004-054**: Submit-hearing MUST take a `hearingId`, a `hearingDay` (`YYYY-MM-DD`), an operator
  and a reason, checked as FR-004-020 states before the cache is read; read the cached payload by
  the dated `EXT_` key and then the undated key — never the results query API; and refuse
  `payload-unavailable`, enqueuing nothing, when neither key holds a payload it can read with a
  `sharedTime`. *(§4.8 action 5; A2-4; DIGEST §11; Assumption 19)*
- **FR-004-055**: It MUST take `sharedTime` as the payload's raw JSON string — never parsed and
  re-rendered, and never read from `processed_request.shared_time` — and compute the `requestId`
  with `RequestIdRecipe` (name proposed): the name-based UUID of `hearingId|hearingDay|sharedTime`
  over the raw strings, pinned by the vector of results' own helper test (DIGEST §14), with no build
  dependency on a results artefact. The vector MUST be re-taken when Q20 lands. *(A2-4; A2-25;
  DIGEST §14; Q20)*
- **FR-004-056**: It MUST enqueue one normal command — `source = RESULTS`, that `requestId`, the
  `hearingId` and `hearingDay` as given, the raw `sharedTime`, `eventType = Hearing_Resulted` and no
  `userId` — with `messageId = RESULTS:{requestId}`, `correlationId` = the `hearingId`,
  `contentType = application/json`, and the application properties `origin = SUPPORT`, the
  operator, a run id new to each invocation and the reason; and answer `enqueued` with the
  `requestId` and the run id. Operator and reason follow the bounds of FR-004-020. *(§4.8 action 5;
  DIGEST §5, §11; R1-22; R1-22b)*
- **FR-004-057**: When a command carrying `origin = SUPPORT` is admitted — as a first delivery or as
  a replay — the service MUST append its operator, run id and reason, sanitised to one line, to
  `audit_note`. Its run is a queue delivery — `runOrigin = queue`, any query-API read as the
  service's own system user — unless it meets a `FAILED` request, when it is a replay with route
  `support` (FR-004-005). *(§4.8 action 5; A2-57; A2-71; A3-34; DV-17; DIGEST §8 `audit_note`
  events)*
- **FR-004-058**: Submit-hearing, like the replay action, MUST be available whatever the intake
  state and MUST write nothing to the store itself; the broker's sender role on `probation.requests`
  serves both. *(§4.8 action 5; R1-22b; §8.4 ask 2; Assumption 20)*

### Key Entities *(include if feature involves data)*

- **Processed request** (slice 002's row, keyed `(source, requestId)`): its status (`RECEIVED`,
  `RETRYING`, `COMPLETED`, `FAILED`); `failure_reason` (`leg-exhausted` or `rolled-back`, only on
  `FAILED`); `exhausted_message_id` (only with `leg-exhausted`); `completion_reason`
  (`both-legs-terminal` or `recovered`); `replayed_at`, set by a replay's admission and cleared at
  completion or final failure; `command`, the admitted body that the replay action re-sends; the
  request claim; and `audit_note`, append-only, one timestamped line per event. This slice writes
  six kinds of `audit_note` line: the replay note, the route line, submit-hearing's operator, run id
  and reason, a `payload-changed` refusal, a forced-resend reason and a recovery.
- **Leg attempt** (slice 002's row, one per leg per attempt): `attempt`; `attempt_origin` (`queue`
  for attempt 1, `resend`, `replay`); status and reason; the outcome and evidence columns; the leg
  claim and `resend_run_id`, present on resend rows only; `operator`; `override`; and `note`, whose
  closed forms this slice writes: `prev=<STATUS>/<reason>`, `prev=PENDING/-`, `rerun-by=<messageId>`,
  `override=allow-changed-payload`, `override=force-resend`.
- **Latest attempt**: the leg's highest attempt — the leg's truth, read by every report, listing and
  eligibility decision of this slice.
- **Stored body**: the exact bytes POSTed for an attempt, replaced on an in-place re-run, new for each
  new attempt, kept for the retention window (slice 003).
- **Replay message**: the stored command's bytes under a fresh `messageId`, marked `origin = REPLAY`.
- **Dead-letter entry**: a message and its reason (`validation`, `collision`, `exhausted`,
  `leg-exhausted`); the original stays after a resubmission until an operator removes it.
- **Replay route**: `service`, `support` or `copy` — how an admitted replay arrived; an audit label.
- **Resend run**: the run's `resend_run_id`, its distinct ID list, operator, reason and overrides;
  its report gives each ID one outcome.
- **Operator pause**: the operator's hold on intake, separate from the store-outage hold, shown by
  `probation_intake_suspended{cause="operator"}`; read by resend-leg and purge-queue.
- **Submit-hearing command**: a normal command built from the cached payload's raw strings, with the
  recipe's `requestId`, no `userId`, and the application properties `origin = SUPPORT`, operator,
  run id and reason.
- **Purge record**: the number of messages a purge removed and its cut-off instant, reported and
  logged; nothing about it is stored.
- **Outcome and refusal codes**: per ID, `unknown-request`, `not-eligible`, `resend-in-progress`,
  `request-claimed` (the design's); `not-run`; for a whole run or an action, `intake-paused`,
  `intake-not-paused`, `too-many-request-ids`, `store-unavailable`, `broker-unavailable`, and
  submit-hearing's `payload-unavailable` (the design's); for the input of every action that takes
  arguments — replay, resend-leg, submit-hearing and the request listing — `missing-argument`,
  `unreadable-argument` and `argument-too-long` (FR-004-020); and the outcomes `enqueued` and
  `no-op`. All are answer and report codes, never written to a row.
- **Class-1 and class-2 `FAILED`**: class 1 (`receiver-rejected`, `rules-exception`; `contract-invalid`
  reserved) is terminal at once and counts towards completion and recovery; class 2
  (`payload-unavailable`, `gateway-transient`, `payload-changed`, `rolled-back`) never completes a
  request.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-004-001**: 100% of service replays carry the stored command with zero differing bytes, under
  a `messageId` never used before for the request; none is built from the dead-letter queue.
- **SC-004-002**: Across every replay route and every latest-row state of the eligibility table,
  zero legs whose latest row is `SENT` or `NOT_APPLICABLE` are sent again by a replay, and no
  `FAILED` row is re-run twice by one replay message outside the crash window.
- **SC-004-003**: The DV-26 matrix passes in full — same id at delivery count 1 admitted; same id at
  count 2 or more dead-lettered `exhausted` with the request unchanged; a new id admitted at any
  count — every admitted replay records exactly one route and one `probation_replays_total`
  increment, and DV-26's register row names the test that proves it.
- **SC-004-004**: A resend run over N distinct IDs produces exactly N report entries, new attempt
  rows for exactly the eligible IDs, and refusal-counter increments equal to the refusals, by code.
- **SC-004-005**: A resend run refused whole — over the cap, missing, unreadable or over-long input,
  or intake paused — and any other action refused for its input (FR-004-020) writes zero rows, sends
  zero POSTs and enqueues nothing.
- **SC-004-006**: The same share passes the `sharedTime` check in 100% of cases, whichever payload
  form is read (zero false `payload-changed` refusals after the cache entry expires), and a later,
  absent or unparseable `sharedTime` is refused in 100% of cases without the override.
- **SC-004-007**: Every `FAILED` request whose resend ends `SENT`, `NOT_APPLICABLE` or class-1
  `FAILED` is `COMPLETED(recovered)` with both columns cleared, in the transaction that recorded the
  outcome; zero requests complete from a class-2 outcome; and zero `COMPLETED` requests change under
  any statement of this slice.
- **SC-004-008**: Under every tested race — two resend runs and a late queue delivery; a replay and a
  resend in either order — there is exactly one POST per leg attempt in 100% of test runs.
- **SC-004-009**: Zero log lines, metric labels, reports, listings or refusals contain a body, a
  stored command, a payload value, a `userId`, an operator's name or a reason's text, proven by the
  repository's privacy sweeps extended to this slice's output.
- **SC-004-010**: 100% of runner invocations start no queue consumer and no scheduled task, and every
  runner result is one of done, refused or failed.
- **SC-004-011**: In SIT, a message dead-lettered after `maxDeliveryCount` deliveries and then
  resubmitted from the dead-letter queue is admitted and processed to a terminal outcome — the
  evidence slice 006 needs for go-live gate row 21.
- **SC-004-012**: An operator following only the published runbook can decide, for each of the four
  dead-letter reasons, whether to resend, and can select and resend up to 100 IDs, without reading
  code — confirmed by a walk-through with the owning team.
- **SC-004-013**: The repository's build and quality gates pass with the coverage thresholds
  unchanged.
- **SC-004-014**: While intake is paused by an operator, zero deliveries are taken from the queue,
  including after a store outage and its recovery; after a resume, intake takes deliveries again.
- **SC-004-015**: Purge-queue is refused in 100% of attempts while intake is running; while it is
  paused, it removes every message enqueued before its cut-off and none enqueued after, and reports
  both the number removed and the cut-off.
- **SC-004-016**: `RequestIdRecipe` reproduces the pinned vector exactly; every submit-hearing
  command's `requestId` equals the recipe over the payload's raw strings; and zero commands are built
  from the results query API.

## Assumptions

1. **One request per replay.** The replay action takes one `requestId`; `source` is always
   `RESULTS`. A list is resend-leg's input only (§4.8 action 2; H16).
2. **Replay reuses the design's codes.** The design names only `resend-in-progress` for replay. A
   missing request is `unknown-request` and a request still `RECEIVED` or `RETRYING` is
   `not-eligible`, codes the design defines for resend-leg (A3-56); a `COMPLETED` request enqueues
   nothing and answers `no-op`. Slice 005 renders the same.
3. **Whole-run and failure codes are not the design's.** `intake-paused`, `too-many-request-ids`,
   `missing-argument`, `unreadable-argument`, `argument-too-long`, `store-unavailable`,
   `broker-unavailable`, `enqueued`, `no-op` and the report value `not-run` are names aligned with
   slice 005's draft, so that a runner and the API answer alike; `intake-not-paused`, purge-queue's
   refusal, follows the same style. The design names no code for these cases. The set is fixed in
   this spec (Key Entities); 005 renders the same set and mirrors it in its OpenAPI enums.
4. **A normal resend's reason is required but not recorded.** The design appends only a forced
   resend's reason to `audit_note` and lists no other reason event (DIGEST §8); where an unforced
   resend's reason goes is not established (A3-56, DIGEST §16). This slice persists and logs it
   nowhere. Over HTTP, slice 005's audit event names the caller. Flagged for the design owner.
5. **Operator and reason are bounded at 200 characters, and refused when longer.** The design bounds
   a forced resend's reason at 200 characters (A3-36). The same bound applies to every reason and
   every operator this slice takes — resend-leg's and submit-hearing's — and a longer value refuses
   the action instead of being truncated, as slice 005 does. Sanitising — reducing the text to one
   line without control characters — happens before anything is written to `audit_note`.
6. **The cap counts distinct IDs.** This is this slice's reading, shared with 005 (its assumption
   12): duplicates are collapsed first and the cap applies to the distinct IDs. The design states
   both rules without fixing their order (DIGEST §11; A3-56).
7. **An unreadable identifier refuses the whole run.** A value that is not a UUID is not a request,
   so it gets no per-ID entry; the per-ID codes describe requests. The same input check applies to
   every action that takes arguments (FR-004-020); the design names no input rule for any of them.
8. **"Intake paused" means the operator pause, for the whole deployment.** It is the rollback state
   (§9.4 step 1) and must hold for the whole rolled-back period (A2-52). This slice provides the
   pause on slice 002's intake control, which already holds intake during a store outage; how the
   pause reaches every replica and survives a restart is FR-004-052's open question. The pause wins
   over the store's recovery — derived from A2-52, since the copied control resumes intake by itself
   when the store answers again. Resend-leg reads the pause before a run and before each claim; the
   check before each claim is derived from A2-40's reason ("a resend would duplicate" the legacy)
   rather than stated by the design.
9. **The leg claim uses the service's claim lease** (`PT4M30S`), and a resend attempt runs within
   the processing deadline (4 min), as a queue run does (Q17).
10. **The two listings are bounded without a new property.** The `distribution.*` catalogue is closed
    (§8.1), so the plan fixes the listings' page bound in code; each listing says when more rows
    exist.
11. **Recovery is evaluated in the completion transaction.** The design evaluates recovery under the
    request-row lock (A2-49); doing it in the transaction that writes the outcome is what keeps a
    crash from leaving a `FAILED` request with a terminal latest row.
12. **IDs may run one after another or concurrently** — the plan chooses; each ID is independent,
    within its own lease and deadline. Run one after another, a full list can take up to the cap
    times the per-attempt deadline in the worst case; slice 005's open question on answering a
    resend call synchronously turns on this.
13. **The broker's tools may keep or change a copy's `messageId`.** Either is admitted (A3-31 as
    superseded; DV-26).
14. **The replay action records no operator or reason**, because the design gives it neither; its
    audit label is `origin = REPLAY`, the route `service` at admission, and — over HTTP — slice 005's
    audit event.
15. **Access to the runners is the cluster's permission to run a command in the service's pods**;
    the runners add no authorisation of their own (§4.4 Exposure row).
16. **Results change only by being re-shared.** The `sharedTime` check rests on the design's
    recorded assumption that a hearing's results change only through a new share, which brings a new
    `sharedTime`; a change made in place without a new share would not be detected (A3-57).
17. **An unexpected `origin` value is recorded as `copy`.** The design derives the route from
    `REPLAY`, `SUPPORT` or no `origin` and says nothing of another value. Since no behaviour reads
    `origin` and the route label is a closed set, any other value is treated as absent; the value
    itself is never recorded or logged.
18. **A purge's cut-off is the instant it starts.** The design has purge-queue record "the count
    removed and the cut-off instant" and has the roll-forward reconcile "every message received
    since the recorded cut-off" (A2-52), so the cut-off is the boundary: messages enqueued before it
    are removed, later ones stay for that reconciliation. Nothing about a purge is stored; the
    operator records the report on the rollback ticket.
19. **Submit-hearing's refusal and run id.** The design refuses submit-hearing when the payload is
    absent from Redis. A cached payload that cannot be read, or that carries no `sharedTime`, cannot
    give a `requestId` either and is refused the same way, `payload-unavailable`. The run id is a new
    UUID for each invocation.
20. **Submit-hearing is not refused while intake is paused.** The design gives it no such guard; its
    runbook use comes after a roll-forward has re-enabled the service (§9.4 step 7), and a command
    enqueued while paused only waits (Edge Cases).

## Dependencies

### Slices

- **002-hmpps-distribution** (required first). This slice takes from it, unchanged: the V1 schema —
  `processed_request` with `command` and `replayed_at`; `outbound_message` with `attempt_origin`, the
  leg claim, `resend_run_id`, `operator`, `override` and `note`; the latest-attempt view;
  `outbound_body` (no digest trigger, H39) — with every CHECK and the `NoteForms` forms; the enums and
  vocabularies; the guard's first-delivery, redelivery, no-op, live-claim, reclaim, collision and
  insert-race paths; the queue run's stages (payload source, youth switch, redaction, body digest,
  send, the outcome-and-body write); the settlement statements, with the completion-reason choice
  from the replay history; the request-claim-guarded leg write; intake control with its store-outage
  hold and the `probation_intake_suspended` gauge, on which this slice adds the operator pause; the
  inbound validation, with its format keywords asserted, so that a value of the wrong type or format
  is dead-lettered `validation` (002's FR-002-003), which a submit-hearing command built from an
  invalid cached `sharedTime` relies on; the startup validator; and the metric family. Where 002
  lands the copied guard with its interim rule for `FAILED` requests (002's FR-002-008), this slice
  replaces that rule with replay admission and its recorded departures: the DV-26 predicate, the
  appended `audit_note`, `replayed_at`, `REPLAY_DEFERRED`, the route, and submit-hearing's
  admission audit.
- **001-parity-kernel**: the redaction every replayed and resent run applies, unchanged.
- **003-body-retention**: no functional dependency; stored bodies for investigations exist only
  within the retention window it enforces.
- **005-operations-api** (downstream): exposes replay and resend-leg over HTTP through the same
  application services and renders this slice's outcomes and codes; it keeps the rollback's
  close-in-flight step off HTTP.
- **006-production-readiness** (downstream): the SIT negative test of go-live gate row 21; the alert
  rules over `origin = replay` and `origin = resend`, `forced` and Q28; and the rollback and cutover
  runbook inputs, which use this slice's runners (pause and resume, purge-queue, replay,
  submit-hearing, the request listing), its pinned `RequestIdRecipe` vector, the close-in-flight
  statement of rollback step 4 (FR-004-049a), and whatever answers FR-004-049 for its execution. Those inputs, not this slice's runbook, state when intake is
  resumed after a rollback — only at the end of the roll-forward: purge, reconcile every message
  received since the purge's cut-off, a full new cutover, then resume (006's FR-006-043; A2-52;
  A3-117 (ii)) — and that messages waiting on the paused queue are purged (006's FR-006-044, which
  names owed producer events). This slice hands over one more input for them: a replay or a
  submit-hearing command enqueued while intake is paused waits on the queue like any message and
  may be purged with it, to be replayed or submitted again once intake has resumed (Edge Cases).
- **No slice** builds the optional flag check (§4.8 action 7); the design leaves it optional, and the
  service checks no Results switch (H26; A3-92).

### External

- **The queue and its dead-letter queue**, created with duplicate detection off and
  `maxDeliveryCount` 5 (platform ask 1).
- **The workload identity**: receiver rights on the namespace, including the dead-letter queue the
  completed-entry listing peeks, and sender rights on `probation.requests`, which the replay action
  needs (platform ask 2; §8.2 read by H9).
- **The broker's tools**, with which operations resubmit and remove dead-letter entries.
- **HMPPS**, through the owning team: confirmation that an outcome-unknown send was not received,
  before it is sent again (§9.4 step 7).
- **The results team**: the helper test whose vector pins `RequestIdRecipe`, and Q20 — the landing
  order of the results branch chains, and whether the helper is shared or forked — after which the
  vector is re-taken.
- **The owning team (H31)**: answers FR-004-024, FR-004-049 and FR-004-052, and the
  unforced-reason default of Assumption 4.

### Pinning tests the design names (names proposed)

`ReplayActionTest` (FR-004-014–FR-004-016), `DlqResubmissionReplayIT` (FR-004-001, FR-004-002,
FR-004-005, FR-004-050), `RequestReplayStatementTest` (FR-004-001, FR-004-004, FR-004-012),
`LegRerunTest` (FR-004-007–FR-004-010), `OutcomeMetricsTest` (FR-004-011, FR-004-038),
`RequestRecoveryTransitionTest` (FR-004-012, FR-004-032–FR-004-034), `ResendByRequestIdsIT`
(FR-004-018–FR-004-023, FR-004-039, and the resend halves of DV-07 and DV-17, FR-004-050a),
`ResendSharedTimeCheckIT` (FR-004-029–FR-004-031), `ResendConcurrencyIT` (FR-004-021, FR-004-034,
FR-004-035), `ForcedResendIT` (FR-004-036–FR-004-038, FR-004-006), `RollbackFencingIT` (the
close-in-flight statement, FR-004-049a, in three cases: a late queue runner and a late leg-outcome
UPDATE of a queue or replay row, FR-004-013; a late resend runner, FR-004-028), `RequestIdRecipeTest`
(FR-004-055), and the `PropertiesValidatorTest` case for `max-ids-per-run` (FR-004-019).

## Out of Scope

- HTTP exposure of any action (slice 005).
- The optional flag check (§4.8 action 7), and the HTTP exposure of every action other than replay
  and resend-leg (O-1).
- The rollback and cutover protocols and their runbook (§9.3, §9.4; not this repository's code),
  beyond the interplay of FR-004-048, the close-in-flight statement (FR-004-049a) and the open
  FR-004-049.
- Alert rules and dashboards (slice 006), including Q28: whether `FAILED(payload-changed)` and
  `FAILED(rolled-back)` page.
- Counting a crash during a replay's re-run of a `FAILED` row, or a resend's reclaim — the design has
  not established how (A3-115). A redelivery that re-runs an expired `PENDING` replay-created row is
  likewise not counted: the design's metric counts only `PENDING` attempt-1 re-runs and leaves the
  rest not established (A3-115; 002's FR-002-061).
- An abandon or redelivery delay for duplicate copies: the noise is accepted (H15).
- Selection of resend requests by status, date or count (withdrawn, H16), a payload digest or
  baseline (removed, H17), and any outbound validation stage (parked, H13).
- Automatic removal of dead-letter entries.
- Whether `distribution.resend.max-ids-per-run` binds an environment variable (open, DIGEST §16;
  the scaffold binds none).
- Any change to what is sent to HMPPS (D10), and any VEP leg (H5).

## Design traceability

| Design id | What it fixes in this slice | FRs |
|---|---|---|
| H9 (a); DV-26; A3-30 | A dead-letter copy is a replay; same id admitted at delivery count 1 only; one admission chance; replaces 002's interim FR-002-008 | FR-004-001, FR-004-002, FR-004-005, FR-004-046, FR-004-050; 002's FR-002-008 (replaced) |
| H9 (b); A2-48; §4.8 action 2 | The service's replay re-sends the stored command under a fresh `messageId`, at any time | FR-004-014–FR-004-017, FR-004-042, FR-004-048 |
| H9 (c); A3-36 | Forced resend of a `SENT` or `NOT_APPLICABLE` leg, with operator and reason, request untouched | FR-004-036–FR-004-038, FR-004-042 |
| H9; A3-36 | A replay of a `COMPLETED` request is a no-op by either route | FR-004-006, FR-004-014 |
| A3-31 (superseded by H14) | No duplicate-detection wait or `messageId` edit | FR-004-046 |
| A3-32; A3-33 | Which dead-letter codes to resend; remove the original after completion; the `dlq-completed` listing | FR-004-043, FR-004-046 |
| A3-34 | The replay route recorded, an audit label only | FR-004-005, FR-004-011, FR-004-040 |
| A3-35 | Replay at any time; the runners as the fallback | FR-004-016, FR-004-042 |
| H12; A3-47 | Every duplicate is genuine at HMPPS | FR-004-038, FR-004-047 |
| H13; A3-50 | No validation stage in a replayed or resent run | FR-004-026, FR-004-036 |
| H14; A3-53 | Every copy reaches the guard; the replay id stays fresh for distinctness | FR-004-006, FR-004-015, FR-004-046 |
| H15; A3-54; A3-55 | Outcome-unknown sends are not retried in-run; duplicate-copy noise accepted | FR-004-027, FR-004-046 |
| H16; A3-56; Q16 | Explicit ID list, cap, per-ID refusals, report | FR-004-018–FR-004-023, FR-004-039, FR-004-047 |
| H17; A3-57; A2-24 | The `sharedTime` check, as instants, before any leg stage | FR-004-029, FR-004-031 |
| A3-58 | `payload_shared_time` recorded | FR-004-030 |
| H7; H8 | The switch read on every run kind; an unreadable switch defaults and sends | FR-004-026 |
| H20; A3-63 | The body stored with the outcome; replaced on an in-place re-run | FR-004-008, FR-004-028 |
| H30; DV-17 | The system user on the query-API read: on every resend, and on a replay whose command has no `userId`; DV-17's resend half pinned here | FR-004-007, FR-004-026, FR-004-050a |
| H25; §9.2 row 21 | SIT negative test: a dead-letter resend admitted and processed | SC-004-011 (evidence for slice 006) |
| Q19 | A resend after the cache entry expires sends the query API's form as it comes | FR-004-047 |
| A2-50 | The leg claim and `resend_run_id` present on resend rows only, kept as the record of who ran them | FR-004-025 |
| DV-07; R2-8 | A resend never parks; DV-07's resend half pinned here | FR-004-027, FR-004-050a |
| A2-27; A2-49 | Completion is historical; no statement moves a `COMPLETED` request | FR-004-034, FR-004-038 |
| A2-34 | The dead-letter codes; the inherited `exhausted` paths | FR-004-001, FR-004-003 |
| A2-37; A2-41; A2-47 | `recovered` by replay history; `audit_note` append-only | FR-004-004, FR-004-012 |
| A2-38; R1-8 | Recovery after a resend by the settlement criterion | FR-004-032, FR-004-033 |
| A2-39; A2-68 | In-place re-run in one update, with the `prev=` note | FR-004-008 |
| A2-40; A2-43; R1-28b | Rolled-back rows: attempt 1 by replay, attempt ≥ 2 by resend; a rolled-back resend row leaves its request as it was; the request outcomes of the close; resend refused while paused | FR-004-021, FR-004-023, FR-004-033, FR-004-048, FR-004-049a |
| A2-42; A2-60 | The replay column keyed on `replayed_at`, on every delivery | FR-004-007, FR-004-012 |
| A2-45; A2-64 | `rerun-by`: a `FAILED` row once per replay message; `PARKED` every delivery | FR-004-008, FR-004-010 |
| A2-46; A2-59; A2-63 | Append a replay row over `FAILED` attempt ≥ 2; never over `PENDING`; a resend row never re-run in place by a replay | FR-004-009 |
| A2-51; A2-67 | Fencing: token rotation for resend rows; request-claim-guarded writes for queue and replay rows; `RollbackFencingIT`'s three cases | FR-004-013, FR-004-028, FR-004-049a |
| A2-52 | Intake stays paused for the whole rolled-back period; resumed only at the end of the roll-forward, which 006's inputs state | FR-004-021, FR-004-052 (open), FR-004-053; Dependencies (006) |
| §4.8 action 4; A2-57; §9.4 step 1 | Pause and resume intake; the `operator` cause of `probation_intake_suspended` | FR-004-042, FR-004-051, FR-004-052 |
| O-2 | One or two replicas, which an operator pause must cover | FR-004-052 (open) |
| §4.8 action 6; R1-28b; §9.4 step 6 | Purge-queue only while intake is paused; the count and the cut-off reported | FR-004-042, FR-004-053 |
| §4.8 action 5; R1-22; R1-22b | Submit-hearing: a normal command marked as support, from the cache, by the sender role | FR-004-042, FR-004-054, FR-004-056–FR-004-058 |
| A2-4; A2-25; DIGEST §14; Q20 | `RequestIdRecipe` over the raw strings, pinned by results' vector; re-taken when Q20 lands | FR-004-055 |
| A3-117; §9.3 | The roll-forward order, which 006's inputs state; an urgent rollback's pre-T messages go to a re-share | FR-004-053; Dependencies (006); Edge Cases |
| A2-55 | A message never admitted is repaired by a resend or submit-hearing, not by replay | FR-004-046 |
| A2-58 | `note` bounded at 256 characters | FR-004-015 |
| A2-62 | A NULL `exhausted_message_id` is admitted | FR-004-001, FR-004-004, FR-004-048 |
| A2-65 | Lock order request then leg; replay and resend mutually fenced | FR-004-022, FR-004-035 |
| A2-69; Q17; §4.7 | Stage budgets, the attempt-fits rule, deadline before lease | FR-004-026 |
| A2-70; A2-72 | Replay deferred or refused over a `PENDING` resend row, live or expired | FR-004-003, FR-004-014, FR-004-035 |
| A2-71 | `runOrigin` captured at admission; a support command runs as a queue delivery unless it meets a `FAILED` request | FR-004-011, FR-004-040, FR-004-057 |
| A3-61 | Choosing resend IDs by `service_version` | FR-004-047 |
| R1-5; R2-7 | A replay re-runs only `FAILED` and `PARKED` legs; payload outcome to eligible legs only | FR-004-007 |
| R1-24 | Per-attempt audit columns; append-only `audit_note` | FR-004-025, FR-004-037 |
| R1-31; §10.1; §10.2; §10.3 | One outcome metric labelled by origin and `forced`; alerts read all origins; log fields | FR-004-011, FR-004-017, FR-004-038–FR-004-040 |
| §4.4 claim contract (b), (d), (e) | Resend claim committed before the POST; token-guarded completion | FR-004-025, FR-004-028 |
| §4.4 claim contract (c) against A3-56 | An expired `PENDING` resend row — design conflict | FR-004-024 (open) |
| §4.4 Exposure row; §4.8 | Runners in the image, as the pod | FR-004-042–FR-004-045 |
| §4.8 action 1 | The read-only request listing | FR-004-043 |
| §4.8 runbook; "Resending a leg" | The dead-letter and resend runbook | FR-004-046, FR-004-047 |
| §9.4 step 4; A2-43; A2-51; A2-66; DIGEST §7 "Rollback step 4" | The close-in-flight statement, pinned by `RollbackFencingIT` (late queue runner, late resend runner, late leg-outcome UPDATE); no executor designed | FR-004-049a; FR-004-049 (open) |
| §9.4 steps 6a, 7 | Replay as the route back; repair cautions | FR-004-016, FR-004-047, FR-004-048 |
| Constitution Principle VII; DV-09; A3-60 | No body, command, payload, user id or free text in telemetry or output | FR-004-020, FR-004-041 |
| Constitution Principles I, II | DV-26's row names its pinning test | FR-004-050 |
| A3-115 | Crash re-runs beyond a `PENDING` attempt 1 are not counted | FR-004-008; Out of Scope |
| Q28 | Whether `payload-changed` and `rolled-back` page | Out of Scope (slice 006) |
| O-1 | HTTP exposure of the other actions | Out of Scope (slice 005) |
| §4.8 action 7; H26; A3-92 | The optional flag check | Out of Scope (not built) |
