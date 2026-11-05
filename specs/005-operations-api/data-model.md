# Phase 1 Data Model: Operations API

This slice adds **no persistence** — no table, no column, no migration. Every entity below is a
request/response shape or an in-flight value; the system of record stays slice 002's three tables
and one view, read and written exclusively through slice 004's application services. `OperationsAction`
and `OperationsFailure` (the two closed enums this slice's metric needs) already exist in `domain/`,
built ahead of this slice during 002's work.

## Request/response entities (new Java records, `api/dto/`)

### `ReplayRequest` (implicit — the path variable only)

| Field | Type | Rule |
|---|---|---|
| `requestId` | `UUID` (path variable) | FR-005-006; no body fields accepted — no body or `{}` only (FR-005-033) |

### `ReplayResponse`

| Field | Type | Rule |
|---|---|---|
| `requestId` | `UUID` | The service's own parse, canonical form (FR-005-031) |
| `outcome` | `enqueued` \| `no-op` (closed) | FR-005-007 |
| `messageId` | `String`, present iff `outcome = enqueued` | The fresh message identity the command went under |

### `ResendLegRequest`

| Field | Type | Rule |
|---|---|---|
| `requestIds` | `List<UUID>` | Read as sent — no collapse, no cap, no reorder here (FR-005-011, FR-005-013); an entry that isn't a UUID refuses the whole call |
| `leg` | closed enum, `HMPPS` only | Absent → `missing-argument`; not `HMPPS` → `unreadable-argument` (FR-005-010) |
| `operator` | `String` | Required, ≤ 200 chars, refused not truncated (FR-005-016, Assumption 4) |
| `reason` | `String` | Required, ≤ 200 chars, refused not truncated |
| `allowTerminalSuccess` | `boolean`, default `false` | The forced-resend override (FR-005-017) |
| `allowChangedPayload` | `boolean`, default `false` | Passed through unchanged to 004 (FR-005-010) |

Closed field set: any field not in this table is `400 unknown-field` (FR-005-033).

### `ResendLegAcceptedResponse`

| Field | Type | Rule |
|---|---|---|
| `resendRunId` | `UUID` | 004's run id; the only field this slice's `202` body carries (research.md Decision 4) |

The full per-ID report (status/reason per ID, refusal codes, `not-run`, counts) is **not** part of
this slice's HTTP response — it lives in 004's run-report log line and the attempt rows, per the
resolved Clarification (FR-005-014, FR-005-015).

### `ProblemDetail` (RFC 9457; Spring's own type, no new class)

| Field | Rule |
|---|---|
| `status` | One of the status families of FR-005-030 |
| `reason` (extension property) | One of the closed set named in FR-005-029 |
| `argumentName` (extension property, `400` about one argument only) | The argument's name, never its value (FR-005-031) |

## Entities this slice reads but never originates (owned elsewhere)

| Entity | Owner | This slice's use |
|---|---|---|
| `ReplayOutcome` (name per 004's eventual type) | 004 `ReplayAction` | Mapped 1:1 to `ReplayResponse` by `OperationsResponseMapper` (research.md Decision 5) |
| The resend-leg claim/report shape | 004 `LegResender` | Only `resendRunId` is read out of it for the `202` body; everything else stays 004's |
| `Leg`, `LegStatus`, `LegReason`, the resend refusal codes | slice 002 | Mirrored as closed enums in the OpenAPI document (FR-005-035) — never redefined here |
| `OperationsAction`, `OperationsFailure` | slice 002 (`domain/`, already implemented) | Passed to `MetricRegistry.operationsFailure(action, reason)` on every `5xx` and every unpublished response event (FR-005-043) |
| Caller identity (`CJSCPPUID`) | `cp-auth-rules-filter` | Read only to populate the audit event's caller field; never logged, never in a metric label, never in an answer (FR-005-023) |

## State this slice is responsible for (none persisted)

- **Authorisation decision**: computed per request by `cp-auth-rules-filter` from the Drools rules
  file (research.md Decision 1) and the caller's users-groups membership; not cached or stored by
  this slice.
- **Audit event pair**: published through `OperationsAuditPublisher` (research.md Decision 3);
  exists only as two Artemis messages, never written to this service's own store.
- **The `202` response's `resendRunId`**: not stored by this slice — it is 004's `resend_run_id`
  column value, read once at acceptance time and handed back; 004's attempt rows remain the durable
  record.

## Closed value sets this slice consumes (no new enum)

Per plan.md's Project Structure, this slice adds **zero** new domain enums: `OperationsAction` and
`OperationsFailure` already exist. The two action strings
(`probation-results-distribution-operations.replay`,
`probation-results-distribution-operations.resend-leg`) are plain constants used in the Drools rules
file and the OpenAPI `operationId` fields, not a Java enum — there being exactly two, named once
each, with no behaviour keyed on the set as a whole.
