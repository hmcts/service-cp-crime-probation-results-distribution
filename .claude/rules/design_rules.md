# Architecture & Domain Rules

This service is a **message-driven pipeline**, not a REST application. There is no business HTTP
API. The HTTP surface is actuator plus the operations API under `/operations/**`, which is in scope
for **replay** and **resend-leg** (the forced-resend override included), behind YOT's five
deployment gates, with the command-line runners in the image as the fallback (H9; design §4.8). The
other operator actions (list, pause and resume intake, submit-hearing, purge-queue, the optional
flag check, the recommended `dlq-completed` listing) run as command-line runners; their HTTP
exposure is open (O-1). Everything below assumes that shape. Section references are to the design,
rev 3.6 (decisions H1–H34) and H35 (2026-10-21); `doc/design/DIGEST.md` restates its rules with
their row ids.

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
         ├─▶ body digest               no schema validation in this release (H13)
         ├─▶ HmppsSender       «port»  POST to the APIM gateway's HMPPS route
         └─▶ OutboundLog       «port»  one outbound_message row per leg per attempt; the exact
                                       bytes POSTed into outbound_body (H20)
LegResender                             resend-leg: an explicit requestId list, each ID checked and
                                        refused on its own (H16); the forced-resend override (H9)
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
- **On a resend, the `sharedTime` comparison runs directly after the fetch, before any leg stage**
  (H17; rows A2-24, A3-57).

NEVER put business logic in the message listener.
NEVER call a repository or an HTTP client from the listener.
NEVER reference `ServiceBusReceivedMessage` outside the inbound adapter.

### Package structure

```
uk.gov.hmcts.cp.probationresultsdistribution
├── inbound/       listener, consumer config, command parser, closed schema, ConsumerLifecycleController, StoreGate (copied from YOT)
├── application/   DistributionRunner, LegResender, SubmitHearingAction, RequestIdRecipe,
│                  IdempotencyGuard, RunClaim, RequestFingerprint, port interfaces
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
├── persistence/   ProcessedRequestRepository, OutboundMessageRepository, ProcessedLogProbe, the outbound_body
│                  writes (behind OutboundLog); Flyway in db/migration
├── config/        typed @ConfigurationProperties, the startup validator, health indicators, metrics
└── api/           the operations API: replay and resend-leg (H9); other actions only once O-1 decides
```

`OutboundContractValidator` is **not built** in this release (H13). The design's package tree
(§4.4) places neither the `outbound_body` writes nor the body-retention job (H20): the writes sit
in `persistence/` behind `OutboundLog`, as its `package-info.java` says, and the first spec that
needs the job places it, keeping the dependency rules above. Each package's
`package-info.java` states its responsibility and what it may depend on; keep them true as code
lands.

## Domain Model (design §4.6, Appendix B)

V1 is **three tables and one view** (H17, H19, H20, H21).

| Type | Kind | Fields / meaning |
|------|------|------------------|
| `DistributionCommand` | record (inbound message) | `source`, `requestId`, `hearingId`, `hearingDay`, `sharedTime`, `eventType`, optional `userId` |
| `processed_request` | table | PK `(source, request_id)`; the informant's columns (claim triple, fingerprint, `status`, `attempts`, `exhausted_message_id`, `failure_reason`, `completion_reason`, `audit_note`) plus this service's ALTER section (`command`, `replayed_at`, …) |
| `outbound_message` | table | one row per leg per **attempt**, UNIQUE `(source, request_id, leg, attempt)`; `status`, `reason`, `youth_switch`, `payload_source`, `payload_shared_time` (H17), `body_digest`, `service_version` (H19), `http_status`, `attempt_origin`, the resend claim triple, `note` (closed forms). No `payload_digest`, `rules_version`, `serializer_version` or body column |
| `outbound_message_latest` | view | the latest attempt per leg — a leg's truth |
| `outbound_body` | table | the exact UTF-8 bytes POSTed (`text`), one row per attempt that serialised a body; monthly partitions on `created_at` (UTC) plus a DEFAULT partition; PK `(outbound_message_id, created_at)`; FK to `outbound_message` `ON DELETE RESTRICT`; `outbound_body_digest_trg` refuses a body whose SHA-256 differs from its attempt's `body_digest` (H20; row A3-63) |

