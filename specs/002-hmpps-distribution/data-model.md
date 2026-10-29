# Data model: HMPPS distribution

This model implements `spec.md` FR-002-005–012, FR-002-040–056 and FR-002-071–072. The normative V1 SQL is design Appendix B.2, summarised in `doc/design/DIGEST.md` §8. Flyway `V1__create_processed_log.sql` creates exactly three tables and one view. V1 includes constraints and columns needed by slices 003 and 004 even where slice 002 does not yet write those values. The database clock is the only time authority for persisted timestamps.

## Identity and relationships

```text
DistributionCommand (source, requestId)
  1 ── 1 processed_request (source, request_id)
             1 ── many outbound_message (source, request_id, leg, attempt)
                           1 ── 0..1 current outbound_body (outbound_message_id)
             └── outbound_message_latest: highest attempt per leg
```

The guard's key is `(source, requestId)`. Its SHA-256 fingerprint covers the canonical `hearingId`, `hearingDay`, `sharedTime` and `eventType`; `userId` is excluded. The command is a claim check, so the hearing payload is fetched and held in memory only. This slice writes one HMPPS attempt per admitted request, then re-runs that attempt in place when eligible. Slices 004's replay and resend create later attempt rows. The body row represents the most recently POSTed bytes for an attempt and can later be purged without deleting the request or attempt.

## Persisted entities

### `processed_request`

Keep the informant service's original DDL block byte-identical; apply this service's changes through subsequent `ALTER`s. See FR-002-050–051 and digest §8.

| Column | Type / presence | Meaning |
|---|---|---|
| `source`, `request_id` | text, uuid; required | Composite primary key and command identity. |
| `hearing_id`, `hearing_day`, `shared_time`, `event_type` | uuid, date, timestamptz, text; required | Immutable identity fields from the admitted command. |
| `request_fingerprint` | text; required | SHA-256 of the four canonical identity fields. |
| `status` | text; required | `RECEIVED`, `RETRYING`, `COMPLETED` or `FAILED`. |
| `attempts` | integer; required, default 0 | Lifetime number of request runs; non-negative. |
| `completion_reason` | text, nullable | `both-legs-terminal` or `recovered`; required when `COMPLETED`. |
| `failure_reason` | text, nullable | `leg-exhausted` or `rolled-back`; required when `FAILED`, NULL otherwise. |
| `exhausted_message_id` | text, nullable | Broker message ID required for `FAILED(leg-exhausted)`. |
| `audit_note` | text, nullable | Append-only, ISO-8601-stamped event lines. Slice 002 writes none. |
| `claim_owner`, `claim_token`, `claim_expires_at` | text, uuid, timestamptz; nullable as a group | Request lease and fencing token; all three set or all three NULL. |
| `created_at`, `updated_at` | timestamptz; required, default `now()` | Database-clock timestamps. |
| `command` | text; required, added by `ALTER` | Accepted command body, byte for byte. |
| `replayed_at` | timestamptz, nullable, added by `ALTER` | Slice 004's replay marker; selects the replay eligibility column. |

Constraints: primary key `(source, request_id)`; closed status and reason sets; `attempts >= 0`; claim-triple all-or-none; `COMPLETED` requires a valid completion reason, and any non-NULL completion reason belongs to that set; `FAILED` requires a valid failure reason, and other statuses require it NULL; `FAILED(leg-exhausted)` requires `exhausted_message_id`. Replace the inherited exhausted-ID CHECK, then add the completion- and failure-reason CHECKs. Index `(hearing_id, hearing_day)` for support lookups. The application preserves command identity after first admission and appends, rather than replaces or truncates, `audit_note`.

### `outbound_message`

Each row is one `(source, request_id, leg, attempt)`; its `attempts` count is incremented when that attempt is run or re-run. Its foreign key to `processed_request(source, request_id)` uses `ON DELETE RESTRICT`. See FR-002-040–043, FR-002-052 and digest §8.

