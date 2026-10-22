---

description: "Task list for HMPPS distribution (slice 002)"
---

# Tasks: HMPPS distribution

**Input**: Design documents from `specs/002-hmpps-distribution/`
**Prerequisites**: plan.md, spec.md, data-model.md, research.md, contracts/ (inbound-command.md, hmpps-gateway.md, consumed-reads.md), quickstart.md

**Tests**: MANDATORY (Constitution Principle II). Every user story's test tasks are ordered before the implementation tasks they guard. Each test task MUST land compile-safe seams so its recorded red run is a failing assertion, never a compile error; the paired implementation task quotes the green run.

**Organization**: Tasks are grouped by user story (spec.md priorities P1–P3) so each story is independently implementable and testable. Package layout follows plan.md's Project Structure exactly: `inbound/`, `application/`, `domain/`, `pipeline/` (slice 001, reused), `adapter/payload/`, `adapter/usersgroups/`, `adapter/gateway/`, `persistence/`, `config/`, under `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/`, mirrored under `src/test/java/...`.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on an incomplete task)
- **[Story]**: US1–US6, mapped to spec.md's priorities
- Constructor injection only, `private final` fields, Java records for DTOs/value types, no `@Autowired`, no wildcard imports, Flyway only (never Liquibase) — per `.claude/rules/technical-rules.md`

---

## Phase 1: Setup

**Purpose**: Slice-specific scaffolding that every story needs before any schema or port work starts.

