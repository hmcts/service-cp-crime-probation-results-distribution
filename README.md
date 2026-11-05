# service-cp-crime-probation-results-distribution

Each time a hearing's results are shared on the Common Platform, the probation feed sends the
redacted results to HMPPS (His Majesty's Prison and Probation Service). Today that feed is a Node.js
Azure Function App triggered from Event Grid. It reads the hearing payload from the Redis claim
check, applies the HMPPS redaction rules, removes youth defendants while the users-groups
`EMYouthFilter` switch is active, and posts the result through the APIM gateway. It keeps no record
of what it sent, and it reports success even when a send fails.

This service replaces that function app with one Spring Boot service on AKS. It consumes one
command per shared hearing from the Azure Service Bus queue `probation.requests`, which results'
flag-gated producer publishes to. It reads the hearing's `EXT_` payload from Redis, falling back to
the results query API, and reads the users-groups youth switch for that message. It applies the
HMPPS rules **verbatim** and posts the result to the gateway's HMPPS route, keeping the exact body
it sent for six months. Every admitted share ends with a recorded outcome for its leg, including how
the receiver answered. Every failure is logged and counted; genuine issues raise an alert, and known
ones are counted on the dashboard. Nothing ends in silence.

The rules are **ported, not fixed**: what is sent to HMPPS is what the function app sends today
("lift and shift"). The **delivery** defects around the rules are fixed: no timeouts, no retries,
no record and swallowed failures. Each fix is a registered deviation with its own pinning test.

**Scope.**

- The service is **HMPPS-only**. The function app's second receiver is not built, and it is
  decommissioned with the app.
- The youth filter is **kept at legacy parity**. The service follows whatever the live
  `EMYouthFilter` toggle says, so cutover changes no youth behaviour. If the switch cannot be
  read, the configured default (`ACTIVE` unless set otherwise) is assumed and the hearing is
  still sent (H8, DV-25); that default must match the live toggle.

| Field     | Value                                                 |
|-----------|-------------------------------------------------------|
| Team      | Resulting Assistant                                   |
| Programme | Crime Common Platform (CPP) — Modern by Default (MbD) |
| Stack     | Spring Boot 4.1, Java 25, Gradle                      |
| Package   | `uk.gov.hmcts.cp.probationresultsdistribution`        |
| Release   | `probation-results-distribution`                      |
| Ports     | 8082 local / 4550 Kubernetes                          |

## Design

The design lives on Confluence and is the authority for what this service does and why:

**[Probation Results Distribution Service](https://hmcts.atlassian.net/wiki/spaces/CRA/pages/343377459/Probation+Results+Distribution+Service)**
(CRA space). It covers the as-is function app and gateway route, the to-be architecture, the
producer increment in `cpp-context-results`, cutover and rollback, and the open questions. This
repository follows its rev 3.6 (decisions H1–H34) and H35 (2026-10-21).

This repository carries no design narrative of its own. What it does carry:

| Artefact | Location | Purpose |
|---|---|---|
| Design digest | [doc/design/DIGEST.md](doc/design/DIGEST.md) | The design's rules for spec authors and implementers, each with its design row id; no rationale. The design wins where they differ |
| Engineering constitution | [.specify/memory/constitution.md](.specify/memory/constitution.md) | The non-negotiable principles: parity with registered deviations, TDD, message contract first, ports and adapters, explicit failure, privacy in telemetry, estate conventions, HMPPS-only scope |
| Contracts | [doc/API_CONTRACTS.md](doc/API_CONTRACTS.md) | The inbound `probation.requests` message, the outbound HMPPS publish and the operations API |
| Deviation register | [doc/DEVIATIONS.md](doc/DEVIATIONS.md) | Every intended difference from the legacy (DV-01 … DV-26; next free DV-27), with its pinning test, and the carried and pending-external defects. Any unregistered difference fails the build |
| Changelog | [doc/CHANGELOG.md](doc/CHANGELOG.md) | Notable changes |
| Specifications | `specs/` | Spec Kit increments: spec, plan, research, data model, tasks and checklists. None yet; the first feature starts with `/speckit-specify` |
| Working conventions | [CLAUDE.md](CLAUDE.md) | Build loop, contract rule and build and test commands |

## Architecture

```
cpp-context-results (WildFly)                     probation-results-distribution (AKS, Spring Boot)
┌───────────────────────────────────┐            ┌──────────────────────────────────────────────┐
│ HearingResultedEventProcessor     │  ASB queue │ inbound/      listener · parser · schema     │
│  · Redis EXT_ / INT_ writes       │ probation. │ application/  idempotency guard · runner     │
│  · request-probation-distribution │  requests  │               · leg resender                 │
│    -publish, gated on the         │──(+DLQ)───▶│ pipeline/     HMPPS redaction (pure)         │
│    ProbationResultsDistribution-  │            │ adapter/      payload · usersgroups · gateway│
│    Service flag                   │            │ persistence/  processed_request ·            │
└───────────────────────────────────┘            │               outbound_message ·             │
                                                 │               outbound_body                  │
                                                 │ api/          operations: replay · resend    │
                                                 └───┬──────────────┬───────────────┬───────────┘
          Redis EXT_{hearingId}_{hearingDay}_result_ ◀┘              │               │
          miss ⇒ results query API (hearingDetails)                  │               ▼
          users-groups permission EMYouthFilter ◀────────────────────┘     APIM gateway, HMPPS route
                                                                                 │
                                                                                 ▼
                                                                    HMPPS court-hearing-event receiver
```

- **Inbound.** The service owns the ASB queue `probation.requests` and its dead-letter queue. It
  uses peek-lock with auto-complete off. Each delivery is settled exactly once: complete, abandon
  or dead-letter. Broker duplicate detection is **off**: the service's own processed log is the only
  duplicate protection, and every copy of a message reaches it. The lock duration is 5 minutes,
  `maxDeliveryCount` is 5, there are no sessions and concurrency starts at 2. The message is a
  claim check: it names the hearing share, and the payload is read separately.
- **Reads.**
  - The `EXT_` payload comes from Redis (Lettuce), by the dated key and then the undated key.
  - On a miss, the results query API is the fallback.
  - The users-groups `EMYouthFilter` permission is read once per message, with the legacy's
    3 × 1 s retry and finite timeouts.
  - The users-groups read is made as the service's own system user. The results query API
    fallback is made as the command's user or, when the command has no `userId` and on every
    resend, as the service's own system user (DV-17, H30).
- **Pipeline.** The HMPPS rules are a pure function over the payload. The youth rules apply only
  while the switch reads active. If the switch cannot be read, the leg assumes the configured
  default (`ACTIVE`, matching production today) and the hearing is sent as normal. Today nothing is
  sent in that case. The row records `UNREADABLE_DEFAULTED_ACTIVE` (or `_INACTIVE`), and the read
  is counted and alerted (DV-25).
- **Outbound.** There is one HTTPS POST to the APIM gateway's HMPPS route. It carries the existing
  gateway subscription key, so the gateway itself does not change. The body is not validated
  against any schema: it is the function app's redacted body, unchanged, and the parity harness is
  what guards it. Timeouts are 5 s to connect and 60 s to read. Only a connection failure and a
  429 are retried in-run, with a bounded backoff that honours `Retry-After`. A 5xx, a 408 or a read
  timeout leaves the outcome unknown, and the HMPPS POST is not idempotent, so the leg parks for
  broker redelivery instead of retrying.
- **Store.** PostgreSQL 15, owned by this service and migrated by Flyway. It holds:
  - `processed_request`, the idempotency key `(source, requestId)` with a
    `RECEIVED → RETRYING → COMPLETED | FAILED` state machine;
  - `outbound_message`, one row per leg per attempt, carrying the body digest, the build's
    `service_version`, the `sharedTime` the attempt read, the receiver's status and the reason;
  - the view `outbound_message_latest`, each leg's current state;
  - `outbound_body`, the exact bytes POSTed, partitioned by month and kept for six to seven
    months.
- **Shape.** Ports and adapters: the application layer depends on interfaces only. There is one
  image, with no batch job. Its one scheduled task is the daily body-retention job, which
  pre-creates the monthly partitions and drops expired ones. There is **no business REST API**. The
  HTTP surface is Spring Boot Actuator and the operations API under `/operations/**`, decided for
  replay and resend-leg, including a forced resend. The other operator actions (pause and resume
  intake, submit-hearing, purge-queue and the rest) run as command-line runners until their HTTP
  exposure is decided. The operations API's estate starters, `cp-auth-rules-filter` and
  `cp-audit-filter-springboot`, are already on the classpath.

Package modules under `uk.gov.hmcts.cp.probationresultsdistribution`. Each module's
`package-info.java` states what it is for and what it may depend on.

| Package | Responsibility |
|---|---|
| `inbound` | Service Bus listener, message parser, closed inbound schema and settlement |
| `application` | Idempotency guard, distribution runner, leg resender (resend by `requestId` list, forced resend) and the submit-hearing action |
| `pipeline` | HMPPS redaction rules: pure, with no I/O |
| `adapter.payload` | Redis `EXT_` reads and the results query API fallback |
| `adapter.usersgroups` | The per-message `EMYouthFilter` permission read |
| `adapter.gateway` | The HMPPS publisher behind the APIM gateway |
| `persistence` | `processed_request`, `outbound_message` and `outbound_body` repositories, and the store probe |
| `domain` | Value types and the state vocabulary |
| `config` | Typed properties and their startup validation |
| `api` | The operations API: replay and resend-leg |

## Status

**Scaffold only.** The repository builds, runs its gates and carries every library the design
needs, but it has no business code and no Flyway migration yet. The first increment starts with
`/speckit-specify`, under the constitution, written against the design digest.

There is no outbound HMPPS schema in this release. Outbound schema validation is parked, so there
is no pre-build gate. The HMPPS interface control document is recorded as information only: its
schema is out of date and the receiver does not enforce it.

## Prerequisites

- ☕️ Java 25 on `PATH`. The build resolves a 25 toolchain; always use `./gradlew`, never a system
  Gradle.
- 🐳 Docker, for the compose stack: Postgres, the Service Bus emulator with its SQL Server
  companion, Redis and WireMock. The `*IT` suites use the same images through Testcontainers.

## Quickstart

```bash
./gradlew build                 # compile + tests + PMD + Checkstyle + JaCoCo gate
./gradlew test                  # test suite only; the *IT suites in it need Docker
./gradlew test -PexcludeTags=timing  # the same, without wall-clock cases (local hatch only)
./gradlew checkstyleMain        # style gate on main sources
./gradlew pmdMain               # PMD on main sources; `check` runs pmdMain and pmdTest as well
./gradlew jacocoTestReport      # coverage report → build/reports/jacoco
./gradlew bootRun               # local run against docker-compose dependencies (see below)
./scripts/container-smoke.sh    # packaged-artefact smoke: compose up, then the readiness gate
```

**The JaCoCo gate is a ratchet.** The thresholds in `gradle/test.gradle` start at line 0.88 and
branch 0.85. They may be raised but never lowered in passing, and `ci-build-publish.yml` fails a PR
that lowers one. `-PexcludeTags` is a local hatch only: CI runs every tag.

### Local run

Start the dependencies, then run the service against them:

```bash
docker compose up -d postgres servicebus-emulator redis wiremock

SPRING_PROFILES_ACTIVE=local \
SERVICEBUS_CONNECTION_STRING='Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;' \
REDIS_PORT=6379 REDIS_SSL=false \
RESULTS_BASE_URL=http://localhost:8089 \
USERSGROUPS_BASE_URL=http://localhost:8089 \
HMPPS_PUBLISH_URL=http://localhost:8089/probation/api/v1/hearing/results \
PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID=00000000-0000-0000-0000-000000000000 \
GATEWAY_SUBSCRIPTION_KEY=local-only-not-a-key \
AUTHZ_HTTP_ENABLED=false HTTP_AUDIT_ENABLED=false \
  ./gradlew bootRun
```

To run the service in its own container instead, use `docker compose up app`. The `app` service
sets the same variables, using compose-network hostnames.

- **Compose is local-only.** Every credential in `docker-compose.yml` is a development default
  that no deployed environment uses. The Service Bus string is the emulator's published
  development string.
- **`bootRun` does not inherit compose environment variables**, which is why the command above
  passes them itself.
- **The `local` profile** relaxes three deployed-only startup checks the compose stack cannot
  satisfy: Redis TLS with peer verification, workload identity, and an https HMPPS route. Both the
  command above and the compose `app` service activate it; no deployed environment does.
- **The emulator's queue definition** lives in `docker/servicebus-emulator/config.json`. The
  Testcontainers harness mounts the same file, so local, CI and deployed queue properties cannot
  drift.
- **WireMock** stands in for the results query API, the users-groups read and the gateway's HMPPS
  route on one port: `http://localhost:8089` from the host and `http://wiremock:8080` inside the
  network. Its mappings arrive with the features that call them; see
  [docker/wiremock/README.md](docker/wiremock/README.md).

Health: `GET http://localhost:8082/actuator/health/readiness`. Readiness is gated on the store only.
The broker is deliberately never a readiness input: a broker blip must not roll the pods.

## Configuration

Every property is in `src/main/resources/application.yaml`, under `distribution.*`, with its
default and the reason for it. Secrets are environment bindings mounted from Key Vault through the
Secrets Store CSI driver. They are never committed values. An empty default leaves a local run with
nothing to borrow.

| Environment variable | Property | Default | Notes |
|---|---|---|---|
| `SERVER_PORT` | `server.port` | `8082` | 4550 in Kubernetes |
| `SPRING_DATASOURCE_URL`, `_USERNAME`, `_PASSWORD` | `spring.datasource.*` | local compose values | Key Vault in deployed environments |
| `SERVICEBUS_CONNECTION_STRING` | `distribution.servicebus.connection-string` | empty | Local and CI only (the emulator). Exactly one of this and `SERVICEBUS_NAMESPACE` |
| `SERVICEBUS_NAMESPACE` | `distribution.servicebus.namespace` | empty | Deployed environments, with workload identity |
| `SERVICEBUS_QUEUE` | `distribution.servicebus.queue-name` | `probation.requests` | Stack-prefixed when deployed |
| `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE` | — | — | Workload identity, read by `azure-identity` itself |
| `REDIS_HOST`, `REDIS_PORT` | `distribution.redis.host`, `.port` | `localhost`, `10000` | The `EXT_` payload cache |
| `REDIS_KEY` | `distribution.redis.key` | empty | Key Vault |
| `REDIS_SSL` | `distribution.redis.ssl`, `.verify-peer` | `true` | TLS with peer verification; `false` only for the local compose Redis |
| `RESULTS_BASE_URL` | `distribution.results.base-url` | `http://localhost:8080` | Results query API, the payload fallback |
| `PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID` | `distribution.results.system-user-id`, `distribution.usersgroups.system-user-id` | empty | **Required.** The service's own system user: the `CJSCPPUID` on every users-groups read, and on the results fallback when the command has no `userId` and on every resend. One id for both. Key Vault. Variable name proposed |
| `USERSGROUPS_BASE_URL` | `distribution.usersgroups.base-url` | `http://localhost:8080` | The youth-switch read, and the operations API's group resolution |
| `EM_YOUTH_FILTER_FEATURE_KEY` | `distribution.usersgroups.youth-filter-key` | `EMYouthFilter` | The permission key the legacy reads |
| `YOUTH_SWITCH_DEFAULT_WHEN_UNREADABLE` | `distribution.youth-switch.default-when-unreadable` | `ACTIVE` | `ACTIVE` or `INACTIVE`: the value assumed when the youth switch cannot be read. Logged at startup. **Must equal the environment's live `EMYouthFilter` state**; check it at go-live and after any change to the switch. Variable name proposed |
| `HMPPS_PUBLISH_URL` | `distribution.hmpps.url` | empty | **Required**, https (relaxed under the `local` profile only). The gateway's HMPPS route |
| `GATEWAY_SUBSCRIPTION_KEY` | `distribution.gateway.subscription-key` | empty | **Required.** Sent as `Ocp-Apim-Subscription-Key`. Key Vault, never committed |
| `AUTHZ_HTTP_ENABLED` | `authz.http.enabled` | `true` | `cp-auth-rules-filter`, default-deny |
| `AUTHZ_RELOAD_RULES` | `authz.http.reload-on-each-request` | `false` | Only for local rule editing |
| `HTTP_AUDIT_ENABLED` | `audit.http.enabled` | `true` | `cp-audit-filter-springboot`, which never includes payload bodies |
| `HTTP_AUDIT_OPENAPI_SPEC` | `audit.http.openapi-rest-spec` | `probation-results-distribution-openapi.yaml` | Ships now as an empty-paths placeholder so the filter starts; the operations API fills it in |
| `CP_AUDIT_ENABLED` | `cp.audit.enabled` | `false` | The estate audit transport. Required wherever the operations API is served |
| `CP_AUDIT_INITIAL_CONNECT_ATTEMPTS` | `cp.audit.jms.initial-connect-attempts` | `2` | |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | — | — | Read by the Application Insights Java agent (`lib/applicationinsights.json`). Key Vault |

The queue and time-budget properties are deliberately **not** environment-driven: `max-delivery-count`,
`lock-duration`, the claim `lease` and `processing-deadline`, and the per-stage `budget`. Each
mirrors a broker setting or a relationship that startup validates, so changing one means changing
it here and on the queue together. Nor are the gateway's timeouts and retry block: connect 5 s,
read 60 s, and a retryable set fixed in code.

Three more properties have no environment variable, as in the design's property catalogue; whether
they should get one is still open:

- `distribution.resend.max-ids-per-run` (default `100`): the longest `requestId` list one resend-leg
  run takes;
- `distribution.body-retention` (default `P6M`): how long stored bodies are kept, in whole months;
- `distribution.body-maintenance.cron` (default `0 15 2 * * *`, in UTC): when the daily
  body-retention job runs.

Deployment values (Helm, Key Vault, the stack-prefixed queue) live in `cpp-aks-deploy`, not in
this repository.

## CI/CD

GitHub Actions, in `.github/workflows/`:

| Workflow | Trigger | What it does |
|---|---|---|
| `ci-draft.yml` — *Build and Publish (Non-Release)* | PR and push to `main` / `team/**`, manual | Calls `ci-build-publish.yml`: Artefact-Version, Build, Test (`jacocoTestReport check` plus the coverage-ratchet guard), Container-Smoke, publishes the artefact and builds the image |
| `ci-build-publish.yml` | `workflow_call` | The reusable pipeline above. On publish it pushes the image to GitHub Packages and triggers the ADO deploy pipeline |
| `ci-released.yml` — *CI Build and Publish – Release* | Release published, manual | The same pipeline with a release version, then updates the release notes |
| `code-analysis.yml` | PR | `pmd-analysis`: `./gradlew pmdMain` against `.github/pmd-ruleset.xml` |
| `codeql.yml` | PR, push, weekly, manual | CodeQL `Analyze (java)`, a CycloneDX SBOM and an OWASP ZAP baseline scan |
| `secrets-scanner.yml` | PR, push, weekly, manual | `scan`: the HMCTS secrets scanner (gitleaks); false positives go in `.gitleaksignore` |
| `auto-merge-dependabot.yml` | PR from Dependabot | Approves and enables auto-merge |
| `post-merge-catch-up.yml` | PR closed on `main`, every 15 minutes, manual | Dispatches the build, CodeQL and secrets workflows for a `main` commit that has no run. A merge made with the Actions token creates no `push` run |

Dependabot (`.github/dependabot.yml`) tracks Gradle dependencies and Actions. Every third-party
action is pinned to a commit SHA.

### Branch protection

`main` is protected by a repository ruleset. It lives in the repository settings, imported once from
a `.github/rulesets/main.json` definition that is not kept in the tree. The ruleset enforces:

- no deletion and no force-push;
- a pull request with one approval and all review threads resolved;
- strict, up-to-date status checks: `Analyze (java)`, `CodeQL`, `pmd-analysis`,
  `ci-draft / Build`, `ci-draft / Artefact-Version`, `ci-draft / Test` and `scan`.

Repository admins may bypass it.

### Contribute to this repository

See [.github/CONTRIBUTING.md](.github/CONTRIBUTING.md). Repository working conventions live in
`CLAUDE.md`. The engineering constitution is `.specify/memory/constitution.md`, and it takes
precedence where they overlap.

## Licence

Released under the MIT Licence — see [LICENSE](LICENSE).
