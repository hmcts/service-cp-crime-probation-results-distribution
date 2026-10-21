# Deviation register

Every intended difference between this service and the legacy HMPPS code at
`cpp-context-azure-legalaidagency` commit `122e2152` is a numbered **DV-n** row below. The rules
and what is sent are lift-and-shift (decision D1): ported verbatim, quirks included. A difference
with a number passes; **any unregistered difference fails the build** (constitution Principle I,
design §7). Each row names the test that pins it. A golden file changes only in the same commit as
its row.

Seeded from design §7.2 as restated in [`doc/design/DIGEST.md`](design/DIGEST.md) §4. **DV ids are
never reused.** The next free id is **DV-27**. Withdrawn, unallocated and reserved ids are kept so
no id is ever reused.

A row's **Built?** column is `yes` once the behaviour and its pinning test are on the main line, and
`no` while the behaviour belongs to a later slice. A `no` row still names the test it will land
with.

## Registered deviations (DV-01 … DV-26)

| DV | Status | Defects | Legacy → new behaviour | Changes the HMPPS body? | Pinning test | Built? |
|---|---|---|---|---|---|---|
| DV-01 | Withdrawn (H5) | P08 | VEP leg not built; no VEP projection, sender, schema or flag | — | — | n/a |
| DV-02 | Withdrawn | — | An unreadable youth switch sending nothing (P16) is fixed by DV-25, not by a separate deviation | — | see DV-25 | n/a |
| DV-03 | Withdrawn | — | `permissions[0]` decides the switch (P17 carried); no multi-permission reconciliation | — | see P17 | n/a |
| DV-04 | Active | P04 | Legacy POST has no timeouts and no retries → the gateway POST has connect 5 s / read 60 s timeouts and bounded in-run retries, in-run **only** for a connection failure and 429, `Retry-After` honoured and capped (H11, H15, H27) | No | `GatewayRetryPolicyTest` / `HmppsPublisherIT` (slice 002) | No |
| DV-05 | Active | P05 | Legacy reports success even when a send fails → the leg outcome reflects the receiver's real answer (`SENT`, `FAILED(receiver-rejected)`, `PARKED(gateway-transient)`) | No | `HmppsPublisherIT` (slice 002) | No |
| DV-06 | Active | P06 | Legacy swallows a failed send → failed sends are logged at ERROR and counted | No | `FailureSignalIT` (slice 002) | No |
| DV-07 | Active | P09 | Legacy treats a Redis outage as fatal → a Redis connection failure is a miss, falls back to the query API, then parks; on a resend it is `FAILED(payload-unavailable)` | No | `PayloadSourceIT` (slice 002); resend half with slice 004 | No |
| DV-08 | Active | P27 | No duplicate protection in the legacy → a duplicate or republish is never re-sent; the processed-log guard is the **only** duplicate protection, broker duplicate detection off (H14) | No | `DuplicateDeliveryIT` (slice 002) | No |
| DV-09 | Active | P14, P15 | R12 logs the filtered payload (`PF/functions.js:22`) → R12 is **not ported**; the redaction writes no log line, and no payload or defendant data appears in logs at INFO or above | No | `HmppsRedactionPurityTest` (this slice); `TelemetryPrivacyTest` / `LogStatementSweepTest` (slice 002) | Partly — the redaction's no-log half is built (this slice); the service-wide log privacy sweep lands with slice 002 |
| DV-10 | Active | P13 | A query-API 2xx with an empty body, `{}` or non-JSON is treated as a miss, not a payload (non-JSON pending §16) | No | `ResultsQueryClientIT` (slice 002) | No |
| DV-11 | Unallocated | P35 | A payload fix that would need HMPPS's agreement; D10 is "no change". Kept so the id is never reused | — | — | n/a |
| DV-12 | Unallocated | P21 | A payload fix that would need HMPPS's agreement; D10 is "no change". Kept so the id is never reused | — | — | n/a |
| DV-13 | Active | P10, P11 | Lettuce client: reconnecting, with timeouts applied | No | `RedisPayloadAdapterIT` (slice 002) | No |
| DV-14 | Active | P12 | The Redis TLS peer is verified | No | `RedisTlsConfigTest` (slice 002) | No |
| DV-15 | Withdrawn (H5) | P25 | VEP-only; not built | — | — | n/a |
| DV-16 | Active | P26 | Closed inbound schema: a wrong `eventType`, a missing or an unknown field dead-letters `validation` at once, with no `processed_request` row | No | `InboundSchemaIT` (slice 002) | No |
| DV-17 | Active | — | The query-API fallback runs without a command `userId`, as the service's own system user (`CJSCPPUID`, one id, H30); on every resend the system user is used | No | `ResultsFallbackUserIT` (slice 002); resend half with slice 004 | No |
| DV-18 | Reserved | — | Reserved; never reused | — | — | n/a |
| DV-19 | Reserved | — | Reserved; never reused | — | — | n/a |
| DV-20 | Reserved | — | Reserved; never reused | — | — | n/a |
| DV-21 | Reserved | — | Reserved; never reused | — | — | n/a |
| DV-22 | Reserved | — | Reserved; never reused | — | — | n/a |
| DV-23 | Active | — | Results-query read: the legacy's retry block (4 attempts, 500 ms doubling to 20 s, cut by the payload budget). Retries 408, 429, ≥ 500 and connect/read failures; honours `Retry-After`; timeouts 5 s / 30 s (A3-8, A3-13) | No | `ResultsQueryRetryIT` (slice 002) | No |
| DV-24 | Active | — | Users-groups read: finite timeouts of 2 s / 5 s; everything else as the legacy `LegacyGetRetry` (3 attempts, fixed 1 s, retry only an error with no response or a status above 429) (A3-13) | No | `FeaturePermissionClientIT` (slice 002) | No |
| DV-25 | Active | P16 | Legacy sends nothing when the switch read fails (`PF/index.js:29-32`) → the leg applies `distribution.youth-switch.default-when-unreadable` (`ACTIVE` unless set to `INACTIVE`), records `UNREADABLE_DEFAULTED_<value>`, redacts and sends (H8). Case 13 derives its expected output from the configured-default output | **Sends where the legacy sent nothing** (the normal redacted body for the default state) | corpus case 13 derivation (this slice); `YouthSwitchUnreadableIT`, per cause (slice 002) | No |
| DV-26 | Active | — (IR departure) | The processed-log guard admits a same-`messageId` copy of a `FAILED` request as a replay, but only at broker delivery count 1 (predicate `exhausted_message_id IS NULL OR exhausted_message_id <> :messageId OR :deliveryCount = 1`, H9) | No | `DlqResubmissionReplayIT` (slice 004) | No |

