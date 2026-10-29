# Dead-letter recovery

The owning team uses this runbook when the "DLQ not empty" alert fires, or when a hearing has to be
sent again. It covers which dead-letter entries to resend and which to remove, and how to run and
read `resend-leg`, the forced resend included. Every rule below is tested against the service's
own behaviour: `RunbookCrossReferenceIT` covers the dead-letter rules, `DlqResubmissionReplayIT`
the one-admission-chance rule, and `ResendByRequestIdsIT`, `ResendSharedTimeCheckIT` and
`ForcedResendIT` the resend rules.

This runbook does not say when intake is resumed after a rollback, or what happens to a message
enqueued while intake is paused. Slice 006's rollback inputs cover both.

Every value in angle brackets is a placeholder. Never paste a hearing payload, a stored body or a
stored command into a ticket or a chat.

## The queue and its dead-letter reasons

The service consumes `probation.requests` (stack-prefixed in deployed environments) and settles
every delivery with exactly one complete, abandon or dead-letter. A dead-letter entry carries one
of four reasons:

| Reason | What happened | Request row | Action |
|---|---|---|---|
| `leg-exhausted` | The HMPPS leg was still parked (`payload-unavailable` or `gateway-transient`) on the final delivery. The request is `FAILED(leg-exhausted)` under this entry's `messageId` | Yes, `FAILED` | **Resend** the entry, or replay or resend-leg the request |
| `exhausted` | Every delivery was abandoned: a live claim held the request (a duplicate copy, or a replay deferred behind a resend), or the run failed before its outcome was recorded | Maybe | **Resend** it, unless its request is already `COMPLETED`; in that case **remove** it |
| `validation` | The body failed the closed inbound schema. Nothing was admitted | No | **Never resend.** Fix it at source (the producer) |
| `collision` | A request with the same `(source, requestId)` exists with a different fingerprint | Yes, someone else's | **Never resend.** Investigate |

The broker does not detect duplicates (H14). Every copy reaches the service's processed-log guard,
and that guard is the only duplicate protection.

## Resending a dead-letter entry

