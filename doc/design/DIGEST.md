# Design digest — design rev 3.6

## 1. Baseline

| | |
|---|---|
| Baseline | Design rev 3.6 (draft, 2026-10-20), decisions H1–H34. Frozen for this repo until a later amendment. |
| Source of truth | [Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service) (Confluence) |
| How to use | Rules only; the reasoning is on Confluence. Where this digest differs from the design, the design wins. A Confluence section that still predates a rev 3.6 decision (H1–H34) does not override that decision: the rev 3.6 decision applies, and the difference is raised with the design owner as an open issue (constitution, Governance). |
| Tags | Ids from the design record: H-n, D-n decisions; A2-n, A3-n, R1-n, R2-n, Q-n, O-n design rows; DV-n deviations; P-n legacy defects; R0–R12 HMPPS rules; §n and App. A/B are sections of design rev 3.6. The Confluence page states the same design in plain English, without ids. |
| Names | All `distribution.*` property names, and every name marked *(proposed)*, are proposals of the design (§8.1), not fixed. |

## 2. Scope (§1.3, §1.4, §4.1)

**The service does**
- Consume `probation.requests`: one command per non-SJP `Hearing_Resulted` share, from results' flag-gated producer (H3, §4.2).
- Read the payload: Redis `EXT_` dated key → undated key → results query API (H2, D5).
- Read the users-groups `EMYouthFilter` permission per message; unreadable → configured default (H7, H8).
- Redact with HMPPS rules R0–R12 verbatim (R3/R4 when `ACTIVE`) and POST through the existing gateway route (D1, D4a, §5.1).
- Record every leg attempt and its outcome; keep the exact body sent for 6–7 months (H18, H20).
- Guard duplicates; replay; resend-leg, forced resend included; operator actions (§4.4–§4.8, H9, H16).
- Run one scheduled task: the daily `outbound_body` partition maintenance (H20).
- Emit the metrics, logs and alert signals of §13 (§10).

**The service does not**
- Build a VEP leg (H5, working assumption). `leg` stays as the extension point (A3-1).
- Validate the outbound body against a schema (H13).
- Read the Results switch `ProbationResultsDistributionService` (H26, A3-92).
- Change what is sent: rules verbatim; application-only hearings and other 400s are sent as today (D1, D10, D16, A3-109).
- Change the gateway: same route, same subscription key, no client certificate (D4a, D4b).
- Provide a business REST API, batch jobs, the LAA feed, the Results Store (H1), SJP hearings, the NOWs app or the receivers (§1.4).

**Shape (§4.1):** Spring Boot 4.1, Java 25, Gradle; port 4550 in Kubernetes; package root `uk.gov.hmcts.cp.probationresultsdistribution`; ports and adapters; one image; PostgreSQL with Flyway `V1`; operations API `/operations/**` (§11).

**Not this repo's code:** the results producer increment (§14), the cutover and rollback runbook (§9.3, §9.4), decommissioning (§9.6).

**Quality gate (H21, §6)**
- Corpus: real SIT payloads, both the `EXT_` form and the query-API form of the same hearings, committed under `src/test/resources/corpus/`. Never PRD or PRP data; never in logs or reports (§6.4).
- Expected output: with the switch `INACTIVE`, SIT's own logged HMPPS body — the body of the form the legacy read at the time, normally the cache form; the oracle for the other form of the same hearing is open (§16); with the switch `ACTIVE`, and for synthetic cases, the legacy JavaScript executed (filter and body construction) (A3-67). Every case is recorded in both switch states (A3-18).
- Comparator: semantic JSON equality after canonicalisation; numbers as BigDecimal; registered deviations asserted by derivation (DV-25: a lookup-failed case expects the `ACTIVE` output); any unregistered difference fails the build (§6.2).
- Required cases (§6.3): 1 virtual-box hearing (synthetic); 2 top-level `judiciary` and `associatedPersons`; 3 application with no `masterDefendant` (both states); 4 offence without `judicialResults`; 5 `courtOrder` without `courtOrderOffences`; 8 `plea.delegatedPowers`; 9 non-boolean `isYouth`; 11 youth defendant in an adult-court hearing; 12 everything removed under `ACTIVE`; 13 switch read fails (each cause). Cases 6, 7 and 10 are dropped.
- Whole-corpus checks: `hearing.id` is in every body (Q13); every carried null guard (P18, P19) has a case, or is listed as an accepted risk (§6.3).
- Reused from the informant parity pack: the `recorded/` layout with pinned digests, `comparator-vectors/vectors.json`, `JsonParity`, `ParityCase`, and `RegisteredFieldDeviations` keyed on `doc/DEVIATIONS.md` (§6.1).
- Before cutover: one week of SIT events is replayed into STE against a stub gateway. STE's `outbound_body` is compared hearing by hearing with SIT's logged bodies; any unexplained difference blocks cutover (A3-68, §6.5).

## 3. Decisions H1–H34

| Id | Rule |
|---|---|
| H1 | The Results Store is on hold and its design line is parked. No store dependency. |
| H2 | Payload source: Redis `EXT_` (dated key, then undated key), with the results query API as fallback. |
| H3 | Trigger: results' flag-gated producer publishes to the dedicated queue `probation.requests`. The service takes no Event Grid input. |
| H4 | Record-keeping only (the earlier design line is parked). No build rule. |
| H5 | No VEP leg (working assumption; business confirmation outstanding). Keep `leg` with `CHECK leg IN ('HMPPS')`; VEP would return only as a new leg value, projection and sender (A3-1). |
| H6 | **Withdrawn** by H7. |
| H7 | Read `EMYouthFilter` per message, on every run kind. `permissions[0].active` truthy = `ACTIVE` = remove youth. Run R3/R4 verbatim when `ACTIVE`. *Failure handling superseded by H8.* |
| H8 | Switch unreadable → apply `distribution.youth-switch.default-when-unreadable` (default `ACTIVE`), redact and send. Record `UNREADABLE_DEFAULTED_<value>` (DV-25). |
| H9 | Replay routes: DLQ resubmission (DV-26), and ops-API replay of the stored command (the only route after a rollback). A forced resend of a `SENT` or `NOT_APPLICABLE` leg goes through a resend-leg override, with operator and reason. Replaying a `COMPLETED` request is a no-op. |
| H10 | **Parked** by H13. |
| H11 | APIM owns 5xx retries; the service never retries a 5xx in-run (§9). *Narrowed by H15.* |
| H12 | ICD v0.8: success = 200 only (the service records any 2xx as `SENT` and flags a non-200 2xx). The results POST is not idempotent, so every duplicate is genuine. The ICD schema is an input only. |
| H13 | No outbound schema validation and no gate G1 in this release. `contract-invalid` and `contract=invalid` stay reserved, unused. |
| H14 | The queue is created with duplicate detection **off**. The `processed_request` guard is the only duplicate protection. |
| H15 | Outcome unknown parks like a 5xx: a read timeout after the request was written, an I/O failure once writing started, or 408. In-run retries cover only a connection failure and 429. DLQ noise from duplicate copies is accepted; no abandon delay. |
| H16 | Resend-leg (normal and forced) takes an explicit `requestId` list only, capped by `distribution.resend.max-ids-per-run`. Each ID is checked and refused on its own; the report lists every ID. |
| H17 | No payload digest. A resend compares the re-read payload's `sharedTime` with the stored `shared_time`, as instants: equal → proceed; different, absent or unparseable → `FAILED(payload-changed)`, unless `allow-changed-payload`. The row records `payload_shared_time`. |
| H18 | Store the exact UTF-8 body POSTed, on every attempt that serialised one; `body_digest` is its SHA-256. No column encryption; never logged. *Placement and retention superseded by H20.* |
| H19 | One `service_version` (the build version) replaces `rules_version` and `serializer_version`; present iff `body_digest` is. |
| H20 | The body lives in `outbound_body`, partitioned by month (UTC) on `created_at`. A daily in-service job, single runner by advisory lock, purges it after `distribution.body-retention` (`P6M`, so 6–7 months in effect). A trigger checks each body against `body_digest`. |
| H21 | No shadow run and no `shadow_capture`. Quality gate: SIT-data unit tests plus a one-week SIT → STE replay against a stub gateway; unexplained differences block cutover. |
| H22 | Cutover uses two switches and no legacy code change: switch off the legacy function app's Event Grid subscription (no-match filter), then turn on the Results switch, in the weekend deployment window while Common Platform is down. *Results' users-groups read superseded by H26.* |
| H23 | The planned rollback runs in the same window, steps in order: 1 pause intake, 2 Results switch off and wait ≥ 10 min (A3-103), 2a subscription filter restored, 3 record the cut-off, 4 close in-flight rows `FAILED(rolled-back)`, 5 reconcile the live period, 6 purge the queue, 6a replay only through the ops API, 7 repair only hearings not known to be delivered (§9.4). |
| H24 | The Results switch is flipped in App Configuration, under each environment's label. The platform sync keeps a users-groups copy for the UI. A new switch needs both the flag and the static-data patch. *Results' read superseded by H26.* |
| H25 | Subscription off = included event type `TOGGLED-OFF`; on = restore exactly the filter recorded before cutover. SIT negative testing is a cutover precondition: queue retry up to `maxDeliveryCount` then DLQ, and DLQ replay. Cutover switches the subscription off before turning the switch on; rollback turns the switch off before switching the subscription on. |
| H26 | Results gates the publish with `FeatureControlGuard.isFeatureEnabled("ProbationResultsDistributionService")`, fail-closed. The service reads only `EMYouthFilter`, through the users-groups query API, and never checks the Results switch. |
| H27 | Gateway read timeout 60 s, connect timeout 5 s; the send budget stays 130 s. |
| H28 | No further body-size measurement; the sizing floor is in §8. |
| H29 | The legacy topic's ad hoc replay subscription is out of scope. |
| H30 | The service has its own system user (static-data patch; id from Key Vault). It is sent as `CJSCPPUID` on the users-groups read, and on the results fallback when the command has no `userId` and on every resend (Q14, DV-17). |
| H31 | The owning team owns and supports the service and sets the soak length with stakeholders. The daily reconciliation runs throughout the soak. |
| H32 | An urgent rollback that cannot wait for the window runs out of hours, with Common Platform up: 1 pause intake; 2 restore the subscription and record T; 3 turn the Results switch off and wait ≥ 10 min; 4 close in-flight sends; 5 reconcile the paused queue against T (pre-T → court re-share; post-T → purge; intake stays paused); 6 purge. Then planned steps 5–7 (A3-113, A3-117). |
| H33 | Application-only 400s are counted daily (`shape="application-only"`), not alerted. *Generalised by H34.* |
| H34 | Alerts page only for genuine issues. Known recurring behaviour is counted on the dashboard and in the daily reconciliation. Thresholds are proposals, tuned after go-live (§13). |

