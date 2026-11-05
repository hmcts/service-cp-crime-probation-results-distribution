# Implementation Plan: HMPPS distribution

**Branch**: `001-parity-kernel` (planning checkout; target `002-hmpps-distribution`)

**Date**: 2026-10-21

**Spec**: `specs/002-hmpps-distribution/spec.md`

**Input**: Clarified feature spec, design digest rev 3.6 + H35, constitution v2.0.1, and slice 001.

## Summary

Build the HMPPS business path on the existing Spring Boot service: consume a closed queue command;
claim its `(source, requestId)` in PostgreSQL; read the payload from Redis or results; read and
default the youth switch; apply slice 001's redaction; serialize, digest and POST the exact body;
atomically record the outcome and stored bytes; then settle the broker delivery. Flyway V1, owned
ports, closed vocabularies, metrics, logs and validated configuration are the shared foundation
for slices 003–006. A 3xx parks without redirect following and uses `http="other"`; a `PARKED`
rerun does not increment the crash-rerun counter. No business REST endpoint, replay admission,
resend, retention scheduler or deployed alert rule is implemented in this slice.

## Technical Context

**Language/Version**: Java 25, using the existing Gradle toolchain and Spring Boot 4.1.1.

**Primary Dependencies**: Existing Spring JDBC/Flyway, PostgreSQL driver, Azure Service Bus SDK
and identity, Lettuce, Jackson 3, Micrometer/Prometheus, Actuator/OpenTelemetry, Logstash JSON
encoder. No new runtime library is planned (`build.gradle`; research R1).

**Storage**: PostgreSQL 15; Flyway V1 creates `processed_request`, `outbound_message`,
`outbound_body`, and `outbound_message_latest`. The body table is monthly range-partitioned with a
DEFAULT partition and digest trigger. Redis `EXT_` keys are a read-only claim check, not a second
durable ledger (spec FR-002-050–056; research R3–R4).

**Testing**: JUnit Jupiter and AssertJ; PostgreSQL and Azure Service Bus Testcontainers for
transactions, fencing and settlement; WireMock for results, users-groups and gateway branches;
schema-vocabulary tests; existing slice 001 parity/corpus suite. All tests run through
`./gradlew test`, and build includes PMD, Checkstyle and JaCoCo.

**Target Platform**: Existing Linux container on AKS, with Azure Service Bus, PostgreSQL 15,
Redis and HTTP services reached through adapters. Docker Compose and the broker emulator serve
local development.

**Project Type**: One existing message-driven Spring Boot service. This slice exposes a queue
consumer and calls external services; HTTP exposure remains Actuator only until later operations
API slices (constitution Principle III; spec Context).

**Performance Goals**: Payload, switch and send stages stay within 60 s, 25 s and 130 s; their
215 s total is below the four-minute deadline. The 4m30s claim lease and five-minute broker lock
preserve that ordering. Initial concurrency is two deliveries per pod; no throughput rate is
asserted before measurement (spec FR-002-013–014; Assumptions).

**Constraints**: Manual settlement exactly once after durable state; no in-run retry after a
possibly sent HMPPS POST; exact UTF-8 body bytes sent, hashed and stored; no body in logs or metric
labels; JSON-tree hearing payload and typed service-owned decisions; no outbound schema validator,
VEP leg or App Configuration read. The final permitted delivery terminates explicitly.

**Scale/Scope**: One HMPPS leg and one attempt-1 row per admitted share in this slice, with in-place
reruns on redelivery. V1 contains future-slice vocabulary and columns. Initial deployment is one
to two replicas at concurrency two per pod; traffic volume is not measured.

## Constitution Check

*GATE: evaluated before research and re-evaluated after Phase 1 design. No unjustified principle
violation is present. The target `002-hmpps-distribution` branch must be created from `main` before
implementation; the dirty `001-parity-kernel` checkout is used only to generate reviewable
planning artifacts.*

