# Quickstart: HMPPS distribution

This is the planned workflow for slice 002. The queue consumer, V1 migration and adapters are
implementation work; this document does not imply they already exist. The current checkout holds
slice 001 and its parity tests. Follow `plan.md` and `data-model.md` when generating `tasks.md`.

## Before implementation

1. Create the `002-hmpps-distribution` feature branch from `main` and bring the reviewed spec and
   planning artifacts onto it. This plan was authored in the dirty `001-parity-kernel` checkout
   using `SPECIFY_FEATURE_DIRECTORY`; do not mix unrelated parity changes into the 002 commit.
2. Review the clarified `http="other"` gateway bucket in `spec.md`,
   `contracts/hmpps-gateway.md`, `doc/design/DIGEST.md` and `doc/API_CONTRACTS.md`. The local
   digest tags it as a slice 002 clarification to the original design revision.
3. Vendor design Appendix B.1 as `src/main/resources/contracts/distribution-command.schema.json`
   before writing the consumer. Keep `additionalProperties: false` and reject explicit
   `userId: null`. Do not create a business REST endpoint or outbound body schema validator.

## Local prerequisites

- JDK 25 and the committed Gradle wrapper.
- Docker for PostgreSQL 15, the Azure Service Bus emulator, Redis, and WireMock. Local service
  defaults and environment variable names are documented in `README.md` and
  `src/main/resources/application.yaml`; supply local-only credentials through the shell, never a
  committed file.
- Slice 001's `HmppsRedaction` and corpus tests remain on the classpath. The SIT corpus still needs
  its external capture to satisfy the eventual go-live parity gate; synthetic cases can verify
  development behavior now.

Start the local dependencies with:

```bash
docker compose up -d postgres servicebus-emulator redis wiremock
```

For a local `bootRun` after implementation, use the environment bindings in `README.md`'s local
run section and set the `local` profile. Supply a local emulator connection string, local Redis
port, system user id, gateway stub URL and development subscription key. The production profile
must require TLS and its real secrets; no production hearing is needed for this quickstart.

## Test in layers

```bash
./gradlew test
./gradlew build
./gradlew jacocoTestReport check
```

There is no separate `integrationTest` task. `test` includes unit and Testcontainers integration
tests; `build` also runs PMD, Checkstyle and the coverage gate. Run `jacocoTestReport check` when
reviewing a coverage failure so the report remains available. During task implementation, first
run the focused new red test, then implement its behavior and rerun it green, before broadening to
the full suite.

## Proof matrix for this slice

| Boundary | Required evidence |
|---|---|
| Command and admission | Closed schema accepts valid command, rejects unknown/missing/null fields; body invalidity dead-letters `validation` before inserting a request. |
| Idempotency and claim | Concurrent and sequential duplicates send no extra body; collisions dead-letter; expired claim is reclaimed with a fresh token; stale outcome UPDATE writes nothing. |
| Payload and switch | Dated Redis, undated Redis, then query fallback; cache failures are misses; users-groups retry rule, null first permission → `EMPTY_PERMISSIONS`, and unreadable default sends. |
| Redaction and bytes | Slice 001 corpus/parity remains green; the one UTF-8 serialization is byte-identical across POST, SHA-256 and `outbound_body`; a rerun replaces the body. |
| Gateway | 2xx sends; 4xx rejects; 408/5xx/3xx or post-write failure parks; 3xx is not followed and uses `http="other"`; only never-sent connection failure and 429 retry in-run. |
| Store and settlement | V1 CHECKs match Java vocabularies, trigger rejects digest mismatch, latest view chooses the highest attempt, outcome/request/body commit atomically, and every broker delivery settles once. |
| Operations signals | All bounded metric labels and structured log fields are present; no payload, stored body, `userId` or personal data appears in logs or labels. |

The integration suites should use the Service Bus emulator, PostgreSQL Testcontainers and
WireMock; they establish local behavior only. Go-live verification of gateway response headers,
real users-groups permissions, secrets, deployed alert rules, and SIT → STE replay belongs to the
later deployment gates.
