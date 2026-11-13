# Changelog — service-cp-crime-probation-results-distribution

All notable changes to this service are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The design lives on the
[Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service)
Confluence page, which is the source of truth. This file does not replay the design's history.

Entries land under `[Unreleased]` as they are merged, and move into a version section at release.
Anything that changes what is **sent** to HMPPS must also be a registered deviation, with its own
assertion in the parity harness.

## [Unreleased]

### Added
- **006-production-readiness.** The one-week SIT → STE pre-cutover proof: `ReplayComparator`
  classes every paired share (match, registered deviation, explained/unexplained difference,
  unpaired, SIT-body-unusable) into a `ReplayReport`, over `HearingPairing` and 001's
  `JsonParity`/`RegisteredFieldDeviations` (moved into `src/main` so production code can depend on
  them). A read-only `evidence` package answers the go/no-go questions directly from the store:
  `GoNoGoEvidenceQuery`/`GoNoGoEvidenceIndex`, `DailyReconciliationQuery` (including the legacy
  both-paths silence check, `LegacyBothPathsCheck`), and `DecommissioningEvidenceQuery`. A typed
  `config.AlertCatalogue` is the single source for the eleven paging alerts and five counted-only
  classes DevOps build Azure Monitor rules from (FR-006-014 to FR-006-022); `runbook.md`
  cross-references it rather than restating conditions.
- **005-operations-api.** The operations API's HTTP surface, behind YOT's deployment gates:
  `ReplayController` (`POST /operations/requests/{requestId}/replay`) and `ResendLegController`
  (`POST /operations/resend-leg`, with the forced-resend override), both wired over 004's
  `ReplayAction`/`LegResender`. Refusals are bounded API codes (`unknown-request`,
  `not-eligible`, `resend-in-progress`, `request-claimed`), never row values or exception text.
  `OperationsAuditPublisher` audits every admitted call through `cp-audit-filter-springboot`,
  carrying no caller detail, reason or request body.
- **004-recovery.** Replay and resend-leg as first-class capabilities: `ReplayAction` re-sends a
  `FAILED` request's stored command byte for byte under a fresh `messageId` (`origin = REPLAY`);
  a dead-letter resubmission under the exhausted `messageId` is admitted as a replay at broker
  delivery count 1 (DV-26). `LegResender` claims and resends an explicit `requestId` list
  (H16), one transaction per id, refused whole while intake is paused. `pause-intake`/
  `resume-intake` CLI runners are the service-side stop, backed by a new single-row
  `intake_control` table (V3; FR-004-052, since confirmed by the design owner — V3's own
  "provisional" comment is left as applied and checksummed); `rollback-close-in-flight` closes
  every in-flight
  `PENDING`/`PARKED` row `FAILED(rolled-back)` under the rollback protocol. `ReplayEnqueuer`
  and `SubmitHearingAction` enqueue onto `probation.requests` with the consumer's own credential
  rule.
- **003-body-retention.** The stored-body retention job: a single scheduled runner, guarded by a
  transaction-scoped advisory lock, pre-creates the current and next two monthly
  `outbound_body` partitions and drops each whole month past the retention cut-off with a plain
  `DROP`, never a trigger (H35; `V2__create_scheduled_job_status.sql` records the job's
  last-success timestamp). `MaintenanceMetrics` publishes the partition-count and
  default-partition-row gauges the alert catalogue and the recovery runbook both read.
- **002-hmpps-distribution.** The message-driven pipeline itself: `ProbationMessageListener`
  parses and settles every `probation.requests` delivery exactly once; `IdempotencyGuard` and
  `JdbcProcessedRequestRepository` claim and settle the request state machine
  (`RECEIVED → RETRYING → COMPLETED | FAILED`); `DistributionRunner` runs the one HMPPS leg over
  `HearingPayloadSource` (the Redis `EXT_` cache, then the results-query fallback),
  `YouthFilterSource` (the users-groups `EMYouthFilter` read, defaulting on an unreadable switch
  per H8/DV-25) and `HmppsSender`, recording one `outbound_message`/`outbound_body` row per leg
  per attempt (V1). `StartupValidator` refuses to start on a misconfigured budget, lease
  ordering, credential or secret. The ported redaction rules, the corpus and golden-file
  parity harness land with it from 001.
- **001-parity-kernel.** `HmppsRedaction` ports the legacy filter rules R0–R12 verbatim
  (`PF/functions.js` at `122e2152`), including JavaScript truthiness and the carried null-guard
  quirks (P17–P19); a thrown read is reported as `RedactionResult.RulesFailure`, never a silent
  guard. `JsonParity`/`RegisteredFieldDeviations` compare a ported result against a legacy
  golden file, field-order-insensitive and BigDecimal-tolerant, with a registered deviation
  checked by derivation instead of equality. The synthetic corpus and Jest-to-JUnit golden
  fixtures land here; the SIT capture is an external dependency, tracked, not yet supplied.

### Changed
- **Constitution 2.0.1 — an expired stored-body month is removed with a plain `DROP` (H35)**, each
  in its own short transaction under the 5 s lock timeout and retried on the next run, while the
  single-runner lock keeps its own transaction; V1 and its DEFAULT partition are unchanged, and the
  digest, the engineering guidance and specs 002 and 003 follow.
