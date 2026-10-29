# Inbound command — `probation.requests`

**Direction:** `cpp-context-results` → this service over Azure Service Bus. Deployed queue names are stack-prefixed. This is the business input for slice 002; it is a queue message, not an HTTP endpoint.

## Body

The body is UTF-8 JSON. Its normative schema is design Appendix B.1, to be vendored without field changes at `src/main/resources/contracts/distribution-command.schema.json` during implementation. That draft-07 schema is closed (`additionalProperties: false`); this planning document does not replace it.

| Field | Required | Value and role |
|---|---|---|
| `source` | Yes | The single allowed value is `RESULTS`; with `requestId`, forms the idempotency key. |
| `requestId` | Yes | UUID. The producer derives it from `hearingId`, `hearingDay`, and `sharedTime`. |
| `hearingId` | Yes | UUID of the hearing; also forms the cache keys and results query path. |
| `hearingDay` | Yes | `YYYY-MM-DD`; also forms the dated cache key and `hearingDate` query parameter. |
| `sharedTime` | Yes | ISO-8601 instant identifying this share. |
| `eventType` | Yes | The single allowed value is `Hearing_Resulted`. |
| `userId` | No | UUID of the sharing user. Absence is valid; explicit `null` is invalid. It is not part of the request fingerprint. |

The command carries identifiers, not the hearing payload. Unknown properties, missing required fields, wrong types or formats, non-JSON, `null` `userId`, and any other `eventType` are rejected on that delivery with dead-letter reason `validation`. Validation precedes admission, so no `processed_request` row is written for a rejected command. The dead-letter description, log and metric use bounded reason codes without producer-selected field names or values.

## Broker envelope and settlement

| Property | Contract |
|---|---|
| `messageId` | Normal publishing uses `RESULTS:{requestId}`. It identifies the delivery; broker duplicate detection is off. The processed log keyed by `(source, requestId)` is the idempotency authority. |
| `correlationId` | Normal publishing uses `hearingId`; it does not alter processing. |
| `contentType` | Normal publishing uses `application/json`; it does not alter processing. |
| Application properties | `origin`, `operator`, `runId`, and `reason`, when present, are audit metadata. They cannot override command fields or change a slice 002 run. Their admission behavior belongs to slice 004. |

Receive in peek-lock mode, with sessions and auto-complete off. The queue has a five-minute lock, auto-renewal up to five minutes, `maxDeliveryCount` 5, and initially two concurrent calls per pod. The service mirrors the broker delivery limit. After a durable outcome is committed, it completes, abandons, or dead-letters the message while holding the lock. `leg-exhausted` is the settlement reason when a parked leg reaches its last permitted delivery; guard failures use bounded `validation`, `collision`, or `exhausted` reasons. A database outage suspends intake and returns the delivery before reading its body; Service Bus is excluded from readiness.

For this slice, a valid copy meeting a `FAILED` request is dead-lettered `exhausted` without running. Slice 004 replaces this interim rule with replay admission. A completed request is acknowledged without another send; a known key with a different fingerprint is dead-lettered `collision`.

**Sources:** `spec.md` FR-002-001–009 and FR-002-047; `doc/API_CONTRACTS.md` §1; `doc/design/DIGEST.md` §§5–7.