| Column | Type / presence | Meaning |
|---|---|---|
| `id` | uuid; required | Application-generated primary key. |
| `source`, `request_id` | text, uuid; required | Parent request identity. |
| `leg` | text; required | Only `HMPPS` in V1. |
| `attempt` | integer; required, default 1 | Attempt number, at least 1; unique per request and leg. |
| `attempt_origin` | text; required, default `queue` | `queue`, `resend` or `replay`; attempt 1 if and only if `queue`. |
| `status`, `reason` | text; status required, reason nullable | Closed status/reason matrix below. |
| `attempts` | integer; required, default 0 | Number of executions of this attempt; non-negative. |
| `http_status` | integer, nullable | Last gateway HTTP answer, if any. |
| `gateway_request_id` | text, nullable | Response-header ID, when a header has been identified. |
| `body_digest` | text, nullable | Lowercase SHA-256 hex of the exact UTF-8 outbound bytes supplied to the sender. |
| `payload_source` | text, nullable | `cache-day`, `cache-nullday` or `query`. |
| `payload_shared_time` | timestamptz, nullable | Parsed `sharedTime` of the fetched payload. |
| `service_version` | text, nullable | Build version; present exactly with `body_digest`. |
| `youth_switch` | text, nullable | `ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE` or `UNREADABLE_DEFAULTED_INACTIVE`. |
| `claim_owner`, `claim_token`, `claim_expires_at` | text, uuid, timestamptz; nullable as a group | Leg lease, set exactly for `resend` rows. |
| `resend_run_id` | uuid, nullable | Set exactly for `resend` rows. |
| `operator` | text, nullable | Resend operator; slice 004. |
| `override` | boolean; required, default false | Whether a resend override was used; slice 004. |
| `note` | varchar(256), nullable | One or more bounded note forms; never incrementally grows on a re-run. |
| `sent_at` | timestamptz, nullable | Database-clock time; present exactly on `SENT`. |
| `updated_at` | timestamptz; required, default `now()` | Database-clock last outcome update. |

The unique key is `(source, request_id, leg, attempt)`. Index `(leg, status, updated_at)`. Both request and leg rows remain after body retention deletes body partitions.

#### Closed status and reason matrix

| Status | Allowed reason | Request settlement in slice 002 |
|---|---|---|
| `PENDING` | NULL | No outcome yet. |
| `SENT` | NULL | `COMPLETED`; complete delivery. |
| `NOT_APPLICABLE` | `sjp-or-box-hearing`, `nothing-left-after-redaction` | `COMPLETED`; complete delivery. |
| `PARKED` | `payload-unavailable`, `gateway-transient` | `RETRYING`; abandon non-final delivery. |
| `FAILED` class 1 | `receiver-rejected`, `rules-exception` | `COMPLETED`; complete delivery. |
| `FAILED` class 1, reserved | `contract-invalid` | V1 permits it; no slice writes it while outbound validation is parked. |
| `FAILED` class 2 | `payload-unavailable`, `gateway-transient` | On final permitted delivery, request `FAILED(leg-exhausted)`; dead-letter. |
| `FAILED` class 2, future | `payload-changed`, `rolled-back` | Slice 004 resend and rollback only. |

`reason` is NULL exactly for `PENDING` and `SENT`; every reasoned branch checks `IS NOT NULL` so SQL three-valued logic cannot admit an unspecified reason. The `nothing-left-after-redaction` reason is restricted to HMPPS. The full V1 matrix includes the future rows even though this slice writes only attempt 1.

#### Presence and pairing checks

V1 enforces all five presence checks across present and future outcomes:

1. `payload_source` is required except on `PENDING`, `payload-unavailable` and `rolled-back` rows.
2. `body_digest` is present exactly on `SENT`, `receiver-rejected` and `gateway-transient` rows; `rolled-back` is exempt.
3. `service_version` is present exactly when `body_digest` is; there is no exemption.
4. `http_status` is required on `SENT` and `receiver-rejected`; gateway-transient records the last answered status or NULL if no answer.
5. On HMPPS, `youth_switch` is NULL on `PENDING`, `payload-unavailable` and `payload-changed`, present otherwise; `rolled-back` is exempt.