There is no `shadow_capture` table (H21). Inbound is **JsonNode-canonical**: the hearing payload
stays a Jackson tree and is read through a typed facade where one helps. Do not write a typed model
of the hearing.

## Processing State Machine (design §4.5)

**Per request** — copied from informant: `RECEIVED → RETRYING → COMPLETED | FAILED`.

- `COMPLETED` names a `completion_reason` ∈ {`both-legs-terminal`, `recovered`} (the first keeps its
  name with one leg, row A3-7). **No statement moves a `COMPLETED` row** — replay, recovery,
  rollback and a forced resend included (rows A2-27, A3-36).
- `FAILED` names a `failure_reason` ∈ {`leg-exhausted`, `rolled-back`}; only `leg-exhausted`
  carries an `exhausted_message_id`. `youth-switch-unreadable` is not a reason, of the request or of
  a leg (H8; row A3-25). `failure_reason` is NULL off `FAILED`.
- A `FAILED` request is **replayable** by two routes (H9; rows A3-30, A3-34): the service's replay
  action re-sends the stored command under a fresh `messageId`, and a **resubmission from the
  dead-letter queue** is admitted too — under its exhausted `messageId` only at broker delivery count
  1 (DV-26). The guard's replay statement sets `replayed_at`, and every delivery while it is set
  follows the replay column of the §4.5 eligibility table (row A2-60). The route (`service`,
  `support`, `copy`) is appended to `audit_note`, logged and counted on
  `probation_replays_total{route}`. A replay of a `COMPLETED` request is a no-op by either route.

**Per leg** — `PENDING → SENT | NOT_APPLICABLE(reason) | PARKED(reason) | FAILED(reason)`:

```
admit (guard, claim)
 └─ fetch payload (EXT_ day → EXT_ no-day → query)   miss everywhere ⇒ PARKED(payload-unavailable)
     └─ HMPPS leg: switch → redact → body_digest → send (+ outbound_body insert)
                   switch UNREADABLE ⇒ the configured default (ACTIVE unless set) and carry on; no park
 settle from the leg's result
```

Settlement:

- leg terminal — `SENT`, `NOT_APPLICABLE` (`sjp-or-box-hearing`, `nothing-left-after-redaction`), or
  `FAILED` with `receiver-rejected` or `rules-exception` → request `COMPLETED`, message completed.
  `contract-invalid` stays in the vocabulary **reserved and unused** while validation is parked
  (H13);
- leg `PARKED` (`payload-unavailable`, `gateway-transient`) → request `RETRYING` (failure reason
  NULL), message abandoned for redelivery; on the **final** delivery the leg is `FAILED(reason)`, the
  request `FAILED(leg-exhausted)` and the message dead-lettered `leg-exhausted`.
  `PARKED(gateway-transient)` is a connection failure or 429 still failing after the in-run retries,
  or — at once, with **no in-run retry** — a 5xx, a 408, or a read timeout or I/O failure after the
  request was written: outcome-unknown (H11, H15). On a resend there is no `PARKED`: the same
  failure is `FAILED` at once;
- an **unreadable switch is not a settlement case** (H8; row A3-23): the leg resolves it to
  `distribution.youth-switch.default-when-unreadable` (`ACTIVE` | `INACTIVE`, default `ACTIVE`),
  records `youth_switch = UNREADABLE_DEFAULTED_ACTIVE` (or `_INACTIVE`), runs R3/R4 when the value is
  `ACTIVE`, and goes on to send on a queue delivery, a replay and a resend alike; it settles by
  whatever the send gives. The read is logged at WARN with its cause and counted on
  `probation_youth_switch_unreadable_total{cause, defaulted}`; the alert stays.
