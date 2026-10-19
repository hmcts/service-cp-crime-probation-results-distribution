# Architecture & Domain Rules

This service is a **message-driven pipeline**, not a REST application. There is no controller layer
and no business HTTP API — actuator only. The operator actions (replay, resend-leg, pause and resume
intake, submit-hearing, purge-queue) are decided capabilities that run as command-line runners in
the image; whether they also get an HTTP front under `/operations/**` is open (design §4.8, O-1).
Everything below assumes that shape. Section references are to the design, rev 3.1.

## Pipeline Architecture (ports and adapters)

```
ASB queue probation.requests (+ DLQ)
   │  (peek-lock delivery)
   ▼
ProbationMessageListener                inbound adapter — parse + settle ONLY
   ▼
IdempotencyGuard                        (source, requestId) processed-log claim — copied from informant
   ▼
DistributionRunner                      application core — the use case, no I/O of its own
   ├─▶ HearingPayloadSource   «port»   Redis EXT_ dated key → undated key → results query API
   └─▶ HMPPS leg (the only leg, H5)
         ├─▶ YouthFilterSource «port»  users-groups EMYouthFilter permission, per message (H7)
         ├─▶ HmppsRedaction            pure: R0–R12 verbatim; R3/R4 only when ACTIVE
         ├─▶ OutboundContractValidator + body digest   interim closed HMPPS schema (gate G1)
         ├─▶ HmppsSender       «port»  POST to the APIM gateway's HMPPS route
         └─▶ OutboundLog       «port»  one outbound_message row per leg per attempt
LegResender                             resend-leg: one leg, selected FAILED latest rows (Q16)
```

- **Inbound adapter:** deserialises the queue message into `DistributionCommand`, calls the guard and
  the runner, and performs exactly one settlement (`complete` / `abandon` / `deadLetter`) on every
  path. NO business logic. NO redaction. NO downstream calls.
- **Application core:** orchestrates against **ports only**. It MUST NOT import Azure, Redis, HTTP
  client or JDBC types. Jackson is the one qualified exception: the hearing payload crosses the core
  as the platform's Jackson `JsonNode` by design (constitution Principle IV), treated as immutable.
  That permission covers `JsonNode` and its subtypes only; binding, streaming and `ObjectMapper`
  configuration stay in the adapters and in `config/`. The core is unit-testable with plain mocks
  and no Spring context.
- **Ports:** Java interfaces owned by the application package — `HearingPayloadSource`,
  `YouthFilterSource`, `HmppsSender`, `OutboundLog` (§4.4). Adapters implement them and live in their
  own packages. If swapping an adapter forces a runner edit, the port is wrong — fix the port.
- **The per-leg structure is the extension point** (row A3-1): the runner iterates legs the eligibility
  table allows; `Leg` has one value, `HMPPS`. A further leg is a new leg value, projection and
  sender. Never branch the runner on "is this HMPPS".
- **The payload fetch is the only pre-leg stage** (row R2-7); its outcome is written only to legs the
  §4.5 eligibility table lets this kind of run touch. The switch read belongs to the HMPPS leg.

NEVER put business logic in the message listener.
NEVER call a repository or an HTTP client from the listener.
NEVER reference `ServiceBusReceivedMessage` outside the inbound adapter.

### Package structure

```
uk.gov.hmcts.cp.probationresultsdistribution
├── inbound/       listener, consumer config, command parser, closed schema, ConsumerLifecycleController, StoreGate (copied from YOT)
├── application/   DistributionRunner, LegResender, SubmitHearingAction, RequestIdRecipe,
│                  IdempotencyGuard, RunClaim, RequestFingerprint, OutboundContractValidator, port interfaces
├── domain/        records + enums (DistributionCommand, Leg, LegStatus, LegReason, PayloadSource,
│                  YouthFilterDecision, YouthSwitchUnreadableCause, PublishOutcome, CompletionReason,
│                  FailureReason, DeadLetterReason)
├── pipeline/      HmppsRedaction (pure) and the JSON / JavaScript-truthiness shims
├── adapter/
│   ├── payload/      CachedHearingPayloadAdapter (keyPrefix "EXT_"), ResultsQueryExternalHearingClient
│   ├── usersgroups/  FeaturePermissionClient
│   ├── gateway/      HmppsPublisher
│   ├── http/         RetryPolicy, RetryPause (copied from YOT), LegacyGetRetry
│   └── stub/         stub sources for local and test profiles
├── persistence/   ProcessedRequestRepository, OutboundMessageRepository, ProcessedLogProbe; Flyway in db/migration
├── config/        typed @ConfigurationProperties, the startup validator, health indicators, metrics
└── api/           reserved for the operations API, if O-1 adopts it — no controller until then
```

