# Research: HMPPS distribution

**Feature**: `002-hmpps-distribution`

**Date**: 2026-10-21

**Sources**: `specs/002-hmpps-distribution/spec.md`, `doc/design/DIGEST.md`,
`.specify/memory/constitution.md`, `doc/API_CONTRACTS.md`, `build.gradle`, and
`src/main/resources/application.yaml`. The three clarification decisions in the spec are inputs,
not questions reopened here.

## R1 — Build on the existing single service

**Decision**: Use Java 25, Spring Boot 4.1.1, Gradle, Jackson 3, Spring JDBC/Flyway, Azure Service
Bus SDK, Lettuce, Micrometer, JUnit Jupiter, Testcontainers, and WireMock already declared by the
repository. Place the runner and owned ports in the application core, with broker, store, Redis,
HTTP, and telemetry adapters around it. Reuse slice 001's pure `HmppsRedaction` and its parity
harness unchanged.

**Rationale**: The scaffold already carries these dependencies and package boundaries. The
constitution requires Gradle, ports and adapters, a JSON-tree payload, and parity with registered
deviations (Principles I, IV, V, VIII; spec FR-002-028, FR-002-071).

**Alternatives considered**: A new project/module or a typed hearing DTO would duplicate the
scaffold and risk dropping unknown fields. A second redaction implementation would break the
parity boundary.

## R2 — Make the inbound command the versioned contract

**Decision**: Vendor the closed Appendix B.1 JSON Schema to
`src/main/resources/contracts/distribution-command.schema.json` during implementation. Document
the queue envelope and its validation/settlement rules in `contracts/` now. Validate the body
before admission, then use typed records and enums for the command and decisions. Treat broker
application properties as metadata, never as command fields.

**Rationale**: The queue command, not an HTTP endpoint, is the business input. The schema excludes
unknown fields and null `userId`; the producer and consumer deploy independently (constitution
Principle III; spec FR-002-001–004). The runtime resource does not yet exist in this checkout.

**Alternatives considered**: An OpenAPI endpoint or an open-ended command would invent a new
interface and weaken validation. Copying a partial schema would drift from Appendix B.1.

## R3 — Use the processed log as the sole idempotency authority

**Decision**: In one admission transaction insert a `processed_request` keyed by
`(source, request_id)` and a `PENDING` attempt-1 `outbound_message`. Reclaim only absent or expired
claims atomically. Fence every outcome UPDATE with expected leg status and the request's claim
owner/token. Commit the leg outcome, request transition, and optional body row together; settle
the broker afterward. Rerun `PENDING` or `PARKED` in place without an intermediate row.

**Rationale**: Broker duplicate detection is off, and the non-idempotent HMPPS POST can cross a
crash window. The row guard, database time, and claim fencing are the specified protection
(spec FR-002-005–012, FR-002-040–049; digest §§7–8).

**Alternatives considered**: Broker message ID deduplication or an in-memory lock cannot protect
the full delivery lifecycle. Writing `PENDING` before each rerun contradicts FR-002-041 and the
clarified crash-counter rule.

## R4 — Preserve the exact outbound body

**Decision**: Serialize the redacted tree once to UTF-8 bytes; send, hash, and store those same
bytes. Flyway V1 creates the three tables and latest-attempt view, with monthly UTC partitions for
`outbound_body`, a DEFAULT partition, and a digest-checking trigger. On a rerun, delete the old
body and insert the replacement after the guarded outcome UPDATE in the same transaction.

**Rationale**: Support must recover exactly what was POSTed. V1 also reserves later slice 004
states, while slice 003 owns ongoing partition maintenance (spec FR-002-033, FR-002-042,
FR-002-050–056; digest §8).

**Alternatives considered**: Re-serializing for storage could differ bytewise. Storing normalized
JSON would erase the evidence the digest verifies. Per-row deletes would take over slice 003's
whole-month retention design.

## R5 — Keep each read and send within its own budget

