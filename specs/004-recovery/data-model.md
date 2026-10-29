# Phase 1 Data Model: Operator recovery

This slice adds **no new business entity** to the two tables slice 002 owns
(`processed_request`, `outbound_message`) or to the `outbound_message_latest` view — it only
exercises existing columns (`command`, `replayed_at`, `attempt_origin`, the leg claim,
`resend_run_id`, `operator`, `override`, `note`) through new write paths. The one schema change is
the confirmed `intake_control` table from `research.md` Decision 3 (FR-004-052 option A,
2026-10-26).

## Entities already defined by slice 002 (read or written by this slice, unchanged shape)

### `processed_request`

| Column this slice writes | Written by | Rule |
|---|---|---|
| `status` | Replay admission (`FAILED`→`RECEIVED`); recovery (`FAILED`→`COMPLETED`); rollback close (`RECEIVED`/`RETRYING`→`FAILED`) | FR-004-004, FR-004-032, FR-004-049a |
| `failure_reason`, `exhausted_message_id` | Cleared on replay admission and on recovery; untouched by a class-2 resend outcome | FR-004-004, FR-004-032, FR-004-033 |
| `completion_reason` | Set to `recovered` on recovery | FR-004-032 |
| `replayed_at` | Set on replay admission; cleared on completion or final failure | FR-004-004, FR-004-012 |
| `audit_note` | Six new append-only line kinds (see below) | FR-004-004/005/032/037/056/057; "Processed request" Key Entity |
| `command` | **Read only** by the replay action — never written by this slice | FR-004-015 |

`audit_note` line kinds this slice appends (never replaces a prior line):
1. the replay note: `REPLAYED_AFTER_FAILURE prior=<failure_reason>` (FR-004-004)
2. the replay route line: `service` / `support` / `copy` (FR-004-005)
3. submit-hearing's admission line: operator, run id, reason (FR-004-057)
4. a `payload-changed` refusal line, quoting no payload content (FR-004-030)
5. a forced-resend reason line, sanitised to ≤ 200 characters (FR-004-037)
6. a recovery line: the prior `failure_reason` and the cleared `exhausted_message_id` (FR-004-032)

### `outbound_message`

| Column this slice writes | Written by | Rule |
|---|---|---|
| `attempt`, `attempt_origin` | A new `resend` row (`max(attempt)+1`); a new `replay` row (`max(attempt)+1`, over `FAILED` attempt ≥ 2 only) | FR-004-009, FR-004-025 |
| the leg claim (`claim_owner`, `claim_token`, `claim_expires_at`) | Set at insert on a resend row only, committed before the POST | FR-004-025 |
| `resend_run_id`, `operator` | Set at insert on a resend row | FR-004-025 |
| `override` | `true` when `allow-changed-payload` or `allowTerminalSuccess` was used | FR-004-031, FR-004-037 |
| `note` | New closed forms this slice writes: `prev=<STATUS>/<reason>`, `prev=PENDING/-`, `rerun-by=<messageId>`, `override=allow-changed-payload`, `override=force-resend` (joined with `;`) | FR-004-008, FR-004-031, FR-004-037, FR-004-049a |
| `payload_source`, `payload_shared_time` | Every resend attempt that read a payload | FR-004-030 |
| the outcome columns (`status`, `reason`, `youth_switch`, `http_status`, `gateway_request_id`, `body_digest`, `sent_at`, `service_version`) | In-place re-run (replay over attempt 1/expired `PENDING`) or a fresh resend/replay row's own run | FR-004-008, FR-004-026–028 |