- [x] T001 Vendor the closed inbound schema (design Appendix B.1) to `src/main/resources/contracts/distribution-command.schema.json`: draft-07, `additionalProperties: false`, `source`/`requestId`/`hearingId`/`hearingDay`/`sharedTime`/`eventType` required, `userId` optional but `null` rejected; only `$id`, title and descriptions differ from the informant contract (FR-002-002)
- [x] T002 [P] Create `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/inbound/package-info.java`, `application/package-info.java`, `domain/package-info.java`, `adapter/payload/package-info.java`, `adapter/usersgroups/package-info.java`, `adapter/gateway/package-info.java`, `persistence/package-info.java` stating each package's responsibility and allowed dependencies per `design_rules.md`'s package tree (`pipeline/` and `config/` already exist from slice 001)
- [x] T003 [P] Add `distribution.*` placeholder properties to `src/main/resources/application.yaml` for every group FR-002-066 names (`results`, `usersgroups`, `youth-switch`, `hmpps`, `gateway`, `budget`, `redis`, `servicebus`, `store`, `claim`) plus the scaffold additions `distribution.servicebus.connection-string`/`namespace`, `distribution.servicebus.max-delivery-count` (mirrors the queue's `maxDeliveryCount=5`, provisioned in the sibling infra repo, FR-002-001/FR-002-048), `distribution.consumer.enabled`, `distribution.consumer.concurrency` (default 2), `distribution.redis.key-prefix`, with environment-variable placeholders only, no secret values (FR-002-070)
- [x] T004 [P] Add Testcontainers, WireMock (`dynamicPort()`) and Azure Service Bus emulator test dependencies/config to `build.gradle` and `docker-compose.yml` if not already present from the slice 001 scaffold, confirmed against `research.md` R1/R8

**Checkpoint**: Schema vendored, package seams declared, configuration skeleton and test infra ready.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared core slice 002 owns for slices 003–006 — V1 schema, closed vocabularies, ports, state machine, metric/log registry, validated configuration. No user story may start before this phase is green.

**⚠️ CRITICAL**: This phase has no story label on any task — it blocks every story.

### Vocabularies and domain types

- [x] T005 [P] Write `SchemaVocabularyIT` skeleton in `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/SchemaVocabularyIT.java` asserting (initially failing to compile against non-existent enums/tables) that the database's closed value sets equal the Java vocabularies for leg status, leg reason, leg, attempt origin, payload source, youth switch, completion reason and failure reason, and that the seven note forms and `contract-invalid`/`contract=invalid` reservation hold, with `contract-invalid` itself reserved-but-unused since outbound schema validation is parked (FR-002-031, FR-002-055, data-model.md)
- [x] T006 [P] Create enums/types in `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/domain/`: `Leg` (`HMPPS` only), `LegStatus` (`PENDING, SENT, NOT_APPLICABLE, PARKED, FAILED`), `LegReason` (`sjp-or-box-hearing, nothing-left-after-redaction, receiver-rejected, rules-exception, payload-unavailable, gateway-transient, payload-changed, rolled-back`), `AttemptOrigin`/`RunOrigin` (`queue, replay, resend`, captured once at admission — FR-002-010 — and never re-derived mid-run), `PayloadSource` (`cache-day, cache-nullday, query`), `YouthSwitchUnreadableCause` (`HTTP_FAILURE, TIMEOUT, EMPTY_PERMISSIONS, UNPARSEABLE`), `CompletionReason` (`both-legs-terminal, recovered`), `FailureReason` (`leg-exhausted, rolled-back`), `DeadLetterReason` (`validation, collision, exhausted, leg-exhausted`), `NoteForm` (the seven forms, `baseline=none` and `contract=invalid` reserved) — each with a `fromValue(String)` that fails explicitly on an unknown value. Keep the switch's pre-default *decision* and its post-default *recorded* value as two separate types (do not conflate them): `YouthSwitchDecision` — a sealed interface/enum-with-cause of exactly `Active`, `Inactive`, `Unreadable(YouthSwitchUnreadableCause)`, the three-valued read result FR-002-022 describes — and `YouthSwitchRecordedValue` — the four-value persisted form `ACTIVE, INACTIVE, UNREADABLE_DEFAULTED_ACTIVE, UNREADABLE_DEFAULTED_INACTIVE` that FR-002-025 produces only after the default is applied. A single four-value type would leave no way to carry an un-defaulted `Unreadable(cause)` through the pipeline (FR-002-072, FR-002-022, FR-002-025, technical-rules.md Enums)
- [x] T007 [P] Create the rev-3.6 label-set enums in `domain/`: `HttpStatusBucket` (`200, 2xx-other, 400, 401, 408, 429, 4xx-other, 5xx, other, none`), `BodyShape` (`application-only, case`), `IntakeSuspensionCause` (`database, operator`), `Non200SuccessStatus` (`201, 202, 204, other`), `GatewayRecovery` (`429-in-run, 5xx-redelivery`), `ReplayRoute` (`service, support, copy`), `ResendRefusal` (`unknown-request, not-eligible, resend-in-progress, request-claimed`), `BodyMaintenanceStep` (`lock, create, drop`), `OperationsAction` (`replay, resend-leg`), `OperationsFailure` (`audit-unavailable, audit-response-unpublished, store-unavailable, broker-unavailable, unexpected`) (FR-002-072)
- [x] T008 [P] Create `domain/DistributionCommand.java` as a record (`source, requestId, hearingId, hearingDay, sharedTime, eventType, Optional<userId>`) and `domain/PublishOutcome.java` as a sealed interface (`Sent(status)`, `FailedNonTransient(status)`, `TransientExhausted(status)`), both with bounded fields only, no business logic; broker application properties (`origin`, `operator`, `runId`, `reason`) are not fields of `DistributionCommand` and have no accessor a `DistributionRunner` could read (FR-002-071, FR-002-004, data-model.md)

> T006–T008 are driven test-first by `domain/DomainVocabularyTest` (plain unit, no Docker). RED: with `Vocabularies.fromValue` stubbed to throw, `2 failed` (`round_trips`/`refuses` hit `UnsupportedOperationException`). GREEN: with the resolver implemented, the suite passes (checkstyle/pmd main+test clean).

### Flyway V1 schema

- [x] T009 Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/ProcessedRequestSchemaIT.java` (Postgres Testcontainers) asserting the `processed_request` table exists with IR's byte-identical block plus the `command`, `replayed_at` `ALTER`s, the replaced exhausted-ID CHECK and the completion-/failure-reason CHECKs — run first to fail (no migration yet) (FR-002-050, FR-002-051)
- [x] T010 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/OutboundMessageSchemaIT.java` asserting `outbound_message`'s full CHECK matrix from data-model.md (unique key, status/reason pairs including future `payload-changed`/`rolled-back` cells, the five presence checks, the four pairings, claim-triple/`resend_run_id` presence) — fails first (FR-002-052, FR-002-043)
- [x] T011 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/OutboundBodySchemaIT.java` asserting the partitioned `outbound_body` table (current month + next two + DEFAULT partition), the `outbound_body_digest_trg` trigger rejecting a mismatched digest, and the `ON DELETE RESTRICT` foreign key — fails first (FR-002-054)
- [x] T012 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/OutboundMessageLatestViewTest.java` asserting the view returns the highest `attempt` per `(source, request_id, leg)` — fails first (FR-002-053)
- [x] T013 Implement `src/main/resources/db/migration/V1__create_processed_log.sql` per design Appendix B.2: IR's `processed_request` block byte-identical, then the `ALTER`s; `outbound_message` with every CHECK and index from data-model.md; the `outbound_message_latest` view; the partitioned `outbound_body` with its trigger function and current+2 monthly partitions plus DEFAULT, created only if absent — makes T009–T012 pass (FR-002-050 to FR-002-054, FR-002-043)
- [x] T014 Run T005's `SchemaVocabularyIT` against V1 and extend it to cover every CHECK's accepted/rejected cells across the full matrix, including `payload-changed` and `rolled-back` — makes it pass (FR-002-031, FR-002-055)

### Application core: ports, runner skeleton, state machine

- [x] T015 [P] Define port interfaces in `src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/application/`: `HearingPayloadSource`, `YouthFilterSource`, `HmppsSender`, `OutboundLog` — each a plain interface with no Azure/Redis/HTTP/JDBC type in its signature, the hearing payload crossing only as `JsonNode` (FR-002-071, design_rules.md Ports)
- [x] T016 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/IdempotencyGuardTest.java` (plain mocks, no Spring context) covering FR-002-007's decision tree: no row → admit; lost-insert race → quiet live-claim handling; `COMPLETED` → no-op; different fingerprint → `collision`; live claim → abandon; expired claim → reclaim; `FAILED` → `exhausted` (FR-002-008 interim rule) — fails first (no class yet)
- [x] T017 Implement `application/IdempotencyGuard.java` and `application/RequestFingerprint.java` (SHA-256 over `hearingId`, `hearingDay`, `sharedTime`, `eventType`, excluding `userId`) against `persistence/ProcessedRequestRepository` (stub/interface at this point) — makes T016 pass (FR-002-005 to FR-002-008)
- [x] T018 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/DistributionRunnerTest.java` (plain mocks) asserting the fixed pipeline order of FR-002-011 (admit → fetch payload → HMPPS leg [switch → redact → digest → send] → record outcome → settle), that the runner depends only on the four owned ports, and that `runOrigin` is read once at admission and the identical value labels every row/metric/log the run produces, never re-derived mid-run (FR-002-010) — fails first
- [x] T019 Implement `application/DistributionRunner.java` as the orchestrator over the four ports, with no Azure/Redis/HTTP/JDBC import, capturing `runOrigin` once at admission and threading the same value through every row write, metric label and log line of the run — makes T018 pass (FR-002-011, FR-002-010, FR-002-071, FR-002-073)
- [x] T020 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/ClaimFencingIT.java` (Postgres Testcontainers) asserting: an outcome UPDATE guarded by expected status + claim owner/token updates zero rows when stale and writes nothing further (the `probation_stale_runner_rejections_total` increment is wired by the runner/listener in T052, US2; this IT pins only the repository-level zero-row fencing); this slice's repositories issue no `DELETE` against `processed_request` or `outbound_message` (FR-002-056) and no slice-002 code path appends to `audit_note` (FR-002-045, append-only, owned by the replay/recovery slices) — fails first (FR-002-040, FR-002-045, FR-002-056, A2-67 reading)
- [x] T021 Implement `persistence/ProcessedRequestRepository.java` and `persistence/OutboundMessageRepository.java` (claim insert/reclaim, the one-UPDATE outcome write with status+claim-token guard, the `outbound_body` insert/delete-then-insert in the same transaction), with no `DELETE` statement against either table and no write to `audit_note` — makes T020 pass (FR-002-006, FR-002-040 to FR-002-042, FR-002-045, FR-002-056)

> T015 defines the four owned port interfaces (`HearingPayloadSource`, `YouthFilterSource`, `HmppsSender`, `OutboundLog`) and their value types; it carries no test of its own — its contract is exercised by the `DistributionRunnerTest` seams (T018) that mock each port. T016/T017 are driven test-first by `application/IdempotencyGuardTest` (plain mocks, no Spring), observed by checking out the pre-implementation commit in a disposable worktree, dropping in compile-safe seams (`RequestFingerprint.compute`/`IdempotencyGuard.admit` stubbed to throw `UnsupportedOperationException`, the data-only `RunClaim`/`GuardDecision`/`ProcessedRequestRepository` shapes left real) and running the test alone. RED: `10 tests completed, 10 failed`, every one hitting `UnsupportedOperationException` at `RequestFingerprint.compute` from `setUp()`. GREEN: with the real `IdempotencyGuard`/`RequestFingerprint` swapped in against `ProcessedRequestRepository`, all 10 pass (checkstyle/pmd main+test clean).

> T018/T019 are driven test-first by `application/DistributionRunnerTest` (plain mocks, no Spring), observed the same way (a stubbed `DistributionRunner.run` throwing `UnsupportedOperationException`; the data-only `RunResult` left real). RED: `8 tests completed, 8 failed`. GREEN: with the real runner capturing `runOrigin` once at admission and threading the identical value over the four ports, all 8 pass. T020/T021 are driven test-first by `persistence/ClaimFencingIT` (Postgres Testcontainers over the shared `SchemaTestSupport`), observed the same way (stubbed `JdbcProcessedRequestRepository`/`OutboundMessageRepository` methods throwing `UnsupportedOperationException`, against the V1 schema already in place from T005/T009–T014). RED: `6 tests completed, 6 failed`. GREEN: with the real `ProcessedRequestRepository`/`OutboundMessageRepository` (and the JDBC adapter) implemented, all 6 pass; the `probation_stale_runner_rejections_total` increment is left to T052 (US2), so this IT pins only the repository-level zero-row fencing.

### Metrics, logging, configuration

- [x] T022 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/MetricRegistryTest.java` asserting every series of FR-002-057/FR-002-058/FR-002-059/FR-002-062 is registered once with its closed label set, including the proposed `probation_operations_failures_total{action, reason}` registered-but-unread, and that no label accepts an open string — fails first
- [x] T023 Implement `config/MetricRegistry.java` (or equivalent Micrometer `MeterRegistry` customizer) defining every counter/gauge/timer name and closed label set from FR-002-057 to FR-002-062 — makes T022 pass
- [x] T024 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/StartupValidationTest.java` covering FR-002-066/FR-002-067: every `distribution.*` property group validated, the composed-stage-fit checks (payload 55≤60s, switch 23≤25s, send 95≤130s, total 215<240s), claim-lease-vs-lock ordering, refusal naming the offending property, `distribution.results.system-user-id` and `distribution.usersgroups.system-user-id` resolving to the identical configured value (FR-002-068, H30), `distribution.servicebus.max-delivery-count` equal to the queue's configured `maxDeliveryCount` (FR-002-001, FR-002-048), and that no forbidden property (`distribution.*.contract-validation-mode`, any App Configuration connection string/label, `distribution.vep.*`) is present (FR-002-069) — fails first
- [x] T025 Implement the `@ConfigurationProperties` records and a startup validator (`ApplicationRunner`/`SmartLifecycle`) in `config/` enforcing T024's checks, refusing start with a named property on failure — makes T024 pass (FR-002-014, FR-002-066, FR-002-067, FR-002-068, FR-002-069, FR-002-001, FR-002-048)
- [x] T026 [P] Write `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/TelemetryPrivacyTest.java` and `LogStatementSweepTest.java` (copied-pattern privacy tests) asserting no INFO+ log statement carries payload/defendant data, the outbound body is never logged at any level/profile, and MDC never carries `userId` — fails first (FR-002-065, DV-09)
- [x] T027 Configure MDC provider fields (`requestId, hearingId, hearingDay, source, sequenceNumber, deliveryCount, leg`) and confirm `logback.xml`'s unconditional JSON encoder covers them — makes T026 pass (FR-002-063, FR-002-065)

> T005, T009–T014: the schema ITs (`SchemaVocabularyIT`, `ProcessedRequestSchemaIT`, `OutboundMessageSchemaIT`, `OutboundBodySchemaIT`, `OutboundMessageLatestViewTest`) over a shared `SchemaTestSupport` Postgres 15 Testcontainer. RED: with no V1 migration, `34 tests completed, 30 failed` (missing tables/constraints). GREEN: with `V1__create_processed_log.sql`, all pass (checkstyle/pmd test clean).

> T022/T023 are driven test-first by `config/MetricRegistryTest` (plain unit), observed the same way (a stubbed `MetricRegistry` with an empty `METRIC_NAMES` and every method throwing `UnsupportedOperationException`). RED: `5 tests completed, 5 failed`. GREEN: with every counter/gauge/timer name and closed label set defined for real, all 5 pass. T024/T025 are driven test-first by `config/StartupValidationTest` (plain unit), observed the same way (a stubbed `StartupValidator` throwing `UnsupportedOperationException` from both methods; the data-only `DistributionProperties` record tree left real). RED: `10 tests completed, 10 failed`. GREEN: with the real validator's composed-stage-fit, lease-vs-lock ordering, identical-system-user and youth-default checks in place, all 10 pass; the stricter `max-delivery-count == 5` and forbidden-App-Configuration checks were added afterwards, in the gate-round-1 remediation, each itself landing red (against the then-current `>= 1` check) before its own green. T026/T027 are driven test-first by `config/TelemetryPrivacyTest` and `LogStatementSweepTest`, observed the same way (a stubbed `MdcFields` with an empty `KEYS`). RED: `3 tests completed, 1 failed` — only `TelemetryPrivacyTest`'s assertion on `MdcFields.KEYS` fails; `LogStatementSweepTest`'s two sweeps already pass at this point, since nothing in the codebase yet carries a payload/body/`userId` in a log statement or uses the console — it is a standing regression guard, not code this task implements. GREEN: with the real MDC field set in place (and `logback.xml`'s unconditional JSON encoder already covering it), all 3 pass (checkstyle/pmd main+test clean).

**Checkpoint**: V1 schema, vocabularies, ports, runner skeleton, claim fencing, metrics and startup validation are all green. User stories can now begin.

---

## Phase 3: User Story 1 — A resulted hearing reaches HMPPS once, and the record says exactly what was sent (Priority: P1) 🎯 MVP

**Goal**: One valid command, through cache payload read, switch read, slice 001 redaction, one POST, one recorded outcome, one stored body, settled exactly once.

**Independent Test** (spec.md): a valid queued command, payload under the dated cache key, switch `ACTIVE`, stub gateway answering 200 → exactly one POST whose body equals slice 001's redaction output; request `COMPLETED(both-legs-terminal)`; attempt row `SENT`, `http_status=200`, `payload_source=cache-day`, `youth_switch=ACTIVE`, digest, build version, send time; one byte-identical stored body; message completed only after those writes.

### Tests for User Story 1 (MANDATORY — write and run these first) ⚠️

- [x] T028 [P] [US1] Contract test for the inbound schema in `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/inbound/DistributionCommandSchemaTest.java`: valid command accepted, each invalid shape (unknown field, missing field, wrong type, `eventType` other than `Hearing_Resulted`, explicit `userId: null`) rejected — fails first (FR-002-002, FR-002-003)
- [x] T029 [P] [US1] WireMock contract test for the gateway send in `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/gateway/HmppsPublisherTest.java`: 200 → `Sent(200)`, no client certificate sent, `Ocp-Apim-Subscription-Key` and `Content-Type: application/json` headers present, exact serialized bytes match what was digested — fails first (FR-002-032 to FR-002-034, FR-002-037, contracts/hmpps-gateway.md)
- [x] T030 [P] [US1] End-to-end integration test `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/EndToEndSentIT.java` (Service Bus emulator + Postgres Testcontainers + WireMock + Redis): valid command, payload under dated `EXT_` key, switch `ACTIVE`, gateway 200 → asserts the full acceptance scenario 1 outcome, byte-identical stored body hashing to the digest, message completed only after the writes, and `completion_reason = both-legs-terminal` — never `recovered`, since that branch depends on slice 004's recovery write which does not exist in this slice (FR-002-044) — fails first (US1 Acceptance Scenario 1, SC-002-001, SC-002-004, FR-002-044)
- [x] T031 [P] [US1] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/EndToEndNotApplicableIT.java`: an R1/R11-emptied payload → no POST, row `NOT_APPLICABLE` with the correct reason, no digest, no stored body, request `COMPLETED` — fails first (Acceptance Scenario 3)
- [x] T032 [P] [US1] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/EndToEndReceiverRejectedIT.java`: application-only hearing, gateway 400 → one POST no retry, `FAILED(receiver-rejected)`, body+digest stored, `shape=application-only`, `http=400`, request `COMPLETED` — fails first (Acceptance Scenario 4, D16(a))
- [x] T033 [P] [US1] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/EndToEndRulesExceptionIT.java`: a payload on which a rule throws (no null guard, P19) → no POST, `FAILED(rules-exception)`, switch value recorded but no digest, ERROR line + counter, request `COMPLETED` — fails first (Acceptance Scenario 7, P19)

> T030-T033 share `EndToEndTestSupport` (Service Bus emulator + MSSQL companion seeded with the
> committed `probation.requests` config, Postgres 15, Redis, WireMock), which wires the real pipeline
> and a peek-lock processor against the live stack. They are landed against a compile-safe
> `ProbationMessageListener` seam that completes a delivery without recording. RED (seam in place,
> `./gradlew test --tests EndToEnd*IT`): `4 tests completed, 4 failed`, every one
> `AssertionError: Expecting Optional to contain a value but it was empty` at `awaitSettledLeg` — no
> `outbound_message` row is ever written. GREEN is quoted by the paired implementation tasks: T039
> (the `OutboundLog` adapter), T040 (the redaction/metrics wiring), T041 (the shape label) and T042
> (the listener parse/guard/run/settle wiring), after which all four pass.
- [x] T034 [P] [US1] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/SupportLookupIT.java`: given a recorded leg, a lookup by hearing id and hearing day via `outbound_message_latest` returns the request, the latest attempt and the exact stored bytes hashing to the digest — fails first (Acceptance Scenario 8, SC-002-008)

### Implementation for User Story 1

- [x] T035 [US1] Implement `inbound/DistributionCommandParser.java` validating against T001's vendored schema and mapping to `domain/DistributionCommand` — makes T028 pass

> T028/T035 driven test-first by `inbound/DistributionCommandSchemaTest` (plain unit). RED: with
> `DistributionCommandParser.parse` stubbed to throw `UnsupportedOperationException`, `13 tests
> completed, 13 failed` — every case hitting `UnsupportedOperationException` where a parsed command or
> a `CommandValidationException` was expected. GREEN: with the explicit-validation parser implemented
> (closed-schema field set, required-field, type/format, closed-enum and null-`userId` checks, each
> mapped to a bounded `CommandValidationException.Kind` that never quotes a field name), all 13 pass
> (checkstyle/pmd main+test clean). The networknt schema library declared in `build.gradle` is not
> resolvable on this classpath, so the contract is pinned against the parser directly; the vendored
> `distribution-command.schema.json` remains the documented authority.
- [x] T036 [P] [US1] Implement `adapter/payload/CachedHearingPayloadAdapter.java` (dated-key read only, for this story's happy path; undated/query fallback lands in US3) implementing `HearingPayloadSource`, with `payload_source=cache-day` recorded — partial implementation toward FR-002-015

> T036 driven test-first by `adapter/payload/CachedHearingPayloadAdapterTest` (Redis GenericContainer),
> scoped to the US1 dated-key happy path; US3's T053 extends this file with the undated-key fallback
> and the full miss matrix. RED: with the constructor and `fetch` stubbed to throw
> `UnsupportedOperationException`, `4 tests completed, 4 failed` (12 `UnsupportedOperationException`
> hits across setUp+body). GREEN: with the dated-key read (`EXT_{hearingId}_{hearingDay}_result_`)
> recording `payload_source = cache-day` and the parsed `sharedTime` (empty when it does not parse),
> and treating an absent/blank/JSON-null/unparseable value as a miss, all 4 pass (checkstyle/pmd
> main+test clean).
- [x] T037 [P] [US1] Implement `adapter/usersgroups/FeaturePermissionClient.java` (happy-path `ACTIVE`/`INACTIVE` decision only; retry/timeout/unreadable branches land in US4) implementing `YouthFilterSource` per contracts/consumed-reads.md

> T037 driven test-first by `adapter/usersgroups/FeaturePermissionClientTest` (WireMock,
> `dynamicPort()`), scoped to the US1 happy path; US4's T065 extends this file with the retry/timeout/
> unreadable-cause/ambiguity matrix. RED: with the constructor and `read` stubbed to throw
> `UnsupportedOperationException`, `6 tests completed, 6 failed` (18 `UnsupportedOperationException`
> hits across setUp+body). GREEN: with the 2xx JavaScript-truthy decision over `permissions[0].active`
> (first-permission-decides, a non-object non-null first entry INACTIVE, missing/empty/null first
> entry UNREADABLE(EMPTY_PERMISSIONS), unparseable UNREADABLE(UNPARSEABLE)) and the `Accept` +
> `CJSCPPUID` headers, all 6 pass (checkstyle/pmd main+test clean). The single read maps any non-2xx
> or transport failure to UNREADABLE(HTTP_FAILURE); the legacy 3x1s retry and the TIMEOUT cause are
> US4's.
- [x] T038 [US1] Implement `adapter/gateway/HmppsPublisher.java` implementing `HmppsSender`: one serialization to UTF-8 bytes, POST with the two required headers, no redirect following, maps a 2xx to `Sent(status)` — makes T029 pass (FR-002-032, FR-002-033, FR-002-037, FR-002-039)

> T029/T038 driven test-first by `adapter/gateway/HmppsPublisherTest` (WireMock, `dynamicPort()`).
> RED: with `HmppsPublisher`'s constructor and `send` stubbed to throw `UnsupportedOperationException`,
> `4 tests completed, 4 failed` — every case hitting `UnsupportedOperationException` from the
> constructor in `setUp`. GREEN: with the publisher serialising the tree once (Jackson 3), POSTing the
> exact bytes over a no-redirect JDK `HttpClient` (connect 5 s / read 60 s) with `Content-Type:
> application/json` and `Ocp-Apim-Subscription-Key`, and mapping the answer to `PublishOutcome`
> without throwing past its outcome, all 4 pass (checkstyle/pmd main+test clean; the long-lived
> `HttpClient` carries a justified `PMD.CloseResource` suppression). The in-run connection/429 retry
> loop and the full 4xx/5xx/3xx recorded mapping belong to US5 (T080-T086); this publisher does one
> POST and already maps every answer so it never throws.
- [x] T039 [US1] Implement `adapter/persistence/OutboundLogAdapter.java` implementing `OutboundLog`: the one-UPDATE outcome write (status, reason, youth_switch, http_status, payload_source, payload_shared_time, body_digest, sent_at, service_version, note) plus the `outbound_body` insert in the same transaction — depends on T021

> The adapter lives in `adapter/persistence/`, not `persistence/`: `persistence`'s `package-info`
> declares it never imports from `application`, so the thing that implements the `application`-owned
> `OutboundLog` port against `persistence.OutboundMessageRepository` sits outside `persistence` (the
> `package-info` explicitly permits "an adapter outside this package"). Driven test-first by
> `persistence/OutboundLogAdapterIT` (Postgres Testcontainers over the shared `SchemaTestSupport`,
> which is package-private, so the IT lives in the `persistence` test package). RED (seam throwing):
> `2 tests completed, 2 failed`, both `UnsupportedOperationException` at `record`. GREEN (the write
> delegating to `OutboundMessageRepository.writeOutcome(outcome, claim, PENDING)` and returning
> whether one row was written): both pass — the SENT row carries the stored body hashing to the
> digest, and a stale claim writes nothing and returns false.
- [x] T040 [US1] Wire `DistributionRunner` to call slice 001's `pipeline.HmppsRedaction` with the effective switch state, handle the `NOT_APPLICABLE`/rules-failure/success result kinds, compute `body_digest` and read `service_version` once at startup — makes T030, T031, T033 pass (FR-002-028 to FR-002-030, FR-002-033)

> Driven test-first by the rewritten `application/DistributionRunnerTest` (plain mocks, a real
> `MetricRegistry` over a `SimpleMeterRegistry`). The leg-outcome metrics cross an `OutcomeMetrics`
> port declared in `application` and implemented by `config.MetricRegistry`, so the core never imports
> `config` (its `package-info` forbids it) — the dependency points inward. RED (runner constructor
> taking the metrics port but still raw-sending the fetched tree, redaction unwired):
> `13 tests completed, 9 failed` — the redacted-tree, NOT_APPLICABLE(R1/R11), rules-exception,
> gateway-transient, non-200 and metric assertions all fail. GREEN (the switch-expression over the
> four `RedactionResult` kinds — `Body` sends and maps the outcome, `EmptiedByR1`/`EmptiedByR11` record
> `NOT_APPLICABLE` without sending, `RulesFailure` records `FAILED(rules-exception)` with no digest —
> each emitting `probation_leg_outcomes_total` and, where apt, the non-200/parked/unreadable-switch
> counters): `13 tests` pass. The `service_version` is written by the persistence layer with the
> digest (H19); `body_digest` is the SHA-256 of the exact bytes the sender returns.
- [x] T041 [US1] Implement `shape` derivation (`application-only` vs `case`, reading `hearing.courtApplications`/`hearing.prosecutionCases` presence) and wire it into the `probation_leg_outcomes_total` label — makes T032 pass (FR-002-059, research.md R7)

> Driven test-first by two `DistributionRunnerTest` cases (`counts_application_only_shape`,
> `counts_case_shape`). RED (the `shapeOf` placeholder labelling every body `case`):
> `15 tests completed, 1 failed` — the application-only assertion has no matching counter. GREEN
> (`shapeOf` reading the redacted body: a non-empty `hearing.prosecutionCases` is `case`, otherwise —
> since R11 guarantees a `Body` carries at least one of the two — `application-only`): all 15 pass.
- [x] T042 [US1] Implement `inbound/ProbationMessageListener.java` and its `ServiceBusProcessorClient`/`ServiceBusConsumerConfig` wiring in `config/`: peek-lock mode (never `ReceiveAndDelete`), auto-complete disabled, `distribution.consumer.concurrency` (default 2) concurrent calls, lock duration and claim-lease ordering per `distribution.servicebus.max-delivery-count`; parse via T035, call the guard then the runner, exactly one explicit settlement per delivery — makes T030's end-to-end settlement assertion pass (FR-002-001, FR-002-046, FR-002-048)

> The listener's parse/guard/run/settle decision is a testable `decide(body, deliveryCount)` returning
> a bounded `Settlement`; `processMessage(context)` applies exactly one `complete`/`abandon`/`deadLetter`
> and the Azure `ServiceBusReceivedMessage` never leaves the class. The request state transition is a
> new pair of claim-guarded `ProcessedRequestRepository` statements (`markCompleted`, `markRetrying`);
> the `OutboundLog` port now carries the `RunClaim` (the prior round's declined finding), threaded from
> `GuardDecision.Admitted` through the runner to the fenced write. `ServiceBusConsumerConfig` is the
> composition root, gated on `distribution.consumer.enabled=true`, so the `test`-profile context-load
> test (intake off, no datasource) still refreshes.
>
> Driven test-first. `inbound/ProbationMessageListenerTest` (plain mocks, real `MetricRegistry`) —
> RED against a throwing `decide` seam: every case hits `UnsupportedOperationException`; GREEN with the
> real decision: 9 pass. `persistence/RequestTransitionIT` (Postgres) for the two transition
> statements — RED against throwing seams: `4 tests completed, 4 failed`; GREEN: 4 pass. The four
> end-to-end suites (T030-T033), red since their own commit, go GREEN here: `4 tests, 0 failures`
> (Sent, NotApplicable, ReceiverRejected, RulesException).
>
> The end-to-end suites caught a real pre-existing defect in `OutboundMessageRepository`: the
> `service_version = CASE WHEN :bodyDigest IS NULL …` left the bound NULL parameter untyped, so the
> driver raised *"could not determine data type of parameter"* for every no-body outcome
> (`NOT_APPLICABLE`, `FAILED(rules-exception)`). The write now precomputes `service_version` in Java
> (NULL when there is no digest) and binds a typed `:sent` boolean for `sent_at`, removing the
> untyped-NULL CASE. Pinned by `OutboundLogAdapterIT.writes_a_not_applicable_outcome`.
- [x] T043 [US1] Build the support-lookup query against `outbound_message_latest` plus `outbound_body` in `persistence/` (a repository method, no HTTP endpoint — this is a support capability, not an API) — makes T034 pass (FR-002-053, SC-002-008)

> T034/T043 driven test-first by `persistence/SupportLookupIT` (Postgres Testcontainers over the
> shared `SchemaTestSupport`). RED: with `SupportLookupRepository.lookup` stubbed to throw
> `UnsupportedOperationException`, `2 tests completed, 2 failed` — both hitting
> `UnsupportedOperationException` at the lookup (the SENT-leg fixture inserts, including the
> digest-trigger-checked `outbound_body`, already commit). GREEN: with the join of
> `processed_request` + `outbound_message_latest` + `outbound_body` implemented (the leg read coming
> from the latest-attempt view, never `processed_request.status`), both pass; the returned bytes hash
> to the recorded digest (checkstyle/pmd main+test clean).

**Checkpoint**: User Story 1 is fully functional and independently testable — this is the MVP.

---

## Phase 4: User Story 2 — Only valid new work is admitted, and no copy of a share is sent twice (Priority: P1)

**Goal**: The processed-log guard is the sole duplicate protection; invalid messages never reach it; a database outage pauses intake instead of burning deliveries.

**Independent Test** (spec.md): each invalid-message class, a republish of a completed share, two concurrent first copies, a same-`requestId`-different-day collision, a delivery during a database outage → correct dead-letter reasons/counts, exactly one POST per share, rows untouched where specified, intake suspended/resumed around the outage.

### Tests for User Story 2 (MANDATORY — write and run these first) ⚠️

- [x] T044 [P] [US2] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/inbound/ContractValidationDeadLetterIT.java`: each invalid shape of Acceptance Scenario 1 (unknown field, missing field, bad `eventType`, `null userId`, wrong type, non-JSON) → dead-lettered `validation` on that delivery, no `processed_request` row, ERROR line names no field name or value, `probation_deadlettered_total{reason="validation"}` incremented — fails first (FR-002-003, DV-16, SC-002-003)

> T044/T049: the validation dead-letter path (parse throws `CommandValidationException` → `DeadLetter(VALIDATION)` before `guard.admit`, no `processed_request` row, counted, bounded reason on the dead-letter) landed whole in US1's T042. This new end-to-end IT (Service Bus emulator + Postgres + WireMock) pins it against the real broker and DLQ: `1 test completed, 0 failed` on arrival. There is no RED because there is no production gap — T049 adds no production code; it is satisfied by the existing listener, which this IT now guards.
- [x] T045 [P] [US2] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/DuplicateDeliveryIT.java` (Service Bus emulator + Postgres): a republish of a `COMPLETED` share is a no-op with no run and no POST; two concurrent first copies produce exactly one admission/run/POST, the other abandoned with no ERROR line and completed as a no-op on redelivery — fails first (DV-08, Acceptance Scenarios 3–4, SC-002-002)

> T045: the processed-log guard's duplicate protection (COMPLETED → `DuplicateComplete`; lost-insert race and live claim → `Abandon`) landed in the foundational guard (T016/T017) and listener (T042). This new IT pins it end to end against the real broker: `2 tests completed, 0 failed` on arrival (a republish of a completed share and two concurrent first copies each yield exactly one POST). Green-on-arrival: the behaviour is US1/foundational, and this IT is its US2 regression guard — part of what T050 keeps true.
- [x] T046 [P] [US2] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/RequestDedupeIT.java`: a live-claim copy is abandoned without delay (dead-lettered `exhausted` if final); a different-fingerprint collision on a known key is dead-lettered `collision` with rows untouched; a `FAILED` request's copy is dead-lettered `exhausted` with no run (interim FR-002-008) — fails first (Acceptance Scenarios 5, 6, 10)

> T046/T050: RED against the pre-T050 listener (`3 tests completed, 1 failed`): the collision and FAILED→exhausted cases passed from the inherited guard, but the live-claim copy, abandoned on every delivery, reached the dead-letter queue under the broker's own `MaxDeliveryCountExceeded` rather than the service's `exhausted` (`expected: "exhausted" but was: "MaxDeliveryCountExceeded"`). GREEN after T050 added the final-permitted-delivery hand-back (`isFinalPermittedDelivery`/`handBack`, zero-based `deliveryCount >= maxDeliveryCount - 1`) that dead-letters `exhausted` on the last delivery of the guard-`Abandon` path: `3 tests completed, 0 failed`, and the 12 `ProbationMessageListenerTest` cases stay green.
- [x] T047 [P] [US2] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/persistence/DatabaseOutageIT.java` (Testcontainers, paused container or Toxiproxy): a delivery during an outage is abandoned before body read, `probation_intake_suspended{cause="database"}=1`, readiness down, final-delivery dead-letters `exhausted`, intake resumes on the next probe once the database answers — fails first (FR-002-009, Acceptance Scenario 7, SC-002-011)

> T047/T051: the IT wires a real `ConsumerLifecycleController`/`ProcessedLogProbe` over a dedicated Postgres it pauses (socketTimeout 5s), the broker/Redis/stubs staying up. RED, captured against a listener with the store-check bypassed (`if (false && …)`): `2 tests completed, 2 failed` — the run read the body, hit `CannotGetJdbcConnectionException`, abandoned every delivery with no suspend (`intake-suspended gauge never reached 1.0`) and the broker burned the budget to `MaxDeliveryCountExceeded` instead of the service's `exhausted`. GREEN after T051 added the store gate (`StoreGate`, `ProcessedLogProbe`, `ConsumerLifecycleController`, the `probation_intake_suspended{cause}` gauge, the listener's store-check-before-body and apply-then-suspend ordering): `2 tests, 0 failures`, with `ServiceBusConsumerConfigIT` (2) and `TestProfileContextTest` (3) green alongside. A first green run surfaced two real ordering bugs — suspending before the settlement was applied raced the processor drain (abandon lost → 5-minute lock → never completed; dead-letter lost → stuck on queue), and a raw `@Value Duration` the `ApplicationContextRunner` could not convert — both fixed (suspend moved after `apply`; probe interval parsed with `DurationStyle`).
- [x] T048 [P] [US2] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/StaleRunnerRejectionIT.java`: an unexpected run exception is abandoned (or dead-lettered `exhausted` on the final delivery); a stale-runner UPDATE (claim taken over after lease expiry) updates zero rows, increments the rejection counter, and does not complete the message — fails first (Acceptance Scenarios 8, 9)

> T048/T052: the IT drives an unexpected-exception run and a stale-runner run (a `RunResult` with `recorded=false`) over the real broker with mocked collaborators and `maxDeliveryCount = 1` so the one delivery is final. RED against the pre-T052 listener (`2 tests completed, 2 failed`): both handed the delivery back as a plain abandon, so the broker burned the budget to `MaxDeliveryCountExceeded` (`expected: "exhausted" but was: "MaxDeliveryCountExceeded"`). GREEN after T052 routed both paths through `handBack(deliveryCount)` (stale: `probation_stale_runner_rejections_total` + never `markCompleted`; unexpected: `probation_processing_failures_total`): `2 tests, 0 failures`, with the 12 `ProbationMessageListenerTest` cases still green. The repository-level zero-row fencing is pinned separately by the foundational `ClaimFencingIT`.

### Implementation for User Story 2

- [x] T049 [US2] Extend `inbound/DistributionCommandParser.java`/`ProbationMessageListener.java` to dead-letter `validation` with a bounded reason only, before any `processed_request` write, on every schema violation — makes T044 pass (FR-002-003)
- [x] T050 [US2] Extend `application/IdempotencyGuard.java` to atomically handle the lost-insert race (quiet, no ERROR), the live-claim abandon path and the reclaim-on-expiry path under concurrent load — makes T045, T046 pass (FR-002-007)

> T050: the guard's lost-insert-race, live-claim-abandon and reclaim-on-expiry logic already existed from the foundational phase (IdempotencyGuard, ClaimFencingIT). The one gap US2 exposed was the listener's missing final-permitted-delivery rule, so the production change landed in `ProbationMessageListener` (`maxDeliveryCount` constructor arg, `isFinalPermittedDelivery`, `handBack`), wired from `distribution.servicebus.max-delivery-count` in `ServiceBusConsumerConfig` and from `MAX_DELIVERY_COUNT` in the test harness. The guard itself needed no change.
- [x] T051 [US2] Implement the database-outage detection (store probe every `distribution.store.probe-interval`) and `probation_intake_suspended` gauge wiring in `config/` or `persistence/`, with the listener handing back a delivery before reading its body during an outage — makes T047 pass (FR-002-009)

> T051 new production: `inbound/StoreGate`, `persistence/ProcessedLogProbe` (`SELECT 1`), `inbound/ConsumerLifecycleController` (gated start, probe-driven suspend/resume, deferred migration — ported from YOT, decoupled from the health indicator), the `probation_intake_suspended{cause="database"}` gauge on `MetricRegistry`, and the listener's store precondition (check before body read; hand back, apply, then suspend). `ServiceBusConsumerConfig` now builds the processor unstarted and lets the controller own its lifecycle, resolving the listener↔controller↔processor cycle with a `DeferredStoreGate`. Readiness continues to rest on the stock `db` contributor (down during the outage); the start-with-the-store-already-down migration-deferral case (YOT's third outage scenario) is out of this slice's T047 and left for a later pass — see open points.
- [x] T052 [US2] Add stale-runner and unexpected-exception handling to `DistributionRunner`/`ProcessedRequestRepository` (zero-row UPDATE detection, `probation_stale_runner_rejections_total`, abandon-or-dead-letter-exhausted on final delivery) — makes T048 pass (FR-002-007, A2-67)

