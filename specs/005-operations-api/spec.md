# Feature Specification: Operations API — replay and resend-leg over HTTP

**Feature Branch**: `005-operations-api`
**Created**: 2026-10-21
**Status**: Draft
**Input**: Design rev 3.6 (2026-10-20; decisions H1–H34). H9 and rows A3-35 and A3-36 put
**replay** and **resend-leg**, the forced-resend override included, on an `/operations/**` API
copied from the sibling YOT service, behind the five deployment gates of §4.8, with the
command-line runners in the image kept as the fallback; O-1 (§11.1) leaves the HTTP exposure of
every other operator action open. Rules taken from `doc/design/DIGEST.md` §2 (scope), §11
(operator actions), §12 (the closed property catalogue), §13 (observability), §15 (platform asks 2,
12 and 14) and §16 (O-1, the A3-56 and §4.4 (c) items, and the design text read by its decisions),
each checked against the design; constitution 2.0.0, Principles III, V, VI and VII.

## Context

Design rev 3.6 decides two things operators do over HTTP. **Replay** re-sends a `FAILED`
request's stored command under a fresh message identity, at any time; after a rollback it is the
only route back, because the rollback purges the queue and the dead-letter queue holds no copy
(H9 (b); §9.4 step 6a). **Resend-leg** re-runs the HMPPS leg for an explicit list of `requestId`s,
each checked and refused on its own; with the `allowTerminalSuccess` override it sends again a
hearing already recorded `SENT` or `NOT_APPLICABLE` — a forced resend, a genuine duplicate at HMPPS
because the results POST is not idempotent (H9 (c), H12, H16; rows A3-36, A3-56). The HTTP exposure
of the other operator actions — list, pause and resume intake, submit-hearing, purge-queue, the
optional flag check and the recommended `dlq-completed` listing — is still open (O-1).

This slice is the HTTP surface for those two actions and nothing behind it. The actions are slice
004's: the replay action; the resend-leg capability with its whole-call input rules (duplicates,
the empty list, the cap, the operator and reason bounds), its forced override, its `sharedTime`
check, its per-ID refusals and report, and its recording of operator, override and reason; and the
command-line runners for both. This slice parses a request, has it authorised and audited, calls
the application service the runner calls, and renders the result. It holds no eligibility rule, no
lock, no store write and no broker call of its own.

Three things set this API apart from the sibling YOT service's, whose shape it copies:

1. **The runners stay.** YOT's operations API replaced its command-line interface. Here the runners
   in the image remain the fallback for both actions: without the gates, replay and resend-leg run
   only through them (§4.8; platform ask 14).
2. **Two actions, not every action.** Anything beyond replay and resend-leg waits for O-1 and a
   constitution amendment (constitution Principle III).
3. **No switch of its own.** The property catalogue is closed (§8.1), so nothing in the service
   serves or hides the API. What makes it safe to serve is the five gates, all outside this
   repository.

An HTTP call adds attribution over a runner: the call is authorised against the caller's
users-groups membership and published to the estate's audit context, where a runner runs as the
pod for whoever holds exec rights on the namespace. It also adds reach, which is what the gates are
for: without the gateway stripping and injecting `CJSCPPUID`, and the mesh policies restricting the
path to that gateway, the identity on a request is whatever a caller inside the mesh chose to
assert.

This slice adds no deviation (DV) row. The function app has no operator surface and no replay tool
(§9.4), so there is no legacy behaviour to deviate from, and no ported rule is touched
(constitution Principle I).

## Clarifications

### Session 2026-10-21

- Q: Which operator actions does this slice put on HTTP? → A: Replay and resend-leg, the
  forced-resend override included, and nothing else. O-1 is still open for list, pause and resume
  intake, submit-hearing, purge-queue, the optional flag check and the `dlq-completed` listing;
  each stays a command-line runner, and exposing one takes O-1 decided plus a constitution
  amendment (H9; rows A3-35, A3-36; §11.1 O-1; constitution Principle III).
- Q: §4.1's identity table still calls the operations API "recommended, open". Is it in scope? →
  A: Yes. H9 decides it for replay and resend-leg; the table is read by the decision (DIGEST §16,
  design text that contradicts its own decisions).
- Q: What are the five gates? → A: Exactly §4.8's list: an internal ingress or APIM route; the
  gateway stripping and injecting `CJSCPPUID`; an Istio AuthorizationPolicy and a NetworkPolicy;
  users-groups reachable for the authorisation filter; an Artemis audit connection (platform ask
  14). Rate limiting is not among them; the only bound the design sets is the resend list cap
  (H16).
- Q: Does the API replace the command-line runners, as YOT's did? → A: No. The runners stay as the
  fallback for both actions, and the API and its runner call one application service (§4.8, the
  O-1 paragraph; §4.4 Exposure row; ask 14).
- Q: Can replay be called outside a rollback, or while intake is paused? → A: At any time, for
  general use (H9 (b); row A3-35); after a rollback it is the route back once the Results switch is
  on again (§9.4 step 6a). The design does not address replay while intake is paused: this spec
  reads "at any time" to include it, as slice 004 does, and adds no refusal of its own
  (assumption 11).
- Q: Does replay take one request or a list? → A: One `requestId` per call. The design replays "a
  `FAILED` request" (§4.8 action 2); an explicit list is resend-leg's input only (H16).
- Q: Does replay take an operator and a reason? → A: No; the design gives replay neither. The
  caller is named in the call's audit event, and the replayed run records its route, `service`, at
  admission (§4.8 action 2; row A3-34).
- Q: How is a replay of a `COMPLETED` request answered? → A: As a success that changed nothing,
  outcome `no-op`. The design calls it a no-op, not a refusal (H9; row A3-36).
- Q: What does replay answer for a request it cannot replay? → A: The design names one code,
  `resend-in-progress` (rows A2-70, A2-72). It names none for a missing request or for one still
  `RECEIVED` or `RETRYING` ("`FAILED` requests only", §4.8 action 2), so the spec reuses the
  design's own codes, `unknown-request` and `not-eligible` (row A3-56), rather than coining new
  ones; rendering refusals is this spec's to settle (technical rules, "Error Handling").
- Q: Is the forced resend an action of its own? → A: No. It is an override of resend-leg, "not a
  new mechanism", so it shares resend-leg's endpoint, inputs, allow rule and cap (row A3-36).
- Q: Can a forced resend select by hearing, day or date range? → A: No. Since rev 3.5 every
  resend, forced or not, takes an explicit `requestId` list (H16; row A3-36 as amended by A3-56).
- Q: What does resend-leg take? → A: `requestIds` (1 … `distribution.resend.max-ids-per-run`,
  duplicates collapsed), the leg (`HMPPS`), `operator`, a reason, and optionally
  `allowTerminalSuccess` and the allow-changed-payload override (row A3-56; names proposed).
- Q: Who collapses duplicates, refuses an empty list and applies the cap — the endpoint or the
  action? → A: The action. DIGEST §11 (action 3) and row A3-56 state them as rules of resend-leg,
  which the runner and the API share, so slice 004's resend-leg capability applies them for both
  front doors (FR-004-019): one rule in one place. The endpoint reads each entry as a UUID, passes
  the list as sent and renders 004's refusals. Whether the cap counts the IDs as listed or the
  distinct IDs is not fixed by the design; this spec reads it as the distinct IDs, as 004 does
  (assumption 12).
- Q: Does a per-ID refusal decide the call's status? → A: No. Each ID is refused on its own and the
  report lists every ID; refusal codes are answer and report codes, never row values (row A3-56).
- Q: How is the whole resend-leg call refused while intake is paused? → A: The design refuses it
  (row A2-40) and names no code; the spec names it `intake-paused`, a `409`.
- Q: Where do the operator and the reason go? → A: Both are required on every resend — slice 004
  refuses a call without them (FR-004-020) — and the endpoint passes them unchanged to slice 004,
  which records `operator` on every new attempt row and appends a forced resend's sanitised
  reason, at most 200 characters, to `processed_request.audit_note` (row A3-36; §4.4 Audit row).
  Where an unforced resend's reason is recorded is not established (A3-56; DIGEST §16) and stays
  with 004.
