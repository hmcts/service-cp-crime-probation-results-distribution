# Contracts — service-cp-crime-probation-results-distribution

## There is no business REST API

This service exposes **no business REST API**. No hearing is submitted to it over HTTP, and nothing
is read out of it. Its HTTP surface is Spring Boot Actuator (health, info, metrics and Prometheus),
which is operational and not a contract with any consumer. A small `/operations/**` API for
operators is recommended but **not yet decided**. If it is adopted, its OpenAPI document will be
owned and versioned in this repository and described here.

This service has exactly **two** contracts:

| # | Contract | Direction | Owner | Shape |
|---|----------|-----------|-------|-------|
| 1 | ASB queue `probation.requests` message | Inbound | **Agreed jointly with `cpp-context-results`**, the publisher. Any change is a cross-team change | JSON message body, closed schema, documented below |
| 2 | HMPPS hearing-results publish | Outbound | **HMPPS**, the receiver, reached through the APIM gateway's HMPPS route | JSON body, an interim closed schema **pending gate G1** |

It also makes three **reads**. They are dependencies, not contracts this service owns:

- the Redis `EXT_` payload cache, which the results producer writes;
- the results query API, the payload fallback;
- the users-groups `EMYouthFilter` permission.

The authority for every rule below is the design: the
[Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service)
Confluence page. Section references (§n, Appendix B) are to that design.

---

## 1. Inbound contract — the queue message

**Queue:** `probation.requests`, dedicated to this service, plus its dead-letter queue. Deployed
environments use the stack-prefixed name.
**Publisher:** `cpp-context-results` (`results-event-processor`). It sends one message per resulted
regular (non-SJP) hearing share, and only while the `ProbationResultsDistributionService` feature
flag reads on. A failed flag read counts as off.
**Body:** UTF-8 JSON, `application/json`.

The contract is the informant register's, verbatim. Only the schema's `$id`, title and descriptions
change. The full closed schema is **design Appendix B.1**. It is vendored as
`src/main/resources/contracts/distribution-command.schema.json` with the first feature, and the
vendored file is then the build's authority.

```json
{
  "source": "RESULTS",
  "requestId": "6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34",
  "hearingId": "1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa",
  "hearingDay": "2026-08-19",
  "sharedTime": "2026-08-19T16:42:07.512Z",
  "eventType": "Hearing_Resulted",
  "userId": "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48"
}
```

### Field semantics

| Field | Type | Required | Semantics |
|-------|------|----------|-----------|
| `source` | string, enum `["RESULTS"]` | yes | The publishing system. It is half of the idempotency key. |
| `requestId` | string (UUID) | yes | Deterministic: `UUID.nameUUIDFromBytes(hearingId + "\|" + hearingDay + "\|" + sharedTime)`. A republish of the *same* share carries the same id. A re-share of the hearing has a new `sharedTime`, so it gets a new id and is processed as new work. The same share gives the same `requestId` on the sibling queues as well; that is harmless, because each queue and processed log is separate. |
| `hearingId` | string (UUID) | yes | The resulted hearing. Together with `hearingDay` it forms the cache key `EXT_{hearingId}_{hearingDay}_result_`. |
| `hearingDay` | string (`YYYY-MM-DD`) | yes | The hearing day of this share. It is part of the `EXT_` key, and it is the `hearingDate` parameter of the query-API fallback. |
| `sharedTime` | string (ISO-8601 instant) | yes | When the hearing was shared. It is part of the `requestId` recipe. |
| `eventType` | string, enum `["Hearing_Resulted"]` | yes | `Hearing_Resulted` only. SJP shares are never published to this queue. Any other value dead-letters at once. |
| `userId` | string (UUID) | **no** | The user who shared the results. It may be **absent**, but `null` is refused. It is not part of the request fingerprint. When it is absent, as in a hand-built support command, the fallback read runs as the configured system user (`RESULTS_SYSTEM_USER_ID`). The value is never logged. |

`additionalProperties: false`. A message with an unknown field, a missing field, a wrong type or a
wrong `eventType` dead-letters at once with reason `validation`. It gets **no `processed_request`
row**, because the request state machine starts only after the contract is validated.

The payload itself is **not** in the message: this is a claim check. The service reads the
`EXT_{hearingId}_{hearingDay}_result_` key first and then the undated key. When both miss, it
calls the results query API (`hearingDetails/{hearingId}?hearingDate={hearingDay}`).

### Message properties (broker-level, part of the contract)

| Property | Value | Why |
|----------|-------|-----|
| `messageId` | `RESULTS:{requestId}` for normal publishing | It is deterministic, so the broker's duplicate detection collapses identical republishes. A replay is the documented exception, as the replay rule below explains. |
| `contentType` | `application/json` | |
| `correlationId` | `hearingId` | For tracing across services. |

### Application properties (operator actions only)

Two operator actions mark their messages with broker application properties. These are **not body
fields**, so the closed schema does not cover them and they do not change it.

| Action | Properties | Meaning |
|---|---|---|
| Replay | `origin = REPLAY` | An audit label only. Which eligibility rules a run applies depends on the stored request (`processed_request.replayed_at`), not on this property. |
| Submit-hearing | `origin = SUPPORT`, `operator`, `runId`, `reason` | A repair for a share that reached neither path, or that was never admitted. The service builds a normal command with `source = RESULTS` and no `userId`, and records the operator, run id and reason on admission. |

### Replay rule

A replay carries the **same body**, byte for byte, under a **fresh `messageId`** that has never
been used for that request.

- **Why the fresh identity.** It lets the service tell a deliberate replay (`FAILED` → `RECEIVED`)
  from the exhausted message coming round again. If a replay reused an old `messageId` inside the
  duplicate-detection window, the broker would silently drop it.