**Other decisions in force**
- D1: lift and shift. Rules and payload are copied verbatim; delivery and reliability defects are fixed by construction, each as a numbered DV with its own test.
- D4a: no gateway change. Same HMPPS route, same subscription key value as the legacy.
- D4b: no client certificate (closed, A3-108).
- D5: the payload source sits behind a separable `HearingPayloadSource` port.
- D6: names. Repo `service-cp-crime-probation-results-distribution`; release `probation-results-distribution`; queue `probation.requests`; flag `ProbationResultsDistributionService`.
- D10: no payload change in this delivery.
- D12: the LAA feed keeps the shared key and payload; its owner is kept informed.
- D16 (a): application-only hearings are sent as today and recorded `FAILED(receiver-rejected)`.
- Q14 → DV-17.
- Q15: the service reads no cutover flag.
- Q16: resend-leg is part of the design.
- Q17: lock 5 min, lease `PT4M30S`, deadline 4 min, lease < lock (IR's rule; do not align with YOT).
- Superseded or closed: D2 (H5); D3b (superseded by H7, answered by H8 option (d)); D7 (H31); D8 (H21); D11; D13 (H18/H20); D15a–c (H5).
- D3a stands, reinstated by H7: the switch is the users-groups `EMYouthFilter` permission, read per message.

## 4. Registered deviations (§7.2; seed for `doc/DEVIATIONS.md`)

| DV | Rule | Defect |
|---|---|---|
| DV-01 | Withdrawn (H5) | P08 |
| DV-02 | Withdrawn; P16 is fixed by DV-25 | — |
| DV-03 | Withdrawn; `permissions[0]` decides (P17 carried) | — |
| DV-04 | The gateway POST has timeouts and bounded in-run retries (connection failure and 429 only) | P04 |
| DV-05 | The leg outcome reflects the real answer | P05 |
| DV-06 | Failed sends are logged at ERROR and counted | P06 |
| DV-07 | A Redis connection failure is a miss → query API → park (on a resend, `FAILED(payload-unavailable)`) | P09 |
| DV-08 | Duplicates and republishes are not re-sent; the processed-log guard alone enforces this | P27 |
| DV-09 | No payload or defendant data in logs at INFO or above (R12 is not ported) | P14, P15 |
| DV-10 | A query-API 2xx with an empty body or `{}` is a miss (non-JSON too; see §16) | P13 |
| DV-11, DV-12 | Unallocated (payload fixes need HMPPS agreement; D10 is "no change") | P35, P21 |
| DV-13 | Lettuce client: reconnecting, with timeouts applied | P10, P11 |
| DV-14 | Redis TLS peer is verified | P12 |
| DV-15 | Withdrawn (H5) | P25 |
| DV-16 | Closed inbound schema: a wrong event type, or a missing or unknown field, dead-letters | P26 |
| DV-17 | The query-API fallback runs without `userId`, as the service's system user | — |
| DV-18 … DV-22 | Reserved; never reused | — |
| DV-23 | Results query read: IR's retry block (4 attempts, 500 ms doubling to 20 s, cut by the payload budget). Retries 408, 429, ≥ 500 and connect/read failures; honours `Retry-After`; timeouts 5 s / 30 s | — |
| DV-24 | Users-groups read: finite timeouts of 2 s / 5 s; everything else as the legacy (A3-13) | — |
| DV-25 | Unreadable youth switch → the configured default, then send | P16 |
| DV-26 | The guard admits a same-`messageId` copy of a `FAILED` request as a replay, at delivery count 1 | — (IR departure) |

The next free id is **DV-27**. Ids are never reused, and every DV needs its own test assertion (D1). Carried (§7.1): P17, P18 and P19. Pending external: P07, P21, P35 and P36.

**Recorded departures from copied IR/YOT code (not DVs)**

*Inbound and guard*
- The inbound classes are copied from YOT. On the final delivery, the listener's store-outage and unexpected-exception paths dead-letter `exhausted` (IR's rule); on earlier deliveries they abandon (A2-34).
- The guard's abandon path passes a null reason, so `failure_reason` is NULL off `FAILED` (A2-35).
- The guard abandons with `REPLAY_DEFERRED` over a `PENDING` resend row (A2-70, A2-72).
- The guard records the replay route (A3-34).

*Statements*
- Replay statement:
  - appends to `audit_note` instead of replacing it (A2-47);
  - uses the predicate `(exhausted_message_id IS NULL OR exhausted_message_id <> :messageId OR :deliveryCount = 1)` (A2-62, DV-26);
  - sets `replayed_at = now()` (A2-60).
- The completion and final-failure statements clear `replayed_at`. The completion reason comes from the replay history (A2-41).
- The admission insert writes `command` (A2-48).

*Retry*
- YOT's `RetryPolicy`: for the gateway client only, the retryable predicate is narrowed in code to a connection failure and 429 (A3-43, A3-54); the results-query read keeps YOT's set (DV-23). Attempt-fits is checked at the end of the wait on both (A2-69).
- The users-groups read uses `LegacyGetRetry`, not `RetryPolicy` (A3-13).

*Schema and deployment*
- The IR `processed_request` DDL is kept byte-identical; it changes only by `ALTER` (App. B).
- NetworkPolicy manifests are authored, not copied (A2-21).

## 5. Inbound contract (§4.2, App. B.1)

| Queue setting | Value |
|---|---|
| Name | `probation.requests` plus its DLQ; stack-prefixed in deployed environments (`{stack}.probation.requests`) |
| Duplicate detection | Off (H14) |
| Lock duration / max auto-renew | 5 min / 5 min (Q17) |
| `maxDeliveryCount` | 5, mirrored in config to identify the final delivery |
| Receive mode | Peek-lock, auto-complete off; sessions off |
| Concurrency | 2 per pod (O-2) |

**Body:** IR's closed schema, unchanged except `$id`, title and descriptions. Vendored at `src/main/resources/contracts/distribution-command.schema.json` with the first feature; draft-07; `additionalProperties: false`.

| Field | Rule |
|---|---|
| `source` | Required; enum `["RESULTS"]` |
| `requestId` | Required; UUID; recipe in §14 |
| `hearingId` | Required; UUID |
| `hearingDay` | Required; `format: date` (`YYYY-MM-DD`) |
| `sharedTime` | Required; `format: date-time` (ISO-8601 instant) |
| `eventType` | Required; enum `["Hearing_Resulted"]` |
| `userId` | Optional UUID. Absent is allowed; `null` is refused. Not in the fingerprint. |

**Validation and properties**
- An invalid body (unknown or missing field, wrong `eventType`) dead-letters `validation` at once, with no `processed_request` row; the outcome is message-level only (DV-16, A2-55).
- Message properties: `messageId = RESULTS:{requestId}`, `contentType = application/json`, `correlationId = hearingId`.
- Application properties (not body fields): an ops replay sets `origin=REPLAY`; submit-hearing sets `origin=SUPPORT`, `operator`, `runId` and `reason`.
  - They are audit labels only; no behaviour reads `origin` (A2-68).
  - The recorded replay route is `service` (`origin=REPLAY`), `support` (`origin=SUPPORT`) or `copy` (no `origin`) (A3-34).

