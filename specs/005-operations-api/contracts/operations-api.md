# Contract: the operations API (this slice)

This is the authoritative statement of the two HTTP operations ahead of filling in
`src/main/resources/probation-results-distribution-openapi.yaml` (FR-005-035), which remains the
document the audit filter resolves paths from and the one a contract test asserts against, both
ways (story 6). Where this file and the OpenAPI document disagree once the document is filled in,
the OpenAPI document wins — this file is the plan-time contract that the document must realise.

## 1. `POST /operations/requests/{requestId}/replay`

**Action**: `probation-results-distribution-operations.replay`

**Request**: no body, or `{}`. Any other field → `400 unknown-field`.

| Outcome | Status | Body |
|---|---|---|
| Enqueued | `200` | `{"requestId": "<uuid>", "outcome": "enqueued", "messageId": "<the fresh id>"}` |
| `COMPLETED` request | `200` | `{"requestId": "<uuid>", "outcome": "no-op"}` |
| `PENDING` resend row on the leg | `409` | `reason: resend-in-progress` |
| No request row | `404` | `reason: unknown-request` |
| `RECEIVED`/`RETRYING` | `409` | `reason: not-eligible` |
| Store down | `503` | `reason: store-unavailable` |
| Broker can't take the enqueue | `503` | `reason: broker-unavailable` |

Available at any time, including while intake is paused (FR-005-008). Takes no `operator`/`reason`
(FR-005-009) — the caller is named in the audit event only.

## 2. `POST /operations/resend-leg`

**Action**: `probation-results-distribution-operations.resend-leg`

**Request body**:
```json
{
  "requestIds": ["<uuid>", "..."],
  "leg": "HMPPS",
  "operator": "<name, ≤200 chars>",
  "reason": "<text, ≤200 chars>",
  "allowTerminalSuccess": false,
  "allowChangedPayload": false
}
```
Any field not listed → `400 unknown-field`. `requestIds` is passed to 004 exactly as sent — no
collapse, no cap, no reorder here (FR-005-011, FR-005-013).

**Response**: `202 Accepted`
```json
{"resendRunId": "<uuid>"}
```
No per-ID detail in this response (research.md Decision 4). The full report is 004's run-report
log line and the attempt rows, readable via 004's `list-requests` runner.

**Whole-call refusals** (nothing read or run):

| Condition | Status | `reason` |
|---|---|---|
| `requestIds` absent/empty | `400` | `missing-argument` (names `requestIds`) |
| `operator`/`reason` absent or blank | `400` | `missing-argument` (names the argument) |
| `operator`/`reason` > 200 chars | `400` | `argument-too-long` (names the argument) |
| An entry of `requestIds` not a UUID | `400` | `unreadable-argument` (names `requestIds`) |
| `leg` absent | `400` | `missing-argument` (names `leg`) |
| `leg` not `HMPPS` | `400` | `unreadable-argument` (names `leg`) |
| Distinct IDs over `distribution.resend.max-ids-per-run` | `400` | `too-many-request-ids` (carries the cap) |
| Intake paused by an operator | `409` | `intake-paused` |
| Store down | `503` | `store-unavailable` |

Every whole-call refusal above is rendered from 004's own refusal, never computed independently by
this layer (FR-005-003, FR-005-011, FR-005-012, FR-005-016).

## 3. Cross-cutting

| Concern | Rule |
|---|---|
| Authorisation | Every request: `401 unauthenticated` (no/blank `CJSCPPUID`); `403 forbidden` (outside the admitted group, or users-groups unreachable — fail closed). Exactly one Drools allow rule per action, `Second Line Support` only (FR-005-018–022). |
| Audit | Two events per admitted call (before and after). Request-event publish failure → `503 audit-unavailable`, nothing changed. Response-event publish failure → logged ERROR + counted, call's outcome unchanged (FR-005-025–028). |
| Content type | Non-JSON → `415 unsupported-content-type`. Unparseable JSON → `400 unreadable-body` (FR-005-033). |
| Unmapped route | Framework default, no stack trace, no path echo (FR-005-034). |
| `5xx` | Always logged ERROR and counted on `probation_operations_failures_total{action, reason}` (FR-005-043). |
| Echo | No caller-supplied value (operator, reason, unreadable identifier, path) in any answer or log line; a `requestId` in an answer is always this service's own canonical parse (FR-005-031, FR-005-032). |
| Actuator | Unaffected — outside both filters, not in the OpenAPI document (FR-005-024). |

## What this slice never adds

- A third HTTP action. Every other `§4.8` action (list, pause/resume, submit-hearing, purge-queue,
  the flag check, `dlq-completed`) stays a command-line runner while O-1 is open.
- An idempotency key or de-duplication — a repeated call is a new call (FR-005-042).
- A `distribution.*` property, an App Configuration read, or a Results-switch read.
