# Cutover and rollback runbook — service-side inputs

The human-readable mirror of `evidence.RunbookInputs` (slice 006, FR-006-037 to FR-006-046). These
are the service-side inputs to the DevOps runbook on each environment's cutover ticket, covering
four procedures — cutover, planned rollback, urgent rollback, roll-forward — 21 steps in total
(SC-006-007), in the design's exact order. **The runbook itself lives on the ticket, not in this
repository.** Every environment-specific value (the subscription, its topic, the configuration
store, the label, hosts and resource names) is a named placeholder the ticket fills in — never a
real value here.

This service reads no App Configuration and never reads the Results switch itself; pause-intake is
its only service-side stop (FR-006-045, constitution Principle IX). The two switches a step turns
are the legacy subscription's filter and the Results flag, both external to this service.

Planned rollback step 5's read-back names `DailyReconciliationQuery`'s legacy-silence check, which
also needs `AlertCatalogue` alert 11's query definition (User Story 6, later in this same slice);
it is corrected in place once that story lands, the same way one row of
`doc/go-live/evidence-index.md` is. Urgent rollback step 5 and roll-forward step 2 need only
`DailyReconciliationQuery` itself (User Story 5), already built and pinned by
`DailyReconciliationQueryIT`.

## Cutover (2 steps, weekend window, Common Platform down)

| Step | Actor | Action | Service capability | Read-back | Precedes |
|---|---|---|---|---|---|
| 1 | DevOps | Record the legacy subscription's current filter (included event types, subject filters, advanced filters), then set its included event type to `TOGGLED-OFF` | — | the subscription's filter read back `TOGGLED-OFF` (DevOps' own evidence, external to this repository) | 2 |
| 2 | operations | Turn the Results flag on under the environment's label, and read it back for that label; wait at least 10 minutes before Common Platform comes back up | — | the flag read back ON for the label (the results team's own evidence); routing is proven by `GoNoGoEvidenceQuery.testCallEvidence()` once Common Platform is up | — |

The order is fixed: the subscription is switched off before the flag is turned on. There is no
cutover reconciliation step. SIT is cut over first, and its switch-ON proofs are preconditions for
every later environment. The function app stays deployed, with its subscription filtered, until
the soak ends — it is the rollback.

## Planned rollback (9 steps, including 2a and 6a; weekend window, Common Platform down)