- Q: Is `operator` the authenticated caller? → A: No: the design takes it as an input of
  resend-leg (row A3-56; the runner's `--operator`). The authenticated caller is recorded
  separately, in the call's audit event; the service does not compare the two.
- Q: How are refusals rendered? → A: As the sibling YOT service's operations API renders them,
  copied with the API (§4.8): RFC 9457 problem details with a bounded `reason`, status families
  that separate "declined, nothing changed" from "tried and could not", and no caller value echoed.
  Codes are lower-kebab, like the design's own.
- Q: What if an audit event cannot be published? → A: The design does not say: §4.8 copies the
  API's shape and its gates from YOT, not this behaviour, and the sibling service records it as
  open rather than decided. This spec chooses it under constitution Principle VI, modelled on the
  sibling: the request event is published before the action and a failure refuses the call,
  `503 audit-unavailable`, with nothing changed; a response event that fails after the action is
  logged at ERROR and counted. Because the estate audit library's default publisher logs and
  discards its own publishing failures, the service supplies its own publisher in its place. The
  lost-response-event shortfall needs the owning team's sign-off (Dependencies).
- Q: Does the API get a property to switch it on or off? → A: No. The `distribution.*` catalogue is
  closed (§8.1; DIGEST §12); the authorisation and audit switches stay as scaffolded, on by default.
  Where the API may be served is decided by the gates (ask 14).
- Q: Which identity enqueues a replay? → A: The pod's workload identity, which needs the Service
  Bus data-sender role on `probation.requests` (ask 2). §8.2 grants it for submit-hearing only; it
  is read by H9, because the replay action enqueues too (DIGEST §16).
- Q: Does the API read App Configuration or the Results switch? → A: Neither (H26; rows A3-69,
  A3-92; Q15).

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Replay a `FAILED` request over HTTP (Priority: P1)

An operator has a `FAILED` request — a leg that used up its deliveries, or a request a rollback
closed — and asks for it to be replayed, by its `requestId`. The service re-sends the command it
stored when it admitted the request, byte for byte, under a fresh message identity marked
`origin = REPLAY`, and answers as soon as the command is on the queue. The replayed run is then
processed like any delivery, and its outcome is read where every run's is: on the request's leg
rows. A `COMPLETED` request is answered as a no-op, and a request with a resend in flight is
refused until the resend has finished.

**Why this priority**: H9 makes replay an API route for general use, and after a rollback it is
the only route back (§9.4 step 6a): the rollback purged the queue and dead-lettered nothing, so the
dead-letter queue holds no copy to resubmit.

**Independent Test**: with slice 004's replay action stubbed at its service boundary, call the
replay endpoint once for each result the action can give and assert the status, the outcome and
that no caller value comes back; then, against the store and the broker emulator, replay one
`FAILED` request and assert that exactly one command was enqueued, identical to the stored one,
under a fresh message identity with `origin = REPLAY`.

**Acceptance Scenarios**:

1. **Given** a `FAILED` request whose leg has no `PENDING` resend row, **When** an admitted
   operator calls the replay endpoint with its `requestId`, **Then** the stored command is
   enqueued once under a fresh message identity with `origin = REPLAY`, and the answer is `200`
   with the `requestId`, the outcome `enqueued` and the message identity used.
2. **Given** a `COMPLETED` request, **When** it is replayed, **Then** nothing is enqueued and
   nothing changes, and the answer is `200` with the outcome `no-op`.
3. **Given** a `FAILED` request whose leg's latest row is a `PENDING` resend row, live or expired,
   **When** it is replayed, **Then** the answer is `409 resend-in-progress` and nothing is
   enqueued.
4. **Given** a well-formed `requestId` that names no request, **When** it is replayed, **Then** the
   answer is `404 unknown-request`.
5. **Given** a request that is `RECEIVED` or `RETRYING`, **When** it is replayed, **Then** the
   answer is `409 not-eligible` and nothing is enqueued.
6. **Given** a request a rollback closed as `FAILED(rolled-back)`, with no copy in the dead-letter
   queue, **When** it is replayed, **Then** it is enqueued like any other `FAILED` request.
7. **Given** intake paused by an operator, **When** a `FAILED` request is replayed, **Then** the
   endpoint adds no refusal of its own: the answer is the action's outcome, `enqueued`, and the
   command waits on the paused queue (assumption 11).
8. **Given** a `FAILED` request, **When** it is replayed with no body or with the empty object
   `{}`, **Then** the call is served; **and when** it is replayed with a body carrying any field —
   `operator` or `reason` included — **Then** it is refused `400 unknown-field` and nothing is
   enqueued.

---

### User Story 2 - Resend the HMPPS leg for an explicit list of requests (Priority: P1)

An operator has chosen the requests to resend — with the list runner or the documented query on
`outbound_message_latest`, a separate, visible step — and sends their `requestId`s with the leg,
their name and a reason. Each ID is checked on its own: an eligible one gets a new `resend` attempt
and its outcome; any other is refused with its own code, without holding up the rest. The answer
lists every ID.

**Why this priority**: resend-leg is the only way back for a leg no queue message will revisit
(§4.4; Q16), and the forced resend of story 3 rides on it.

**Independent Test**: with the resend-leg capability stubbed, assert that the call reaches it with
the list as sent and every other input unchanged, and that each report shape and each whole-call
refusal the capability returns renders; end to end, send the design's mixed list — two eligible
`FAILED`, one unknown, one `SENT` without the override, one under a live request claim — and assert
two new attempts, three refusals with their codes and one report entry per ID (row A3-56). The
rules themselves — collapsing duplicates, the empty list and the cap, the `sharedTime` check and
what each attempt records — are slice 004's and pinned by its tests.

**Acceptance Scenarios**:

1. **Given** the mixed list above, **When** it is sent with an operator and a reason, **Then** the
   report has five entries: two carrying the leg's resulting status and reason, and
   `unknown-request`, `not-eligible` and `request-claimed` for the other three.
2. **Given** a list naming one request twice, **When** it is sent, **Then** the list reaches slice
   004 as sent, and the answer renders 004's report, which holds that request once.
3. **Given** a list of more distinct IDs than `distribution.resend.max-ids-per-run`, **When** it is
   sent, **Then** the answer renders slice 004's whole-call refusal as `400 too-many-request-ids`
   carrying the cap applied, and nothing is run.
4. **Given** intake paused by an operator, **When** any list is sent, **Then** the answer is
   `409 intake-paused` and nothing is run.
5. **Given** a listed request whose re-read payload carries a different `sharedTime`, **When** the
   list is sent without `allowChangedPayload`, **Then** the call reaches slice 004 with that
   override `false` and the ID's report entry renders `FAILED(payload-changed)`; **and when** it is
   sent with `allowChangedPayload: true`, **Then** the call reaches 004 with the override `true`,
   the answer says it was requested, and the entry renders the attempt's outcome.
6. **Given** a list holding a value that is not a UUID, **When** it is sent, **Then** the answer is
   `400 unreadable-argument` naming `requestIds`, and nothing is run.
7. **Given** a call without an operator or without a reason, **When** it is sent, **Then** the
   answer renders slice 004's whole-call refusal as `400 missing-argument` naming the argument, and
   nothing is run.

---

### User Story 3 - Force a resend of a hearing already sent, on the record (Priority: P1)

HMPPS sometimes needs again a hearing the service recorded as sent (H9 (c)). The operator lists it
with the `allowTerminalSuccess` override, their name and a reason. The hearing is re-read, the youth
switch re-read, the rules re-applied and the body sent on a new `resend` attempt; the request stays
`COMPLETED`. Because the HMPPS POST is not idempotent the send is a genuine duplicate at HMPPS, so
the override is explicit, made per call, and written down three times: the operator on the attempt
row with `override=force-resend` and the reason on the request's `audit_note`, both by slice 004,
and the caller in the call's audit event, by this slice.

**Why this priority**: it is the only supported way to send a hearing to HMPPS again once its
latest row is `SENT` or `NOT_APPLICABLE`, and the design makes it an API route (row A3-35). It is
also the one action here that deliberately duplicates at the receiver.

**Independent Test**: with slice 004's resend-leg capability stubbed, send a list with the
override, an operator and a reason, and assert that the capability receives
`allowTerminalSuccess: true` with the operator and the reason unchanged, that the answer and both
audit events say the override was requested and the events name the caller, and that every entry
the capability returns renders — a `FAILED` one under a successful call included; end to end, send
a request whose latest HMPPS row is `SENT` with the override and then without it, and assert one
new attempt and then `not-eligible`. What the forced resend records — the attempt row with the
operator and `override=force-resend`, the reason line on `audit_note`, the request staying
`COMPLETED` — is pinned by slice 004's tests.

**Acceptance Scenarios**:

1. **Given** a request whose latest HMPPS row is `SENT`, **When** it is listed with
   `allowTerminalSuccess: true`, an operator and a reason, **Then** the call reaches slice 004 with
   `allowTerminalSuccess: true`, the operator and the reason unchanged; the answer and both audit
   events say the override was requested; and the report entry renders the attempt's outcome.
2. **Given** the same request listed without the override, **When** the call is sent, **Then** its
   entry renders slice 004's `not-eligible`, and the call still succeeds.
3. **Given** a request whose latest HMPPS row is `NOT_APPLICABLE`, **When** it is listed with the
   override, **Then** it is handled as in scenario 1.
4. **Given** a reason, or an operator, longer than 200 characters, **When** the call is sent,
   **Then** the answer renders slice 004's whole-call refusal as `400 argument-too-long` naming
   the argument, and nothing is run (FR-005-016; assumption 4).
5. **Given** any call, **When** it is answered, logged or counted, **Then** neither the operator
   nor the reason appears in the answer, in a log line or in a metric label.
6. **Given** a forced resend whose send fails, **When** the call is answered, **Then** the call
   still succeeds and the entry renders `FAILED` with its reason: a failed send is a per-ID
   outcome, never the call's status (FR-005-014).

---

### User Story 4 - Only admitted callers reach either action, and every call is audited (Priority: P1)

Every call is authorised before any action is reached, against the caller's users-groups
membership, by an explicit allow rule per action, denying by default. Every admitted call is
published to the estate's audit context twice: before the action and after it.

**Why this priority**: authorisation and audit are what make an HTTP route to a forced resend
acceptable at all. A route anyone could call, or one nobody audits, is worse than the runner it
sits beside.

**Independent Test**: for each endpoint, call it with no identity, with an identity outside the
admitted group or groups, and with one inside, through the authorisation filter wired as deployed
and the identity service stubbed at its HTTP boundary; assert the refusals, the service call and
the two audit events.

**Acceptance Scenarios**:

1. **Given** a request with no `CJSCPPUID`, **When** it reaches either endpoint, **Then** it is
   refused `401` and nothing is read or changed.
2. **Given** a caller outside the admitted group or groups, **When** they call either endpoint,
   **Then** the call is refused `403` before any application service is reached.
3. **Given** an identity service that cannot be asked, **When** any call arrives, **Then** it is
   refused `403`: the authorisation fails closed.
4. **Given** an admitted call, **When** it is answered, **Then** one audit event was published
   before the action and another after it, naming the caller, the action and the outcome, and
   neither carries a request or response body.
5. **Given** an admitted call whose request event cannot be published, **When** it arrives,
   **Then** it is refused `503 audit-unavailable` and nothing is read, enqueued, sent or written.
6. **Given** a caller admitted to replay who sends an action header naming resend-leg, **When** the
   call is authorised, **Then** it is authorised as the action its path and method name.
7. **Given** the authorisation rules, **When** they are read, **Then** there is exactly one allow
   rule per action and none admits by default.
8. **Given** Actuator, **When** health, readiness or metrics are called, **Then** they answer as
   before, outside both filters.

---

### User Story 5 - Every refusal is bounded and quotes nothing the caller sent (Priority: P2)

A runbook step must be able to tell "declined, nothing changed" from "tried and could not", and
nothing an operator types may come back out in an answer or a log line.

**Why this priority**: both properties are cheap to lose and expensive to notice; the second is
constitution Principle VII.

**Independent Test**: drive each endpoint into each of its refusals and assert the status, the
bounded reason, the argument name where there is one, and the absence of every supplied value from
the answer and the log.

**Acceptance Scenarios**:

1. **Given** an argument that cannot be read, **When** the call is answered, **Then** the status is
   `400`, and the answer names the argument by this service's name for it and does not contain the
   value sent.
2. **Given** any refusal or failure, **When** it is answered, **Then** the body is RFC 9457 problem
   details whose `reason` comes from the closed set the OpenAPI document names, with no exception
   message, no stack trace and no store's or far end's own words.
3. **Given** a successful call, **When** it is answered, **Then** each `requestId` it carries is
   the service's parse of the identifier, in canonical form, never the characters sent.
4. **Given** a body field the contract does not name, **When** the call is sent, **Then** it is
   refused `400 unknown-field`, not ignored.
5. **Given** the store is unavailable, **When** either endpoint is called, **Then** the answer is
   `503 store-unavailable` and nothing has changed.
6. **Given** the broker cannot take the replay's command, **When** replay is called, **Then** the
   answer is `503 broker-unavailable`, nothing has changed in the store, and the call may be made
   again.
7. **Given** a path or method nobody mapped, **When** it is called, **Then** it is answered without
   a stack trace and without quoting the path.
8. **Given** a `5xx` answer, **When** it is given, **Then** it is also logged at ERROR and counted.

---

### User Story 6 - The API is described before it is built, and agrees with its description (Priority: P2)

The OpenAPI document this repository owns describes the two operations, every answer they can give
and every reason they can refuse with, and it changes before the code that serves it.

**Why this priority**: the document is a contract this repository owns (constitution Principle
III), and the audit filter reads it to name the paths it audits.

**Independent Test**: a contract test compares the served operations, statuses, reasons and
closed value sets with the document, both ways.

**Acceptance Scenarios**:

1. **Given** the document, **When** it is read, **Then** it describes exactly the two operations,
   with each request, success shape, status and `reason` they can answer, under a version above
   the placeholder's `0.0.0`.
2. **Given** the served endpoints, **When** the contract test runs, **Then** every operation and
   every `reason` the code can answer is in the document, everything the document describes is
   served, and each closed value set in the document — `leg`, the per-ID status, reason and refusal
   code, replay's outcome — equals the service's own vocabulary (FR-005-035).
3. **Given** the change history, **When** it is read, **Then** the document and the summary in
   `doc/API_CONTRACTS.md` changed before the code that serves them.

---

### User Story 7 - The API and its command-line fallback give the same answer (Priority: P3)

Where the gates are not yet in place, or users-groups or the audit context is down, operators run
the same actions through the runners in the image (slice 004). The two are one capability with two
front doors, never two implementations.

**Why this priority**: the runners are the documented fallback (§4.8). If the API drifted from
them, an operator falling back in the middle of an incident would meet different rules.

**Independent Test**: run the same mixed list through the runner and through the API, each against
an identically seeded store, and compare the reports ID for ID.

**Acceptance Scenarios**:

1. **Given** the same inputs and identically seeded stores, **When** a resend is made through the
   API and through the runner, **Then** both give the same whole-call refusal, or both report the
   same outcome or the same refusal code for every ID.
2. **Given** a replay refused through the API, **When** the same request is replayed through the
   runner in the same state, **Then** it is refused with the same code.
3. **Given** this slice is built, **When** the image is inspected, **Then** the replay and
   resend-leg runners are still present and documented as the fallback.

---

### Edge Cases

- **A forced resend repeated after a lost answer.** Every call is a new call. A repeated forced
  resend sends the hearing again, a further genuine duplicate at HMPPS (H12); the API takes no
  idempotency key and none is designed. The operator reads the latest row first (the list runner
  or the documented query). This risk is what the open question in FR-005-015 weighs.
- **A replay repeated after a lost answer.** A second replay command is enqueued under another
  fresh identity, and the guard makes it harmless: a `COMPLETED` request completes as a no-op, and
  a copy meeting a live claim is abandoned — possibly dead-lettered `exhausted` in seconds, the
  accepted noise the runbook removes once the request is `COMPLETED` (rows A3-53, A3-55).
- **Replay while intake is paused.** Accepted, on this spec's reading of H9 (b) "at any time"
  (assumption 11). The command waits on the paused queue. A rollback purges the queue at its step 6,
  and the roll-forward purges it again before intake resumes (§9.4 step 6; §9.3 "Rolling forward
  after a rollback"; rows A2-52, A3-117), so such a command can be purged — and counted in that
  purge's count — while the request stays `FAILED`, to be replayed again once the Results switch is
  on again (§9.4 step 6a).
- **Replay of a request a rollback closed.** `FAILED(rolled-back)` carries no exhausted message id
  and is admitted as a replay (row A2-62); the dead-letter queue holds no copy, so the API or its
  runner is the only route.
- **Replay and resend racing on one request.** Slice 004's lock order decides (row A2-65): a
  resend whose row came first makes the replay `resend-in-progress`; a replay whose claim came
  first makes that ID's resend entry `request-claimed` (rows A2-70, A2-72).
- **Two resend calls listing the same ID at once.** One takes the leg; the other's entry is
  `resend-in-progress` or `request-claimed`. There are never two POSTs for it (row A2-65).
- **An expired `PENDING` resend row.** The design conflicts: reclaimed in place (§4.4 claim
  contract (c)) or refused `resend-in-progress` (row A3-56) (DIGEST §16). The entry is whatever
  slice 004 settles; the API adds nothing of its own.
- **`allowTerminalSuccess` with only `FAILED` IDs.** They proceed as ordinary resends: the
  override is used only by an ID whose latest row is `SENT` or `NOT_APPLICABLE` (004). The answer
  still says the override was requested.
- **The store becomes unavailable part-way through a resend.** The IDs already taken keep their
  outcomes on their rows and in 004's run report line. How the answer reports the rest follows the
  answer to FR-005-015.
- **A pause that begins during a resend.** Slice 004 claims no further ID and reports the IDs it
  had not reached as `not-run` (FR-004-021); where the answer carries the report (FR-005-015), it
  renders them so, under a successful call.
- **The store is unavailable before the call.** `503 store-unavailable`, nothing changed.
  Readiness is the store, so a pod in that state is normally taken out of the route anyway.
- **The broker cannot take a replay's command.** `503 broker-unavailable`, nothing changed in the
  store, safe to repeat. An enqueue whose outcome is unknown may have landed; a repeat then gives a
  second replay command, harmless as above.
- **users-groups is unavailable.** Every call is refused `403` (fail-closed). Operators use the
  runners, which run as the pod and make no group lookup.
- **The audit transport is off in a deployed environment.** Nothing is published, so calls are
  served unaudited, and the pod says so once at start-up. This is not the refusal of FR-005-027,
  which is for a publish that is attempted and fails; gate 5 and the deployment review exist to
  stop it (FR-005-028).
- **A caller naming the other action in a header.** Ignored: the action comes from the path and
  the method (FR-005-022).
- **One ID listed in two spellings** (upper and lower case). Both parse to one UUID; slice 004
  collapses it, and it is run once and reported once, in canonical form.
- **Exactly the cap after collapsing, or one more.** Accepted, or refused whole by slice 004
  (assumption 12).
- **A reason with line breaks or control characters.** Passed to slice 004, which sanitises it
  before it becomes one `audit_note` line; never echoed (row A3-36).
- **A request whose `EXT_` payload has expired (about 91 days).** 004's resend reads the query API
  instead and proceeds when the `sharedTime` matches (H17); the API adds nothing.

## Requirements *(mandatory)*

### Functional Requirements

**The surface and its scope**

- **FR-005-001**: The service MUST expose exactly two operator actions over HTTP under
  `/operations/**` — replay a `FAILED` request, and resend the HMPPS leg for an explicit list of
  `requestId`s, the forced-resend override included — and no other path besides Spring Boot
  Actuator and the framework's error forward, which serves no request of its own. Proposed paths:
  `POST /operations/requests/{requestId}/replay` and `POST /operations/resend-leg`, fixed in the
  OpenAPI document (FR-005-035). *(H9; rows A3-35, A3-36; §4.8 actions 2 and 3)*
- **FR-005-002**: No other §4.8 action — list requests, pause and resume intake, submit-hearing,
  purge-queue, the optional flag check, the recommended `dlq-completed` listing — MUST be
  reachable over HTTP. They stay command-line runners while O-1 is open, and exposing one needs
  O-1 decided and a constitution amendment, not a change to this spec. Nor MUST the rollback's
  close-in-flight step be reachable over HTTP: the design gives it no action at all.
  *(O-1; row A3-35; constitution Principle III; DIGEST §16, §9.4 step 4)*
- **FR-005-003**: The HTTP layer MUST parse, have the call authorised and audited, call the slice
  004 application service, and render its result — nothing else. It MUST NOT hold a store
  repository, a broker client, an outbound HTTP client, an eligibility rule, a lock or a recording
  rule, and MUST NOT re-implement anything slice 004 decides. Parsing is reading the call as typed
  input: the content type, the JSON syntax, the closed field set, and each field as its type — a
  UUID for each `requestId`, the leg vocabulary for `leg`, a boolean for each override, text for
  `operator` and `reason`. Every rule about what was read is slice 004's, applied alike for the
  runner: whether `operator` and `reason` are present, non-blank and within bound; an empty list,
  duplicates and the cap; intake paused; and each ID's eligibility (FR-004-019 to FR-004-023).
  *(§4.4 ports and adapters; DIGEST §11 action 3; constitution Principles IV and V)*
- **FR-005-004**: The endpoints MUST add no capability the actions lack: no selection by hearing,
  day, date range, status or count (withdrawn, H16), no replay of a list, no override beyond the
  two the design names, and no read of a hearing payload, a stored body, a stored `command` or an
  `audit_note`. *(H16; rows A3-36, A3-56; constitution Principle III, "operators' tooling")*
- **FR-005-005**: The command-line runners for replay and resend-leg MUST remain, unchanged, as
  the fallback. The endpoint and its runner MUST call the same application service with the same
  inputs, so the same request against the same state gives the same outcome and the same codes.
  *(§4.8, O-1 paragraph; row A3-35; §4.4 Exposure row; ask 14)*

**Replay**

- **FR-005-006**: The replay endpoint MUST take one `requestId` (a UUID; `source` is always
  `RESULTS`) and no body fields (FR-005-033), and call slice 004's replay action for it. *(§4.8
  action 2; row A2-48)*
- **FR-005-007**: It MUST render the action's result as follows:
  - enqueued → `200` with the `requestId` (the service's parse), the outcome `enqueued` and the
    fresh message identity the command went under;
  - the request is `COMPLETED` → `200` with the outcome `no-op`; nothing enqueued, nothing changed;
  - a leg's latest row is a `PENDING` resend row, live or expired → `409 resend-in-progress`;
  - no request row → `404 unknown-request`;
  - the request is `RECEIVED` or `RETRYING` → `409 not-eligible`.

  *(§4.8 action 2; H9; rows A2-70, A2-72, A3-36)*
- **FR-005-008**: Replay MUST be available at any time, not only after a rollback. It is not
  refused while intake is paused: the endpoint adds no refusal of its own, and slice 004's replay
  action admits the call then too (FR-004-016) — this spec's reading of "at any time", since the
  design does not address the paused state (assumption 11). *(H9 (b); row A3-35; §9.4 step 6a)*
- **FR-005-009**: The replay endpoint MUST take no operator and no reason, because the design
  gives replay neither: who replayed is the caller named in the call's audit event, and the
  replayed run records its route, `service`, at admission. *(§4.8 action 2; row A3-34)*

**Resend-leg and the forced override**

- **FR-005-010**: The resend-leg endpoint MUST take a JSON body of `requestIds` (UUIDs), `leg`
  (closed set: `HMPPS`), `operator`, `reason`, and the optional booleans `allowTerminalSuccess`
  (forced resend) and `allowChangedPayload`, both `false` when absent (names proposed;
  `allowChangedPayload` is the design's `allow-changed-payload`). A `leg` that is absent is
  answered `400 missing-argument`, and one that is not `HMPPS` `400 unreadable-argument`, each
  naming `leg`. *(row A3-56; row A3-36; H17; H5; row A3-1)*
- **FR-005-011**: The endpoint MUST read each `requestIds` entry as a UUID: a value that is not one
  refuses the whole call, `400 unreadable-argument` naming `requestIds`, and nothing is run
  (assumption 5). It MUST pass the list as sent — duplicates and order included — to slice 004's
  resend-leg capability, and MUST apply no collapse and no cap of its own. Collapsing duplicates,
  refusing an empty list and applying the cap `distribution.resend.max-ids-per-run` (default 100)
  are rules of the resend-leg action, which slice 004 applies for both front doors (FR-004-019;
  assumption 12). The endpoint renders them: an absent or empty list as `400 missing-argument`
  naming `requestIds`, and a list over the cap as `400 too-many-request-ids` carrying the cap
  applied. In each case nothing is run. *(H16; row A3-56; DIGEST §11 action 3; §8.1)*
- **FR-005-012**: While intake is paused — the state slice 004's refusal reads; an operator's
  pause is the rollback state — slice 004 refuses the whole call (FR-004-021), and the endpoint
  MUST answer `409 intake-paused`; nothing is run. *(row A2-40; row A3-56)*
- **FR-005-013**: The endpoint MUST pass the list as sent (read as UUIDs, FR-005-011), the leg, the
  operator, the reason and both overrides unchanged to slice 004's resend-leg capability, which
  applies the whole-call rules (FR-005-011, FR-005-012, FR-005-016) and then checks each distinct
  ID on its own, in its own transaction. The endpoint MUST NOT pre-check, collapse, reorder or drop
  an ID. *(row A3-56; row A2-65)*
- **FR-005-014**: A per-ID refusal MUST NOT become the call's status: a call that passes the
  whole-call checks (FR-005-011, FR-005-012, FR-005-016) succeeds even when every ID is refused,
  and the refusals live in the run's report. The report MUST carry the run's `resendRunId`; the
  leg; whether each override was requested; every distinct listed ID with its outcome — the leg's
  resulting status and reason (`SENT`; `NOT_APPLICABLE` with its reason; `FAILED` with its
  reason), one refusal code (`unknown-request`, `not-eligible`, `resend-in-progress`,
  `request-claimed`), or slice 004's `not-run` for an ID a pause that began mid-run left
  unreached (FR-004-021); and the run summary's counts (selected, `SENT`, `NOT_APPLICABLE` by
  reason, `FAILED` by reason, refusals by code). Refusal codes are answer and report codes, never
  row values. *(row A3-56; §4.4 Output row)*
- **FR-005-015**: The resend-leg call MUST answer [NEEDS CLARIFICATION: synchronously, once every
  listed ID has run, with the whole report as the runner prints it — or by accepting the run
  (`202` with its `resendRunId`) and leaving the per-ID outcomes to slice 004's run report line and
  the attempt rows? The design fixes the report's content (§4.4 Output row; row A3-56) but not
  whether one HTTP call waits for up to `max-ids-per-run` IDs, each bounded only by the 215 s
  message budget (§4.7). A call that outlives the route's timeout gives the caller an error while
  the pod carries on, and a caller who repeats a timed-out forced resend sends a further genuine
  duplicate (H12); an accepted-run answer has no HTTP read-back while the list action is open
  (O-1).] Whichever is chosen, no ID's outcome is lost: it is on that ID's attempt row and in 004's
  run report line.
- **FR-005-016**: `operator` and `reason` are required on every resend-leg call, forced or not
  (row A3-56). The endpoint MUST pass both unchanged to slice 004, which refuses the whole call when
  either is absent, blank or over its bound (FR-004-020), and MUST render that refusal as
  `400 missing-argument` or `400 argument-too-long` naming the argument, never quoting the value.
  The design bounds a forced resend's reason at 200 characters (row A3-36). The same bound on
  `operator` and on an unforced resend's reason, and refusing a longer value rather than
  truncating it, are this spec's proposal (assumption 4), shared with slice 004 so that both front
  doors agree. Sanitising and recording both is slice 004's: `operator` on every new attempt row,
  and a forced resend's reason appended to `processed_request.audit_note`. Where an unforced
  resend's reason is recorded is open in the design and stays with 004. *(row A3-36; row A3-56;
  §4.4 Audit row; DIGEST §16)*
- **FR-005-017**: `allowTerminalSuccess: true` MUST be the only way a call reaches a leg whose
  latest row is `SENT` or `NOT_APPLICABLE`. It is a per-call request, never a configuration default
  or a deployed value, and the answer and the audit events MUST say it was requested. No other
  endpoint takes an override. *(H9 (c); row A3-36; H12)*

**Authorisation**

- **FR-005-018**: Every `/operations/**` request MUST be authorised by the estate's rules filter
  (`cp-auth-rules-filter`) against the caller's users-groups membership, with the caller named by
  the `CJSCPPUID` header, before the request reaches any application service. *(§4.8 gates 2 and
  4; ask 14)*
- **FR-005-019**: The authorisation MUST know exactly two actions, `replay` and `resend-leg`
  (proposed names `probation-results-distribution-operations.replay` and
  `probation-results-distribution-operations.resend-leg`). The forced override is part of
  `resend-leg`, not an action of its own. *(§4.8; row A3-36)*
- **FR-005-020**: There MUST be exactly one explicit allow rule per action, each admitting
  [NEEDS CLARIFICATION: which users-groups group or groups? The design copies the API from YOT
  (§4.8), whose rules admit one estate group, "Second Line Support", but it names no group for this
  service. A forced resend re-sends defendants' personal data to HMPPS as a genuine duplicate
  (H12), youth defendants included wherever the youth switch does not remove them (H7), so who may
  call is a security decision for the owning team (H31), not the spec's.] and no other.
  An action with no rule MUST be refused, and there MUST be no default-allow. *(§4.8)*
- **FR-005-021**: A request with no identity, or a blank one, MUST be refused
  `401 unauthenticated`. A caller outside the admitted group or groups, an identity service that
  cannot be asked, and an action with no matching rule MUST be refused `403 forbidden`. Every
  authorisation decision denies by default, and a refused call reads and changes nothing.
  *(§4.8 gate 4)*
- **FR-005-022**: The action a request is authorised against MUST be derived by the service from
  the path and the method, overriding any action header or media type the caller sent, so that a
  caller admitted to one action cannot reach the other by naming it. *(§4.8, copied from YOT)*
- **FR-005-023**: The `CJSCPPUID` on a request is the gateway's assertion and is trustworthy only
  behind gates 1 to 3 (FR-005-037). Its value MUST NOT appear in an access log line, an application
  log line, a metric label or an answer; the audit event is the one place the caller is named.
  *(§4.8 gates 1–3; constitution Principle VII; DIGEST §13, "Never `userId`")*
- **FR-005-024**: Actuator MUST be unaffected: outside both filters, not in the OpenAPI document,
  and answering as it does today. *(DIGEST §2; §10.1 readiness)*

**Audit**

- **FR-005-025**: Every call the authorisation admits, whatever its outcome, MUST produce two audit
  events through the estate's audit filter (`cp-audit-filter-springboot`): one before the action
  and one after it, naming the caller, the derived action and the outcome. *(§4.8 gate 5)*
- **FR-005-026**: The audit events MUST carry bounded fields only: the action; the outcome
  (`enqueued`, `no-op`, the refusal reason, or for resend-leg the `resendRunId` and the counts);
  for replay the `requestId`; and whether `allowTerminalSuccess` and `allowChangedPayload` were
  requested. They MUST NOT carry a request or response body, the `operator` or `reason` text, a
  hearing payload or a stored body. *(constitution Principle VII; row A3-60)*
- **FR-005-027**: With the audit transport on, a call whose request event cannot be published
  MUST be refused `503 audit-unavailable` before anything is read or changed. A response event
  that cannot be published after the action MUST be logged at ERROR and counted (FR-005-043); the
  call cannot be refused by then, and that shortfall — a lost response event, with no durable
  outbox to replay it — needs the owning team's sign-off (Dependencies). This is this spec's choice
  under constitution Principle VI, modelled on the sibling service, where it is recorded as open
  rather than decided; the design copies the API's shape and gates (§4.8), not this behaviour.
  Meeting it means the service MUST NOT rely on the estate audit library's default publisher,
  which logs and discards its own publishing failures: the service supplies its own publisher in
  that one's place (assumption 6). *(constitution Principle VI)*
- **FR-005-028**: The authorisation and audit switches MUST default on in the service's
  configuration. Switching either off is a deliberate act recorded in that environment's
  configuration (the local loop and the `test` profile do it, each with its reason beside it).
  Every deployed configuration that serves this API MUST also switch the audit transport on
  (gate 5); a pod with the audit filter on and its transport off MUST come up and say so once at
  WARN, naming both settings. Start-up MUST NOT refuse any combination of these switches.
  *(§4.8 gate 5; scaffold configuration)*

**Refusals, and what an answer may carry**

- **FR-005-029**: Every non-`2xx` answer MUST be RFC 9457 problem details carrying a bounded
  `reason` from a closed set named in the OpenAPI document and, on a `400` about one argument, the
  argument's name, never its value. The set, in the design's lower-kebab spelling:
  `unauthenticated`, `forbidden`, `missing-argument`, `unreadable-argument`, `argument-too-long`,
  `unknown-field`, `unreadable-body`, `too-many-request-ids`, `unsupported-content-type`,
  `unknown-request`, `not-eligible`, `resend-in-progress`, `intake-paused`, `audit-unavailable`,
  `store-unavailable`, `broker-unavailable` and `unexpected`.
- **FR-005-030**: The status MUST say which kind of refusal it is:
  - `400` — an argument missing, unreadable or too long; an unknown field; a body that will not
    parse; a list over the cap;
  - `401` and `403` — the authorisation (FR-005-021);
  - `404` — a well-formed `requestId` that names no request (replay);
  - `409` — a state refusal that changed nothing (`resend-in-progress`, `not-eligible`,
    `intake-paused`);
  - `415` — a request declaring a content type other than JSON;
  - `503` — something the call must write through is unavailable: the store, the broker (replay's
    enqueue) or the audit context;
  - `500` — `unexpected`, a defect: a `500` this service can explain is a `409` or a `503` it
    failed to classify.
- **FR-005-031**: No answer and no log line MUST echo a value the caller supplied — the operator,
  the reason, an unreadable identifier, the path. A success answer, and a refusal about one
  request, MAY carry the service's own parse of a `requestId`, rendered in canonical form. No
  exception message, stack trace, or store's or far end's own words MUST reach either.
- **FR-005-032**: No answer MUST carry a hearing payload or any part of one, a stored or sent body,
  defendant data, a `userId`, or the text of a stored `command` or `audit_note`. *(constitution
  Principle VII; row A3-60; DV-09)*
- **FR-005-033**: The request contract MUST be closed. On resend-leg, a field the contract does not
  name is refused `400 unknown-field`. On replay, which takes no body (FR-005-006, FR-005-009), no
  body or the empty object `{}` is accepted, and a body carrying any field at all — `operator` or
  `reason` included — is refused `400 unknown-field`. On either endpoint, a body that will not
  parse is `400 unreadable-body`, and a request declaring a content type other than JSON is
  `415 unsupported-content-type`.
- **FR-005-034**: An unmapped path or method MUST be answered as the framework answers it, without
  a stack trace and without quoting the path.

**Contract documents**

- **FR-005-035**: `src/main/resources/probation-results-distribution-openapi.yaml` MUST describe
  exactly the two operations — each request, success shape, status and bounded `reason` — under a
  version raised from the placeholder's `0.0.0`. Every value set the two operations carry MUST be a
  closed enum in the document, mirrored from the service's own vocabularies: `leg`, and the resend
  report's per-ID leg `status` and `reason` (slice 002's leg, leg status and leg reason,
  FR-002-072); the per-ID refusal `code` (row A3-56's four codes, slice 002's resend refusal) and
  slice 004's `not-run`; and replay's outcome (`enqueued`, `no-op`; slice 004). The override flags
  are booleans. The document MUST be updated before the code that serves it, and MUST stay the
  document the audit filter resolves paths from. A contract test MUST assert, in both directions,
  that the served operations and the document agree, that the document's `reason` set and the
  code's agree, and that each of those enums equals the code's vocabulary, value for value.
  *(constitution Principle III; Development Workflow; row A3-56)*
- **FR-005-036**: `doc/API_CONTRACTS.md` §3 MUST summarise the two endpoints, their refusals, the
  five gates and the runner fallback, updated, like the OpenAPI document, before the code that
  serves the endpoints. *(constitution Principle III; Development Workflow)*

**Deployment and configuration**

- **FR-005-037**: The API MUST be served in an environment only once all five gates are in place
  there; until then operators use the runners. The gates are:
  1. an internal ingress or APIM route for `/operations/**`, not exposed outside the estate;
  2. the gateway stripping any client-supplied `CJSCPPUID` and injecting the authenticated
     identity;
  3. an Istio AuthorizationPolicy and a NetworkPolicy restricting `/operations/**` to that gateway
     (the NetworkPolicy authored, there being none to copy);
  4. users-groups reachable from the pod for the authorisation filter;
  5. the Artemis audit connection.

  None of them is in this repository; the deployment review checks them. They are listed among
  slice 006's external preconditions (asks 12 and 14) and are not a cutover precondition, since
  replay and resend-leg run through the runners without them (§4.8; §9.4 step 6a).
  *(§4.8; ask 14; ask 12; row A2-21)*
- **FR-005-038**: Replay's enqueue MUST use the pod's workload identity, which needs the Service
  Bus data-sender role on `probation.requests`. §8.2 grants it for submit-hearing only; it is read
  by H9. *(ask 2; §8.2; H9; DIGEST §16)*
- **FR-005-039**: This slice MUST add no `distribution.*` property: the catalogue is closed. The
  cap is `distribution.resend.max-ids-per-run`; the filters' own settings stay as scaffolded; no
  property serves or hides the API. *(§8.1; DIGEST §12)*
- **FR-005-040**: The API MUST read no App Configuration and no cutover switch, and MUST NOT become
  a lever on which implementation is live. *(H26; rows A3-69, A3-92; Q15)*

**Concurrency, and failure after the fact**

- **FR-005-041**: The endpoints run in a serving pod with intake running. Their safety against
  queue runs, replays, other resends and the rollback protocol MUST come from slice 004's fencing —
  the request row locked before the leg row, the leg claim, the request claim — which the endpoints
  call and neither bypass nor duplicate. *(row A2-65; rows A2-70, A2-72; §4.4 claim contract)*
- **FR-005-042**: A failure after an action's effects — an answer that cannot be written, a caller
  that has gone — MUST NOT be reported as a refusal and MUST NOT repeat the action. The API takes
  no idempotency key and does not de-duplicate calls: a repeated call is a new call. *(H12)*

**Signals**

- **FR-005-043**: Each call that reaches an endpoint MUST write one log line naming the action and
  its outcome or refusal reason (for resend-leg the `resendRunId` and the counts), carrying
  identifiers only as the service's parse and no other caller value; the per-ID run report line is
  slice 004's. Every `5xx` answer, and every response event
  that could not be published, MUST also be logged at ERROR and counted on
  `probation_operations_failures_total` (name proposed), labelled `action` ∈ {`replay`,
  `resend-leg`} and `reason` ∈ {`audit-unavailable`, `audit-response-unpublished`,
  `store-unavailable`, `broker-unavailable`, `unexpected`}. The series and its two label sets
  (`OperationsAction`, `OperationsFailure`) are registered in slice 002's shared registry
  (FR-002-057, FR-002-072); this slice only increments the series. No alert or dashboard reads it
  until the design owner adds it to design §10.1 (006's FR-006-022). *(constitution Principles VI
  and VII; DIGEST §13; FR-002-057; FR-002-072)*

### Key Entities

- **Operator action**: one of two named things an operator can do over HTTP, `replay` and
  `resend-leg`, each with one endpoint, one allow rule and one OpenAPI operation. Derived from the
  path and the method, never from the caller.
- **Caller identity**: the `CJSCPPUID` on a request, asserted by the gateway and resolved to a
  users-groups membership. Written down only in the audit event.
- **Replay call**: one `requestId`. Its answer is `enqueued` (with the message identity used),
  `no-op`, or a refusal.
- **Resend-leg call**: the `requestIds` as sent (slice 004 collapses them and applies the cap of
  1 … `distribution.resend.max-ids-per-run`), the leg (`HMPPS`), `operator`, `reason`, and the
  optional `allowTerminalSuccess` and `allowChangedPayload`.
- **Resend report**: the run's `resendRunId`, the leg, the overrides requested, one entry per
  distinct listed ID (the leg's resulting status and reason, a refusal code, or `not-run`), and the
  run's counts; every value set in it is a closed enum of the API description.
- **Refusal**: a status family, a bounded `reason` from the closed set and, for a `400` about one
  argument, the argument's name.
- **Audit event pair**: the request event and the response event of one admitted call, carrying
  the caller, the action, the outcome and bounded flags.
- **Operations failure count**: `probation_operations_failures_total{action, reason}`, every `5xx`
  answer and every unpublished response event. Proposed; registered in slice 002's shared registry
  (FR-002-057, FR-002-072), and only incremented here.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-005-001**: Exactly two operator actions answer over HTTP. None of the six O-1 actions does,
  and no other path does besides the platform's health and metrics endpoints, as a check of the
  served routes against the API description shows.
- **SC-005-002**: 100% of operator endpoints refuse a call with no identity and a call from outside
  the admitted group or groups, and serve a call from inside, each shown by a test per endpoint
  through the real authorisation path.
- **SC-005-003**: With the audit transport on, 100% of admitted calls produce two audit events,
  and 100% of calls whose first audit event cannot be published change nothing: no command
  enqueued, no attempt recorded, nothing sent to HMPPS.
- **SC-005-004**: 0 answers and 0 log lines contain a value the caller supplied — the operator,
  the reason, an unreadable identifier, the identity header — shown by the repository's privacy
  sweep plus one case per refusal.
- **SC-005-005**: For the design's mixed list, the API and the runner report the same five
  outcomes, ID for ID.
- **SC-005-006**: A call over the cap, a call holding an unreadable identifier and a call while
  intake is paused each leave the record untouched: 0 new attempts, 0 sends, 0 `audit_note` lines.
- **SC-005-007**: 100% of the reasons the API description names are produced by at least one test,
  and 100% of the reasons the endpoints can produce are named in the description; every closed
  value set the description names (leg, per-ID status, reason and refusal code, replay outcome)
  matches the service's own vocabulary with 0 values missing on either side.
- **SC-005-008**: An operator replays a `FAILED` request in one call, with no access to the
  cluster, and exactly one command, identical to the stored one, goes on the queue.
- **SC-005-009**: 100% of forced resends made through the API can be attributed from the record
  alone: the audit event names the caller, each new attempt names the operator and the override,
  and the request's note carries the reason.
- **SC-005-010**: The build stays green under the existing gates, with the coverage thresholds
  unchanged (line ≥ 88%, branch ≥ 85%).
- **SC-005-011**: The slice adds 0 properties to the closed `distribution.*` catalogue and 0 reads
  of App Configuration or of the Results switch, as the service's configuration tests show.

## Assumptions

1. **Slice 004's services are the contract.** Slice 004 delivers the replay action and the
   resend-leg capability as application services whose results this slice renders: for replay,
   `enqueued` (with the message identity used), `no-op`, or a refusal; for resend-leg, a whole-call
   refusal (`missing-argument`, `argument-too-long`, `too-many-request-ids` with the cap applied,
   `intake-paused`) or a report (run id, per-ID outcome, refusal code or `not-run`, counts). Where
   004 names a result differently, this slice follows 004 and the OpenAPI document records it.
2. **Paths and action names are proposals**: `POST /operations/requests/{requestId}/replay` and
   `POST /operations/resend-leg`; the actions `probation-results-distribution-operations.replay`
   and `probation-results-distribution-operations.resend-leg`. The OpenAPI document fixes them.
3. **Field names** are `requestIds`, `leg`, `operator`, `reason`, `allowTerminalSuccess` and
   `allowChangedPayload`. `allowTerminalSuccess` is the design's proposed name;
   `allowChangedPayload` is its `allow-changed-payload`, written as a JSON field.
4. **One bound for operator and reason — a proposal, not a design rule.** The design bounds only a
   forced resend's reason, at 200 characters, gives `operator` no bound (a `text` column), and does
   not say whether "bounded" means refused or truncated (row A3-36). This spec and slice 004 apply
   the same 200-character bound to every reason and to the operator, and refuse a longer value
   rather than truncate it, so one rule serves both kinds of resend and both front doors, and
   nothing an operator typed is silently changed at the boundary. Slice 004 applies the rule
   (FR-004-020); the endpoint renders it. Sanitising what is recorded stays 004's.
5. **An unreadable identifier refuses the whole call.** A value that is not a UUID is not a
   request, so it gets no per-ID entry; the per-ID codes describe requests (row A3-56).
6. **The error model is the sibling YOT service's, copied with the API** (§4.8): problem details
   with a bounded reason; status families; no echo; one `unexpected` fallback. **The audit
   behaviour is this spec's choice**, under constitution Principle VI and modelled on the sibling
   service, where it is recorded as open: the request event published before the action and the
   call refused if it cannot be; the response event's failure logged and counted, a shortfall the
   owning team signs off. Because the estate audit library's default publisher logs and discards
   its own publishing failures, the service supplies its own publisher in that one's place.
7. **Secure by default, switchable by configuration**, as scaffolded: the authorisation and audit
   switches default on; the local loop and the `test` profile switch them off with the reason
   beside them; a deployed environment switches the audit transport on (gate 5).
8. **One new metric, proposed.** Constitution Principle VI requires every failure path to be
   counted, and the design's catalogue (§10.1) has no counter for a failed operations call, so this
   slice proposes `probation_operations_failures_total{action, reason}` for the design owner to add
   to it. It is registered in slice 002's shared registry, with its two label sets (FR-002-057,
   FR-002-072), so that the registry keeps one owner; this slice only increments it. Until the
   design owner adds it, slice 006 neither alerts on it nor counts it (FR-006-022).
9. **Resend runs share the pod.** A resend made through the API runs in a serving pod beside
   intake, as a runner in the image does. The design sets no limit on concurrent resend runs; slice
   004's per-ID fencing keeps them apart.
10. **Constitution Principle II applies unchanged**: every endpoint behaviour is test-first, with
    the red run recorded. No waiver is sought.
11. **Replay during paused intake is admitted** on the strength of H9 (b), "at any time"; the
    design does not address it. A command enqueued then is purged with the queue (§9.4 step 6;
    §9.3 "Rolling forward after a rollback") and the request is replayed again afterwards, once the
    Results switch is on again (§9.4 step 6a). Slice 004 reads the design the same way
    (FR-004-016).
12. **The cap counts distinct IDs.** Neither DIGEST §11 nor row A3-56 says whether duplicates are
    collapsed before the cap is applied. This spec reads the cap as bounding the work, so a list is
    over the cap only when its distinct IDs are, as slice 004 reads it. The rule is 004's; the
    endpoint applies no cap of its own.

## Dependencies

### Slices

- **004-recovery** (required first): the replay action; the resend-leg capability with the forced
  override, the `sharedTime` check, the per-ID refusals and report (with `not-run`), the
  intake-paused refusal, and the recording of operator, override, note and reason; the
  command-line runners. 004 also owns the whole-call input rules this slice renders, applied alike
  for both front doors so that they agree: collapsing duplicates, the empty-list refusal and the
  cap, its `too-many-request-ids` refusal carrying the cap applied (FR-004-019); and the presence
  and 200-character bound of `operator` and `reason`, refused rather than truncated (FR-004-020;
  assumption 4). The API passes through, unchanged, 004's resolution of the two items the design
  leaves to it: what an expired `PENDING` resend row gives (§4.4 claim contract (c) against row
  A3-56), and where an unforced resend's reason is recorded (A3-56).
- **002-hmpps-distribution**: the store and its vocabularies (`Leg`, the request and leg statuses
  and reasons, the resend refusal codes; FR-002-072), which the OpenAPI document mirrors as closed
  enums (FR-005-035); the intake-paused state the resend refusal reads; the `probation_` metric
  family, in whose shared registry this slice's proposed counter and its label sets are registered
  (FR-002-057, FR-002-072); the MDC logging and the privacy tests this slice extends.
- **006-production-readiness** (downstream): lists platform asks 12 and 14, the five gates, among
  its external preconditions — not a cutover precondition, since the runners serve without them —
  and its rollback runbook inputs send replay through the service's replay action, which this
  slice exposes (§9.4 step 6a). The purge-queue count the rollback records may include replay
  commands enqueued while intake was paused (assumption 11).
- **001-parity-kernel** and **003-body-retention**: none.

### External

- **Platform ask 14**: the five gates, per environment.
- **Platform ask 12**: the NetworkPolicy, authored rather than copied (row A2-21); where its
  manifests live is not established (platform team).
- **Platform ask 2**: the data-sender role on `probation.requests`, which replay's enqueue needs.
- **users-groups**: the identity lookup the authorisation filter makes (gate 4).
- **The estate audit context**: the Artemis broker, its connection supplied from Key Vault (gate
  5).
- **The estate libraries** `cp-auth-rules-filter` and `cp-audit-filter-springboot`, already on the
  classpath with the scaffold's configuration.
- **The owning team (H31)**: answers the questions in FR-005-015 and FR-005-020, and owns O-1. It
  also signs off, before the audit work lands, that a response event lost after the action is an
  accepted shortfall — logged at ERROR and counted, with no durable outbox to replay it
  (FR-005-027; assumption 6).
- **The audit header allowlist**: the sibling service found that the audit library captures request
  headers verbatim and that a header allowlist is to be agreed with the audit owners before a broad
  rollout. That is an estate decision and a rollout matter, not a build gate.

## Out of Scope (this slice)

- HTTP exposure of list, pause and resume intake, submit-hearing, purge-queue, the optional flag
  check and the `dlq-completed` listing (O-1), and of the rollback's close-in-flight step, for which
  no action is designed (§9.4 step 4; DIGEST §16).
- Any dead-letter-queue operation over HTTP — peeking, resubmitting or removing entries. A
  dead-letter resubmission stays with the broker tools (H9 (a); DV-26, slice 004).
- What replay and resend-leg do (slice 004), and what is sent to HMPPS (D10).
- Removing or changing the command-line runners.
- Any business API, and any user interface.
- An idempotency key or de-duplication of calls; rate limiting beyond the list cap.
- A switch that serves or hides the API; reading App Configuration or the Results switch (H26).
- Any leg value other than `HMPPS` (H5).
- The gates themselves — the route, the gateway identity handling, the mesh and network policies,
  the users-groups path, the audit connection — which live in the infrastructure repositories.
- Any DV row.

## Design traceability

| Design id | What it fixes in this slice | FRs |
|---|---|---|
| H9; row A3-35 | Replay and resend-leg on the API, replay at any time; the runners as fallback | FR-005-001, FR-005-005, FR-005-006, FR-005-008 |
| O-1 (§11.1); constitution Principle III | Every other action stays off HTTP; exposing one needs an amendment | FR-005-002 |
| §4.8 action 2; rows A2-48, A2-70, A2-72 | Replay: the stored command; `resend-in-progress`; `COMPLETED` → no-op | FR-005-006, FR-005-007 |
| Row A3-34 | Replay's route is recorded by the run; replay takes no operator or reason | FR-005-009 |
| H9 (c); row A3-36 | The forced override: per call, operator and reason, part of resend-leg | FR-005-010, FR-005-016, FR-005-017, FR-005-019 |
| H16; row A3-56; DIGEST §11 action 3 | Explicit list; duplicates collapsed, empty list and cap applied by slice 004 and rendered here; per-ID refusals; the report; no selection by hearing, day or date | FR-005-003, FR-005-004, FR-005-011, FR-005-013, FR-005-014 |
| Row A3-56; slice 002 vocabularies | The report's and replay's value sets as closed enums in the API description | FR-005-035 |
| §4.8 (copied from YOT); row A3-56 (refusal codes are API codes) | The error model: problem details, the closed reason set, status families, the closed request contract | FR-005-029, FR-005-030, FR-005-033, FR-005-034 |
| Row A2-40 | Whole resend refused while intake is paused | FR-005-012 |
| H17; row A3-57 | `allowChangedPayload` passed through; the check is 004's | FR-005-010, FR-005-013 |
| §4.4 Output row; §4.7 | The report's content; the open question on the answer's timing | FR-005-014, FR-005-015 |
| §4.4 Audit row; row A3-36; row A3-56 (DIGEST §16) | Where operator and reason are recorded; the forced reason's 200-character bound (the rest of the bound proposed) | FR-005-016 |
| §4.8 gates 1–5; ask 14; ask 12; row A2-21 | Authorisation, audit, the five gates, the authored NetworkPolicy | FR-005-018 – FR-005-028, FR-005-037 |
| H31 | The owner who answers the caller-group question | FR-005-020 |
| Ask 2; §8.2 (DIGEST §16) | Data-sender role for replay's enqueue | FR-005-038 |
| §8.1 (DIGEST §12) | No new property | FR-005-039 |
| H26; rows A3-69, A3-92; Q15 | No App Configuration, no switch read, no second lever | FR-005-040 |
| H5; row A3-1 | `leg` is `HMPPS` only | FR-005-010 |
| Row A2-65; §4.4 claim contract | Fencing is 004's, never bypassed | FR-005-041 |
| H12 | A forced resend, or a repeated one, is a genuine duplicate | FR-005-015, FR-005-017, FR-005-042 |
| Constitution Principle VII; row A3-60; DV-09 | No body, payload, caller value or identity in answers, logs or audit events | FR-005-023, FR-005-026, FR-005-031, FR-005-032 |
| Constitution Principle VI | Failures logged and counted; the audit-before-action refusal (this spec's choice, modelled on the sibling service) | FR-005-027, FR-005-043 |
| Constitution Principles III and V; §4.4 | Contract first; the HTTP layer is an adapter | FR-005-003, FR-005-035, FR-005-036 |
| §4.4 Exposure row | One service, two front doors | FR-005-005 |
| §9.4 step 6a; H23 | Replay is the route back after a rollback; replay while intake is paused is this spec's reading of "at any time" | FR-005-008 |
| DIGEST §16, §4.1 identity table | The API is in scope, read by H9 | FR-005-001 |