> T052: the zero-row UPDATE detection and `probation_stale_runner_rejections_total` already existed in the listener/repositories from the foundational phase and T042; this task added the final-permitted-delivery rule to both non-settling paths — `staleRunnerRejection(deliveryCount)` and the unexpected-exception catch now call `handBack(deliveryCount)`, dead-lettering `exhausted` on the last delivery instead of leaving the broker to park it `MaxDeliveryCountExceeded`. A stale run still never completes the message.

**Checkpoint**: User Stories 1 and 2 both work independently — the service is now safe to run.

---

## Phase 5: User Story 3 — A hearing whose payload cannot be read yet is retried by redelivery, and failed visibly if it never appears (Priority: P2)

**Goal**: Dated cache → undated cache → query API fallback chain; every failure mode is a miss, not a run failure; park-and-redeliver with in-place re-run; final-delivery dead-letter when nothing is ever found.

**Independent Test** (spec.md): dated-absent/undated-present; both-absent with query answering 200/empty/`{}`/non-JSON/503-then-200; nothing anywhere across five deliveries.

### Tests for User Story 3 (MANDATORY — write and run these first) ⚠️

- [ ] T053 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/payload/CachedHearingPayloadAdapterTest.java`: dated-absent/undated-present → `payload_source=cache-nullday`; cache unreachable/timeout/blank/null/unparseable → each a miss, not a failure — fails first (Acceptance Scenarios 1–3, DV-07, DV-13)
- [ ] T054 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/payload/ResultsQueryExternalHearingClientTest.java` (WireMock): empty body/`{}`/non-JSON 2xx → miss; 503-then-200 retried with doubling backoff honouring `Retry-After`, sent on recovery; 404 not retried; fallback form `{hearing, sharedTime}` passed through unexpanded; `CJSCPPUID` = command `userId` or system user when absent — fails first (Acceptance Scenarios 4–7, DV-10, DV-17, DV-23)
- [ ] T055 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/PayloadUnavailableParkIT.java`: every source missing on a non-final delivery → `PARKED(payload-unavailable)`, switch not read, no body, request `RETRYING`, message abandoned, `probation_parked_total` incremented, ERROR line — fails first (Acceptance Scenario 8)
- [ ] T056 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/PayloadRerunInPlaceIT.java`: payload now available on redelivery → same attempt-1 row re-run in place, no new `PENDING`, `attempts` incremented, `note=prev=PARKED/payload-unavailable`, outcome `SENT`, request `COMPLETED` — fails first (Acceptance Scenario 9, FR-002-041)
- [ ] T057 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/PayloadExhaustedIT.java`: every source misses on the final permitted delivery → leg `FAILED(payload-unavailable)`, request `FAILED(leg-exhausted)` with `exhausted_message_id`, message dead-lettered `leg-exhausted` and counted — fails first (Acceptance Scenario 10)
- [ ] T058 [P] [US3] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/PayloadStageBudgetTest.java`: an attempt whose worst case (with wait) no longer fits the 60s payload budget is not started, stage ends `payload-unavailable`; a `sharedTime` that fails to parse leaves `payload_shared_time` empty without failing the leg — fails first (Acceptance Scenarios 11–12, SC-002-005)

