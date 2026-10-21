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