For every row, including `rolled-back`: `payload_shared_time` implies `payload_source`; `body_digest` implies `payload_source`; `youth_switch` implies `payload_source`; `http_status` implies `body_digest`. `sent_at` is present exactly on `SENT`. The claim triple is all-or-none and, with `resend_run_id`, present exactly on `resend` rows. A non-NULL note is 1–256 characters, built only from these forms: `prev=<STATUS>/<reason>`, `prev=PENDING/-`, `rerun-by=<messageId>`, `override=allow-changed-payload`, `override=force-resend`; `baseline=none` and `contract=invalid` are reserved. The schema test must compare every closed database value set with its Java vocabulary and exercise both accepted and rejected cells.

### `outbound_message_latest` view

The view selects the highest `attempt` for each `(source, request_id, leg)`:

```sql
SELECT DISTINCT ON (source, request_id, leg) *
FROM outbound_message
ORDER BY source, request_id, leg, attempt DESC;
```

All reads of a leg's current state use this view, including support lookups and parked-age gauges. `processed_request.status` is a request-level settlement state, not the leg's latest outcome. Later slices use the view for reconciliation and resend selection. See FR-002-053.

### `outbound_body`

| Column | Type / presence | Meaning |
|---|---|---|
| `outbound_message_id` | uuid; required | Foreign key to `outbound_message(id)` with `ON DELETE RESTRICT`. |
| `body` | text; required | Exact UTF-8 outbound JSON text prepared for the send; never `jsonb` or the receiver's response body. |
| `created_at` | timestamptz; required, default `now()` | UTC month partition key from the database clock. |

The primary key is `(outbound_message_id, created_at)` and the parent is `PARTITION BY RANGE (created_at)`. Monthly partitions are `outbound_body_YYYY_MM`, with inclusive first-of-month and exclusive next-month UTC bounds. V1 creates the current month and the next two only when no table of that name exists, plus `outbound_body_default`. Slice 003 owns subsequent partition creation and retention. The `BEFORE INSERT OR UPDATE` row trigger `outbound_body_digest_trg` rejects a body if its attempt's `body_digest` is NULL or differs from `encode(sha256(convert_to(body, 'UTF8')), 'hex')`; a missing parent is rejected by the foreign key.

An attempt has a body row exactly when it serialised a body for sending: `SENT`, `FAILED(receiver-rejected)`, `PARKED(gateway-transient)` or `FAILED(gateway-transient)`. This includes a connection failure before a POST reached the receiver; the stored text is still the exact prepared outbound body. Insert the text after the leg-outcome UPDATE in the same transaction as the request transition. On an in-place re-run, delete any former body row, then insert the new body if one exists, in that same transaction. Thus at most one body row remains for the attempt. A digest may outlive the body after retention, so the reverse implication `body_digest → body row` is not a database CHECK. Bodies contain personal data and must never be logged or placed in labels.

## State transitions and concurrency

| Event | Request state | Leg attempt state | Broker settlement |
|---|---|---|---|
| First valid admission | No row → `RECEIVED`, with request claim | Insert attempt-1 `PENDING`, no leg claim | Run; settle after outcome commit. |
| Redelivery meets live claim | Unchanged | Unchanged | Abandon; dead-letter `exhausted` if final. |
| Redelivery meets expired claim | `RECEIVED`/`RETRYING` claim replaced, `attempts + 1` | Re-run eligible attempt-1 `PENDING` or `PARKED` in place | Settle after outcome commit. |
| Terminal leg: `SENT`, `NOT_APPLICABLE` or class-1 `FAILED` | `RECEIVED`/`RETRYING` → `COMPLETED` | One outcome UPDATE; `attempts + 1` | Complete. |
| Non-final transient leg | `RECEIVED`/`RETRYING` → `RETRYING` | `PARKED(payload-unavailable/gateway-transient)` | Abandon without delay. |
| Final transient leg | `RECEIVED`/`RETRYING` → `FAILED(leg-exhausted)` | `FAILED` with same reason instead of `PARKED` | Dead-letter `leg-exhausted`. |
| Duplicate completed command | `COMPLETED` unchanged | Unchanged | Complete with no POST. |
| Different fingerprint for existing key | Unchanged | Unchanged | Dead-letter `collision`. |
| Failed request before slice 004 | `FAILED` unchanged | Unchanged | Dead-letter `exhausted` immediately. |