### Implementation for User Story 3

- [ ] T059 [US3] Extend `CachedHearingPayloadAdapter` with the undated-key fallback and miss classification (unreachable, timeout, blank, JSON null, unparseable → all misses) — makes T053 pass (FR-002-016)
- [ ] T060 [US3] Implement `adapter/payload/ResultsQueryExternalHearingClient.java` implementing the query-API leg of `HearingPayloadSource`: the retry block (408/429/5xx/connect/read, doubling backoff, `Retry-After` honoured, budget-bounded), empty/`{}`/non-JSON-as-miss, `CJSCPPUID` selection — makes T054 pass (FR-002-017)
- [ ] T061 [US3] Compose the three-source chain (dated → undated → query) in the payload-fetch stage of `DistributionRunner`, recording `payload_source` and the attempt-fits rule against the 60s budget — makes T058's budget check and the chain-selection scenarios pass (FR-002-015, FR-002-013)
- [ ] T062 [US3] Implement the `PARKED(payload-unavailable)`/final-delivery `FAILED(payload-unavailable)` settlement path in `DistributionRunner`/`ProcessedRequestRepository`, with the switch stage skipped entirely when the payload is unavailable — makes T055, T057 pass (FR-002-018, FR-002-047, FR-002-048)
- [ ] T063 [US3] Implement in-place re-run (FR-002-012 eligibility column, FR-002-041 note rewrite) for a `PARKED` or claim-reclaimed `PENDING` attempt-1 row — makes T056 pass
- [ ] T064 [US3] Parse `sharedTime` defensively (ISO-8601 instant, any offset) leaving `payload_shared_time` NULL on a parse failure without failing the leg — makes T058's sharedTime assertion pass (FR-002-019)