**Idempotency and settlement**
- Idempotency key `(source, requestId)`. The request fingerprint is a SHA-256 over `hearingId`, `hearingDay`, `sharedTime` and `eventType`. A mismatch on a known key dead-letters `collision` and leaves the row untouched (§4.4).
- Settlement: each delivery gets exactly one of complete, abandon or dead-letter. Never let the lock expire, never answer twice, and write state before settling (§4.2).
- Every duplicate reaches the guard (A3-53):
  - `COMPLETED` → no-op, complete;
  - live claim → abandon, with no delay;
  - `FAILED` → replay, if DV-26 admits it;
  - concurrent first copies → insert race; the loser backs off quietly.
- Dead-letter reasons are a closed set (A2-34):
  - `validation`;
  - `collision`;
  - `exhausted` (inherited guard paths, and the listener's final-delivery hand-backs);
  - `leg-exhausted` (final delivery with a `PARKED` leg).

## 6. Pipeline order and time budgets (§4.4, §4.7)

**Queue or replay run, in order**
1. **Admit (guard).** On first sight, insert `processed_request` (`RECEIVED`, with `command`) and the attempt-1 `outbound_message` row (`PENDING`) in one transaction; otherwise reclaim an expired claim or admit a replay. Capture `runOrigin` (`queue` or `replay`; `resend` for resend-leg); it is immutable for the run and labels its metrics, logs and new rows (A2-71).
2. **Fetch the payload** (the only pre-leg stage): `EXT_` dated key → undated key → query API. A miss everywhere → `PARKED(payload-unavailable)`. Its outcome goes only to legs eligible for this run kind (R2-7).
3. **HMPPS leg** (only if eligible, §7): read the switch → redact R0–R12 (R3/R4 when the recorded value is `ACTIVE` or `UNREADABLE_DEFAULTED_ACTIVE`) → body digest → send. There is no validation stage (H13).
4. **Record.** Write the leg outcome, plus its `outbound_body` row, in the same transaction as the request transition (A2-67, A3-63).
5. **Settle** the broker message, last.

**Resend run:** in one transaction, lock the request row, then the leg row, and insert the new claimed attempt row; commit → fetch → `sharedTime` check (before any leg stage, A2-24) → leg stages → send → token-guarded completion.

**Intake:** suspended while the store is down (`probation_intake_suspended{cause="database"}`); readiness = store only (§10.1).

| Stage | Budget | Default composition |
|---|---|---|
| Payload | 60 s | 2 Redis reads × (5 s + 5 s) + 1 query attempt (5 s + 30 s) = 55 s |
| Youth switch | 25 s | 3 × (2 s + 5 s) + 2 × 1 s = 23 s |
| Send | 130 s | (3 − 1) × (5 s + 10 s) + (5 s + 60 s) = 95 s (H27, A3-96) |
| Total | 215 s | < deadline 4 min < lease `PT4M30S` < lock 5 min; lock renewal ≥ deadline + 30 s |

**Attempt-fits rule (A2-69):** start an attempt or retry only if `wait + connect + read` fits the remaining stage deadline. Otherwise the stage ends:
- payload → `PARKED(payload-unavailable)` (on a resend, `FAILED`);
- send → `PARKED(gateway-transient)` (on a resend, `FAILED`);
- switch → `UNREADABLE(TIMEOUT)` → default (H8), on every run kind.

**Startup refusals.** Startup fails, naming the property, unless all of these hold (A2-56, A2-69, A3-96):
- a client's connect + read timeout fits its stage;
- each composed stage fits its budget;
- each back-off fits its stage;
- payload + switch + leg < deadline.

**Scale:** concurrency 2 per pod and 1–2 replicas to start (O-2), so at most 2 POSTs and 2 switch reads in flight per pod.

## 7. State machine (§4.4, §4.5)

**Request** (`processed_request.status`): `RECEIVED → RETRYING → COMPLETED | FAILED`; a replay moves `FAILED → RECEIVED`.
- `completion_reason` ∈ {`both-legs-terminal`, `recovered`}; required on `COMPLETED` (name kept with one leg, A3-7).
- `failure_reason` ∈ {`leg-exhausted`, `rolled-back`}; required on `FAILED`, NULL otherwise (A2-35). `exhausted_message_id` is required iff `leg-exhausted`.
- Completion is historical: no statement moves a `COMPLETED` row (replay, recovery, resend and rollback included) (A2-27, A2-49). The leg's truth is its latest `outbound_message` row.

**Leg** (`outbound_message.status`, one row per attempt): `PENDING`, `SENT`, `NOT_APPLICABLE`, `PARKED`, `FAILED`. `reason` is NULL iff `PENDING` or `SENT`. Reasons are a closed set and never quote payload values.

| Status | Reason | When |
|---|---|---|
| `NOT_APPLICABLE` | `sjp-or-box-hearing` | R1 emptied the payload |
| `NOT_APPLICABLE` | `nothing-left-after-redaction` | R11 left nothing |
| `PARKED` | `payload-unavailable` | Payload missed everywhere (queue or replay run) |
| `PARKED` | `gateway-transient` | 5xx, 408 or read timeout after write; or a connection failure or 429 after the in-run retries |
| `FAILED`, class 1: terminal at once, settles the request | `receiver-rejected` | 4xx other than 408/429 (400 and 401 included) |
| `FAILED`, class 1 | `rules-exception` | A rule threw (carried null guards, P19) |
| `FAILED`, class 1 | `contract-invalid` | Reserved, unused (H13) |
| `FAILED`, class 2: never completes a request | `payload-unavailable`, `gateway-transient` | Final delivery, or at once on a resend |
| `FAILED`, class 2 | `payload-changed` | Resend only (H17) |
| `FAILED`, class 2 | `rolled-back` | Rollback step 4 only |

**Settlement of a queue or replay run (§4.4)**
- Leg `SENT`, `NOT_APPLICABLE` or class-1 `FAILED` → request `COMPLETED`, message completed. The reason is `recovered` if `audit_note` contains `REPLAYED_AFTER_FAILURE`, else `both-legs-terminal` (A2-41).
- Leg `PARKED` → request `RETRYING` (`failure_reason` NULL); message abandoned.
- Final delivery with the leg `PARKED`:
  - leg → `FAILED(<same reason>)`;
  - request → `FAILED(leg-exhausted)`, with `exhausted_message_id`;
  - message → dead-letter `leg-exhausted`.
- An unreadable switch on its own never parks, fails or dead-letters anything (H8).

**Eligibility by run kind** ("latest" = the highest `attempt`; the replay column applies while `replayed_at` is set, A2-60). Forced resend = resend with `allowTerminalSuccess`.

| Latest HMPPS row | Delivery or redelivery (`replayed_at` NULL) | Replay | Resend | Forced resend |
|---|---|---|---|---|
| None | Run (first delivery) | — | — | — |
| `PENDING` attempt 1, request claim live | Abandon | Abandon | `request-claimed` | `request-claimed` |
| `PENDING` attempt 1, request claim expired | Reclaim, re-run in place | Reclaim, re-run in place | `not-eligible` | `not-eligible` |
| `PENDING` resend row, lease live | Abandon | Not admitted (`resend-in-progress` / `REPLAY_DEFERRED`) | `resend-in-progress` | `resend-in-progress` |
| `PENDING` resend row, lease expired | Abandon | Not admitted (as above) | Reclaim in place — **conflict, §16** | as Resend |
| `PENDING` replay row, request claim live | — | The guard abandons (live claim) | `request-claimed` | `request-claimed` |
| `PENDING` replay row, request claim expired | — | Re-run in place | `not-eligible` | `not-eligible` |
| `PARKED` (attempt 1 or replay row) | Re-run in place, on every delivery | Re-run in place, on every delivery | `not-eligible` | `not-eligible` |
| `SENT` or `NOT_APPLICABLE` | Skip | Skip | `not-eligible` | New `resend` row, `override=force-resend` |
| `FAILED` (not `rolled-back`), attempt 1 | Skip | Re-run in place, once per replay message | New `resend` row | New `resend` row |
| `FAILED` (not `rolled-back`), attempt ≥ 2 | Skip | Append a `replay` row, once per replay message | New `resend` row | New `resend` row |
| `FAILED(rolled-back)`, attempt 1 | Skip | Re-run in place | `not-eligible` | `not-eligible` |
| `FAILED(rolled-back)`, attempt ≥ 2 | Skip | Append a `replay` row | New `resend` row | New `resend` row |

A resend of an ID with no request row → `unknown-request`. The whole resend run is refused while intake is paused (A2-40).

**Replay admission (DV-26, A2-60, A2-70, A2-72)**
- A `FAILED` request is admitted as a replay iff one of these holds; otherwise the delivery is re-dead-lettered `exhausted`:
  - `messageId ≠ exhausted_message_id`;
  - `exhausted_message_id` is NULL;
  - `messageId = exhausted_message_id` and the delivery count is 1.
- If any leg's latest row is a `PENDING` resend row (live or expired), the delivery is abandoned `REPLAY_DEFERRED`: the request stays `FAILED` and `replayed_at` is not set. On the final delivery it is dead-lettered `exhausted`. The exhausted-identity test runs before this check.
- The replay statement:
  - moves `FAILED → RECEIVED`;
  - clears `failure_reason` and `exhausted_message_id`;
  - sets `replayed_at = now()`;
  - appends `REPLAYED_AFTER_FAILURE prior=<failure_reason>` to `audit_note`.
- Every admitted run of a `FAILED` request is a replay.

**Re-runs (A2-39, A2-45, A2-59, A2-63, A2-64)**
- An in-place re-run (an attempt-1 row, or a `PARKED` or expired `PENDING` replay-created row) writes no `PENDING` first. One UPDATE sets `status`, `reason`, `youth_switch`, `http_status`, `gateway_request_id`, `payload_source`, `payload_shared_time`, `body_digest`, `sent_at` and `service_version`. `attempts` += 1. `note` is rewritten with `prev=<STATUS>/<reason>`, plus `rerun-by=<messageId>` on a replay re-run.
- A replay appends a new row (`max(attempt)+1`, `attempt_origin='replay'`, no leg claim) only over a latest `FAILED` row with attempt ≥ 2, and never over a `PENDING` row. A resend row is never re-run in place.
- The `rerun-by` skip applies to `FAILED` rows only: a `PARKED` row is re-run on every delivery.

**Claims and fencing (§4.4 claim contract)**
- **Attempt-1 and replay rows:** they carry no leg claim and are protected by the request claim. The leg-outcome UPDATE is guarded by `EXISTS` on the request's claim owner and token. Zero rows updated → stale-runner rejection, and the run does not settle (A2-67).
- **Resend rows:** the leg claim (`claim_owner`, `claim_token`, `claim_expires_at`, `resend_run_id`) is set at insert and committed before the POST. Completion is `WHERE id = ? AND claim_token = ?`. An expired `PENDING` resend row is reclaimed in place, keeping its attempt number. Never append while a `PENDING` attempt exists.
- **Lock order:** always the request row (`FOR UPDATE`), then the leg row (`FOR UPDATE SKIP LOCKED`). A resend refuses a request with a live claim (A2-65).
- **Recovery** (A2-38): after a resend, a `FAILED` request becomes `COMPLETED(recovered)` when its latest row is `SENT`, `NOT_APPLICABLE` or class-1 `FAILED`. Recovery runs under `SELECT … FOR UPDATE` of the request, clears `failure_reason` and `exhausted_message_id`, and appends both to `audit_note`.
- **Crash window:** a crash after a 2xx but before the row is written is re-run on redelivery. That is a genuine duplicate: it is counted and alerted (§13).
- **Rollback step 4** (A2-43, A2-51, A2-66): one transaction per `PENDING` or `PARKED` row.
  - The row → `FAILED(rolled-back)`; its `note` gets `prev=<STATUS>/<reason>` (`prev=PENDING/-`), replacing any earlier `prev=`.
  - Only `status`, `reason`, `note` and `updated_at` change, plus a fresh `claim_token` on resend rows.
  - A `RECEIVED` or `RETRYING` request → `FAILED(rolled-back)`, with its claim triple cleared. A `FAILED` request keeps its reason and id; a `COMPLETED` one is untouched.

## 8. V1 data model (§4.6, App. B)

Flyway `V1__create_processed_log.sql` creates three tables and one view. The database clock is the only time authority. The SQL vocabularies must equal the Java enums (`LegStatus`, `LegReason`, `Leg`, `AttemptOrigin`, `PayloadSource`, `YouthFilterDecision`, `CompletionReason`, `FailureReason`) and `NoteForms`; `SchemaVocabularyIT` asserts this (R1-26).

**`processed_request`** — IR's block, byte-identical, then `ALTER`s

| Column | Type |
|---|---|
| `source` | text NN |
| `request_id` | uuid NN |
| `hearing_id` | uuid NN |
| `hearing_day` | date NN |
| `shared_time` | timestamptz NN |
| `event_type` | text NN |
| `request_fingerprint` | text NN |
| `status` | text NN |
| `attempts` | integer NN, default 0 |
| `completion_reason`, `failure_reason`, `exhausted_message_id` | text |
| `audit_note` | text; append-only, one ISO-8601-stamped line per event |
| `claim_owner`, `claim_token`, `claim_expires_at` | text, uuid, timestamptz |
| `created_at`, `updated_at` | timestamptz NN, default `now()` |
| `command` (ALTER) | text NN; the admitted body exactly as received (A2-48) |
| `replayed_at` (ALTER) | timestamptz (A2-60) |

- PK `(source, request_id)`.
- `status` ∈ {`RECEIVED`, `RETRYING`, `COMPLETED`, `FAILED`}; `attempts >= 0`; the claim triple is all-or-none.
- `processed_request_exhausted_id_chk` (replaced): `status <> 'FAILED' OR failure_reason IS DISTINCT FROM 'leg-exhausted' OR exhausted_message_id IS NOT NULL`.
- `processed_request_completion_reason_chk`: `COMPLETED` ⇒ non-null and in the set; any non-null value is in the set.
- `processed_request_failure_reason_chk`: `FAILED` ⇒ non-null ∈ {`leg-exhausted`, `rolled-back`}; otherwise NULL.
- Index `processed_request_hearing_idx (hearing_id, hearing_day)`.
- `audit_note` events:
  - a replay note;
  - the replay route line, at admission;
  - submit-hearing's operator, run id and reason;
  - a `payload-changed` refusal;
  - a forced-resend reason;
  - recovery (the prior reason and the exhausted id).

**`outbound_message`** — new

| Column | Type | Rule |
|---|---|---|
| `id` | uuid NN, PK | Application-generated |
| `source`, `request_id` | text, uuid NN | FK → `processed_request`, `ON DELETE RESTRICT` |
| `leg` | text NN | `IN ('HMPPS')` |
| `attempt` | integer NN, default 1 | `>= 1`; UNIQUE `(source, request_id, leg, attempt)` |
| `attempt_origin` | text NN, default `'queue'` | ∈ {`queue`, `resend`, `replay`}; `attempt = 1` ⇔ `queue` |
| `status`, `reason` | text NN, text | Pairs as §7 |
| `attempts` | integer NN, default 0 | `>= 0` |
| `http_status` | integer | |
| `gateway_request_id` | text | Only if the gateway returns one |
| `body_digest` | text | SHA-256 hex of the exact bytes sent |
| `payload_source` | text | ∈ {`cache-day`, `cache-nullday`, `query`} |
| `payload_shared_time` | timestamptz | The `sharedTime` read, when it parses |
| `service_version` | text | Build version |
| `youth_switch` | text | ∈ {`ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`, `UNREADABLE_DEFAULTED_INACTIVE`}; tied to no reason |
| `claim_owner`, `claim_token`, `claim_expires_at` | text, uuid, timestamptz | All-or-none; present iff `resend` |
| `resend_run_id` | uuid | Present iff `resend` |
| `operator` | text | Who ran a resend |
| `override` | boolean NN, default false | True when any override was used |
| `note` | varchar(256) | 1–256 chars; closed forms only |
| `sent_at` | timestamptz | Present iff `SENT` |
| `updated_at` | timestamptz NN, default `now()` | |

**CHECKs** (names as App. B, prefix `outbound_message_`)

*Single-column rules*
- `status_reason_chk`: every reasoned branch states `reason IS NOT NULL` (R2c-2).
- `leg_reason_chk`, `youth_switch_chk` (the value set only), `payload_source_chk`.
- `sent_at_chk`.
- `sent_evidence_chk`: `SENT` ⇒ `http_status`.
- `body_digest_chk`: `SENT` ⇒ `body_digest`.
- `claim_triple_chk`, `attempt_origin_chk`, `attempt_origin_attempt_chk`.
- `claim_attempt_chk` and `resend_run_chk`: present iff `resend`.
- `note_chk`.

*Presence matrix* (A2-54, A2-61)
- `presence_payload_chk`: `payload_source` is required except on `PENDING`, `payload-unavailable` and `rolled-back` rows.
- `presence_body_digest_chk`: `body_digest` ⇔ `SENT`, `receiver-rejected` or `gateway-transient`; `rolled-back` is exempt.
- `presence_service_version_chk`: `service_version` ⇔ `body_digest`, no exemption.
- `presence_http_status_chk`: `SENT` or `receiver-rejected` ⇒ `http_status`.
- `presence_youth_switch_chk` (HMPPS): NULL on `PENDING`, `payload-unavailable` and `payload-changed`; set otherwise; `rolled-back` is exempt.

*Pairings, on every row* (A2-66): `payload_shared_time ⇒ payload_source`; `body_digest ⇒ payload_source`; `youth_switch ⇒ payload_source`; `http_status ⇒ body_digest`.

**Index:** `outbound_message_status_idx (leg, status, updated_at)`.

**`note` forms** (`NoteForms`; joined by `;`; each outcome UPDATE rewrites the whole note):
- in use: `prev=<STATUS>/<reason>`, `prev=PENDING/-`, `rerun-by=<messageId>`, `override=allow-changed-payload`, `override=force-resend`;
- reserved, unused: `baseline=none`, `contract=invalid`.

**`outbound_message_latest`** (view): `SELECT DISTINCT ON (source, request_id, leg) * FROM outbound_message ORDER BY source, request_id, leg, attempt DESC`. Dashboards, reconciliation and resend selection read it; leg state never comes from `processed_request.status`.

**`outbound_body`** — new (H20, A3-63)

| Column | Type |
|---|---|
| `outbound_message_id` | uuid NN; FK → `outbound_message(id)`, `ON DELETE RESTRICT` |
| `body` | text NN; the exact UTF-8 JSON bytes POSTed (not `jsonb`) |
| `created_at` | timestamptz NN, default `now()` |

- PK `(outbound_message_id, created_at)`; `PARTITION BY RANGE (created_at)`.
- Monthly partitions `outbound_body_YYYY_MM` on UTC month bounds, plus `outbound_body_default`. V1 creates the current month and the next two.
- Trigger `outbound_body_digest_trg` (`BEFORE INSERT OR UPDATE`, per row): refuses a body if the parent's `body_digest` is NULL or differs from `encode(sha256(convert_to(body, 'UTF8')), 'hex')`. A missing parent is left to the FK.
- A body row exists only for an attempt with a `body_digest`. It is written after the outcome UPDATE, in the same transaction.
- An in-place re-run deletes the earlier body row and inserts the new one in the same transaction. "The body" is the latest row by `created_at`.
- "Digest ⇒ body" is not a constraint (the purge removes bodies).

**Retention job (H20, A3-64)**
- `processed_request` and `outbound_message` are never purged.
- `outbound_body` is maintained daily on `distribution.body-maintenance.cron`, with the scheduler zone UTC; not `pg_cron`.
- Single runner: `pg_try_advisory_xact_lock` on a fixed key. A pod that does not get the lock skips the run; that is not a failure.
- Each run:
  - pre-creates the current month's partition and the next two;
  - drops each partition whose upper bound ≤ `date_trunc('month', (now() AT TIME ZONE 'UTC') - retention)`, using `DETACH PARTITION … CONCURRENTLY` then `DROP` (the bound is read from the catalogue, never parsed from the name);
  - deletes DEFAULT rows older than the cut-off;
  - runs each DDL under `lock_timeout` 5 s, retrying on the next run;
  - counts creates, drops and failures, and gauges the DEFAULT row count.
- Partitions are created, and so owned, by the service role.
- PostgreSQL ≥ 13 for the trigger and ≥ 14 for `DETACH … CONCURRENTLY`; both confirmed on the platform (A3-105).
- DEFAULT non-empty → alert. Runbook: detach DEFAULT, create the month, move the rows, re-attach.
- After the purge, only `body_digest`, `service_version`, the status and the metadata remain.

**Sizing floor (H28):** average body ≥ ~24 KB, so ≥ ~100 MB a day and ≥ ~20 GB held (before TOAST). The real figure comes from partition sizes after go-live.

**Data protection (H18, A3-60):** bodies hold personal data, youth defendants included. No column encryption. Access is limited to the service role and the security-cleared operations team. Bodies are never logged. The data owner and the DPIA are informed (a note, not a gate).

## 9. Outbound send rules (§3.1, §4.4, H11, H12, H15, H27)

- POST to `distribution.hmpps.url` (https) with `Ocp-Apim-Subscription-Key` (the same key value as the legacy, D4a) and `Content-Type: application/json`. No client certificate (D4b). The truststore carries the gateway's internal CA (§8.2).
- The body must contain `hearing.id`; the gateway builds the backend path from it (Q13).
- Spring `RestClient`, connect 5 s, read 60 s.
- In-run retries use `RetryPolicy`, with the retryable set fixed in code: a connection failure (never sent; connect timeout included) and 429 only. Up to 3 attempts; back-off 500 ms doubling to 10 s; `Retry-After` delta-seconds honoured, capped at `max-backoff`; attempt-fits applies.

| Gateway answer | Queue or replay run | Resend |
|---|---|---|
| Any 2xx | `SENT`, status recorded. A non-200 is logged at WARN and counted in `probation_gateway_non200_success_total{status}` | Same |
| 4xx except 408 and 429 | `FAILED(receiver-rejected)`, no retry | Same |
| 5xx; 408; read timeout after the write; I/O failure once writing started (outcome unknown) | `PARKED(gateway-transient)`, abandon. On the final delivery, `FAILED(gateway-transient)` | `FAILED(gateway-transient)` at once |
| Connection failure or 429 still failing after the in-run retries or the stage deadline | `PARKED(gateway-transient)`, then as above | `FAILED(gateway-transient)` |

- The publisher returns `SENT(status)`, `FAILED_NON_TRANSIENT(status)` or `TRANSIENT_EXHAUSTED`. It never throws past the outcome, and it hands the runner the exact bytes sent.
- Success criterion: the gateway answered 2xx. That is all `SENT` means until HMPPS confirms receipt (§10.4).
- Stored on every attempt that serialised a body (`SENT`, `receiver-rejected`, `gateway-transient`): `body_digest`, `service_version`, `http_status` (when answered), and the body in `outbound_body`. The receiver's response body is not stored. `gateway_request_id` is stored only if a header carries one (§16).
- `service_version` = `build.version` from the Spring Boot build information, read once at startup (mechanism proposed).
- Every duplicate is genuine at HMPPS:
  - a crash-window re-run;
  - a redelivery after an outcome-unknown answer;
  - a forced resend.
- Confirm an outcome-unknown `FAILED(gateway-transient)` with HMPPS before any re-send (§9.4 step 7, A3-115).

## 10. Payload source, youth switch and redaction (§4.4, §5, H2, H7, H8)

**Payload** — IR's `CachedHearingPayloadAdapter`, built with `keyPrefix = "EXT_"`
1. Redis: the dated key `EXT_{hearingId}_{hearingDay}_result_`, then the undated `EXT_` key. Lettuce; TLS with peer verification; connect 5 s, command 5 s.
2. Results query API: `GET /results-query-api/query/api/rest/results/hearingDetails/{hearingId}?hearingDate={hearingDay}`, with `Accept: application/vnd.results.hearing-information-details+json`. `CJSCPPUID` is the command's `userId` when present, else the service's system user; always the system user on a resend (DV-17, H30). Retry and timeouts per DV-23.

**Rules**
- **Miss:** the key is absent; the cached value does not parse; Redis cannot be reached (DV-07); or the query answers 2xx with an empty body, `{}` or non-JSON (DV-10). A miss everywhere → `PARKED(payload-unavailable)`; on a resend, `FAILED(payload-unavailable)`.
- **Record:** `payload_source` (`cache-day`, `cache-nullday` or `query`), and `payload_shared_time` when `sharedTime` parses.
- **Forms:** the cache form is `{hearing, cpsProsecutorIds, policeCases, sharedTime}`; the fallback form is `{hearing, sharedTime}` with a different field set. Both are redacted and sent as they come; a fallback body has no `cpsProsecutorIds` (Q19 closed). `EXT_` keys live about 91 days. A resend after that uses the fallback form and proceeds when `sharedTime` matches (H17).

**Youth switch** — `FeaturePermissionClient` (A3-11, A3-13, DV-24)

*When and how*
- **When:** per message, on every run kind (queue, replay, resend). It is the first step of the HMPPS leg: after the payload fetch, before redaction.
- **Request:** `GET {users-groups base}/usersgroups-query-api/query/api/rest/usersgroups/permissions?key={youth-filter-key}`, with `Accept: application/vnd.usersgroups.get-permissions-by-feature-key+json`. `CJSCPPUID` is the service's system user (H30).

*Decision*
- **Decision (JavaScript semantics):**
  - `permissions[0].active` truthy → `ACTIVE` (remove youth);
  - a non-null `permissions[0]` whose `active` is falsy or absent (a non-object included) → `INACTIVE`;
  - anything else → `UNREADABLE(cause)`.
- **`UNREADABLE` cases:**
  - an HTTP failure after the retries;
  - a timeout (the last attempt, or the 25 s stage);
  - an empty or missing list;
  - a `null` `permissions[0]`;
  - an unparseable body.
- **Causes** (`YouthSwitchUnreadableCause`, proposed): `HTTP_FAILURE`, `TIMEOUT`, `EMPTY_PERMISSIONS`, `UNPARSEABLE` (mapping gap, §16).
- **Only `permissions[0]` decides** (P17 carried). More than one permission → `probation_youth_switch_ambiguous_total`, with no change in behaviour.
- **Retry** (legacy, `LegacyGetRetry`): at most 3 attempts with a fixed 1 s wait. Retry only an error with no response, or a status above 429; no `Retry-After`. A 2xx with an unusable body is not retried; it is `UNREADABLE` at once. Timeouts 2 s / 5 s (DV-24).

*Unreadable handling (H8)*
- **`UNREADABLE`** → apply `distribution.youth-switch.default-when-unreadable` (default `ACTIVE`):
  - record `youth_switch = UNREADABLE_DEFAULTED_<value>`;
  - R3/R4 run for `ACTIVE` and `UNREADABLE_DEFAULTED_ACTIVE`;
  - log at WARN with the cause;
  - count `probation_youth_switch_unreadable_total{cause, defaulted}`;
  - the leg carries on, and its outcome is the send's.
- **The default must equal the environment's live `EMYouthFilter` reading.** Check it at cutover and after any change to the live toggle (A3-24, A3-29). It is logged at startup.
- **The service never reads `ProbationResultsDistributionService`** (H26).

**Redaction** — `HmppsRedaction`, a pure function (§5.1, §5)
- Deep copy on entry; then R0–R12, verbatim and in the legacy order.
  - JavaScript truthiness: an empty array is truthy; null, absent, `false`, `0` and `""` are falsy.
  - Jackson tree with `USE_BIG_DECIMAL_FOR_FLOATS`. There is no R2.
- The rules:
  - R0 drops `policeCases`.
  - R1: SJP, box or virtual-box → `{}`.
  - R3 (`ACTIVE`) drops youth defendants and any case left empty.
  - R4 (`ACTIVE`) keeps an application only if `subject.masterDefendant` is truthy and not a youth (P18 carried).
  - R5–R7 drop LAA references.
  - R8 drops judiciary.
  - R9 drops associated persons, at top level and on prosecution-case defendants.
  - R10 drops delegated powers, on prosecution-case offences only.
  - R11: nothing left → `{}`.
  - R12 (logging the payload) is not ported (DV-09).
- Output `{}` → `NOT_APPLICABLE(sjp-or-box-hearing)` (R1) or `NOT_APPLICABLE(nothing-left-after-redaction)` (R11).
- A throw → `FAILED(rules-exception)`, logged at ERROR and counted; nothing is sent.

## 11. Operator actions (§4.8, H9, H16)

**Exposure:** an `/operations/**` API copied from YOT, behind its five gates:
- an internal ingress or APIM route;
- the gateway stripping and injecting `CJSCPPUID`;
- an Istio AuthorizationPolicy and a NetworkPolicy;
- users-groups reachable for the auth filter;
- an Artemis audit connection.

The API is decided for replay and resend-leg (H9, A3-35); the other actions are recommended but open (O-1). Every action is also a command-line runner in the image, as the fallback, running under the pod's identity.

| # | Action | Rules and guards |
|---|---|---|
| 1 | List requests | Read-only, by hearing and day, or by the latest HMPPS status, reason and `updated_at` range (`outbound_message_latest`). This is how resend IDs are chosen. |
| 2 | Replay | `FAILED` requests only. Refuses `resend-in-progress` if any leg's latest row is a `PENDING` resend row, live or expired (checked under the request-row lock). Sends `processed_request.command` byte for byte, under a fresh `messageId`, with `origin=REPLAY`; never from the DLQ. A `COMPLETED` request → no-op. |
| 3 | Resend-leg | Inputs: `requestIds` (1 … `max-ids-per-run`, duplicates collapsed; over the cap, the whole list is refused), leg `HMPPS`, `operator` and a reason. Optional: `allowTerminalSuccess` (forced resend; `--force-resend-sent` on the runner) and `allow-changed-payload`. The whole run is refused while intake is paused. Each ID runs in its own transaction and either proceeds or is refused `unknown-request`, `not-eligible`, `resend-in-progress` or `request-claimed`. The report, and one structured log line, list every ID. A forced-resend reason is sanitised, ≤ 200 chars, and appended to `audit_note`. The row records `operator`, `override` and the note. |
| 4 | Pause / resume intake | Through `ConsumerLifecycleController`. A pause sets `probation_intake_suspended{cause="operator"}`. After a rollback, resume only at the end of roll-forward: purge → reconcile since the cut-off → a full new cutover → resume (A2-52, A3-117). |
| 5 | Submit-hearing | Input: `hearingId` + `hearingDay`. Reads the `EXT_` dated key, then the undated one (Redis only; absent → refuse `payload-unavailable`). `sharedTime` is the raw JSON string from the payload, never parsed and re-rendered, and never taken from `shared_time`. Computes `requestId` with `RequestIdRecipe` *(proposed)*. Enqueues a normal command (`source=RESULTS`, no `userId`, `messageId=RESULTS:{requestId}`, `correlationId=hearingId`) with `origin=SUPPORT`, `operator`, `runId` and `reason`. Audited on admission; processed as a queue delivery (`origin=queue` in metrics), or as a replay if it meets a `FAILED` request (DV-26). |
| 6 | Purge-queue | Only while intake is paused. Records the count removed and the cut-off instant. |
| 7 | Flag check (optional) | Diagnostic only, never a gate. It would show the users-groups copy, not what results reads (A3-92). |
| — | `dlq-completed` listing (recommended) | Peeks the DLQ and lists entries whose request is now `COMPLETED`, for removal (A3-33). |

Refusal codes are API and report codes only, never row values. Resend runner: `resend-leg --leg HMPPS --request-ids <id,…> --operator <name> --reason <text>`.

**Resend payload check (H17, A3-57):** parse the payload's ISO-8601 `sharedTime` (honouring the offset or zone) and compare it with the stored `timestamptz`, at the stored precision, directly after the fetch. This applies to normal and forced resends alike.

**DLQ runbook (A3-32, A3-33, A3-53)**
- **Resend:** `leg-exhausted` and `exhausted`. A copy of an admitted request replays; a message never admitted is processed as a first delivery.
- **Never resend:** `validation` (fix it at source) or `collision` (investigate).
- **Remove after completion:** once the replayed request is `COMPLETED`, remove the original and any re-dead-lettered copy. An `exhausted` entry whose request is `COMPLETED` is duplicate-copy noise: remove it, do not resend it.
- **Same-id copies:** a same-id copy has one admission chance. If its first delivery is abandoned, it is re-dead-lettered; resend again later or replay through the API.
- **Investigations:** read `outbound_body` within the 6-month window.

## 12. Configuration keys (§8.1, App. A)

A `distribution.*` property not listed here, or among the scaffold additions below the table, does not exist. Startup fails, naming the property, on any check below, on the range checks, or on the composed-stage checks of §6.

| Property | Env var | Default | Startup check |
|---|---|---|---|
| `distribution.results.base-url` | `RESULTS_BASE_URL` | — | Required, absolute |
| `distribution.results.system-user-id` | `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID` *(proposed, H30)* | — | Required UUID |
| `distribution.results.connect-timeout` / `read-timeout` | — | 5 s / 30 s | > 0 |
| `distribution.results.max-attempts` / `initial-backoff` / `max-backoff` | — | 4 / 500 ms / 20 s | ≥ 1; > 0; ≥ initial (DV-23) |
| `distribution.usersgroups.base-url` | `USERSGROUPS_BASE_URL` | — | Required, absolute |
| `distribution.usersgroups.system-user-id` | `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID` *(proposed, H30)* | — | Required UUID |
| `distribution.usersgroups.youth-filter-key` | `EM_YOUTH_FILTER_FEATURE_KEY` | `EMYouthFilter` | Non-blank |
| `distribution.usersgroups.connect-timeout` / `read-timeout` | — | 2 s / 5 s | > 0 (DV-24) |
| `distribution.usersgroups.max-attempts` / `retry-wait` | — | 3 / 1 s | ≥ 1; > 0 (A3-13) |
| `distribution.youth-switch.default-when-unreadable` | `YOUTH_SWITCH_DEFAULT_WHEN_UNREADABLE` *(proposed)* | `ACTIVE` | `ACTIVE` \| `INACTIVE`; logged at INFO at startup |
| `distribution.resend.max-ids-per-run` | — (§8.1); App. A proposes `RESEND_MAX_IDS_PER_RUN`; not bound (§16) | 100 | ≥ 1 |
| `distribution.body-retention` | — (§8.1); App. A proposes `BODY_RETENTION`; not bound (§16) | `P6M` | Whole months, ≥ `P1M` |
| `distribution.body-maintenance.cron` | — (§8.1); App. A proposes `BODY_MAINTENANCE_CRON`; not bound (§16) | `0 15 2 * * *` (scheduler zone UTC) | Valid cron |
| `distribution.hmpps.url` | `HMPPS_PUBLISH_URL` | — | Required, https |
| `distribution.gateway.subscription-key` | `GATEWAY_SUBSCRIPTION_KEY` (Key Vault) | — | Required, non-blank |
| `distribution.gateway.connect-timeout` / `read-timeout` | — | 5 s / 60 s | > 0 (H27) |
| `distribution.gateway.max-attempts` / `initial-backoff` / `max-backoff` | — | 3 / 500 ms / 10 s | ≥ 1; > 0; ≥ initial; the set is fixed in code |
| `distribution.budget.payload` / `youth-switch` / `leg` | — | 60 s / 25 s / 130 s | > 0; their sum < deadline |
| `distribution.redis.host` / `port` / `key` | `REDIS_HOST` / `REDIS_PORT` / `REDIS_KEY` (Key Vault) | — / 10000 / — | Required |
| `distribution.redis.ssl` / `verify-peer` | — | `true` / `true` | Both true outside the `local` profile (DV-14) |
| `distribution.redis.connect-timeout` / `command-timeout` | — | 5 s / 5 s | > 0 |
| `distribution.servicebus.namespace` / `queue-name` | `SERVICEBUS_NAMESPACE` / `SERVICEBUS_QUEUE` | — / `probation.requests` | `namespace`: required in deployed environments; local and CI set the connection string instead (exactly one of the two; scaffold addition below). `queue-name`: required |
| `distribution.servicebus.max-concurrent-calls` | — | 2 | ≥ 1 |
| `distribution.servicebus.max-delivery-count` | — | 5 | Equals the queue's |
| `distribution.servicebus.lock-duration` | — | 5 min | Equals the queue's; > lease |
| `distribution.servicebus.max-auto-lock-renew-duration` | — | 5 min | ≥ deadline + 30 s |
| `distribution.servicebus.health-staleness` | — | 60 s | > 0 |
| `distribution.store.probe-interval` | — | 10 s | > 0 |
| `distribution.claim.lease` / `processing-deadline` | — | `PT4M30S` / 4 min | Deadline < lease < lock |
| Workload identity | `AZURE_CLIENT_ID`, `AZURE_TENANT_ID` *(proposed)*, `AZURE_FEDERATED_TOKEN_FILE` | — | Required outside `local` |
| Datasource | `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` (Key Vault) | — | Required |
| Telemetry | `APPLICATIONINSIGHTS_CONNECTION_STRING` (Key Vault) | — | — |

Not in this release: `distribution.contract-validation.mode` / `CONTRACT_VALIDATION_MODE` (H13); `distribution.feature.connection-string` / `label` (H22); `distribution.vep.*` (H5).

**Scaffold additions, not in the design catalogue (§8.1):**
- `distribution.servicebus.connection-string` (`SERVICEBUS_CONNECTION_STRING`): local and CI only, the emulator's development string. Exactly one of it and `namespace` is set; startup refuses anything else.
- `distribution.consumer.enabled` (default `true`): the intake master switch; `false` in the `test` profile.
- `distribution.redis.key-prefix` (`EXT_`): the design's `keyPrefix` constructor argument (§10), as a property.

## 13. Observability (§10, H33, H34)

**Metrics**

Label values come only from the closed sets below. A label never carries a hearing id, a payload value or a user id.

| Metric | Type | Labels (closed values) |
|---|---|---|
| `probation_processed_total` | counter | `status` (final request status) |
| `probation_leg_outcomes_total` | counter | `leg`=`HMPPS`; `status`; `reason`; `origin` ∈ queue / replay / resend (= `runOrigin`); `forced` ∈ false / true (true only with `resend`); `shape` ∈ application-only / case *(proposed)*; `http` ∈ 200 / 2xx-other / 400 / 401 / 408 / 429 / 4xx-other / 5xx / none *(proposed)* |
| `probation_parked_total` | counter | `leg`, `reason` |
| `probation_deadlettered_total` | counter | `reason` ∈ validation / collision / exhausted / leg-exhausted |
| `probation_gateway_request_duration` | timer | `leg` |
| `probation_youth_switch_state` | gauge | `state` ∈ the four `youth_switch` values |
| `probation_youth_switch_unreadable_total` | counter | `cause` ∈ HTTP_FAILURE / TIMEOUT / EMPTY_PERMISSIONS / UNPARSEABLE; `defaulted` ∈ ACTIVE / INACTIVE |
| `probation_youth_switch_ambiguous_total` | counter | — |
| `probation_payload_source_total` | counter | `source` ∈ cache-day / cache-nullday / query |
| `probation_oldest_non_terminal_age`, `probation_oldest_parked_age` | gauge | — |
| `probation_servicebus_up` | gauge | — (never part of readiness) |
| `probation_replay_deferred_total` | counter | — |
| `probation_replays_total` | counter | `route` ∈ service / support / copy |
| `probation_resend_refusals_total` | counter | `reason` ∈ unknown-request / not-eligible / resend-in-progress / request-claimed |
| `probation_body_partitions_created_total`, `probation_body_partitions_dropped_total` | counter | — |
| `probation_body_maintenance_failures_total` | counter | `step` ∈ lock / create / drop |
| `probation_body_default_partition_rows` | gauge | — |
| `probation_gateway_non200_success_total` | counter | `status` ∈ 201 / 202 / 204 / other |
| `probation_gateway_recovered_total` *(proposed)* | counter | `after` ∈ 429-in-run / 5xx-redelivery; counted when it happens |
| `probation_crash_reruns_total` *(proposed)* | counter | `leg`; a redelivery that re-runs a `PENDING` attempt-1 row with an expired request claim |
| `probation_intake_suspended` | gauge 0/1 | `cause` ∈ database / operator |
| `probation_{processing_failures, settlement_failures, lock_loss, stale_runner_rejections, intake_suspensions}_total` | counter | — (inherited) |
| `probation_flag_read_failures_total` *(proposed; results side)* | counter | — |

Not built: `probation_contract_violations_total` (H13). DLQ depth comes from the Azure Monitor `DeadletteredMessages` metric, not from service code. Readiness = the store only (§1.5, §10.1).

**Logs (§10.2)**
- Structured JSON (Logstash encoder) with MDC fields `requestId`, `hearingId`, `hearingDay`, `source`, `sequenceNumber`, `deliveryCount` and `leg`. Never `userId`.
- No payload or defendant data at INFO or above. The stored body is never logged at any level, and never put in a metric (DV-09, A3-60).
- One line per leg outcome, with:
  - status, reason, HTTP status and body digest;
  - `runOrigin`;
  - `route` (on a replay) and `forced=true` (on a forced resend);
  - `youth_switch`, and the cause on a defaulted read.
- A defaulted switch read and a non-200 2xx are logged at WARN.
- Every failure path writes a sanitised ERROR line and a metric.
- The privacy tests are copied from the sibling repos: `TelemetryPrivacyTest` and `LogStatementSweepTest`.

**Alerts (page)** — every alert reads all origins (R1-31)

| Alert | Series | Condition |
|---|---|---|
| DLQ not empty | `DeadletteredMessages` | > 0 |
| Leg parked too long | `probation_oldest_parked_age` | > 30 min |
| Silent drop | `probation_oldest_non_terminal_age` | > 30 min |
| Leg failed | `probation_leg_outcomes_total{status="FAILED", reason=~"gateway-transient\|payload-unavailable\|rules-exception\|payload-changed\|rolled-back"}` | Any. Runbook: for an outcome-unknown `gateway-transient`, check with HMPPS before any re-send. `payload-changed` and `rolled-back` stay until Q28 is decided |
| Youth switch unreadable | rate of `probation_youth_switch_unreadable_total` | Non-zero for > 5 min; also re-check the default against the live toggle |
| Repeated 401s | `probation_leg_outcomes_total{reason="receiver-rejected", http="401"}` | > 3 in an hour (proposal) |
| Send re-run after a crash | `probation_crash_reruns_total` | Any increase; record it for HMPPS |
| Case-hearing 400 spike | daily `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` | > 2 × the normal daily level (proposal) |
| Body partition maintenance | `probation_body_default_partition_rows`; `probation_body_maintenance_failures_total` | DEFAULT non-empty, or any create or drop failure |
| Results switch unreadable | results' `probation_flag_read_failures_total` | Any increase |
| Legacy app active after cutover | The legacy function app's telemetry for the environment: HMPPS send lines and the orchestration-start trace (§9.5 row 9) | Any count > 0 after cutover. Scheduled query every 15 min over 15 min (proposal). Lapses at decommissioning |

**Counted, not paged** (dashboard and daily reconciliation; A3-116)
- application-only 400s (`shape="application-only"`);
- case 400s (`http="400"`, `shape="case"`), with the spike alert above;
- 500s recovered by a later delivery, and 429s recovered in-run (`probation_gateway_recovered_total{after}`);
- non-200 2xx (`probation_gateway_non200_success_total`).

Thresholds are proposals, tuned after go-live.

**Dashboard and reconciliation (§10.4, §9.4)**
- Per day: `SENT`, `NOT_APPLICABLE` by reason, `FAILED` by reason, and `PARKED` and `PENDING` in flight — against results' share count.
- Read only from `outbound_message_latest`.
- A DLQ entry with no `processed_request` row is a class of its own. Repair it by a DLQ resend (for `exhausted`), else by submit-hearing.
- During the soak, the daily reconciliation also checks that the legacy function app shows zero HMPPS sends and zero orchestration starts since cutover.
- P36 stays open until the alert rules, their routing, an owner and a runbook are deployed (§10.3).

## 14. Producer (results) contract (§4.3, H26, A2-25)

| Item | Interface fact |
|---|---|
| Where | `HearingResultedEventProcessor`, on the non-SJP `Hearing_Resulted` branch only. It runs after the `EXT_`/`INT_` cache writes, the Event Grid publish and `add-hearing-result-for-day`. |
| Gate | `FeatureControlGuard.isFeatureEnabled("ProbationResultsDistributionService")`, read from App Configuration under the environment's label. Fail-closed: a missing entry or a failed read = off, and nothing is published. A failed read is counted (`probation_flag_read_failures_total`, to be added) and alerted. |
| Flip latency | Results sees a flip within ≤ 10 min (the feature-cache refresh); cutover and rollback wait ≥ 10 min (A3-103). |
| Users-groups copy | The static-data patch and the platform sync are for the UI only; results does not depend on them (H24, H26). |
| `userId` gate | No send when the envelope has no `userId`. |
| Chain | Command `results.command.request-probation-distribution-publish` → `ProbationDistributionHandler` → event `results.events.probation-distribution-publish-requested` → `ProbationDistributionPublishRequestedProcessor` → `ProbationDistributionQueuePublisher` (JNDI `probationDistributionQueueNamespace` and `probationDistributionQueueName`; workload identity). |
| Failures | Sender configured but the send fails → ERROR and throw, so Artemis redelivers and then dead-letters. Sender not configured → WARN and return. `ProbationDistributionQueueConfigurationHealthcheck` fails when the flag is on and no queue is configured. |
| `requestId` | `UUID.nameUUIDFromBytes(hearingId + "\|" + hearingDay + "\|" + sharedTime)` over the raw JSON strings, never parsed and re-rendered (A2-4). |
| Test vector | `0aa5bf35-1e51-45e5-9e42-64bd57e15c11` \| `2026-08-19` \| `2026-08-19T16:42:07.512Z` → `e74ed270-0b62-36d9-bb10-12bd6baa5bea` (results' helper unit test; re-take it when Q20 lands). |
| Helper | The service re-implements the recipe (`RequestIdRecipe`, proposed) and pins it with the vector; there is no build dependency on a results artefact (A2-25). |
| Properties | `messageId = RESULTS:{requestId}`; `correlationId = hearingId`; `contentType = application/json`. |
| Republish / re-share | A republished share carries the same `requestId` and `messageId`. A re-share has a new `sharedTime`, so a new `requestId`, so it is new work. |
| Ordering | The `EXT_` write is attempted before the command, but that is not a presence guarantee: a failed cache write is logged and the command still sent (A2-14). |
| Not covered | The older `public.hearing.resulted` path (unused in production) produces no command (A3-107). A reference-data failure inside results' handler produces neither `EXT_` nor the command (A2-14). |

## 15. Platform prerequisites and go-live gates (§8.4, §9.2)

| Ask | Prerequisite |
|---|---|
| 1 | Queue `probation.requests` plus DLQ per environment: duplicate detection off, lock 5 min, `maxDeliveryCount` 5 (H14). |
| 2 | Service workload identity: Service Bus Data Receiver on the namespace, Data Sender on `probation.requests`, and Key Vault Secrets User. No App Configuration role. |
| 3, 4 | Producer: results-service holds Data Sender, confirmed per environment; JNDI queue namespace and name set per environment. |
| 5 | Database `probationresultsdistribution` on the environment's Results PostgreSQL server, created by the platform's dbsetup job and owned by the service role (A3-105). Flyway runs in-service. PostgreSQL ≥ 13 / ≥ 14 (confirmed). |
| 6 | Key Vault through the Secrets Store CSI driver: the gateway subscription key (same value as the legacy), the Redis key, the datasource credentials, the system user id and the App Insights connection string. |
| 6a | Network path pod → users-groups query API; the system user permitted on the permissions query. |
| 7 | App Configuration flag `ProbationResultsDistributionService`, off, under every environment label. A users-groups static-data patch creating the feature and permission (title "Probation Results Distribution Service", action `COMPONENT`), inactive. Re-apply after every STE rebuild (H24, H26). |
| 9 | Network path pod → gateway, with the gateway's internal CA in the Java truststore. |
| 12, 14 | The ops-API gates (§11), including an authored NetworkPolicy. |
| 15 | DevOps rights to change the legacy subscription's filter, by hand on the cutover ticket. |
| 16 | Own system user: a static-data patch adding the user with the system-users role, and its id in Key Vault (`PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID`, proposed). Re-apply after STE rebuilds (H30). |

Withdrawn: asks 8, 10, 11 and 13.

**Go-live gates that need build support (§9.2)**

| Row | Gate |
|---|---|
| 1 | One real SIT share travels producer → queue → service: a `processed_request` row and one HMPPS row, with SIT's switch on. |
| 3 | The service is deployed and readiness is UP. |
| 4 | The SIT-data unit tests are green, with only registered deviations. |
| 5 | The STE replay report shows no unexplained differences. STE's `EMYouthFilter` is checked to be inactive first. |
| 7 | The switch is readable from the pod (the row's `youth_switch` equals the legacy's logged reading), and the default property equals the live reading (startup log). |
| 9 | A test call from the pod is accepted on the HMPPS route, giving a `SENT` row with its status. |
| 12 | `hearing.id` is present in every corpus body. |
| 21 | SIT negative tests: a failed send is redelivered up to `maxDeliveryCount`, then dead-lettered; a DLQ resend is admitted and processed. |
| 23 | The service's own system user is in place. |

Operations only: rows 2, 8 and 22. G1 is parked; the other rows are closed or answered.

## 16. Open items that block or constrain implementation

**Block a slice**

| Id | Open item | Blocks |
|---|---|---|
| O-1 | HTTP exposure of list, pause/resume, submit-hearing, purge-queue, the flag check and `dlq-completed` is undecided; the CLI runners are the fallback | Ops-API slice |
| §4.4 (c) vs A3-56 | **Design conflict:** an expired `PENDING` resend row is "reclaimed in place" (claim contract (c), §4.5, A2-72) but also refused `resend-in-progress` "live or expired" (A3-56, §4.4 Selects) | Resend slice |
| §4.4 / A2-28 | **Design gap:** H8 lists a `null` `permissions[0]` as `UNREADABLE`, but none of the four causes is mapped to it | Switch metric label |
| Q28 | Whether `FAILED(payload-changed)` and `FAILED(rolled-back)` page; until decided they stay on the leg-failed alert | Alert rules |
| Q20 / D9 | The landing order of the two results branch chains, and a shared or forked `requestId` helper; re-take the vector when it lands | Producer increment; `RequestIdRecipe` vector |
| A3-78 | STE replay mechanics (what is injected; where STE gets SIT `EXT_` payloads). Export the chosen week's logged bodies and payloads at once | Readiness slice (row 5) |
| §10.4 | The source of results' daily share count for reconciliation is not established (results team) | Reconciliation, dashboard |
| A3-115 | How to count a crash during a `PARKED` re-run or a replay's `FAILED` re-run (only `PENDING` attempt-1 re-runs are counted) | `probation_crash_reruns_total` |
| §9.4 step 4 | **Design gap:** the close-in-flight-rows statement is specified and pinned (`RollbackFencingIT`), but no §4.8 action or runner executes it | Recovery / ops slice |
| §6.1 / A3-67 | **Design gap:** the switch-`INACTIVE` expected output of a fallback-form corpus case is undefined. SIT's logged body is the body of the form the legacy read at the time, normally the cache form (a fallback body has no `cpsProsecutorIds` and a different `hearing` field set, §10), and A3-67 executes the legacy only for `ACTIVE` and synthetic cases | Parity kernel (fallback-form cases) |

**Not established — a default stands, so nothing is blocked**
- Q13: the harness check that `hearing.id` survives redaction over the corpus (row 12).
- §11.3: which header, if any, carries a gateway request id; known after the row-9 test call. `gateway_request_id` stays nullable.
- §4.4 / DV-10: whether the legacy passed on a non-JSON 2xx query body. If it did, treating it as a miss takes DV-27.
- A2-8: the `ApiHearing` defaults for `isSJPHearing` and `isVirtualBoxHearing`. Corpus case 1 stays synthetic.
- §6.3: whether the legacy fixtures already cover cases 8, 9, 11 and 12; build synthetic cases if not.
- §4.2: what `{stack}` renders to outside STE. §8.4 ask 12: where the NetworkPolicy manifests live (platform team).
- A3-75: the "legacy app active" alert query is proposed, not designed; telemetry resources for environments other than SIT and PRD are not established.
- A3-89: the timeout of results' `FeatureControlGuard` call (results side).
- P36 / §10.3: whether counting 400s instead of paging satisfies the legacy alert-on-failure ticket.
- H5: business confirmation that VEP is not needed.
- A3-56: where a normal (unforced) resend's free-text reason is recorded is not stated. A forced resend's goes to `audit_note`.
- §4.4: where the body-retention job sits in the package tree. The scaffold keeps the `outbound_body` writes in `persistence` behind `OutboundLog`; the first spec that needs the job places it.
- §8.1 vs App. A: whether `distribution.resend.max-ids-per-run`, `distribution.body-retention` and `distribution.body-maintenance.cron` bind an environment variable. §8.1 gives none and App. A proposes names (§12); the scaffold binds none.
- Which sections of the Confluence page already carry the rev 3.6 text. Where one lags, the rev 3.6 decision applies (§1).

**Design text that contradicts its own decisions — read by the decision**
- §1.1 "every failure is counted and alerted" → H34: alerts page only for genuine issues; known behaviour is counted.
- §4.1 identity table, "operations API recommended, open" → H9: decided for replay and resend-leg.
- §10.1 `probation_processed_total` note (rev 3.1: an unreadable switch counts as `FAILED`) → H8: an unreadable switch never fails a request.
- §8.2 gives the Service Bus sender role to submit-hearing only → the replay action enqueues too (H9).