Each package's `package-info.java` states its responsibility and what it may depend on; keep them
true as code lands.

## Domain Model (design §4.6, Appendix B)

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`, optional `userId` |
| `processed_request` | table | PK `(source, request_id)`; the informant's columns (claim triple, fingerprint, `status`, `attempts`, `exhausted_message_id`, `failure_reason`, `completion_reason`, `audit_note`) plus this service's ALTER section (`command`, `replayed_at`, …) |
| `outbound_message` | table | one row per leg per **attempt**, UNIQUE `(source, request_id, leg, attempt)`; `status`, `reason`, `youth_switch`, `payload_source`, `payload_digest`, `body_digest`, `http_status`, versions, `attempt_origin`, the resend claim triple, `note` (closed forms) |
| `shadow_capture` | table | shadow profile only: one row per (run, request, leg), would-be status, reason and body |
| `outbound_message_latest` | view | the latest attempt per leg — a leg's truth |

Inbound is **JsonNode-canonical**: the hearing payload stays a Jackson tree and is read through a
typed facade where one helps. Do not write a typed model of the hearing.

## Processing State Machine (design §4.5)

**Per request** — copied from informant: `RECEIVED → RETRYING → COMPLETED | FAILED`.

- `COMPLETED` names a `completion_reason` ∈ {`both-legs-terminal`, `recovered`} (the first keeps its
  name with one leg, row A3-7). **No statement moves a `COMPLETED` row** — replay, recovery and
  rollback included (row A2-27).
- `FAILED` names a `failure_reason` ∈ {`leg-exhausted`, `rolled-back`, `youth-switch-unreadable`};
  only `leg-exhausted` carries an `exhausted_message_id`. `failure_reason` is NULL off `FAILED`.
- A `FAILED` request is **replayable**: the replay action re-sends the stored command under a fresh
  `messageId`; the guard's replay statement sets `replayed_at`, and every delivery while it is set
  follows the replay column of the §4.5 eligibility table (row A2-60).

**Per leg** — `PENDING → SENT | NOT_APPLICABLE(reason) | PARKED(reason) | FAILED(reason)`:

```
admit (guard, claim)
 └─ fetch payload (EXT_ day → EXT_ no-day → query)   miss everywhere ⇒ PARKED(payload-unavailable)
     └─ HMPPS leg: switch → redact → validate + body_digest → send
                   switch UNREADABLE ⇒ FAILED(youth-switch-unreadable) at once; nothing sent; no park
 settle from the leg's result