**Checkpoint**: User Stories 1–3 all work independently.

---

## Phase 6: User Story 4 — An unreadable youth switch never stops a hearing; the configured default applies (Priority: P2)

**Goal**: The legacy's JavaScript-truthy switch read, the legacy's 3×1s retry rule with finite timeouts, every `UNREADABLE` cause mapped correctly, and the configured default sending the hearing on every run.

**Independent Test** (spec.md): stub users-groups driving every decision and every unreadable cause, both default settings (`ACTIVE`/`INACTIVE`); assert body, recorded value, WARN line, counter labels; confirm nothing parks/fails/dead-letters from the switch alone.

### Tests for User Story 4 (MANDATORY — write and run these first) ⚠️

- [ ] T065 [P] [US4] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/usersgroups/FeaturePermissionClientTest.java` (WireMock): `permissions[0].active` truthy → `ACTIVE`; present-but-falsy/absent/non-object `permissions[0]` → `INACTIVE`; more than one permission → first decides, `probation_youth_switch_ambiguous_total` incremented — fails first (Acceptance Scenarios 1–3, P17, FR-002-022)
- [ ] T066 [P] [US4] Extend T065 (or a sibling test class) for the retry/timeout/unreadable-cause matrix: 3×500-answer → 3 attempts at 1s apart, `UNREADABLE(HTTP_FAILURE)`; non-2xx ≤429 not retried; 2s/5s timeouts, three attempts within 23s → `UNREADABLE(TIMEOUT)`; empty/missing/null-first-entry `permissions` → `UNREADABLE(EMPTY_PERMISSIONS)`; unparseable body → `UNREADABLE(UNPARSEABLE)`, neither retried — fails first (Acceptance Scenarios 4–7, DV-24, the 2026-10-21 clarification)
- [ ] T067 [P] [US4] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/YouthSwitchUnreadableIT.java`: for every unreadable cause and both configured defaults (`ACTIVE`/`INACTIVE`) → default body sent, row records `UNREADABLE_DEFAULTED_ACTIVE`/`_INACTIVE`, WARN line with cause, `probation_youth_switch_unreadable_total{cause, defaulted}` incremented; an unreadable switch never parks/fails/dead-letters on its own — fails first (Acceptance Scenarios 8–9, DV-25, SC-002-007)
- [ ] T068 [P] [US4] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/YouthSwitchDefaultConfigTest.java`: startup logs the configured default at INFO; a value other than `ACTIVE`/`INACTIVE` refuses startup naming the property; a run makes no read of `ProbationResultsDistributionService` or App Configuration — fails first (Acceptance Scenarios 10–11, FR-002-026, FR-002-027)

### Implementation for User Story 4

- [ ] T069 [US4] Implement the JavaScript-truthy decision logic and the legacy retry rule (3 attempts, fixed 1s wait, retry only no-response-error or status>429, no `Retry-After`) with 2s/5s timeouts in `FeaturePermissionClient`, returning the three-valued `YouthSwitchDecision` (`Active`, `Inactive`, or `Unreadable(cause)`) from T006 — never the four-value recorded form — makes T065, T066 pass (FR-002-021 to FR-002-024, FR-002-022)
- [ ] T070 [US4] Wire the switch-read stage into `DistributionRunner` as the HMPPS leg's first step (after payload fetch, before redaction): apply `distribution.youth-switch.default-when-unreadable` only when T069 returns `Unreadable(cause)`, converting the `YouthSwitchDecision` into the persisted `YouthSwitchRecordedValue` (`ACTIVE`/`INACTIVE` pass through unchanged; `Unreadable` becomes `UNREADABLE_DEFAULTED_ACTIVE`/`_INACTIVE`) and recording the defaulted value/cause/WARN/counter — makes T067 pass (FR-002-020, FR-002-025, DV-25)
- [ ] T071 [US4] Add the default-value validator (`ACTIVE`/`INACTIVE` only, INFO log, named-property refusal) to the startup validator from T025, and confirm no code path reads the Results switch or App Configuration — makes T068 pass (FR-002-026, FR-002-027)

**Checkpoint**: User Stories 1–4 all work independently.

---

## Phase 7: User Story 5 — A gateway failure is parked or exhausted, never retried into a duplicate (Priority: P2)

**Goal**: In-run retry limited to never-sent connection failures and 429; every outcome-unknown answer (5xx, 408, 3xx, read timeout, I/O failure after write) parks at once with no in-run retry; final-delivery failure is visible; crash-rerun counting is scoped exactly to the clarified rule.

**Independent Test** (spec.md): refused connection, 429-with-`Retry-After`, 503, 408, 302, stalled response, 5xx-then-200-on-redelivery, 5xx-on-every-delivery — assert POST counts per delivery, outcomes, stored bodies, recovery counts, final dead-letter.

### Tests for User Story 5 (MANDATORY — write and run these first) ⚠️

- [ ] T072 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/gateway/GatewayRetryPolicyTest.java`: refused-connection-then-200 retried in-run, row `SENT`, one body stored; 429-with-`Retry-After:2`-then-200 retried within the 10s cap, `probation_gateway_recovered_total{after="429-in-run"}` incremented — fails first (Acceptance Scenarios 1–2, DV-04)
- [ ] T073 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/gateway/ReadTimeoutParksIT.java` and `Http408ParksIT.java`: a 503/408/read-timeout-after-write → exactly one POST, `PARKED(gateway-transient)`, body+digest stored, request `RETRYING`, message abandoned with no artificial delay before the broker's own redelivery — fails first (Acceptance Scenarios 3–5, FR-002-049, SC-002-006)
- [ ] T074 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/gateway/ConnectFailureRetriedIT.java`: every connection attempt fails → at most 3 attempts, `PARKED(gateway-transient)` with no `http_status`, message abandoned immediately with no added delay — fails first (Acceptance Scenario 6, FR-002-049)
- [ ] T075 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/GatewayRecoveryIT.java`: a parked 5xx row re-run on redelivery and met with 200 → same row re-run in place, ends `SENT`, `note=prev=PARKED/gateway-transient`, earlier body replaced (exactly one body row remains), `probation_gateway_recovered_total{after="5xx-redelivery"}` incremented — fails first (Acceptance Scenario 7)
- [ ] T076 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/GatewayExhaustedIT.java`: still transiently failing on the final permitted delivery → leg `FAILED(gateway-transient)`, request `FAILED(leg-exhausted)`, dead-lettered `leg-exhausted`, ERROR line + counter — fails first (Acceptance Scenario 8)
- [ ] T077 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/SendStageBudgetTest.java`: a slow 429 leaving less than wait+65s of the 130s send stage → no further attempt, `PARKED(gateway-transient)` within budget; a non-numeric `Retry-After` falls back to the computed backoff — fails first (Acceptance Scenarios 9, 11, SC-002-005)
- [ ] T078 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/application/CrashRerunCountingIT.java`: a 2xx-then-crash-before-record, redelivered after lease expiry → re-runs the `PENDING` row in place with `note=prev=PENDING/-`, re-POSTs, `probation_crash_reruns_total` incremented; a `PARKED` row that crashes mid-rerun and is re-run again does NOT increment the counter — fails first (Acceptance Scenarios 10, 13; the 2026-10-21 crash-counter clarification)
- [ ] T079 [P] [US5] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/adapter/gateway/RateLimited429RetriedIT.java` and a 302 case in `GatewayOtherStatusIT.java`: a 302 → exactly one POST, no redirect followed, `PARKED(gateway-transient)` with `http_status=302`, metric `http="other"`, final-delivery `FAILED(gateway-transient)` — fails first (Acceptance Scenario 12, FR-002-038, the `other` HTTP-bucket clarification)

### Implementation for User Story 5

- [ ] T080 [US5] Implement the fixed-in-code retry predicate (connection failure never-sent, or 429 only) with 500ms-doubling-to-10s backoff and `Retry-After` honouring in `HmppsPublisher` — makes T072 pass (FR-002-035)
- [ ] T081 [US5] Implement outcome-unknown-parks-at-once handling (5xx, 408, read timeout after write, I/O failure after write began) returning `TransientExhausted`/`FailedNonTransient` with no in-run retry and no artificial abandon delay — makes T073, T074 pass (FR-002-036, FR-002-039, FR-002-049)
- [ ] T082 [US5] Disable HTTP redirect following and map any 3xx (or other non-2xx/4xx/5xx status) to `TransientExhausted(status)` with `http="other"` — makes T079 pass (FR-002-038, contracts/hmpps-gateway.md)
- [ ] T083 [US5] Implement the gateway-recovery re-run path (overwrite the parked row, `prev=` note, body replacement) and wire `probation_gateway_recovered_total{after}` for both the in-run 429 case and the 5xx-redelivery case — makes T075, T072's recovery assertion pass (FR-002-060)
- [ ] T084 [US5] Implement the send-stage attempt-fits budget check (130s stage, (attempts-1)×(connect+max-backoff)+connect+read ≤ 95s default) in the retry loop — makes T077 pass (FR-002-013, FR-002-067, SC-002-005)
- [ ] T085 [US5] Implement crash-rerun detection scoped exactly to an expired-claim `PENDING` attempt-1 reclaim (never a `PARKED` reclaim) and wire `probation_crash_reruns_total{leg}` — makes T078 pass (FR-002-061, the clarified crash-counter rule)
- [ ] T086 [US5] Implement the final-permitted-delivery failure path for the gateway leg (`FAILED(gateway-transient)`, request `FAILED(leg-exhausted)`, dead-letter `leg-exhausted`) — makes T076 pass (FR-002-047, FR-002-048)

**Checkpoint**: User Stories 1–5 all work independently.

---

## Phase 8: User Story 6 — Operations see every outcome, are paged only for genuine issues, and see no personal data (Priority: P3)

**Goal**: Complete the metric/log registry's proof: one log line per outcome with the full field set, every label closed and selectable, the privacy sweeps clean, the gauges correct.

**Independent Test** (spec.md): one message per outcome class (200, 202, R1 `NOT_APPLICABLE`, application-only 400, case-hearing 400, 401, 503-then-200, 429-then-200, payload-unavailable-to-final, defaulted switch, schema-breaking message, broker-unreachable) driven directly, without the other stories' suites.

### Tests for User Story 6 (MANDATORY — write and run these first) ⚠️

- [ ] T087 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/LegOutcomeLogLineTest.java`: every leg outcome produces exactly one structured log line carrying status, reason, HTTP status, body digest, `runOrigin`, recorded `youth_switch` (+ cause if defaulted), and the MDC fields `requestId, hearingId, hearingDay, source, sequenceNumber, deliveryCount, leg`; never a `userId`, payload or body — fails first (Acceptance Scenario 1, FR-002-063)
- [ ] T088 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/ClosedLabelSetIT.java`: drive one message per outcome class of the independent test and assert every emitted label value belongs to its closed set, none a hearing id/user id/payload value/free text — fails first (Acceptance Scenario 2, SC-002-009)
- [ ] T089 [P] [US6] Re-run `LogStatementSweepTest.java` (from T026) over the full outcome matrix of this story's independent test — makes the zero-leak assertion of SC-002-009 hold across every outcome class, not just US1's happy path (Acceptance Scenario 3)
- [ ] T090 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/SelectableOutcomesTest.java`: application-only 400, case-hearing 400, 401, a non-200 2xx and a recovered send are each independently selectable by their labels on `probation_leg_outcomes_total`/`probation_gateway_non200_success_total`/`probation_gateway_recovered_total` — fails first (Acceptance Scenario 4, H34, FR-002-064)
- [ ] T091 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/AgeGaugesTest.java`: `probation_oldest_non_terminal_age` and `probation_oldest_parked_age` report correctly from a non-terminal request / a `PARKED` leg respectively, refreshed on the fixed 30s schedule — fails first (Acceptance Scenario 5, research.md R7)
- [ ] T092 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/ServiceBusHealthTest.java`: broker unreachable → `probation_servicebus_up=0`, readiness stays up while the database is up — fails first (Acceptance Scenario 6, FR-002-009)
- [ ] T093 [P] [US6] `src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/config/DeadLetterAndProcessedCountersTest.java`: each dead-letter and each final request status increments `probation_deadlettered_total{reason}`/`probation_processed_total{status}` respectively — fails first (Acceptance Scenario 7, FR-002-064)