- **Constitution 2.0.0 — design rev 3.6 (decisions H1–H34).** A MAJOR amendment: principles are
  removed or redefined. No code implemented the guidance it replaces.
  - **No outbound schema validation in this release** (H13). The interim outbound schema and
    pre-build gate G1 are gone; the body is redacted, digested and sent. The HMPPS interface control
    document v0.8 is recorded as information (H12): success is 200 only, the POST is not
    idempotent, and its schema is stale.
  - **No shadow run** (H21). Expected bodies come from SIT's own logged HMPPS bodies; the legacy
    JavaScript is run only for youth-switch-on and synthetic cases. A one-week SIT → STE replay is
    the pre-cutover proof.
  - **Broker duplicate detection is off** (H14). The processed-log guard is the only duplicate
    protection.
  - **The operations API is in scope for replay and resend-leg**, with a forced resend (H9). A
    resubmission from the dead-letter queue is a supported replay route under deviation DV-26; the
    next free deviation id is DV-27.
  - **The exact body POSTed is stored**, in `outbound_body`, partitioned by month and purged after
    six months by a daily in-service job (H18, H20). `service_version` replaces `rules_version` and
    `serializer_version` (H19); `payload_shared_time` replaces the payload digest (H17).
  - **Outcome-unknown gateway answers park.** A 5xx, a 408 or a read timeout is not retried
    in-run; only a connection failure and a 429 are (H11, H15). The gateway read timeout is 60 s
    (H27).
  - **Resend-leg takes an explicit `requestId` list** (H16), and its "changed?" check compares
    `sharedTime` as instants (H17).
  - **The service has its own system user** (H30). It reads no App Configuration and does not
    check the cutover switch, which Results reads (H22, H26).
  - **Alerts page only for genuine issues**; known issues are counted on the dashboard and in the
    daily reconciliation (H34).
  - The engineering guidance (`CLAUDE.md`, `.claude/`), `README.md` and
    [API_CONTRACTS.md](API_CONTRACTS.md) are updated to match.
- **Scaffold aligned with design rev 3.6.**
  - `application.yaml`: gateway read timeout 60 s and the in-run retry rule (H15, H27); one
    system-user id, `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID` (name proposed), for both
    internal reads (H30); `distribution.resend.max-ids-per-run`, `distribution.body-retention` and
    `distribution.body-maintenance.cron` added (H16, H20); no App Configuration properties.
  - The Service Bus emulator queue has duplicate detection off (H14).
  - The local PostgreSQL is version 15, as in production.
  - The JSON Schema library is test scope only: the outbound validator it served is parked (H13).
- **Constitution 1.1.0 — an unreadable youth switch defaults and sends.** Design decision H8: a
  youth switch that cannot be read resolves to `distribution.youth-switch.default-when-unreadable`
  (`ACTIVE` by default), and the hearing is sent as normal. Under constitution 1.0.0 (design
  rev 3.1) the guidance was that nothing would be sent and the leg recorded
  `FAILED(youth-switch-unreadable)`; no code implemented it. P16 is now fixed under deviation DV-25.
  `youth-switch-unreadable` is no longer a failure reason. `youth_switch` records
  `UNREADABLE_DEFAULTED_ACTIVE` or `UNREADABLE_DEFAULTED_INACTIVE`. The unreadable counter gains a
  `defaulted` label. The property is added to `application.yaml`
  (`YOUTH_SWITCH_DEFAULT_WHEN_UNREADABLE`). The engineering guidance is updated to match.

### Added
- **Design digest.** [design/DIGEST.md](design/DIGEST.md) restates design rev 3.6's rules for spec
  authors and implementers, each with its design row id and no rationale.
- **Initial scaffold.**
  - **Build and quality gates.** A Gradle build on a Java 25 toolchain, with Spring Boot 4.1,
    Checkstyle, PMD (main and test rulesets) and a JaCoCo coverage ratchet at line 0.88 and branch
    0.85.
  - **Dependencies.** The libraries the design needs:
    - Spring Web, JDBC, Flyway and PostgreSQL;
    - Azure Service Bus and Azure Identity, through the Azure SDK BOM;
    - Lettuce;
    - Actuator, OpenTelemetry and Prometheus;
    - JSON Schema validation, now for tests only;
    - the estate starters `cp-auth-rules-filter` and `cp-audit-filter-springboot`;
    - for tests, Testcontainers (PostgreSQL, Azure, Toxiproxy) and WireMock.
  - **Package modules.** These live under `uk.gov.hmcts.cp.probationresultsdistribution`: `inbound`,
    `application`, `pipeline`, `adapter.payload`, `adapter.usersgroups`, `adapter.gateway`,
    `persistence`, `domain`, `config` and `api`. Each has a `package-info.java` that states its
    responsibility and its allowed dependencies.
  - **Configuration.** The property catalogue in `application.yaml`, with environment-variable
    placeholders and no packaged secrets.
  - **Local stack.** Postgres, the Service Bus emulator with the `probation.requests` queue
    definition, Redis and WireMock, plus a container smoke script.
  - **CI.** GitHub Actions workflows for build and publish (draft and release), PMD analysis,
    CodeQL with SBOM and DAST, secrets scanning, Dependabot auto-merge and post-merge catch-up.
    The `main` branch ruleset lives in the repository settings, imported once from a
    `.github/rulesets/main.json` definition that is not kept in the tree.
  - **Spec Kit.** Spec Kit and the engineering constitution.
  - **Contracts.** [API_CONTRACTS.md](API_CONTRACTS.md) records the inbound queue contract, the
    outbound HMPPS publish and the operations API's decided actions.