```

Settlement:

- leg terminal — `SENT`, `NOT_APPLICABLE` (`sjp-or-box-hearing`, `nothing-left-after-redaction`), or
  `FAILED` with `receiver-rejected`, `rules-exception` or `contract-invalid` → request `COMPLETED`,
  message completed;
- leg `PARKED` (`payload-unavailable`, `gateway-transient`) → request `RETRYING` (failure reason
  NULL), message abandoned for redelivery; on the **final** delivery the leg is `FAILED(reason)`, the
  request `FAILED(leg-exhausted)` and the message dead-lettered `leg-exhausted`;
- leg `FAILED(youth-switch-unreadable)` → the **message is completed** (legacy parity, P16 carried),
  the **request is `FAILED(youth-switch-unreadable)`** with no exhausted message id — replayable, the
  leg resend-eligible, never counted towards recovery (rows A3-12, A3-21).

Rules:

- Every state transition is persisted **before** the message is settled. Settle last.
- The leg-outcome write of a queue or replay row is guarded by the request claim token; a resend row
  by its leg claim token. Zero rows updated is a stale-runner rejection, counted, and the run does not
  settle (row A2-67).
- Attempts are authoritative in the database; cross-checked against `deliveryCount`, never derived
  from it alone.
- `runOrigin` (`queue`, `replay`, `resend`) is captured once at admission and labels every metric,
  log line and created row of the run (row A2-71).

## Queue Semantics Rules (design §4.2)

Queue **`probation.requests`** (+ its dead-letter queue), owned by this service, stack-prefixed in
deployed environments.

- **Peek-lock only.** `ReceiveAndDelete` is banned — it loses messages on crash.
- **Auto-complete disabled.** Every path through the listener performs exactly one explicit
  `complete()`, `abandon()`, or `deadLetter()`. It never lets the lock expire and never answers twice.
- `maxDeliveryCount` = **5**, mirrored in configuration to recognise the final delivery.
- **Broker duplicate detection is ON**, set at queue creation (it cannot be enabled later), window
  PT5M. `messageId` = `RESULTS:{requestId}`. The processed log is the real guard.
- Lock 5 min; claim lease PT4M30S, strictly shorter than the lock; processing deadline 4 min.
- Concurrency starts at **2**; 1–2 replicas (O-2). Sessions off.
- **Replay** re-sends `processed_request.command` byte for byte under a **fresh** `messageId` with the
  application property `origin = REPLAY` (an audit label only). The dead-letter queue is kept for
  forensics, never used as the replay source (row A2-48).
- **ASB health MUST NEVER gate readiness.** A broker blip must not restart the pod.
- Dead-letter reasons: `validation` (closed-schema failure, no row), `collision` and `exhausted`
  (inherited guard paths), `leg-exhausted` (this service's final-delivery settlement).

## Idempotency Log Design (design §4.6)

- `processed_request` — PK `(source, request_id)`; the insert is the claim; a unique violation means
  a concurrent or duplicate delivery, handled, not an error. Copied from informant, with the
  recorded one-line departures the design lists (append-only `audit_note` on replay, a NULL-tolerant
  replay predicate, a null reason on `recordRetrying`).
- `outbound_message` — attempt 1 is inserted `PENDING` at admission under the request claim; a resend
  attempt (n ≥ 2) carries its own leg claim and `resend_run_id`. A re-run of attempt 1 replaces the
  outcome in one statement and records `prev=<STATUS>/<reason>` in `note` (row A2-39).
- `body_digest` is recorded whenever a body was serialised for a POST; `payload_digest` over the raw
  fetched bytes; versions alongside (row A2-61).
- **The guarantee, stated honestly**: no re-send in normal operation, redeliveries and replays
  included; at-least-once across a crash between a 2xx and recording it. An ambiguous POST is retried.
  Whether HMPPS tolerates a duplicate is Q5 (open). Do not write code or a comment that promises more.
- Hearing payloads and outbound bodies are **never persisted** outside the shadow profile (D13
  recommendation, open).
- Migrations are **Flyway** — never Liquibase.
- The log is the support answer to "was this hearing sent?" — keep it queryable by `hearing_id` and
  `hearing_day`.

## Parity and the Deviation Register (design §6, §7)

The rules and what is sent are **lift-and-shift** (D1): ported verbatim, quirks included.

- Every intended difference from the legacy is a numbered **DV-n** row in `doc/DEVIATIONS.md`
  (seeded from design §7.2) with its pinning test. The parity harness asserts each registered
  deviation by derivation and **fails on any unregistered difference**.
- Do NOT "fix" behaviour that looks wrong while porting. Carried, deliberately: the first permission
  decides the switch (P17); with the switch on, an application with no `masterDefendant` is dropped
  (P18); an unreadable switch sends nothing for that hearing (P16 — made visible, not changed);
  `plea.delegatedPowers` is not removed (P35, pending D10). No payload change in this delivery (D10).
- JavaScript truthiness is reproduced wherever a rule tests "truthy" (an empty array is truthy;
  `null`, absent, `false`, `0` and `""` are not).
- Fixed by construction, each a DV: timeouts and bounded retries on the POST (DV-04), the real
  outcome recorded (DV-05), failures at ERROR and counted (DV-06), the Redis outage falling back
  (DV-07), no duplicate sends (DV-08), no payload in logs (DV-09), an empty answer as a miss (DV-10),
  Lettuce and verified TLS (DV-13, DV-14), the closed schema (DV-16), the fallback without `userId`
  (DV-17), the results-read retry (DV-23), the users-groups timeouts (DV-24).
- Golden files: Jest fixtures copied byte-identical into `src/test/resources/fixtures/`; every Jest
  case gets a JUnit twin. The recorded corpus covers both switch states and both payload forms.
  **Real hearing data is not committed to this public repository**; where the corpus lives is open.

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block.
- Every log line carries `requestId` and `hearingId` (MDC); `hearingDay` and `source` where relevant.
- **No defendant PII at `info`**, and no payload or outbound body at any level in a deployed
  environment. Reasons and causes are bounded codes.
- Metrics use the `probation_` family of design §10.1; every `leg` label is `HMPPS`.

## Out of Scope — do not build here

- Any business REST API. The operations API is open (O-1) and needs a constitution amendment.
- **VEP** (H5): no projection, sender, schema, stub, key or enabled flag.
- Reading the cutover flag `ProbationResultsDistributionService` (Q15) — the producer and the legacy
  triggers read it. No second lever of any kind.
- The producer increment — it lives in `cpp-context-results`.
- The LAA feed, the Results Store, SJP hearings, the NOWs app, any APIM gateway change (D4a).
- Any change to what is sent to HMPPS (D10).