### Implementation for User Story 6

- [ ] T094 [US6] Finalize the single-log-line-per-outcome implementation (level routing: INFO for `SENT`/`NOT_APPLICABLE`, WARN when defaulted or non-200-2xx, ERROR for `PARKED`/`FAILED`) in the runner/listener — makes T087 pass (FR-002-063)
- [ ] T095 [US6] Audit every metric-emission call site against the closed label sets from T023; fix any open-string label — makes T088, T090 pass (FR-002-059, FR-002-072)
- [ ] T096 [US6] Implement the two age gauges on a fixed 30s refresh schedule (no new configuration property), reading from the database's `created_at`/`updated_at` — makes T091 pass (FR-002-062, research.md R7)
- [ ] T097 [US6] Implement the `probation_servicebus_up` health indicator, registered outside the readiness group, staleness-judged after `distribution.servicebus.health-staleness` — makes T092 pass (FR-002-009)
- [ ] T098 [US6] Wire `probation_deadlettered_total{reason}` and `probation_processed_total{status}` at every dead-letter and terminal-status write site — makes T093 pass (FR-002-058)

**Checkpoint**: All six user stories are independently functional.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Gate verification spanning every story; this slice's own quality bar (constitution Principle VIII, workflow.md gates 1–9).

- [ ] T099 [P] Register every DV row this slice lands in `doc/DEVIATIONS.md` (DV-04 to DV-10, DV-13, DV-14, DV-16, DV-17, DV-23 to DV-25) and every carried/pending-external/moot defect it touches (P17, P19, P07, P36, P01), each naming its pinning test per FR-002-074's table — verifies SC-002-010
- [ ] T100 [P] Run the privacy sweep (`TelemetryPrivacyTest`, `LogStatementSweepTest`) over the complete, now-implemented codebase and fix any surviving leak — verifies SC-002-009, gate 9
- [ ] T101 [P] Verify settlement discipline across every listener path (gate 2): grep for every `complete()`/`abandon()`/`deadLetter()` call site and confirm exactly one fires per delivery, after durable state — verifies FR-002-046
- [ ] T102 Confirm `doc/API_CONTRACTS.md` and `doc/design/DIGEST.md` already reflect the `http="other"` 3xx clarification and the `EMPTY_PERMISSIONS`/crash-rerun clarifications (already landed on this branch per the clarification commits) remain consistent with the implemented behaviour — no drift
- [ ] T103 Run `./gradlew jacocoTestReport check` and confirm LINE ≥ 0.88 / BRANCH ≥ 0.85 (excluding `Application` and `config/**`), zero PMD/Checkstyle warnings — verifies SC-002-012, constitution Principle VIII
- [ ] T104 Run `./gradlew build` end to end and confirm every schema-vocabulary, parity (slice 001), and this slice's test suites are green together — final gate before PR to `main`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: no dependencies — start immediately
- **Foundational (Phase 2)**: depends on Setup — BLOCKS every user story (V1 schema, ports, runner skeleton, metrics, config validation all live here)
- **User Stories (Phases 3–8)**: all depend on Foundational completion
  - US1 and US2 are both P1 and have no dependency on each other's code, but US2's dedupe/validation tests assume US1's happy-path POST exists to prove "exactly one POST per share" — implement US1 first in practice, though the stories are independently testable
  - US3, US4, US5 each extend a different stage of the same `DistributionRunner` (payload fetch, switch read, gateway send) — independently testable via stubs, but all three touch `DistributionRunner`, so sequence their implementation tasks to avoid file conflicts (see note below)
  - US6 depends on every earlier story's outcome classes existing to drive its independent test meaningfully, but its own log/metric assertions are written against stubs first, so it can start in parallel