**Decision**: Use Redis's dated then undated `EXT_` keys, followed by the results query API;
cache failure is a miss. Read users-groups for each message with the service system user. Apply
each client's retry predicate, connect/read timeout, and stage budget from the spec and existing
configuration. Retry an HMPPS POST in-run only after a connection that never sent the request or
a 429. A 5xx, 408, or failure after writing parks immediately.

**Rationale**: The processing stages total 215 seconds, below the four-minute deadline; the
claim lease and broker lock outlive it. The gateway POST is not idempotent (spec FR-002-013–025,
FR-002-032–035; `application.yaml`).

**Alternatives considered**: A common retry policy across clients would change legacy switch
semantics and could duplicate a gateway POST. A Redis outage as a terminal failure would skip the
required query fallback.

## R6 — Apply the clarified gateway response rule

**Decision**: Disable automatic redirect following. For an answer outside 2xx, 4xx, and 5xx,
including 3xx, return `TRANSIENT_EXHAUSTED` with the actual status, record
`PARKED(gateway-transient)` or final-delivery `FAILED(gateway-transient)`, and use
`http="other"`. Do not retry in-run.

**Rationale**: This is the answer recorded in the spec's 2026-10-21 Clarifications
(FR-002-038, FR-002-059). The local design digest and `doc/API_CONTRACTS.md` were aligned during
planning and tag the rule as a slice 002 clarification; the original design revision omitted it.

**Alternatives considered**: Bucket 3xx as 4xx or 5xx would make the metric misleading; treating
it as success would contradict the 2xx-only success rule.

## R7 — Fix bounded telemetry choices

**Decision**: Use `reason="none"` as the metric-only placeholder when a row has no reason. Derive
`shape="application-only"` only when the sent tree has a present, non-empty
`hearing.courtApplications` and no present, non-empty `hearing.prosecutionCases`; all other
outcomes use `shape="case"`. Refresh the two database-derived age gauges every 30 seconds with a
fixed schedule, adding no configuration property. Count `probation_crash_reruns_total` only for an
expired-claim `PENDING` attempt-1 rerun, never a `PARKED` rerun.

**Rationale**: Each label remains bounded; a 30-second refresh gives the 30-minute age alerts
ample resolution without a per-message database query. The paths and refresh schedule were
explicit plan choices left in the spec's Assumptions. The crash-counter scope is the third
clarification (FR-002-057–064).

**Alternatives considered**: Blank or NULL metric labels vary by exporter. A per-POST gauge query
adds load without improving the alert threshold. Counting all expired claims would overstate
possible duplicate sends.

## R8 — Verify at the contract, store, and transport boundaries

**Decision**: Use schema tests for the closed command and every V1 CHECK vocabulary, Postgres
Testcontainers for claim fencing, transitions, body trigger and view, the Service Bus emulator for
manual settlement and redelivery, WireMock for each HTTP branch, and the existing slice 001 parity
suite for body semantics. Run the repository's Gradle build gates; avoid a separate test source set.

**Rationale**: The behavior at risk includes broker/store races and exact bytes, which mocks of
only the application core cannot establish (spec SC-002-001–012; constitution Principles II, VI,
VIII).

**Alternatives considered**: Unit tests alone would miss transaction, trigger, and settlement
behavior. A new contract-testing platform is unnecessary for this local message contract.

## R9 — Keep external unknowns visible without blocking the slice

**Decision**: Leave `gateway_request_id` NULL until a go-live call identifies a response header.
Keep the spec's interim `FAILED(rules-exception)` outcome for a parseable payload without
`hearing`, pending design-owner review. Treat concurrency two per pod and one to two replicas as
starting configuration, not a measured throughput promise.

**Rationale**: Each case has a defined interim behavior and an owner or later gate (spec
Assumptions; digest §16). None requires a new technical choice to generate this plan.

**Alternatives considered**: Guessing a gateway header, a new leg reason, or a throughput target
would assert evidence the repository does not have.
