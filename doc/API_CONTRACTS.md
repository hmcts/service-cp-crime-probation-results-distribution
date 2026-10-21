# Contracts — service-cp-crime-probation-results-distribution

## There is no business REST API

This service exposes **no business REST API**. No hearing is submitted to it over HTTP, and nothing
is read out of it by a consumer. Its HTTP surface is Spring Boot Actuator (health, info, metrics and
Prometheus), which is operational and not a contract with any consumer, and the operators'
`/operations/**` API. That API is **decided for replay and resend-leg**, the forced resend included
(design decision H9, §4.8). It sits behind the estate's deployment gates and is operators' tooling,
not a business contract, but its OpenAPI document is owned and versioned in this repository and
described in section 3 once it is built. Whether the other operator actions get HTTP exposure is
still open (O-1).

This service has exactly **two** message contracts:

| # | Contract | Direction | Owner | Shape |
|---|----------|-----------|-------|-------|
| 1 | ASB queue `probation.requests` message | Inbound | **Agreed jointly with `cpp-context-results`**, the publisher. Any change is a cross-team change | JSON message body, closed schema, documented below |
| 2 | HMPPS hearing-results publish | Outbound | **HMPPS**, the receiver, reached through the APIM gateway's HMPPS route | JSON body: the function app's redacted body, unchanged. **No outbound schema in this release** (H13) |

It also makes three **reads**. They are dependencies, not contracts this service owns:

- the Redis `EXT_` payload cache, which the results producer writes;
- the results query API, the payload fallback;
- the users-groups `EMYouthFilter` permission.

The authority for every rule below is the design: the
[Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service)
Confluence page, at rev 3.6 (decisions H1–H34). Section references (§n, Appendix B) are to that
design; [design/DIGEST.md](design/DIGEST.md) restates its rules with their row ids.

---

## 1. Inbound contract — the queue message

**Queue:** `probation.requests`, dedicated to this service, plus its dead-letter queue. Deployed
environments use the stack-prefixed name.
**Publisher:** `cpp-context-results` (`results-event-processor`). It sends one message per resulted
regular (non-SJP) hearing share, and only while the `ProbationResultsDistributionService` feature
flag reads on. Results reads that flag itself (`FeatureControlGuard`); a failed read counts as off.
This service never reads it.
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
| `sharedTime` | string (ISO-8601 instant) | yes | When the hearing was shared. It is part of the `requestId` recipe, and a resend compares it, as an instant, with the share it re-reads (H17). |
| `eventType` | string, enum `["Hearing_Resulted"]` | yes | `Hearing_Resulted` only. SJP shares are never published to this queue. Any other value dead-letters at once. |
| `userId` | string (UUID) | **no** | The user who shared the results. It may be **absent**, but `null` is refused. It is not part of the request fingerprint. When it is absent, as in a hand-built support command, and on every resend, the fallback read runs as the service's own system user (H30). The value is never logged. |

`additionalProperties: false`. A message with an unknown field, a missing field, a wrong type or a
wrong `eventType` dead-letters at once with reason `validation`. It gets **no `processed_request`
row**, because the request state machine starts only after the contract is validated.

The payload itself is **not** in the message: this is a claim check. The service reads the
`EXT_{hearingId}_{hearingDay}_result_` key first and then the undated key. When both miss, it
calls the results query API (`hearingDetails/{hearingId}?hearingDate={hearingDay}`).

### Message properties (broker-level, part of the contract)

| Property | Value | Why |
|----------|-------|-----|
| `messageId` | `RESULTS:{requestId}` for normal publishing | It is deterministic, so a republish of the same share carries the same id. The broker's duplicate detection is **off** (H14): every copy is delivered, and the service's processed-log guard handles it (below). The service's own replay uses a fresh id, as the replay rule explains. |
| `contentType` | `application/json` | |
| `correlationId` | `hearingId` | For tracing across services. |

### Application properties (operator actions only)

Two operator actions mark their messages with broker application properties. These are **not body
fields**, so the closed schema does not cover them and they do not change it.

| Action | Properties | Meaning |
|---|---|---|
| Replay | `origin = REPLAY` | An audit label only. Which eligibility rules a run applies depends on the stored request (`processed_request.replayed_at`), not on this property. |
| Submit-hearing | `origin = SUPPORT`, `operator`, `runId`, `reason` | A repair for a share that reached neither path, or that was never admitted. The service builds a normal command with `source = RESULTS` and no `userId`, and records the operator, run id and reason on admission. |

A message resubmitted from the dead-letter queue with the broker tools, or republished by the
producer, carries **no** `origin`. The service records the replay route at admission — `service`
(`origin = REPLAY`), `support` (`origin = SUPPORT`) or `copy` (no `origin`) — in
`processed_request.audit_note`, as a log field and on `probation_replays_total{route}` (H9).

### Replay rule

A `FAILED` request has two supported replay routes (H9; design §4.2, §4.8):