- **Polish (Phase 9)**: depends on all six stories being complete

### User Story Dependencies

- **US1 (P1)**: no dependency on other stories — the MVP
- **US2 (P1)**: independently testable; in practice benefits from US1's happy path existing first
- **US3 (P2)**: independently testable via a stubbed gateway/switch; extends the payload-fetch stage
- **US4 (P2)**: independently testable via a stubbed payload/gateway; extends the switch-read stage
- **US5 (P2)**: independently testable via a stubbed payload/switch; extends the gateway-send stage
- **US6 (P3)**: independently testable via stubs for every outcome class; completes the registry every earlier story already emits into

### Within Each User Story

- Tests MUST be written, run and observed to fail for the right reason before their implementation task starts
- Within Foundational: vocabularies/domain types → Flyway schema → ports/runner skeleton → claim fencing → metrics/config
- Within each story: contract/integration tests → adapter implementation → runner wiring → settlement wiring

### Parallel Opportunities

- All `[P]` Setup tasks (T002–T004) run together once T001 lands
- Within Foundational: T005–T008 (vocabularies) run together; T009–T012 (schema tests) run together once T005–T008 land; T015–T016, T018, T020, T022, T024, T026 (the test-first tasks across ports/runner/metrics/config) run together
- Within each user story phase, every test task marked `[P]` targets a distinct file and runs together
- US3, US4 and US5's test tasks (T053–T058, T065–T068, T072–T079) can all be written and run in parallel across stories since they target different adapter packages; their implementation tasks (T059–T064, T069–T071, T080–T086) all touch `DistributionRunner`, so serialize those three implementation blocks even though the stories are conceptually independent