- `outbound_message.youth_switch` ∈ {`ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`,
  `UNREADABLE_DEFAULTED_INACTIVE`}, NULL before the read, tied to no reason (row A3-25).

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
- **Broker duplicate detection is OFF** (H14; rows A3-53, A3-55). `messageId` = `RESULTS:{requestId}`.
  The processed-log guard is the **only** duplicate protection, and every copy reaches it: a
  `COMPLETED` request → no-op; a live claim → abandon; a `FAILED` request → replay (DV-26 rule); two
  concurrent copies → one wins. A copy abandoned on a live claim is redelivered at once and can be
  dead-lettered `exhausted` in seconds — accepted noise, no abandon delay. Enabling duplicate
  detection later means recreating the queue.
- Lock 5 min; claim lease PT4M30S, strictly shorter than the lock; processing deadline 4 min.
- Concurrency starts at **2**; 1–2 replicas (O-2). Sessions off.
- **Replay** (H9): the service's action re-sends `processed_request.command` byte for byte under a
  **fresh** `messageId` with the application property `origin = REPLAY` (an audit label only) — the
  only route after a rollback purge; the fresh id keeps it distinct from the exhausted identity and
  labels it for audit. The dead-letter queue is the other route: operations resubmit from it with
  the broker tools (DV-26).