- **Resubmission from the dead-letter queue** with the broker tools — the route operations prefer.
  The copy carries the original body byte for byte. It may keep the exhausted `messageId`: the guard
  admits such a copy as a replay at broker delivery count 1, and re-dead-letters it `exhausted`
  above that (deviation DV-26). A copy under a new `messageId` is a replay at any delivery count.
- **The service's replay action**, through the operations API or its command-line runner. It
  re-sends the command stored at admission (`processed_request.command`) byte for byte under a
  **fresh `messageId`** with `origin = REPLAY`, so `userId` is preserved wherever the original
  carried it. It never takes the body from the dead-letter queue, so it is the only route for a
  request whose messages a rollback purged. The fresh identity keeps the replay distinct from the
  exhausted identity and labels it for audit.

What happens on replay, by either route:

- a `COMPLETED` request is acknowledged without being reprocessed, and nothing is re-sent;
- a `FAILED` request is readmitted and reprocessed, and only its `FAILED` and `PARKED` legs are
  re-run;
- a redelivery of the exhausted identity above broker delivery count 1 is not a replay: it is
  re-dead-lettered `exhausted`.

**Every copy reaches the guard** (H14). With no broker duplicate detection, a republish, a
redelivered owed event, a submit-hearing for an admitted share or a dead-letter resend is delivered
to the service, and the `(source, requestId)` processed log is the only duplicate protection:

- a copy of a `COMPLETED` request is a no-op, and the message is completed;
- a copy that meets a live claim is abandoned and redelivered at once;
- a copy of a `FAILED` request is a replay, by the rule above;
- of two concurrent copies of a new request, one runs and the other backs off.

A copy abandoned on a live claim can use up its five deliveries in seconds and be dead-lettered
`exhausted` while the first run completes. That noise is accepted (H15): no redelivery delay is
added, and the runbook below says what to do with such an entry.

The full eligibility table, covering redelivery, replay and resend for each leg status, is design
§4.5.

### Delivery and settlement semantics

| Aspect | Value |
|--------|-------|
| Receive mode | Peek-lock (never `ReceiveAndDelete`) |
| Auto-complete | Off. Every delivery is settled exactly once (`complete()`, `abandon()` or `deadLetter()`) while the lock is held. A lock is never left to expire |
| Lock duration | 5 minutes, with auto-renew up to 5 minutes. The claim lease is strictly shorter |
| `maxDeliveryCount` | 5. The service mirrors it so that it can recognise the final permitted delivery |
| Duplicate detection | **Off** (H14). The `(source, requestId)` processed log is the only duplicate protection. Enabling broker duplicate detection later would mean recreating the queue |
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
| `exhausted` | Written on the final permitted delivery by the inherited guard paths — a delivery not admitted (including a replay deferred over a `PENDING` resend row), a stale runner — and by the listener's store-outage and unexpected-run-failure hand-backs (design §4.5, row A2-34). Also a redelivery under the identity that exhausted the request, but only above broker delivery count 1; at count 1 that copy is a replay (DV-26) |
| `leg-exhausted` | The final delivery still has a `PARKED` leg. The request becomes `FAILED` and records the exhausted `messageId` |

A youth switch that cannot be read is **not** a dead-letter, and not a failure. The leg assumes
`distribution.youth-switch.default-when-unreadable` (`ACTIVE` unless configured otherwise) and the
hearing is sent as normal. The message settles by the outcome of that send (DV-25).

### Dead-letter runbook (design §4.8)

| Dead-letter reason | Action |
|---|---|
| `leg-exhausted` | Resend from the dead-letter queue (a replay), or replay through the operations API |
| `exhausted` | Resend. An admitted request's copy replays; a message that was never admitted (no `processed_request` row) is processed as a first delivery. If the request is already `COMPLETED`, the entry is duplicate-copy noise: remove it, do not resend it |
| `validation` | **Do not resend.** The message fails the closed schema again; fix the producer or the message at source |
| `collision` | **Do not resend.** A fingerprint mismatch under a known request id; investigate |

A resend leaves the original in the dead-letter queue. Once the replayed request is `COMPLETED`,
remove the original — and any copy that was itself re-dead-lettered — or the "DLQ not empty" alert
stays on.

---

## 2. Outbound contract — the HMPPS publish

> **No outbound schema in this release** (H13). Outbound schema validation is parked, and the former
> pre-build gate G1 is not a gate. The HMPPS interface control document, v0.8, is obtained and
> recorded as information (H12): success is 200 only, the results POST is **not idempotent**, and
> its schema is stale — it rejects bodies the live receiver accepts — so nothing is validated
> against it. This section describes the call and where the body comes from, not a field-level
> schema.

