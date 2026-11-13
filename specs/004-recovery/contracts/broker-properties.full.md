# Contract: broker application properties this slice adds

The inbound closed JSON schema (`src/main/resources/contracts/distribution-command.schema.json`,
design Appendix B.1) is **unchanged by this slice** — every message this slice's actions enqueue
(a service replay, a submit-hearing command) carries the same body shape slice 002 already validates.
This slice's only addition is a set of **broker application properties** — delivery metadata outside
the body, which the closed schema's `additionalProperties: false` never sees — carried by messages
this slice's actions produce or that reach the guard by a route this slice admits.

| Property | Set by | Values | Read by |
|---|---|---|---|
| `origin` | The service's replay action (`REPLAY`); submit-hearing (`SUPPORT`); absent on a dead-letter resubmission or a producer republish | `REPLAY`, `SUPPORT`, or absent | The guard, to derive the audit-only `route` (`service`/`support`/`copy`) at admission (FR-004-005). **No eligibility, settlement or sending rule reads `origin` directly** — only the derived `route`, which is itself an audit label. |
| `operator` | submit-hearing | Free text, ≤ 200 characters, sanitised | Appended to `audit_note` on admission (FR-004-057); never logged or echoed elsewhere |
| `runId` | submit-hearing | A fresh UUID per invocation | Appended to `audit_note` on admission (FR-004-057) |
| `reason` | submit-hearing | Free text, ≤ 200 characters, sanitised | Appended to `audit_note` on admission (FR-004-057) |

## Message identity rules this slice's producers follow

| Producer | `messageId` | `correlationId` | `contentType` |
|---|---|---|---|
| Service replay (FR-004-015) | A fresh id, never used before for this `requestId` (not equal to `RESULTS:{requestId}` and not equal to any earlier replay message's id); short enough that `rerun-by=<messageId>` fits `note`'s 256-character bound | `hearingId` | `application/json` |
| Submit-hearing (FR-004-056) | `RESULTS:{requestId}`, where `requestId` is `RequestIdRecipe`'s output | `hearingId` | `application/json` |

## What this slice never changes

- The outbound HMPPS body — no property, header or field this slice adds reaches the gateway POST.
  A forced resend's reason and operator reach only `audit_note` and the attempt row, never the
  request body sent to HMPPS (H12; D10).
- The schema's `additionalProperties: false` surface — every property in this file is a broker
  application property, never a body field, exactly as design row A3-34 requires.

## Consumed, unchanged

Resend-leg's and submit-hearing's reads of the Redis `EXT_` cache, the results query API, and the
users-groups permission use the same request/response shapes and vendor media types slice 002 already
established — this slice adds no new field to any of those reads, only new *callers* of them (the
resend and submit-hearing paths), each carrying the service's own system user as `CJSCPPUID` per
H30/DV-17 (FR-004-026, FR-004-050a, FR-004-057).
