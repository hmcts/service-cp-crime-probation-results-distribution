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

Each active row carries the full FR-001-034 field set: the legacy behaviour (with its
`file:line` citation at `122e2152` where the design gives one), the new behaviour, the reason, the
approval (by role and decision id), whether it changes the HMPPS body, a derivation rule for a
deviation that changes an expected outcome (only DV-25 in this release), the pinning test and
whether the behaviour is built. Withdrawn, unallocated and reserved ids carry only what applies.

| DV | Status | Defects | Legacy behaviour (@122e2152) | New behaviour | Reason | Approval (role; decision) | Changes body? | Derivation | Pinning test | Built? |
|---|---|---|---|---|---|---|---|---|---|---|
| DV-01 | Withdrawn (H5) | P08 | — | VEP leg not built; no VEP projection, sender, schema or flag | — | — | — | — | — | n/a |
| DV-02 | Withdrawn | — | — | An unreadable youth switch sending nothing (P16) is fixed by DV-25, not by a separate deviation | — | — | — | — | see DV-25 | n/a |
| DV-03 | Withdrawn | — | — | `permissions[0]` decides the switch (P17 carried); no multi-permission reconciliation | — | — | — | — | see P17 | n/a |
| DV-04 | Active | P04 | The `PF` publisher POST has no timeouts and no retries, so a hung or dropped connection loses the send | The gateway POST has connect 5 s / read 60 s timeouts and bounded in-run retries, in-run **only** for a connection failure and 429, `Retry-After` honoured and capped | A send can hang forever or be lost to a transient blip | Design owner; D1, H11, H15, H27 | No | — | `HmppsPublisherTest` and `DistributionRunnerTest` (slice 002) | yes |
| DV-05 | Active | P05 | Reports success even when a send fails | The leg outcome reflects the receiver's real answer (`SENT`, `FAILED(receiver-rejected)`, `PARKED(gateway-transient)`) | Success-on-failure hides lost sends from operations | Design owner; D1, H15 | No | — | `EndToEndReceiverRejectedIT`, `DistributionRunnerTest` (slice 002) | yes |
| DV-06 | Active | P06 | Swallows a failed send | Failed sends are logged at ERROR and counted | A swallowed failure cannot be alerted on | Design owner; D1, H34 | No | — | `DistributionRunnerTest`, `MetricRegistryTest` (slice 002) | yes |
| DV-07 | Active | P09 | Treats a Redis outage as fatal | A Redis connection failure is a miss, falls back to the query API, then parks; on a resend it is `FAILED(payload-unavailable)` | A Redis outage must not fail a send the query API can still serve | Design owner; D1, H2 | No | — | `CachedHearingPayloadAdapterTest`, `PayloadUnavailableParkIT`, `ResultsQueryExternalHearingClientTest` (slice 002); resend half with slice 004 | yes |
| DV-08 | Active | P27 | No duplicate protection | A duplicate or republish is never re-sent; the processed-log guard is the **only** duplicate protection, broker duplicate detection off | Without a duplicate guard a redelivery sends twice | Design owner; D1, H14 | No | — | `RequestDedupeIT`, `DuplicateDeliveryIT` (slice 002) | yes |
| DV-09 | Active | P14, P15 | R12 logs the filtered payload (`PF/functions.js:22`) | R12 is **not ported**; the redaction writes no log line, and no payload or defendant data appears in logs at INFO or above | Defendant PII must never reach the logs of a public estate | Design owner; D1, H21 | No | — | `HmppsRedactionPurityTest` (this slice); `TelemetryPrivacyTest` / `LogStatementSweepTest` (slice 002) | yes |
| DV-10 | Active | P13 | An empty, `{}` or non-JSON query-API 2xx is taken as a payload | It is treated as a miss, not a payload (non-JSON pending §16) | An empty or non-JSON 2xx is not a payload and must not be sent as one | Design owner; D1 | No | — | `ResultsQueryExternalHearingClientTest` (slice 002) | yes |
| DV-11 | Unallocated | P35 | — | A payload fix that would need HMPPS's agreement; D10 is "no change". Kept so the id is never reused | — | — | — | — | — | n/a |
| DV-12 | Unallocated | P21 | — | A payload fix that would need HMPPS's agreement; D10 is "no change". Kept so the id is never reused | — | — | — | — | — | n/a |
| DV-13 | Active | P10, P11 | The Redis client does not reconnect and applies no timeouts | Lettuce client: reconnecting, with timeouts applied | A non-reconnecting client drops every send after a broker blip | Design owner; D1 | No | — | `RedisPayloadAdapterIT` (slice 002) | no |
| DV-14 | Active | P12 | The Redis TLS peer is not verified | The Redis TLS peer is verified | An unverified TLS peer is an interception risk | Design owner; D1 | No | — | `RedisTlsConfigTest` (slice 002) | no |
| DV-15 | Withdrawn (H5) | P25 | — | VEP-only; not built | — | — | — | — | — | n/a |
| DV-16 | Active | P26 | No inbound schema; a malformed or mis-typed message is part-processed | Closed inbound schema: a wrong `eventType`, a missing or an unknown field dead-letters `validation` at once, with no `processed_request` row | A malformed or mis-typed message must be rejected at once, not part-processed | Design owner; D1, H14 | No | — | `DistributionCommandSchemaTest`, `ContractValidationDeadLetterIT` (slice 002) | yes |
| DV-17 | Active | — | The fallback read uses the command's user and fails when there is none | The query-API fallback runs without a command `userId`, as the service's own system user (`CJSCPPUID`, one id, H30); on every resend the system user is used | The fallback must authenticate as the service when the command carries no user | Design owner; D1, H30 | No | — | `ResultsQueryExternalHearingClientTest` (slice 002); resend half with slice 004 | yes |
| DV-18 | Reserved | — | — | Reserved; never reused | — | — | — | — | — | n/a |
| DV-19 | Reserved | — | — | Reserved; never reused | — | — | — | — | — | n/a |
| DV-20 | Reserved | — | — | Reserved; never reused | — | — | — | — | — | n/a |
| DV-21 | Reserved | — | — | Reserved; never reused | — | — | — | — | — | n/a |
| DV-22 | Reserved | — | — | Reserved; never reused | — | — | — | — | — | n/a |
| DV-23 | Active | — | The results-query read retries 408, 429, ≥ 500 and connect/read failures (4 attempts, 500 ms doubling to 20 s) with no finite timeout | The same retry block, with timeouts 5 s / 30 s, cut by the payload budget, honouring `Retry-After` | The results read must ride out a transient upstream, bounded by the payload budget | Design owner; D1, A3-8, A3-13 | No | — | `ResultsQueryExternalHearingClientTest` (slice 002) | yes |
| DV-24 | Active | — | The users-groups read uses `LegacyGetRetry` (3 attempts, fixed 1 s, retry only an error with no response or a status above 429) with no finite timeout | The same rule, with finite timeouts of 2 s / 5 s | The users-groups read must time out rather than hang the leg | Design owner; D1, A3-13 | No | — | `FeaturePermissionClientTest` (slice 002) | yes |
| DV-25 | Active | P16 | Sends nothing when the switch read fails (`PF/index.js:29-32`) | The leg applies `distribution.youth-switch.default-when-unreadable` (`ACTIVE` unless set to `INACTIVE`), records `UNREADABLE_DEFAULTED_<value>`, redacts and sends (H8); case 13 derives its expected output from the configured-default output | An unreadable switch must not silently drop a hearing | Design owner; H8, A3-28, DV-25 | No — but sends where the legacy sent nothing (the default-state redacted body) | default ACTIVE uses the ACTIVE expectation; default INACTIVE uses the INACTIVE expectation | corpus case 13 derivation (this slice); `YouthSwitchUnreadableIT`, per cause (slice 002) | yes |
| DV-26 | Active | — (IR departure) | No dead-letter replay path | The processed-log guard admits a same-`messageId` copy of a `FAILED` request as a replay, but only at broker delivery count 1 (predicate `exhausted_message_id IS NULL OR exhausted_message_id <> :messageId OR :deliveryCount = 1`) | A dead-letter resubmission of an exhausted request must replay once, not loop | Design owner; H9, DV-26 | No | — | `DlqResubmissionReplayIT` (slice 004) | no |

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
| P07 | The gateway subscription key is the one the function app uses today (no gateway change, D4a) | `HmppsPublisherTest` (slice 002; required subscription header is sent) |
| P21 | Associated persons and delegated powers inside court applications are kept (not removed by R9/R10, which touch prosecution cases only) | corpus case `p21-application-keeps-associated` (slice 001, US4) |
| P35 | `plea.delegatedPowers` is not removed (R10 removes a judicial result's `delegatedPowers` only) | corpus case 8 (slice 001, US4) |
| P36 | The failure signal (alert-on-failure) stays open until its alert rules, routing, owner and runbook are deployed | `SelectableOutcomesTest` (slice 002; failure classes remain individually selectable); deployed alert rules (slice 006) |

## Moot defects

These legacy concerns were checked and do not require a behaviour change in this slice. Each is
kept here with the test that pins the present boundary so later gateway changes do not silently
introduce it.

| Defect | Legacy concern | Current boundary | Pinning test |
|---|---|---|---|
| P01 | A client certificate might be required by the gateway | The HMPPS publisher sends the required route and subscription headers and does not configure or send a client certificate | `HmppsPublisherTest` (slice 002) |

## Accepted risks (carried guards with no corpus case)

An accepted-risk entry is recorded here for each carried P18/P19 guard that no corpus case reaches
(design §5.3, §6.3; FR-001-027(b), FR-001-035). The `judicialResults` (R10), `courtOrderOffences`
(R7) and `subject.masterDefendant` (R4, P18) guards are reached by corpus cases 4, 5 and 3; the
three P19 guards below are the ones the synthetic blind-spot corpus does not reach where the member
is missing, and are accepted as risks until a corpus case exercises each. They remain pinned as unit
twins by `HmppsRedactionRulesFailureTest`. These entries are maintained alongside the corpus and the
whole-corpus guard-coverage check (`WholeCorpusChecksTest`) in slice 001 User Stories 4 and 5.

| Carried guard | Where the legacy reads it without a guard | Why no corpus case reaches it |
|---|---|---|
| `defendants` | R3, R5, R9, R10 read `prosecutionCase.defendants` (P19) | every corpus prosecution case carries `defendants`; a missing-`defendants` shape is pinned by `HmppsRedactionRulesFailureTest`, not by a corpus case |
| `offences` | R5 and R10 read `defendant.offences` (P19) | every corpus defendant carries `offences`; a missing-`offences` shape is pinned by `HmppsRedactionRulesFailureTest` |
| `courtOrderOffence.offence` | R7 reads each `courtOrderOffence.offence` (P19) | corpus case 5 reaches `courtOrderOffences`; the per-offence `offence` read beyond it is pinned by `HmppsRedactionRulesFailureTest` |

## Corpus status

The committed parity corpus is **synthetic-only** at this slice (slice 001), pending the external
SIT-capture dependency (`spec.md` Dependencies, External; tasks T044, T052). Every corpus case is a
synthetic payload whose `ACTIVE`/`INACTIVE` expectations were produced by running the pinned legacy
oracle at `122e2152` (`tools/oracle-harness/run-oracle.js`), never hand-written. The eleven cases
and their counts are recorded in `src/test/resources/corpus/index.json`
(`environment: SYNTHETIC`). Every case holds the `CACHE` form only; no case carries `FALLBACK` or a
SIT-logged `INACTIVE` outcome. This is **not a code defect of this slice**: real SIT hearing
payloads, in both forms, require the owning team's capture within SIT's retention windows (an
out-of-repository activity; `spec.md` Dependencies, External), and a SIT case will be substituted
for a synthetic one, where the shape exists, once that capture lands. It is, however, **not yet a
satisfied evidence gate**: FR-001-017 requires real SIT payloads in both the `CACHE` and `FALLBACK`
forms, with SIT's own logged `INACTIVE` outcomes, and that evidence does not exist until the SIT
capture lands — this slice MUST NOT be reported as fully meeting the go-live evidence gate (H21)
until it does. The build-time parity gate (SC-001-003) is green over the synthetic blind-spot
corpus, which is this slice's own scope; the one-week SIT → STE replay (slice 006) remains the
pre-cutover proof, not a build gate.