**Endpoint:** `POST {HMPPS_PUBLISH_URL}`. That is the APIM gateway's HMPPS route
(`/probation/api/v1/hearing/results`), configured per environment and never packaged. The gateway
forwards the call to the HMPPS court-hearing-event receiver and owns that hop: the OAuth token, the
path rewrite and its own retries of a backend 5xx. **Nothing changes on the gateway.** The service
uses the same route and the same subscription key as the function app, with no new policy and no
client certificate. Timeouts: connect 5 s, read 60 s (H27).

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
  defendants are removed as the legacy removes them. When the switch cannot be read, the configured
  default decides (`ACTIVE` unless configured otherwise). The body is then exactly the one the
  legacy would send with the switch in that state. The legacy sends nothing in this case (DV-25).
- **Application-only hearings** — court applications and no prosecution case — are sent as today.
  HMPPS rejects them with 400; the leg is `FAILED(receiver-rejected)` and the class is counted
  daily, not alerted (D16 (a), H33).

**No validation before the POST** (H13). The body is redacted, digested and sent. What guards it is
parity with the legacy: the parity harness and the deviation register (constitution Principle I).
`hearing.id` must survive redaction, because APIM builds the backend path from it; the harness
checks it over the corpus.

**What is recorded for each send** (design §4.6):

- a digest of the body as serialised (`body_digest`, SHA-256 over the exact UTF-8 bytes POSTed)
  and the build's `service_version`, on the attempt row (H19);
- the exact bytes themselves, in `outbound_body`, for 6–7 months: bodies are purged by whole month
  after six (H18, H20). After the purge the digest and the row's metadata remain.

**Retries and outcome** (H11, H12, H15):

| Gateway answer | Handling | Leg outcome |
|---|---|---|
| 200 | — | `SENT(status)` |
| Any other 2xx | Not retried; logged at WARN and counted as an anomaly | `SENT(status)` |
| Connection failure (the request was never sent; a connect timeout included), 429 | Retried in-run with bounded attempts and backoff; `Retry-After` is honoured in delta-seconds up to a cap | Still failing at the end: `PARKED(gateway-transient)` and the message is abandoned for redelivery. On the final delivery or a resend it is `FAILED(gateway-transient)` |
| 5xx (after the gateway's own retries), 408, a read timeout or I/O failure after the request was written | **Not retried in-run.** The outcome is unknown: HMPPS may have accepted the hearing | `PARKED(gateway-transient)` at once and the message is abandoned for broker redelivery. On the final delivery `FAILED(gateway-transient)`; on a resend `FAILED(gateway-transient)` at once |
| Any other 4xx, 401 included | Not retried | `FAILED(receiver-rejected)`. The receiver's status is recorded |

Each broker redelivery is a new POST, which the gateway may itself retry, and the POST is not
idempotent: a redelivery after an outcome-unknown answer can be a genuine duplicate at HMPPS. A
`FAILED(gateway-transient)` leg is therefore confirmed with HMPPS before anyone re-sends it by hand.

The publisher never throws past its outcome. Every admitted share ends with exactly one recorded
outcome per attempt of the HMPPS leg. The legacy's second receiver is not built, and the per-leg
structure is kept only as an extension point.

---

## 3. Operations API — `/operations/**`

**Status: decided for replay and resend-leg (H9); not built yet.** Its OpenAPI document is filled
in, and summarised here, by the first feature that builds it. An empty-paths placeholder of that
document already ships, at `src/main/resources/probation-results-distribution-openapi.yaml`, so
that the audit filter starts. The API sits behind the
estate's five deployment gates: an internal ingress or APIM route, the gateway stripping and
injecting `CJSCPPUID`, an Istio AuthorizationPolicy and NetworkPolicy, users-groups reachable for
the authorisation filter, and an audit connection. The command-line runners in the image remain the
fallback for every action.

| Action | Decided behaviour |
|---|---|
| Replay a `FAILED` request | Re-sends the stored command under a fresh `messageId` with `origin = REPLAY`. Refused with `resend-in-progress` while any leg's latest row is a `PENDING` resend row. A replay of a `COMPLETED` request is a no-op |
| Resend one leg | Takes an explicit list of `requestId`s, at most `distribution.resend.max-ids-per-run` (default 100), an operator and a reason (H16). Each ID is checked on its own and either gets a new `resend` attempt row or is refused with one code: `unknown-request`, `not-eligible`, `resend-in-progress` or `request-claimed`. The report lists every ID's outcome. The whole run is refused while intake is paused |
| Forced resend | Resend-leg with the override `allowTerminalSuccess` (name proposed), an operator and a reason: a leg whose latest row is `SENT` or `NOT_APPLICABLE` is sent again on a new `resend` attempt row, recorded `override=force-resend`. The request stays `COMPLETED`. It is a genuine duplicate at HMPPS |

On a resend the service re-reads the hearing and compares its `sharedTime` with the request's, as
instants. The same share proceeds; a different, missing or unparseable `sharedTime` is
`FAILED(payload-changed)` unless the operator allows a changed payload (H17).

The other operator actions — list requests, pause and resume intake, submit-hearing, purge-queue,
the optional flag check and the recommended `dlq-completed` listing — run as command-line runners.
Whether they get HTTP exposure is open (O-1).