The request and attempt-1 rows are inserted in one transaction. The request claim has owner, token and expiry; a takeover obtains a fresh triple atomically. Attempt-1 and future replay rows have no leg claim and are fenced by the request claim. The outcome is one UPDATE guarded by the row's expected status and the request's current claim owner/token. The request transition and body replacement commit with it. Zero updated rows mean a stale runner: increment the rejection counter, write nothing further, and hand settlement to the listener (abandon or final-delivery `exhausted`). Every in-place re-run writes no intervening `PENDING`; it re-fetches the payload and switch, derives every outcome column again and replaces the entire note with `prev=<STATUS>/<reason>` or `prev=PENDING/-`.

The processing deadline is 4 minutes, the request-claim lease is 4 minutes 30 seconds, and the broker lock is 5 minutes; startup validates this order. A crash after a 2xx POST and before commit can cause a duplicate POST on redelivery. Count an expired-claim re-run of a `PENDING` attempt-1 row in `probation_crash_reruns_total`; do not count `PARKED` re-runs because their crash cannot be distinguished from an ordinary retry. Slice 004 introduces replay (`FAILED → RECEIVED`) and resend rows; its states and columns are already legal in V1, but its admission logic does not run in slice 002.

## In-memory domain values

| Value | Fields / allowed values | Persistence boundary |
|---|---|---|
| `DistributionCommand` | `source=RESULTS`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType=Hearing_Resulted`, optional `userId`. Closed JSON Schema; unknown fields and explicit `userId:null` rejected. | Accepted wire body copied exactly into `processed_request.command`; parsed fields form the guard identity. |
| `HearingPayload` | Canonical Jackson `JsonNode` from dated Redis key, undated Redis key or results query; unknown fields retained. | Never stored. `payload_source` and parsed `payload_shared_time` may be recorded. |
| `YouthSwitchReading` | `ACTIVE`, `INACTIVE` or `UNREADABLE(cause)`; cause ∈ `HTTP_FAILURE`, `TIMEOUT`, `EMPTY_PERMISSIONS`, `UNPARSEABLE`. | Resolve unreadable to configured default and record one of the four `youth_switch` values. Null first permission is `EMPTY_PERMISSIONS`. |
| `PublishOutcome` | `SENT(status)`, `FAILED_NON_TRANSIENT(status)`, or `TRANSIENT_EXHAUSTED`, with exact bytes sent and optional gateway request ID. | Maps to leg status, reason, HTTP evidence, body digest/body and `service_version`; 3xx is parked/final-failed as `gateway-transient` and uses metric `http=other`. |
| `RunOrigin` / `AttemptOrigin` | `queue`, `replay`, `resend`; immutable for a run. | Slice 002 always runs as `queue`; later slices may use the other values. |
| `DeadLetterReason` | `validation`, `collision`, `exhausted`, `leg-exhausted`. | Broker settlement and bounded metric value, never a leg reason. |
| Other closed values | `Leg`, `LegStatus`, `LegReason`, `PayloadSource`, `YouthSwitchDecision` (the three-valued pre-default reading of `YouthSwitchReading` above), `YouthSwitchRecordedValue` (the four-value persisted form: `ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`, `UNREADABLE_DEFAULTED_INACTIVE`), `CompletionReason`, `FailureReason`, `NoteForm`; `HttpStatusBucket`, `BodyShape`, `IntakeSuspensionCause`, `Non200SuccessStatus`, `GatewayRecovery`, `ReplayRoute`, `ResendRefusal`, `BodyMaintenanceStep`, `OperationsAction`, `OperationsFailure`. | One definition in the shared core, reused by persistence and telemetry; unknown wire values fail explicitly. HTTP buckets include the clarified `other`. The decision and recorded-value types are kept separate so an un-defaulted `Unreadable(cause)` has somewhere to live before defaulting. |

The core owns ports for the payload source, youth-filter source, HMPPS sender and outbound log. Broker, Redis, HTTP-client and database types remain in adapters; only the canonical hearing JSON tree crosses the core boundary (FR-002-071).