| Principle | Gate | Plan evidence |
|---|---|---|
| I. Behaviour Parity with Registered Deviations | PASS | Reuse slice 001's redaction; preserve carried quirks; require each intended difference in `doc/DEVIATIONS.md` with a pinning test. No body-shape change is planned. |
| II. Test-Driven Development | PASS at task gate | `tasks.md` must place compile-safe red tests before implementation; integration tests cover bytes, claims and settlement. |
| III. Message-Contract First | PASS | `contracts/` documents queue and gateway exchanges; the runtime JSON Schema and contract tests precede consumer code. No new REST API or outbound validator. |
| IV. Canonical JSON In, Typed Models Out | PASS | Hearing payload stays Jackson `JsonNode`; command, outcome, row and vocabulary types are bounded records/enums; one serialization yields sent, hashed and stored bytes. |
| V. SOLID with Ports and Adapters | PASS | The core owns payload, switch, sender and outbound-log ports; adapters own Azure, Redis, HTTP and JDBC types. Slice 001 redaction remains pure. |
| VI. Explicit Failure | PASS | Every delivery is completed, abandoned or dead-lettered once; claims fence stale writers, stages have bounds, and failure has a durable/logged/metric outcome. |
| VII. Privacy in Telemetry | PASS | MDC omits `userId`; no payload or body is logged; labels are closed, including `http="other"`; stored body is purged by slice 003. |
| VIII. Estate Conventions | PASS for design | Extend existing Gradle project and packages; retain PMD, Checkstyle and coverage gates. Implement on sequential branch `002-hmpps-distribution`, then PR to `main`. |
| IX. Scope — HMPPS Leg Only | PASS | No VEP, Results switch/App Configuration, business REST endpoint, or gateway/receiver change. |

The plan makes no principle exception; Complexity Tracking is empty. The clarified `http="other"`
bucket fills a design omission; the local digest and `doc/API_CONTRACTS.md` now record it as a
slice 002 clarification. It does not override a conflicting design rule.

## Project Structure

### Documentation (this feature)

```text
specs/002-hmpps-distribution/
├── spec.md
├── plan.md
├── research.md
├── data-model.md
├── quickstart.md
├── contracts/
│   ├── inbound-command.md
│   ├── hmpps-gateway.md
│   └── consumed-reads.md
└── tasks.md                  # Phase 2 output from /speckit-tasks
```

### Source Code (repository root)

```text
src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── application/              # DistributionRunner and owned ports
├── domain/                   # command, run/outcome types and vocabularies
├── pipeline/                 # existing pure HmppsRedaction from 001
├── inbound/                  # Service Bus listener, validation, manual settlement
├── adapter/payload/          # Redis EXT_ and results-query fallback
├── adapter/usersgroups/      # EMYouthFilter read and defaulting
├── adapter/gateway/          # HMPPS POST and response mapping
├── persistence/              # JDBC guard, log, body and claim fencing
└── config/                   # validated properties, wiring, metric registry

src/main/resources/
├── contracts/distribution-command.schema.json
└── db/migration/V1__create_processed_log.sql

src/test/java/uk/gov/hmcts/cp/probationresultsdistribution/
├── application/              # runner and state-machine tests
├── inbound/                  # settlement and command contract tests
├── adapter/                  # HTTP/Redis retry and failure tests
├── persistence/              # V1, claim, trigger, view and transaction tests
└── config/                   # vocabulary, configuration and metric tests
```

**Structure Decision**: Extend the existing single service and package-info seams; do not create
a second module or test source set. Class-per-file breakdown is a Phase 2 task decision, while the
owned-port boundary and contract resource paths are fixed here.

## Phase 0: Research

`research.md` records nine decisions covering stack, contracts, idempotency, body storage,
budgets and retry boundaries, the 3xx outcome, telemetry, verification, and deferred facts. There
are no unresolved technical questions. Unmeasured volume and the unknown gateway
request-id header have explicit interim behavior and later owners.

## Phase 1: Design & Contracts

`data-model.md` defines V1 entities, presence checks, relationships and transitions. `contracts/`
documents the inbound command and each external exchange without creating a new endpoint.
`quickstart.md` shows local prerequisites, run/test commands and the feature's proof matrix. The
runtime inbound schema is an implementation deliverable and precedes listener code. Phase 1 design
leaves the Constitution Check above at PASS.

**Agent context**: The `<!-- SPECKIT START -->` block in `CLAUDE.md` points to this plan as the next
feature context while retaining slice 001 status.

## Complexity Tracking

No principle violation requires a complexity exception. The external adapters and three durable
tables are the required baseline design, not exceptions.