- **Where the body comes from.** This service's replay action re-sends the command stored at
  admission (`processed_request.command`), so `userId` is preserved wherever the original carried
  it. It never takes the body from the dead-letter queue, which is kept for forensics. That is why
  a request whose messages were purged by a rollback can still be replayed.
- **What happens on replay:**
  - a `COMPLETED` request is acknowledged without being reprocessed, and nothing is re-sent;
  - a `FAILED` request is readmitted and reprocessed;
  - a redelivery under the *original* `messageId` is not a replay.

The full eligibility table, covering redelivery, replay and resend for each leg status, is design
§4.5.

### Delivery and settlement semantics

| Aspect | Value |
|--------|-------|
| Receive mode | Peek-lock (never `ReceiveAndDelete`) |
| Auto-complete | Off. Every delivery is settled exactly once (`complete()`, `abandon()` or `deadLetter()`) while the lock is held. A lock is never left to expire |
| Lock duration | 5 minutes, with auto-renew up to 5 minutes. The claim lease is strictly shorter |
| `maxDeliveryCount` | 5. The service mirrors it so that it can recognise the final permitted delivery |
| Duplicate detection | On, set at queue creation, with a PT5M window. Backed by the `(source, requestId)` processed log |
| Sessions | Off |
| Concurrency | `maxConcurrentCalls` 2 to start with |
| Health | Service Bus health is **never** in the readiness group |

### Dead-letter reasons

The service dead-letters a message itself and gives a bounded reason. It never leaves the broker to
park the message as `MaxDeliveryCountExceeded`. Each settlement is counted in
`probation_deadlettered_total{reason}`.

| Reason | When |
|---|---|
| `validation` | The body fails the closed schema. The message is not admitted, and no row is written |
| `collision` | Same `(source, requestId)` with a different fingerprint (`hearingId`, `hearingDay`, `sharedTime`, `eventType`) |
| `exhausted` | Written on the final permitted delivery by the inherited guard paths — a delivery not admitted (including a replay deferred over a `PENDING` resend row), a redelivery under the identity that exhausted the request, a stale runner — and by the listener's store-outage and unexpected-run-failure hand-backs (design §4.5, row A2-34) |
| `leg-exhausted` | The final delivery still has a `PARKED` leg. The request becomes `FAILED` and records the exhausted `messageId` |

A youth switch that cannot be read is **not** a dead-letter. The message is completed and the
request is settled `FAILED(youth-switch-unreadable)`. It stays replayable, and the leg can be
resent.

---

## 2. Outbound contract — the HMPPS publish

> **Status: pending gate G1.** The interim outbound schema is derived from the recorded parity
> corpus and must be approved before the build starts. If the HMPPS interface control document
> arrives first, it replaces the derived schema. Enabling the HMPPS leg in production waits on that
> interface document and the open receiver-response questions. Until G1 closes, this section
> describes the call, not the body's field-level shape.

**Endpoint:** `POST {HMPPS_PUBLISH_URL}`. That is the APIM gateway's HMPPS route
(`/probation/api/v1/hearing/results`), configured per environment and never packaged. The gateway
forwards the call to the HMPPS court-hearing-event receiver and owns that hop: the OAuth token, the
path rewrite and its own retries. **Nothing changes on the gateway.** The service uses the same
route and the same subscription key as the function app, with no new policy and no client
certificate.

**Headers:**

| Header | Value |
|---|---|
| `Content-Type` | `application/json` |
| `Ocp-Apim-Subscription-Key` | `GATEWAY_SUBSCRIPTION_KEY`, mounted from Key Vault and never committed |

**Body.** This is the hearing's `EXT_` payload after the HMPPS redaction rules, **unchanged from
what the function app sends today**. This delivery makes no payload change: any change needs
agreement with HMPPS.

- **Cache form.** The payload starts as `{hearing, cpsProsecutorIds, policeCases, sharedTime}`.
  The rules drop `policeCases` and any top-level `judiciary` or `associatedPersons`, which leaves
  `{hearing, cpsProsecutorIds, sharedTime}`.
- **Fallback form.** From the query API the payload is `{hearing, sharedTime}` only, and its
  `hearing` field set differs.
- **Youth defendants.** While the users-groups `EMYouthFilter` switch reads active, youth
  defendants are removed as the legacy removes them.

The schema rules are **design §6.6**, and **design Appendix B** carries the related contracts and
the `outbound_message` DDL. The schema is vendored as
`src/main/resources/contracts/outbound/hmpps-hearing-results.schema.json` (proposed name) once G1
is approved:

- every object is closed at every level;
- enums are not closed;
- `hearing.id` is required;
- `required` is taken from the cache-form cases;
- the allowed properties are taken from both forms.

**Before the POST** the body is validated against that schema. A body that fails is **not sent**.
The leg is recorded `FAILED(contract-invalid)` and an alert is raised. Each send also records a
digest of the body as it was serialised.

**Retries and outcome:**

| Gateway answer | Handling | Leg outcome |
|---|---|---|
| 2xx | — | `SENT(status)` |
| 408, 429, 5xx, connect or I/O error | Retried with bounded attempts and backoff; `Retry-After` is honoured in delta-seconds up to a cap | Still transient at the end: `PARKED(gateway-transient)` and the message is abandoned for redelivery. On the final delivery or a resend it is `FAILED(gateway-transient)` |
| Any other 4xx | Not retried | `FAILED(receiver-rejected)`. The receiver's status is recorded |

The publisher never throws past its outcome. Every admitted share ends with exactly one recorded
outcome per attempt of the HMPPS leg. The legacy's second receiver is not built, and the per-leg
structure is kept only as an extension point.