No new CHECK constraint is required: every value this slice writes is already inside the closed sets
slice 002's CHECKs enforce (`attempt_origin` already includes `resend` and `replay`; `note`'s closed
forms are already listed in DIGEST §8 "note forms"). `SchemaVocabularyIT` (slice 002's) continues to
assert the SQL vocabularies against the Java enums; this slice adds no new enum value to `LegStatus`
or `LegReason`.

### `outbound_message_latest` (view)

Read, never written, by every eligibility decision and listing this slice adds
(`LegResender`'s per-ID check, `RequestListingQuery`, the recovery decision). FR-004-034 states this
as a rule: "every report and listing of this slice MUST read a leg's state from its latest attempt,
never from the request's status."

### `outbound_body`

Read only, and only by an operator investigating a stored body within its retention window
(FR-004-047); this slice performs no new write to it beyond what an in-place re-run or a new attempt
row already triggers through slice 002's `OutboundLog` port (delete-and-replace on an in-place
re-run; a fresh insert for a new attempt). No change to the digest trigger, the partitioning, or the
retention job (slice 003).

## New entity: `intake_control` (confirmed 2026-10-26 — Research Decision 3)

```sql
CREATE TABLE intake_control (
    id                   smallint PRIMARY KEY DEFAULT 1,
    paused_by_operator   boolean NOT NULL DEFAULT false,
    paused_at            timestamptz,
    updated_at           timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT intake_control_single_row_chk CHECK (id = 1)
);
INSERT INTO intake_control (id) VALUES (1);
```

- Exactly one row, enforced by the CHECK and seeded by the migration — the same single-row pattern as
  slice 003's `body_maintenance_state`.
- `paused_by_operator`: the durable record of the operator pause (FR-004-051, FR-004-052).
  `paused_at` is set when it flips to `true` and cleared (`NULL`) when it flips back — this is the
  value the alert "a request non-terminal for more than 30 minutes" style reasoning could later use
  to distinguish "paused 5 minutes ago" from "paused 3 days ago"; this slice does not add that alert
  (slice 006's), it only makes the timestamp available.
- `updated_at` is for operational visibility only (e.g. a `kubectl exec` diagnostic query), not read
  by any rule.
- Read by every pod on startup and on `distribution.store.probe-interval` (reusing the existing
  validated property), reconciling `ConsumerLifecycleController`'s in-memory flag and the
  `probation_intake_suspended{cause="operator"}` gauge to this row.
- Written only by `IntakePauseAction` / resume, inside the same transactional JDBC template the rest
  of this service's writes use; never written by a queue run, a replay or a resend.
- Never purged, never partitioned — a control row, not an event log.
- Confirmed on 2026-10-26: this shared row is the operator pause mechanism. A pod must read it
  successfully before starting consumption, then reconcile on the store-probe interval.
  Read failure keeps consumption suspended.

## Closed value sets this slice introduces or completes (Java enums / records, no new table)

These already belong in `domain` per the package structure; none needs a new table or column beyond
what slice 002 defined, since they are all written into existing `text` columns whose CHECKs enumerate
the closed set.

| Type | Values | Written to |
|---|---|---|
| `ReplayRoute` (**already exists** on `002-hmpps-distribution`, `WireValued`-based) | `SERVICE`, `SUPPORT`, `COPY` | `audit_note` line 2; the `route` metric/log label |
| `ResendRefusal` (**already exists** on `002-hmpps-distribution`, `WireValued`-based; this slice's spec calls it `ResendRefusalReason`) | `UNKNOWN_REQUEST`, `NOT_ELIGIBLE`, `RESEND_IN_PROGRESS`, `REQUEST_CLAIMED` | Report/refusal only — never a row (Key Entities: "all are answer and report codes, never written to a row") |
| `WholeRunRefusal` (new; should be refactored onto `002-hmpps-distribution`'s `WireValued`/`Vocabularies` pattern once merged) | `INTAKE_PAUSED`, `TOO_MANY_REQUEST_IDS`, `MISSING_ARGUMENT`, `UNREADABLE_ARGUMENT`, `ARGUMENT_TOO_LONG`, `STORE_UNAVAILABLE`, `BROKER_UNAVAILABLE`, `INTAKE_NOT_PAUSED` | Report/refusal only |
| `RunnerOutcome` (new) | `DONE`, `REFUSED`, `FAILED` | The bounded runner result (FR-004-045) |
| `AttemptOrigin` (extends slice 002's) | `queue`, `resend`, `replay` — already defined; no new value | `outbound_message.attempt_origin` |

**Note on branch base**: this data model was written against `main`, which does not yet carry slice
002's implementation. `002-hmpps-distribution` (the open PR branch this slice merges onto) already
has `ReplayRoute`, `ResendRefusal`, `OperationsAction` and `OperationsFailure`, built against a
shared `WireValued`/`Vocabularies` helper not described above. Treat this table as superseded by
that branch's actual `domain/` package where the two differ; the entries above are kept only as the
record of what this plan anticipated before that was known.

## State transitions this slice adds to the request state machine

```
FAILED --[replay admitted]--> RECEIVED        (FR-004-004; replayed_at set)
RECEIVED/RETRYING --[queue run settles]--> COMPLETED(both-legs-terminal) | FAILED(leg-exhausted)
                                           (slice 002's statement, now reached via a replay too;
                                            completion_reason = recovered when replayed_at's history
                                            shows REPLAYED_AFTER_FAILURE, FR-004-012)
FAILED --[resend recovers the leg]--> COMPLETED(recovered)               (FR-004-032)
COMPLETED --[any statement of this slice]--> COMPLETED                   (FR-004-034: no-op, always)
RECEIVED/RETRYING --[rollback close-in-flight]--> FAILED(rolled-back)    (FR-004-049a)
FAILED --[rollback close-in-flight]--> FAILED (unchanged: keeps its own reason/exhausted id)
COMPLETED --[rollback close-in-flight]--> COMPLETED (untouched)
```

No new terminal state is introduced; `COMPLETED` and `FAILED` keep slice 002's meaning exactly. This
slice's only addition to the state machine is a new *edge into* `RECEIVED` (from `FAILED`, via
replay) and a new *edge into* `COMPLETED` (from `FAILED`, via resend recovery) — both already fully
specified in DIGEST §7 and restated in `plan.md`'s traceability table.