- **ASB health MUST NEVER gate readiness.** A broker blip must not restart the pod.
- Dead-letter reasons: `validation` (closed-schema failure, no row), `collision` and `exhausted`
  (inherited guard paths), `leg-exhausted` (this service's final-delivery settlement). The
  exhausted-identity re-dead-letter applies only above broker delivery count 1 (DV-26).
- **Dead-letter runbook** (rows A3-32, A3-33, A3-53): resend `exhausted` and `leg-exhausted`; never
  resend `validation` or `collision`; once a replayed request is `COMPLETED`, remove the original
  (and any re-dead-lettered copy); an `exhausted` copy whose request is already `COMPLETED` is
  accepted noise — remove it, do not resend it.

## Idempotency Log Design (design §4.6)

- `processed_request` — PK `(source, request_id)`; the insert is the claim; a unique violation means
  a concurrent or duplicate delivery, handled, not an error. Copied from informant, with the
  recorded one-line departures the design lists: append-only `audit_note` on replay, a replay
  predicate `(exhausted_message_id IS NULL OR exhausted_message_id <> :messageId OR
  :deliveryCount = 1)` (rows A2-62, A3-30), a null reason on `recordRetrying`.
- `outbound_message` — attempt 1 is inserted `PENDING` at admission under the request claim; a resend
  attempt (n ≥ 2) carries its own leg claim and `resend_run_id`. A re-run of attempt 1 replaces the
  outcome in one statement and records `prev=<STATUS>/<reason>` in `note` (row A2-39).
- `body_digest` (SHA-256 over the exact bytes POSTed) and `service_version` (the build version) are
  recorded whenever a body was serialised for a POST (H19); `payload_shared_time` records the
  `sharedTime` the attempt read (H17). There is no payload digest.
- **The exact body goes to `outbound_body`** (H18, H20; row A3-63): the outcome UPDATE (with
  `body_digest`) first, then the body INSERT, in the same transaction; an in-place re-run deletes the
  attempt's earlier body row and inserts the new one. Kept 6–7 months, then purged by the
  body-retention job; after that the digest, `service_version` and metadata remain.
- **The guarantee, stated honestly**: a leg recorded `SENT` or `NOT_APPLICABLE` is never sent again
  by a redelivery or a replay; at-least-once across a crash between a 2xx and recording it. An
  outcome-unknown POST is **not retried in-run** — it parks and the broker redelivers (H15). The
  results POST is not idempotent (ICD v0.8; Q5 closed), so every duplicate is genuine at HMPPS. Do not
  write code or a comment that promises more.
- Hearing payloads are **never persisted**; outbound bodies are, in `outbound_body` only.
- Migrations are **Flyway** — never Liquibase. PostgreSQL 15, production's version (row A3-105).
- The log is the support answer to "was this hearing sent?" — keep it queryable by `hearing_id` and
  `hearing_day`.

## Operator Actions (design §4.8)

- **Resend-leg** takes an explicit `requestId` list, 1 … `distribution.resend.max-ids-per-run`
  (default 100), for normal and forced resends alike (H16). Each ID is taken in its own transaction
  and proceeds or is refused on its own: `unknown-request`, `not-eligible`, `resend-in-progress`,
  `request-claimed` — API and run-report codes, never row values, counted on
  `probation_resend_refusals_total{reason}`. Refused whole while intake is paused.
- **Forced resend** (H9): `allowTerminalSuccess` (name proposed) with an operator and a reason
  (sanitised, ≤ 200 characters, appended to `audit_note`) re-sends a leg whose latest row is `SENT`
  or `NOT_APPLICABLE` on a new `resend` attempt row with `override=force-resend`; the request stays
  `COMPLETED`; a genuine duplicate at HMPPS.
- **Rollback constraints** (rows A2-40, A2-52, A3-117): resend-leg is refused while intake is
  paused, and purge-queue is allowed only while it is paused. After a rollback intake stays paused
  until a full new cutover — purge, reconcile, cutover, then resume.

## Outbound Send (design §3.1, §4.4, §4.7)

- Gateway timeouts connect **5 s** / read **60 s** (H27).
- In-run retries **only** for a connection failure (the request never sent; a connect timeout
  included) and 429, honouring `Retry-After` capped by `max-backoff` (H11, H15). The predicate is
  fixed in code, never a property. A 5xx, a 408 and a read timeout or I/O failure after the write
  park at once.
- Any 2xx is `SENT`; a 2xx other than 200 is also logged at WARN and counted
  (`probation_gateway_non200_success_total`, H12). Any other 4xx, 401 included, is
  `FAILED(receiver-rejected)`.
- Budget: payload 60 s + switch 25 s + send 130 s = 215 s < 240 s. The composed send-stage startup
  check is (attempts − 1) × (connect + `max-backoff`) + (connect + read) ≤ 130 s — 95 s with the
  defaults (row A3-96).
- `CJSCPPUID` on the users-groups read, and on the results fallback whenever the command has no
  `userId` (every resend included), is the service's **own system user**, one id for both
  properties (H30).

## Parity and the Deviation Register (design §6, §7)

The rules and what is sent are **lift-and-shift** (D1): ported verbatim, quirks included.

- Every intended difference from the legacy is a numbered **DV-n** row in `doc/DEVIATIONS.md`
  (seeded from design §7.2) with its pinning test. The parity harness asserts each registered
  deviation by derivation and **fails on any unregistered difference**.
- Do NOT "fix" behaviour that looks wrong while porting (design §7.1). Carried, deliberately: the
  first permission decides the switch (P17); with the switch on, an application with no
  `masterDefendant` is dropped (P18); the rules have no null guards, so a rule that throws is
  `FAILED(rules-exception)` and no guard is added (P19). Pending external, and carried until HMPPS
  agrees (D10): associated persons and delegated powers kept inside applications (P21);
  `plea.delegatedPowers` not removed (P35). Application-only hearings are sent as today and
  rejected by HMPPS (D16 (a)) — a known class, counted. No payload change in this delivery (D10).
- P16 (an unreadable switch sends nothing) is **fixed under DV-25** (H8), not carried: the body sent
  is the legacy's body for the same payload with the switch in the configured default state. Corpus
  case 13 (the switch read fails) expects the `ACTIVE` output, asserted by derivation, never by
  editing the golden; in the STE replay report a defaulted read is classified under DV-25. The next
  free DV id is DV-27.
- JavaScript truthiness is reproduced wherever a rule tests "truthy" (an empty array is truthy;
  `null`, absent, `false`, `0` and `""` are not).
- Fixed by construction, each a DV: timeouts and bounded retries on the POST, in-run only for a
  connection failure and 429 (DV-04, narrowed by H11 and H15), the real outcome recorded (DV-05),
  failures at ERROR and counted (DV-06), the Redis outage falling back (DV-07), no duplicate sends by
  the processed-log guard alone (DV-08, H14), no payload in logs (DV-09), an empty answer as a miss
  (DV-10), Lettuce and verified TLS (DV-13, DV-14), the closed schema (DV-16), the fallback without
  `userId` (DV-17), the results-read retry (DV-23), the users-groups timeouts (DV-24), the
  unreadable-switch default (DV-25), and a dead-letter copy under its exhausted `messageId` admitted
  as a replay at delivery count 1 (DV-26, H9).
- Golden files: Jest fixtures copied byte-identical into `src/test/resources/fixtures/`; every Jest
  case gets a JUnit twin. The corpus covers both switch states and both payload forms; its expected
  output is **SIT's own logged HMPPS body** for switch-off SIT cases and the executed legacy for
  switch-on and synthetic cases (H21). Corpus payloads and SIT logged bodies are **SIT data only**,
  committed under `src/test/resources/corpus/` (SIT holds no production data; decision 2026-10-19).
  **Production (PRD/PRP) hearing data is never committed.**
- The pre-cutover proof is the one-week SIT → STE replay (H21): STE's `outbound_body` compared hearing
  by hearing with SIT's logged bodies; any unexplained difference blocks cutover. It is not a build
  gate, and there is no shadow profile.

## Error Handling and Logging (domain-specific)

- **NO swallowed exceptions, ever.** No empty catch, no `catch (Exception e) { log.debug(...); }`,
  no returning a "success" object from a catch block.
- Every log line carries `requestId` and `hearingId` (MDC); `hearingDay` and `source` where relevant.
- **No defendant PII at `info`**; no payload at any level in a deployed environment; the outbound
  body — sent or stored — **never at any level, in any profile**, and never in a metric label (row
  A3-60). Reasons and causes are bounded codes.
- Metrics use the `probation_` family of design §10.1; every `leg` label is `HMPPS`. Beyond the 1.x
  set: `forced`, `shape` and `http` labels on `probation_leg_outcomes_total`;
  `probation_replays_total{route}`; `probation_resend_refusals_total{reason}`; `probation_body_*`
  (partitions created and dropped, maintenance failures by `step`, DEFAULT-partition rows);
  `probation_gateway_non200_success_total`; `probation_gateway_recovered_total{after}`;
  `probation_crash_reruns_total`.
- **Alerts page only for genuine issues** (H34): DLQ not empty; a send parked, or a request
  non-terminal, over 30 minutes; a send still failing after its last delivery or on a resend
  (`gateway-transient`, `payload-unavailable`), or a rules exception; repeated 401s; a hearing sent on
  an assumed youth-switch value; a send re-run after a crash; body-retention failures; the legacy
  function app sending after cutover; and, results-side, failed reads of the Results switch.
  **Counted, not paged** (dashboard and daily reconciliation):
  application-only 400s; other 400s (with a spike alert); 500s recovered by a later delivery; 429s
  recovered in-run; a non-200 2xx. `payload-changed` and `rolled-back` stay on the leg-failed alert
  until placed (Q28). Thresholds are proposals, tuned after go-live.

## Out of Scope — do not build here

- Any business REST API. HTTP beyond actuator is the operations API's replay and resend-leg only
  (H9); exposing another operator action over HTTP waits for O-1 and a constitution amendment.
- **VEP** (H5): no projection, sender, schema, stub, key or enabled flag.
- Outbound schema validation (H13): no `OutboundContractValidator`, no mode property, no
  `contract-invalid` write.
- Reading the cutover switch `ProbationResultsDistributionService` (Q15, row A3-92) — Results reads
  it with `FeatureControlGuard` (H26) and the legacy is switched off at its Event Grid subscription
  (H22). The service reads no App Configuration. No second lever of any kind.
- The producer increment — it lives in `cpp-context-results`.
- The LAA feed, the Results Store, SJP hearings, the NOWs app, any APIM gateway change (D4a).
- Any change to what is sent to HMPPS (D10).