| Step | Actor | Action | Service capability | Read-back | Precedes |
|---|---|---|---|---|---|
| 1 | operations | Pause intake | `pause-intake` (CLI runner) | `probation_intake_suspended{cause="operator"}` reads 1 (`IntakePauseResumeTest`) | 2 |
| 2 | operations | Turn the Results flag off, read it back for the label, and wait at least 10 minutes | — | the flag read back OFF for the label (the results team's own evidence) | 2a |
| 2a | DevOps | Restore exactly the filter recorded before cutover | — | the subscription's filter read back matching step 1's cutover recording (DevOps' own evidence) | 3 |
| 3 | operations | Record the cut-off instant | — | the recorded cut-off instant (operator's own record) | 4 |
| 4 | operations | Run `rollback-close-in-flight`, closing the in-flight rows `FAILED(rolled-back)`, one transaction per row, fencing late runners | `rollback-close-in-flight` (CLI runner) | the runner's own recorded outcome (`pendingClosed`/`parkedClosed` counts); `RollbackFencingIT`'s pinned invariant — no leg `PENDING`/`PARKED`, `RECEIVED`/`RETRYING` now `FAILED(rolled-back)`, a `FAILED` request keeps its reason and exhausted id, no `COMPLETED` request changed | 5 |
| 5 | operations | Reconcile the period the service was live, from cutover to the start of the rollback window; the legacy app must show no HMPPS send or orchestration in that period | `DailyReconciliationQuery`'s legacy-silence check (evidence query, User Story 6) | `DailyReconciliationQuery`'s legacy-silence check reads zero lines in that period (not yet built — corrected once User Story 6 lands) | 6 |
| 6 | AMS / 3rd line support | Only after the reconciliation, purge the `probation.requests` active queue only, with intake paused: in the Azure portal, open the queue's Service Bus Explorer and use **Purge messages** (confirm with `purge`), repeating until the queue reads 0; never purge the dead-letter queue — see [Purging the queue (manual)](#purging-the-queue-manual) | — | the queue's `activeMessageCount` read before the purge and 0 after it (the portal's queue overview, or `az servicebus queue show --query countDetails.activeMessageCount`), with the count, the time, who ran it and the cut-off recorded on the ticket (AMS / 3rd line support's own record) | 6a |
| 6a | operations | Once the Results switch is on again, replay goes only through the service's replay action: the operations API (005), with its runner as the fallback; the dead-letter queue holds no copy | `replay` (operations API `POST /operations/requests/{requestId}/replay`; CLI runner fallback) | the replay action's own recorded outcome: `Enqueued` with `messageId`, or a bounded refusal (`ReplayActionTest`) | 7 |
| 7 | operations | Repair only the hearings not known to be delivered | `submit-hearing` (CLI runner) / `resend-leg` (CLI runner / operations API), per FR-006-041's routing rules | the chosen repair action's own recorded outcome (`SubmitHearingActionTest` / `LegResenderTest`) | — |

Rollback step 4 is executed only through `rollback-close-in-flight` — never a hand-run statement
in its place (FR-006-040, corrected 2026-11-02: 004 built and wired this runner, confirmed
FR-004-049, before this document was first written).

### Repair rules (step 7; FR-006-041)

- Only these are repaired: legs closed `FAILED(rolled-back)`, legs `FAILED` for another reason, and
  dead-lettered messages.
- The repair is a business re-share or, once a new cutover has re-enabled the service,
  `submit-hearing`. Once the service is re-enabled, a `FAILED` request, `FAILED(rolled-back)`
  included, may also be replayed by the service's replay action (step 6a), and a rolled-back resend
  row (attempt ≥ 2) is resend-eligible. Resend-leg is refused while intake is paused.
- A `FAILED(gateway-transient)` after a 5xx, a 408 or a read timeout is outcome-unknown, and is
  confirmed with HMPPS before it is repaired by any route, a replay or a resend included.
- A `FAILED(receiver-rejected)` re-sent unchanged is rejected again, so re-sending is not its
  repair.
- A dead-letter entry with no `processed_request` row is repaired by a resend from the dead-letter
  queue for `exhausted`, and otherwise by `submit-hearing`.

## Urgent rollback (6 steps, out of hours, Common Platform up)

Restoring the subscription before intake is paused is never offered: while the service still
consumes, every share would go by both paths, for up to 10 minutes because of the feature cache.
Step 1 always precedes step 2.

| Step | Actor | Action | Service capability | Read-back | Precedes |
|---|---|---|---|---|---|
| 1 | operations | Pause intake | `pause-intake` (CLI runner) | `probation_intake_suspended{cause="operator"}` reads 1 (`IntakePauseResumeTest`) | 2 |
| 2 | DevOps | Restore the recorded filter at once, and record the time T; from T the function app handles every new share | — | the subscription's filter read back restored; the recorded instant T (DevOps' own record) | 3 |
| 3 | operations | Turn the Results flag off and wait at least 10 minutes; meanwhile Results keeps publishing to the paused queue | — | the flag read back OFF for the label (the results team's own evidence) | 4 |
| 4 | operations | Close the in-flight sends, as in planned step 4 | `rollback-close-in-flight` (CLI runner) | the runner's own recorded outcome; `RollbackFencingIT`'s pinned invariant | 5 |
| 5 | operations | Reconcile the paused queue against T: messages for shares before T reached neither path and are repaired by a court re-share, not by the service; messages for shares after T were handled by the function app and are purged; intake is not resumed; then do planned steps 5-7 | `DailyReconciliationQuery` (evidence query, User Story 5) | `DailyReconciliationQuery.countsFor()`, partitioned at T (`DailyReconciliationQueryIT`) | 6 |
| 6 | AMS / 3rd line support | Only after the reconciliation, purge the `probation.requests` active queue only, with intake paused: in the Azure portal, open the queue's Service Bus Explorer and use **Purge messages** (confirm with `purge`), repeating until the queue reads 0; never purge the dead-letter queue — see [Purging the queue (manual)](#purging-the-queue-manual) | — | the queue's `activeMessageCount` read before the purge and 0 after it (the portal's queue overview, or `az servicebus queue show --query countDetails.activeMessageCount`), with the count, the time, who ran it and the cut-off recorded on the ticket (AMS / 3rd line support's own record) | — |

## Roll-forward (4 steps, intake still paused)

| Step | Actor | Action | Service capability | Read-back | Precedes |
|---|---|---|---|---|---|
| 1 | AMS / 3rd line support | Purge the `probation.requests` active queue only, with intake paused: in the Azure portal, open the queue's Service Bus Explorer and use **Purge messages** (confirm with `purge`), repeating until the queue reads 0; never purge the dead-letter queue — see [Purging the queue (manual)](#purging-the-queue-manual) | — | the queue's `activeMessageCount` read before the purge and 0 after it (the portal's queue overview, or `az servicebus queue show --query countDetails.activeMessageCount`), with the count, the time, who ran it and the cut-off recorded on the ticket (AMS / 3rd line support's own record) | 2 |
| 2 | operations | Reconcile every message received since the recorded cut-off | `DailyReconciliationQuery` (evidence query, User Story 5) | `DailyReconciliationQuery.countsFor()`, since the cut-off (`DailyReconciliationQueryIT`) | 3 |
| 3 | DevOps | Run a full new cutover in a weekend window: the subscription off, then the Results flag on, then the 10-minute wait | — | the subscription's filter read back `TOGGLED-OFF`; the flag read back ON for the label (DevOps'/the results team's own evidence) | 4 |
| 4 | operations | Only then resume intake | `resume-intake` (CLI runner) | `probation_intake_suspended{cause="operator"}` reads 0 (`IntakePauseResumeTest`) | — |

## Purging the queue (manual)

Every purge — planned rollback step 6, urgent rollback step 6 and roll-forward step 1 — is done by
hand in the Azure portal, by the AMS team and 3rd line support (H42, decided 2026-11-09; 004's FR-004-061). A rollback is a rare event,
expected only in the first weeks after go-live, so a manual step was chosen over building a new
one.

The packaged `purge-queue` runner stays in the image as the **backup** and the route for a
scripted workflow, under the same rules (FR-004-053: only while intake is paused, everything
enqueued before the instant it starts, the dead-letter queue untouched). While the installed
Service Bus SDK has no delete-before-time operation it refuses `unsupported-broker-operation` and
removes nothing, so until the SDK can delete, use the portal.

The purge removes everything on the active queue, the shares after T included: in an urgent
rollback the function app has already handled those, and owed producer events can still be
arriving. The recorded cut-off is for the reconciliation, not a filter.

Before the purge:

1. Confirm intake is paused: `probation_intake_suspended{cause="operator"}` reads 1 on every
   replica. Never purge while the service is consuming.
2. Confirm the Results flag reads OFF for the environment's label and, in a rollback, that the
   reconciliation step (planned or urgent step 5) is done.
3. Read and record the queue's active message count (the portal's queue overview, or
   `az servicebus queue show --resource-group <resource-group> --namespace-name <namespace> --name
   <queue-name> --query countDetails.activeMessageCount`).

The purge:

4. In the Azure portal, open the Service Bus namespace, then the queue `<queue-name>` (the
   stack-prefixed `probation.requests`), then **Service Bus Explorer**.
5. With the active queue selected — **not** the dead-letter subqueue — choose **Purge messages**
   and type `purge` to confirm. The dead-letter queue is left alone: its entries are handled by
   the dead-letter runbook (`doc/runbooks/dead-letter-recovery.md`).

After the purge:

6. Read the active message count again. The portal purges in batches, so on a large backlog
   repeat step 5 until the count reads 0.
7. Record on the ticket: the count before the purge, the time of the purge, who ran it and the
   cut-off instant.

The person running the purge needs a role on the namespace that can receive and delete messages
(for example *Azure Service Bus Data Owner*). Rehearse the purge in SIT before it is needed in
production.

## What a rollback does not bring back (FR-006-044)

Events filtered out while the subscription carried `TOGGLED-OFF` were never delivered to the
function app, and are not retained. There is no legacy replay tool. The legacy's silent-failure
modes return, so the reconciliation keeps running. Owed producer events arriving after the switch
is off wait on the paused queue, and are counted and purged at planned-rollback step 6 or at the
next roll-forward.

## Rollback constraints (constitution Principle IX; FR-006-045)

No step uses a property, endpoint or deployment value of this service as a routing lever. The two
switches are the Results flag and the legacy subscription — never a second lever this service
reads. Resend-leg is refused while intake is paused, and the queue is purged only while intake is
paused. After a rollback intake stays paused until a full new cutover — purge, reconcile, cutover,
then resume.