Resend means copying the entry back to `probation.requests` **under its own `messageId`**, with
its body and application properties unchanged, and then removing the dead-letter entry. Use the
broker tools (Service Bus Explorer, or a client running under the pod's own workload identity).

- **No wait and no `messageId` edit are needed.** The broker has no duplicate-detection window to
  wait out, and the service recognises the copy by its `messageId`.
- **A copy under the exhausted `messageId` has one admission chance.** It is admitted as a replay
  only at broker delivery count 1, which is its first delivery. If that delivery is abandoned (for
  example, a live claim is in the way), the next delivery re-dead-letters it `exhausted` straight
  away. In that case, clear the cause and resend once more, or replay or resend-leg the request
  instead.
- **A copy of an admitted `FAILED` request replays.** The request goes `FAILED` → `RECEIVED`, the
  leg re-runs, and `audit_note` records `REPLAYED_AFTER_FAILURE` with `route=copy`. The replay is
  counted on `probation_replays_total{route="copy"}`.
- **A copy of a request that is already `COMPLETED` is a no-op.** The message is completed, no leg
  re-runs and no row changes. Such an `exhausted` entry is duplicate-copy noise: **remove it, do
  not resend it.**
- **A message the service never admitted** (an `exhausted` entry with no `processed_request` row)
  is processed as a first delivery when resent.
- **A dead-letter entry with no request row** is repaired by resending it (for `exhausted`), by
  submit-hearing, or by a court re-share. It is never repaired by the replay action, which can only
  re-send a stored command. Submit-hearing is not packaged in this build (slice 004 US8), so until
  it is, the alternative to a resend is a court re-share.

## Removing entries after completion

The service never removes a dead-letter entry. That is always the operator's job. **Once a replayed
or resent request is `COMPLETED`, remove its original entry and any copy that was itself
re-dead-lettered.** Until you do, the "DLQ not empty" alert keeps firing.

Check a request's state before you remove its entry (read-only):

```sql
SELECT source, request_id, status, completion_reason, failure_reason, exhausted_message_id,
       updated_at
FROM processed_request
WHERE source = 'RESULTS' AND request_id = '<request-id>';
```

The `messageId` of every entry the producer sent is `RESULTS:<request-id>`. A replay message sent by
the service has a fresh `messageId` and the application property `origin = REPLAY`.

The recommended `list-dlq-completed` listing, which lists the entries whose request is `COMPLETED`
and removes nothing, is not packaged in this build (slice 004 US6). Until it is, use the query
above for each entry.

## Replay or resend-leg?

| The request is … | Use |
|---|---|
| `FAILED`, and its dead-letter entry is still there | Resend the entry (above) |
| `FAILED(leg-exhausted)`, and its entry was removed | `resend-leg`. Its latest attempt is `FAILED`, so it is resend-eligible |
| `FAILED(rolled-back)`, and its entry was purged after a rollback | The replay action, which re-sends the stored command under a fresh `messageId`. It is the only route after a rollback purge. **This build has no packaged route for it**: the replay runner is in no command line yet. `resend-leg` cannot stand in. The attempt-1 `FAILED(rolled-back)` row is refused `not-eligible`, and intake stays paused after a rollback, so the whole run is refused `intake-paused`. The request waits for the replay runner and for the full new cutover (purge, reconcile, cutover, then resume intake) |
| `COMPLETED`, and HMPPS needs the hearing again | `resend-leg` with `--force-resend-sent` (below) |
| `FAILED` or `COMPLETED`, and the leg's latest attempt is `FAILED` | `resend-leg` |

## Choosing resend IDs

`resend-leg` takes an explicit list of `requestId`s. It never selects by date, status, reason,
hearing or count. Choose the IDs from the latest attempt of each leg, which is the leg's truth. The
request's status is not.

The `list-requests` runner is not packaged in this build (slice 004 US6). Until it is, use one of
these read-only queries.

By hearing and day:

```sql
SELECT r.request_id, r.status AS request_status, l.attempt, l.attempt_origin, l.status,
       l.reason, l.http_status, l.updated_at
FROM processed_request r
JOIN outbound_message_latest l ON l.source = r.source AND l.request_id = r.request_id
WHERE r.hearing_id = '<hearing-id>' AND r.hearing_day = DATE '<YYYY-MM-DD>'
ORDER BY r.shared_time;
```

By the latest attempts' leg, status, reason and `updated_at` range:

```sql
SELECT request_id, attempt, attempt_origin, status, reason, http_status, updated_at
FROM outbound_message_latest
WHERE leg = 'HMPPS' AND status = 'FAILED' AND reason = '<reason>'
  AND updated_at >= TIMESTAMPTZ '<from>' AND updated_at < TIMESTAMPTZ '<to>'
ORDER BY updated_at
LIMIT 500;
```

To find what a release sent, filter on `service_version`:

```sql
SELECT request_id, attempt, attempt_origin, status, reason, http_status, body_digest, updated_at
FROM outbound_message
WHERE leg = 'HMPPS' AND service_version = '<build-version>'
ORDER BY updated_at
LIMIT 500;
```

A leg is resend-eligible when its latest attempt is `FAILED` with any reason. The one exception is
an attempt-1 `FAILED(rolled-back)` row, which a replay re-runs instead. With the forced-resend
override, a latest attempt of `SENT` or `NOT_APPLICABLE` is eligible too. Every other latest
attempt (`PENDING` or `PARKED`) is refused.

## Running resend-leg

```bash
java -jar app.jar resend-leg --leg HMPPS --request-ids <id>,<id>,... \
    --operator <your-name> --reason "<why>" [--allow-changed-payload] [--force-resend-sent]
```

The runner runs under the pod's own workload identity and network path, from the same image as
the service. It starts no intake and no scheduled job.

- Duplicate IDs are collapsed. One run takes 1 to `distribution.resend.max-ids-per-run` distinct
  IDs (100 by default).
- `--operator` and `--reason` are required, and each is at most 200 characters. The operator's name
  is recorded on every attempt row the run creates. A normal resend's reason is checked but stored
  nowhere. A forced resend's reason is written to `audit_note` (below).
- The run is refused whole while intake is paused (`intake-paused`).
- Each ID is checked, claimed and run on its own, so one ID's refusal or failure never affects
  another's. A resend never parks: a payload missing everywhere is `FAILED(payload-unavailable)`,
  and a send still transient at its end is `FAILED(gateway-transient)`.
- After the payload fetch and before anything else, the run compares the payload's `sharedTime`
  with the stored one, as instants. If the payload is newer, missing or unreadable, the outcome is
  `FAILED(payload-changed)` and nothing is sent. `--allow-changed-payload` lets such a payload
  through, and the attempt records `override = true` and `override=allow-changed-payload`.
  Normally, look for the newer share instead: it has its own `requestId`.
- When a resend ends `SENT`, `NOT_APPLICABLE`, `FAILED(receiver-rejected)` or
  `FAILED(rules-exception)`, a `FAILED` request moves to `COMPLETED(recovered)`, and
  `RECOVERED_BY_RESEND` is appended to `audit_note`. No resend ever changes a `COMPLETED` request.

### Reading the result

The exit code says how the run ended. `0` means the run produced its report. `1` means it was
refused whole and nothing changed. `2` means it failed: either the store failed during the run (the
report still stands, so read it) or the store could not be read at the start (`store-unavailable`,
nothing changed). A `resend-leg report` log line carries the run's `resendRunId`, both overrides as
requested, one `<requestId>=<outcome>` entry for every distinct ID, and a summary. This line is the
authoritative record of the run.

Whole-run refusals (exit 1, nothing read or changed):

| Code | Meaning |
|---|---|
| `missing-argument` | A required argument is missing or blank, or the ID list is empty |
| `unreadable-argument` | A leg other than `HMPPS`, or an ID that is not a UUID |
| `argument-too-long` | `--operator` or `--reason` is over 200 characters |
| `too-many-request-ids` | More distinct IDs than `distribution.resend.max-ids-per-run` |
| `intake-paused` | An operator has paused intake (exit 1) |
| `store-unavailable` | The store could not be read before the run (exit 2) |

Per-ID outcomes:

| Outcome | Meaning | What to do |
|---|---|---|
| `unknown-request` | No request row | A mistake in the list |
| `not-eligible` | The latest attempt is not resendable (for example `SENT` without the override, `PARKED`, or a `PENDING` attempt 1) | A mistake in the list |
| `request-claimed` | A queue run or a replay holds the request right now | Try that ID later |
| `resend-in-progress` | The latest attempt is a `PENDING` resend row, live or expired, or another run is claiming it | Try later. If the row stays `PENDING` after its lease, see "A resend left claimed-incomplete" |
| `SENT` | Sent; any 2xx counts | — |
| `NOT_APPLICABLE(<reason>)` | The rules left nothing to send (`sjp-or-box-hearing`, `nothing-left-after-redaction`) | — |
| `FAILED(<reason>)` | The attempt failed with that reason | See below |
| `not-run` | Never claimed: a pause began during the run, or the store failed before this ID's claim | Resend that ID once the cause has cleared |
| `claimed-incomplete` | Claimed and committed `PENDING`, so **its POST may have gone out**, but its outcome was not recorded: the store failed, or the completion was fenced as a stale runner | See "A resend left claimed-incomplete" |

Failure reasons:

- **`FAILED(gateway-transient)` is outcome-unknown.** A 5xx, a 408 or a read timeout means the
  request was written and HMPPS may have received it. The POST is not idempotent at HMPPS, so
  **confirm with HMPPS whether it arrived before you send it again.** A connection failure (the
  request was never sent) or a 429 that is still failing after the in-run retries was not accepted,
  and can be sent again once the cause has cleared.
- **`FAILED(receiver-rejected)`:** HMPPS refused the body with a 4xx. Sending it again unchanged
  is rejected again. Application-only hearings are a known 400 class (D16 (a)).
- **`FAILED(payload-unavailable)`:** neither the cache nor the results query API had the hearing.
- **`FAILED(payload-changed)`:** see the `sharedTime` check above.
- **`FAILED(rules-exception)`:** a redaction rule threw. Raise it with the owning team, because
  resending the same payload throws again.

### A resend left claimed-incomplete

A `claimed-incomplete` ID, and a `PENDING` resend row that outlived its lease (a runner that died),
is never reclaimed automatically. Until it is closed, `resend-leg` refuses it `resend-in-progress`
and a replay of its request is refused or deferred. It is closed by the guarded rollback
close-in-flight procedure:

1. Confirm with HMPPS whether the attempt's POST arrived. The row may hold no `body_digest`, even
   though a body was sent.
2. Pause intake: `java -jar app.jar pause-intake`. Then wait until every running replica reports
   `probation_intake_suspended{cause="operator"} = 1`.
3. Run `java -jar app.jar rollback-close-in-flight`. It is refused `intake-not-paused` unless
   intake is paused. It closes **every** `PENDING` or `PARKED` leg row in the service, not just
   yours, to `FAILED(rolled-back)` with `prev=<STATUS>/<reason>` in `note`, and gives each resend
   row a fresh claim token, so a late runner's completion changes nothing. In-flight `RECEIVED` or
   `RETRYING` requests become `FAILED(rolled-back)`. `COMPLETED` requests are untouched.
4. Resume intake when the procedure you are following allows it (`java -jar app.jar
   resume-intake`).
5. The closed resend row (attempt ≥ 2, `FAILED(rolled-back)`) is now resend-eligible. If HMPPS did
   not receive the attempt, resend it. A request closed `FAILED(rolled-back)` at attempt 1 is
   replayed instead. The replay runner is not packaged in this build, so that request waits for it
   (see "Replay or resend-leg?").

## The forced resend

When HMPPS needs a hearing again that the service has already sent, or one the rules found nothing
to send for, use the forced resend:

```bash
java -jar app.jar resend-leg --leg HMPPS --request-ids <id> \
    --operator <your-name> --reason "<HMPPS ticket or reason>" --force-resend-sent
```

`--force-resend-sent` is the design's `allowTerminalSuccess` override. It applies to each ID on its
own:

- An ID whose latest attempt is `SENT` or `NOT_APPLICABLE` runs on a new `resend` attempt exactly
  like any resend: the payload is fetched again, the `sharedTime` check runs (with
  `--allow-changed-payload` as for any resend), the youth switch is read again, and the body is
  redacted, digested and sent.
- The attempt row records your name in `operator`, `override = true` and
  `override=force-resend` in `note`. When `--allow-changed-payload` was also used, the note is
  `override=force-resend;override=allow-changed-payload`.
- One line, `FORCED_RESEND attempt=<n> operator=<name> reason=<reason>`, is appended to the
  request's `audit_note` before anything is sent, with the reason sanitised to one line. Write the
  reason with that in mind, and put no personal data in it.
- The request stays `COMPLETED` whatever the outcome. A forced resend that fails leaves the latest
  attempt `FAILED(<reason>)`, raises the same leg-failed signals as any failure, and the leg can
  then be resent again without the override.
- The outcome is counted on `probation_leg_outcomes_total{origin="resend", forced="true"}` and
  logged with `forced=true`.
- For an ID whose latest attempt is `FAILED`, the override is not used. The ID is resent as a
  normal resend: no `override=force-resend`, no reason line, and `forced=false`.

**A forced resend is a genuine duplicate at HMPPS.** The POST is not idempotent, so HMPPS receives
the hearing a second time. Agree it with HMPPS first.

A `NOT_APPLICABLE` leg that is forced runs the rules as they are now. If the switch or the payload
has changed since, there may now be something to send.

## What was sent, and what a resend sends

- **A resend after the cache entry has expired sends the results query API's form.** That form has
  no `cpsProsecutorIds` and a different `hearing` field set, so the body differs in shape from the
  one first sent. This is expected (Q19, D10), and the `body_digest` of the two attempts differs.
- **The exact bytes of every POST are stored in `outbound_body`**, one row per attempt that
  serialised a body, for a retention window of 6 to 7 months (`distribution.body-retention`, `P6M`
  by default, after which whole months are dropped). To investigate what was sent, read the
  attempt's stored body within that window. After it, only the attempt row's `body_digest`,
  `service_version` and metadata remain. Read a body only when an investigation needs it, never
  paste it outside the investigation, and never log it.

```sql
SELECT m.attempt, m.attempt_origin, m.status, m.body_digest, m.service_version, b.created_at
FROM outbound_message m
LEFT JOIN outbound_body b ON b.outbound_message_id = m.id
WHERE m.source = 'RESULTS' AND m.request_id = '<request-id>' AND m.leg = 'HMPPS'
ORDER BY m.attempt;
```
