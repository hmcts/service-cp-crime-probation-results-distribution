# Contract: command-line runners and listings (this slice)

This service has no business REST API (constitution Principle III) and this slice adds no HTTP
surface (O-1; slice 005 owns `/operations/**`). Its external interface is the image's command-line
runners, each invoked the same way the slice's own spec fixes (FR-004-042, FR-004-043), each
exercising exactly the same application service slice 005's eventual endpoint will call
(FR-004-042's "same inputs ... same rules ... same outcomes"). This file is the authority for that
interface until 005 supersedes it with an OpenAPI document; names marked **(proposed)** are the
design's own naming, not yet confirmed.

## Invocation

```
java -jar app.jar <action> [--flag value ...]
```

Supplying `<action>` runs exactly that action's runner and exits — it never starts the message
listener or the body-maintenance scheduler (FR-004-045). Omitting `<action>` starts the service
normally, unchanged from slices 001/002.

Exit codes mirror the bounded `RunnerOutcome`: `0` = `DONE`, `1` = `REFUSED` (with the refusal code on
stdout/the log line), `2` = `FAILED`. A runbook step can therefore branch on the exit code alone,
without parsing free text (FR-004-045).

## 1. `replay` (FR-004-014–017)

```
replay --request-id <uuid>
```

| Input | Rule |
|---|---|
| `--request-id` | A UUID. Not a UUID → `unreadable-argument`. Missing → `missing-argument`. |

| Outcome | Condition |
|---|---|
| `enqueued`, with the new `messageId` | A `FAILED` request with no live `PENDING` resend row on its leg |
| `no-op` | The request is `COMPLETED` |
| `unknown-request` | No request row |
| `not-eligible` | The request is `RECEIVED` or `RETRYING` |
| `resend-in-progress` | The leg's latest row is a `PENDING` resend row, live or expired (Research Decision 1) |
| `store-unavailable` / `broker-unavailable` | Failure; nothing changed |

Writes nothing to the store itself (FR-004-017); the enqueue and every downstream effect happen
through the normal guard/admission path once the message is delivered.

## 2. `resend-leg` (FR-004-018–041; §4.8 action 3)

```
resend-leg --leg HMPPS --request-ids <id,id,...> --operator <name> --reason <text> \
           [--force-resend-sent] [--allow-changed-payload]
```

| Input | Rule |
|---|---|
| `--leg` | Only `HMPPS` accepted; anything else → `unreadable-argument` |
| `--request-ids` | 1…`distribution.resend.max-ids-per-run` (default 100) **distinct** UUIDs, comma-separated; duplicates collapsed first. Empty → `missing-argument`. Over the cap (after collapsing) → `too-many-request-ids`, whole run refused, nothing read or changed. Any entry not a UUID → `unreadable-argument`, whole run refused. |
| `--operator`, `--reason` | Required, non-blank, ≤ 200 characters each, sanitised to one line before any write. Missing/blank → `missing-argument`. Over 200 characters → `argument-too-long`. |
| `--force-resend-sent` | The `allowTerminalSuccess` override — proposed name; JSON field `allowTerminalSuccess` once slice 005 exists |
| `--allow-changed-payload` | The `allow-changed-payload` override — JSON field `allowChangedPayload` once slice 005 exists |

**Whole-run refusals** (nothing read or changed): `missing-argument`, `unreadable-argument`,
`argument-too-long`, `too-many-request-ids`, `intake-paused`, `store-unavailable`.

**Per-ID outcomes** (checked in this order — FR-004-022): `unknown-request` →
`request-claimed` → `resend-in-progress` → `not-eligible` → the resend's own result (`SENT`,
`NOT_APPLICABLE(<reason>)`, `FAILED(<reason>)`), `not-run` (the ID was never claimed: the run was
cut short by an intake pause beginning mid-run, or the store failed or could not confirm the pause
before the ID's claim committed), or `claimed-incomplete` (the ID's attempt row was claimed and
committed `PENDING`, so its POST may have gone out, but its outcome could not be recorded: the store
failed during or after the run, or the completion was fenced as a stale runner; the row stays
`PENDING` — or as the fencing statement left it — for the guarded rollback close-in-flight
procedure, spec edge case "The store fails during a resend run").

**Exit code**: a run that produced a report exits `0` (`DONE`) unless the store failed during it —
a claim, a completion write or a mid-run pause read raising a store error — in which case the report
is still written and the run exits `2` (`FAILED`), the edge case's "the run's result is failed". A
store that cannot answer the run's first pause read refuses it whole `store-unavailable`, exit `2`,
with nothing changed.

**Startup checks**: before the resend pipeline is built, the command runs the service's own startup
validation over the same `distribution.*` properties (the composed stage budgets including the send
stage, lease ordering, one system user, the youth-switch default — logged at INFO —, the delivery
limit, `max-ids-per-run` ≥ 1, the deployed-profile secrets and forbidden properties), and refuses a
blank `distribution.gateway.subscription-key` outside the `local` and `test` profiles. A failed
check exits `2` (`FAILED`) with `reason=invalid-configuration`, before any client is built.

**Telemetry**: the command's counters (`probation_resend_refusals_total`,
`probation_stale_runner_rejections_total`, `probation_leg_outcomes_total`) are recorded on the
command context's own meter registry, the one the service exports through, and the push registry
(OTLP) publishes them when the context closes, before the process exits
(`PackagedResendLegCommandIT`). The command is short-lived and has no scrape of its own, so a
pull-only registry never sees them, and a push that fails at exit is lost. That makes the run's one
structured report line, with every ID's outcome, the refusal codes and the summary, and the
per-outcome log lines the authoritative per-run record. The counters add to it; they do not
replace it.