---

## Parallel Example: User Story 1

```bash
# Launch all US1 tests together (they precede every US1 implementation task):
Task: "Contract test for the inbound schema in src/test/.../inbound/DistributionCommandSchemaTest.java"
Task: "WireMock contract test for the gateway send in src/test/.../adapter/gateway/HmppsPublisherTest.java"
Task: "End-to-end integration test in src/test/.../EndToEndSentIT.java"
Task: "End-to-end NOT_APPLICABLE test in src/test/.../EndToEndNotApplicableIT.java"
Task: "End-to-end receiver-rejected test in src/test/.../EndToEndReceiverRejectedIT.java"
Task: "End-to-end rules-exception test in src/test/.../EndToEndRulesExceptionIT.java"
Task: "Support lookup test in src/test/.../persistence/SupportLookupIT.java"

# Launch independent US1 adapter implementations together once tests are red:
Task: "Implement CachedHearingPayloadAdapter (dated-key happy path)"
Task: "Implement FeaturePermissionClient (happy-path decision only)"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational — V1 schema, vocabularies, ports, runner skeleton, claim fencing, metrics, config validation (this is the largest phase; it is the shared core slices 003–006 build on)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: run T030's end-to-end suite independently
5. This is the smallest thing that replaces the legacy send — a reviewable PR candidate on its own, per the spec's own framing of US1's priority

### Incremental Delivery

1. Setup + Foundational → shared core ready
2. US1 → validate independently → the MVP
3. US2 → validate independently (duplicate/validation safety — spec.md calls this a precondition for US1 to be safe to run in production)
4. US3 → validate independently (payload resilience)
5. US4 → validate independently (youth-switch resilience)
6. US5 → validate independently (gateway resilience)
7. US6 → validate independently (observability completeness)
8. Polish → deviation register, privacy sweep, coverage/quality gates, full build

### Sequencing note on shared files

US3, US4 and US5 each extend a different stage of `DistributionRunner` and are independently testable against stubs, but their implementation tasks all edit the same runner class. Implement them in priority order (US3 → US4 → US5) to avoid merge conflicts within a single contributor's working tree, even though a reviewer can verify each story's independent test in isolation.

---

## Notes

- `[P]` tasks touch different files and have no dependency on an incomplete task
- `[US#]` maps every user-story-phase task to its spec.md story; Setup, Foundational and Polish tasks carry no story label
- Every test task must be run and observed failing for the correct reason (an assertion, not a compile error) before its paired implementation task starts; record the red run in the task's commit narrative, the green run in the implementation commit's
- Constructor injection only, `private final` fields, Java records for value types, explicit imports, Flyway (never Liquibase) — per `technical-rules.md`
- The application core (`application/`, `domain/`) imports no Azure/Redis/HTTP-client/JDBC type; only `JsonNode` crosses the port boundary
- No business REST endpoint, no VEP code, no `ProbationResultsDistributionService`/App Configuration read, no outbound schema validator — all out of scope per spec.md and constitution Principle IX
- Stop at any checkpoint to validate a story independently before continuing