The next free id is **DV-27**.

## Carried defects (design §7.1, carried verbatim)

These are legacy behaviours that look like defects and are **kept** because what is sent to HMPPS
does not change in this delivery (D1, D10). No null guard is added inside a rule; a rule that throws
on the payload's shape is reported as a rules failure, never guarded.

| Defect | Legacy behaviour | Pinning case / test |
|---|---|---|
| P17 | Only `permissions[0]` decides the youth switch; more than one permission is counted, with no change in behaviour | `FeaturePermissionClientTest` (slice 002) |
| P18 | With the switch `ACTIVE`, a court application whose `subject.masterDefendant` is not truthy is removed (`PF/functions.js:41-47`) | corpus case 3 (slice 001, US4); `HmppsRedactionRulesFailureTest` (slice 001, US1) |
| P19 | The rules have no null guards: `defendants` (R3, R5, R9, R10), `offences` (R5, R10), `courtOrderOffences`/`offence` (R7) and `judicialResults` (R10) are read without a guard, so a rule that meets an absent or null value throws → `FAILED(rules-exception)` | `HmppsRedactionRulesFailureTest` (slice 001, US1); `RulesExceptionIT` (slice 002) |

## Pending-external defects (design §7.1)

Carried until HMPPS agrees a change (D10). Disposition is not re-decided here.

| Defect | Legacy behaviour | Pinning case / test |
|---|---|---|
| P07 | The gateway subscription key is the one the function app uses today (no gateway change, D4a) | `GatewayConfigTest` (slice 002) |
| P21 | Associated persons and delegated powers inside court applications are kept (not removed by R9/R10, which touch prosecution cases only) | corpus case `p21-application-keeps-associated` (slice 001, US4) |
| P35 | `plea.delegatedPowers` is not removed (R10 removes a judicial result's `delegatedPowers` only) | corpus case 8 (slice 001, US4) |
| P36 | The failure signal (alert-on-failure) stays open until its alert rules, routing, owner and runbook are deployed | `FailureSignalIT` (slice 002); deployed alert rules (slice 006) |

## Accepted risks (carried guards with no corpus case)

An accepted-risk entry is recorded here for each carried P18/P19 guard that no corpus case reaches
(design §5.3, §6.3; FR-001-027(b), FR-001-035). These entries are maintained alongside the corpus
and the whole-corpus guard-coverage check in slice 001 User Stories 4 and 5; none is recorded yet.