**Output**: one report (also written as one structured log line) listing the run's `resend_run_id`,
the leg, whether each override was requested, every distinct listed ID exactly once with its outcome,
and a summary (counts by outcome/reason). `probation_resend_refusals_total{reason}` increments once
per refused ID.

## 3. `pause-intake` / `resume-intake` (FR-004-051, 052; §4.8 action 4)

```
pause-intake
resume-intake
```

No arguments. Outcomes: the action taken, or `no-op` if intake was already in the requested state.
Sets/clears the persisted `intake_control.paused_by_operator` row (Research Decision 3) and
`probation_intake_suspended{cause="operator"}`.

## 4. `purge-queue` (FR-004-053; §4.8 action 6)

```
purge-queue
```

No arguments. Refused `intake-not-paused` unless intake is paused. On success, reports the number of
messages removed and the cut-off instant (the run's start time); the dead-letter queue is untouched.
Messages at the cut-off are retained. Wait for every running replica to reconcile its operator-pause
gauge before invoking purge or close-in-flight. A standalone runner reads the persisted pause state
before its guard; it starts no consumer or maintenance scheduler.

**T037 capability gap**: the installed Java SDK 7.17.19 exposes no broker-native cutoff-delete
operation. The runner currently returns `FAILED`/`broker-unavailable` when paused, logging the bounded
diagnostic `unsupported-broker-operation`. It receives and removes nothing. A receive/abandon fallback
is forbidden: it can spend later messages' delivery budgets and move them to the DLQ. Successful
purge remains outstanding until a supported native deletion integration is selected and verified.

## 5. `submit-hearing` (FR-004-054–058; §4.8 action 5)

```
submit-hearing --hearing-id <uuid> --hearing-day <YYYY-MM-DD> --operator <name> --reason <text>
```

| Input | Rule |
|---|---|
| `--hearing-id` | UUID; not one → `unreadable-argument` |
| `--hearing-day` | `YYYY-MM-DD`; not one → `unreadable-argument` |
| `--operator`, `--reason` | As resend-leg's: required, ≤ 200 characters |

Outcome: `enqueued` with the `requestId` (from `RequestIdRecipe`) and a fresh run id, or
`payload-unavailable` if neither the dated nor the undated `EXT_` key holds a readable payload with a
`sharedTime`. As built: `enqueued` exits `0`; `payload-unavailable` and an input refusal exit `1`;
`broker-unavailable` exits `2`. The hearing id and day enter the recipe in their canonical forms
(lower-case UUID, `YYYY-MM-DD`) and `sharedTime` as the payload's raw string. Writes nothing to the store itself; the admission effects happen once the message is
delivered (a first delivery, a no-op, an abandon, or a replay with route `support` — FR-004-057).

## 6. `rollback-close-in-flight` (FR-004-049/049a — confirmed 2026-10-26; Research Decision 2)

```
rollback-close-in-flight
```

No arguments. Refused `intake-not-paused` unless intake is paused. Closes every `PENDING`/`PARKED`
leg row exactly as FR-004-049a states, one transaction per row; reports the number of rows closed by
prior status. **This runner is owned by this service**, confirmed 2026-10-26 (option A, FR-004-049).
It remains off HTTP.

## 7. `list-requests` (read-only; FR-004-043; §4.8 action 1)

```
list-requests --hearing-id <uuid> --hearing-day <YYYY-MM-DD>
list-requests --status <status> [--reason <reason>] [--updated-after <instant>] [--updated-before <instant>]
```

Exactly one form per invocation. Prints only identifiers, statuses and reasons, attempt numbers and
origins, HTTP statuses and times (FR-004-041) — never a stored command, body, payload, operator name
or reason text. Pages at 500 rows (Research Decision 5) and states when more exist.

## 8. `list-dlq-completed` (read-only; FR-004-043; §4.8 recommended listing)

```
list-dlq-completed
```

No arguments. Peeks the dead-letter queue, lists only the entries whose `processed_request` is
`COMPLETED`, with their dead-letter reason. Removes nothing. Pages at 500 rows. As built: the peek is
a Service Bus receiver peek of the `DEAD_LETTER_QUEUE` sub-queue (the management client cannot
peek); each entry's `requestId` is read from its body; a dead-letter reason outside this service's
closed set prints as `other` (none recorded: `none`) and a `messageId` that is not `RESULTS:<uuid>`
or `REPLAY:<uuid>` prints as `other`, so text chosen elsewhere is never echoed.

## Cross-cutting rules (every runner above)

- Runs under the pod's own workload identity and network path; no role issued for a runner alone
  (FR-004-044).
- Input is checked and refused whole, before any store, cache or broker read, with nothing changed,
  and a refusal never echoes the text supplied (FR-004-020).
- No log line, metric label, report, listing or refusal from any runner carries a stored or sent
  body, a stored command, a payload value, a `userId`, an operator's name or a reason's text, except
  the four places this slice's rules explicitly put an operator's name or reason text (resend
  attempt rows, a forced-resend's `audit_note` line, a submit-hearing admission's `audit_note` line,
  and the submit-hearing command's own application properties) (FR-004-041).
