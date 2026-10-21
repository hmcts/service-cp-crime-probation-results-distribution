# Coding Conventions — MOJ / CPP Standard

## Dependency Injection

- Constructor injection ONLY — NEVER use `@Autowired` on fields
- All injected fields MUST be `private final`
- Use Lombok `@RequiredArgsConstructor` OR an explicit constructor
- The application layer injects **port interfaces**, never adapter classes

```java
// CORRECT
private final HearingPayloadSource hearingPayloadSource;
private final YouthFilterSource youthFilterSource;

public DistributionRunner(HearingPayloadSource hearingPayloadSource,
                          YouthFilterSource youthFilterSource) {
    this.hearingPayloadSource = hearingPayloadSource;
    this.youthFilterSource = youthFilterSource;
}

// WRONG — never do this
@Autowired
private CachedHearingPayloadAdapter adapter;
```

## DTOs and Data Classes

- Java records for ALL value types — immutable by design
- `DistributionCommand` (inbound message) is a record; parse it from `JsonNode`, validate explicitly
- **JsonNode-canonical inbound**: the hearing payload stays a Jackson tree end to end. The HMPPS body
  is the redacted tree (the legacy sends the filtered JSON) — not bound to a typed model and, in this
  release, not validated against any schema (H13); its exact bytes are digested and stored
- What this service originates — decisions, outcomes, processed-log rows, operator commands — is
  typed records and enums
- `ObjectMapper` configured with the Jackson 3 equivalent of `USE_BIG_DECIMAL_FOR_FLOATS`
- Use sealed interfaces for polymorphic types (e.g. `YouthFilterDecision`, `PublishOutcome`)

## Error Handling

- Custom exceptions extending `RuntimeException`; classify at the throw site as transient or
  non-transient
- **NEVER swallow exceptions.** No empty catch blocks, no catch-and-log-and-continue, no returning a
  success value from a catch block. Catch only to classify and rethrow, or to map to a persisted leg
  outcome (`PARKED` / `FAILED` with a bounded reason) that is then settled explicitly
- The HMPPS publisher never throws past its outcome: it returns `SENT`, `FAILED_NON_TRANSIENT` or
  `TRANSIENT_EXHAUSTED`, and the runner maps the outcome to the leg's (status, reason)
- Where the legacy throws and swallows inside a rule, the port throws and reports:
  `FAILED(rules-exception)`, ERROR log, counted
- The message listener is the only place that converts a run's result into a settlement decision
- The operations API (design §4.8; H9) is the only HTTP request surface beyond actuator. Its
  refusals are **bounded API codes**, never row values and never exception text: `resend-in-progress`,
  `unknown-request`, `not-eligible`, `request-claimed`, and submit-hearing's `payload-unavailable`
  should that action be exposed (O-1). How they are rendered is the operations API spec's to settle,
  following YOT's operations API

## Messaging

- Peek-lock, auto-complete disabled; exactly one explicit `complete()` / `abandon()` / `deadLetter()`
  per delivery on every path
- `maxDeliveryCount` 5; concurrency 2 to start; lock 5 min; claim lease PT4M30S (strictly shorter
  than the lock); processing deadline 4 min
- `messageId` = `"RESULTS:{requestId}"`; `correlationId` = `hearingId`; broker duplicate detection
  **off** — the processed-log guard is the only duplicate protection (H14). The service's replay
  action re-sends the stored command under a **fresh** `messageId`; a dead-letter resubmission under
  the exhausted `messageId` is a replay at broker delivery count 1 (H9, DV-26)
- Operator markers (`origin = REPLAY`, `origin = SUPPORT`, `operator`, `runId`, `reason`) are broker
  application properties, never body fields
- Dead-letter reasons are the closed set `validation`, `collision`, `exhausted`, `leg-exhausted`
- Persist state **before** settling
- ASB processor health is registered as a liveness/details indicator only — **never** in the
  readiness group

## Outbound HTTP

- Spring `RestClient` with connect and read timeouts on every client; no client without them
- The **gateway** POST uses the `RetryPolicy` copied from YOT with a predicate this service passes,
  **fixed in code, never a property**: in-run retries only for a connection failure (the request
  never sent; a connect timeout included) and 429 (H11, H15). A 5xx, a 408 and a read timeout or I/O
  failure after the write are outcome-unknown and park at once. `Retry-After` honoured, capped by
  `max-backoff`; bounded attempts; timeouts connect 5 s / read 60 s (H27)
- The **results-query** read keeps YOT's retryable set: 408, 429, every status from 500, and
  connect and read failures (DV-23)
- On both, an attempt starts only if its worst case fits before the stage deadline, the wait
  included. Startup also validates the gateway's composed send stage: (attempts − 1) × (connect +
  `max-backoff`) + (connect + read) ≤ 130 s, 95 s with the defaults (row A3-96)
- The users-groups permission read uses the legacy's rule instead (`LegacyGetRetry`: 3 attempts,
  fixed 1 s wait, retry only an error without a response or a status above 429), connect 2 s /
  read 5 s (DV-24). A read that stays unreadable resolves to
  `distribution.youth-switch.default-when-unreadable` (`ACTIVE` | `INACTIVE`, default `ACTIVE`,
  validated and logged at startup) and the leg carries on (DV-25)
- No client certificate; the gateway key is `Ocp-Apim-Subscription-Key` from configuration
- Exact CPP vendor media types on the internal reads; asserted in tests

## Persistence

- Flyway migrations at `src/main/resources/db/migration/V<n>__<snake_case_description>.sql`
- Migrations are additive and forward-only; never edit an applied migration
- The copied informant DDL stays byte-identical; this service's additions go in a separate ALTER
  section (design Appendix B.2)
- The processed-log insert is the idempotency claim — a unique-constraint violation means duplicate
  delivery, and is handled, not logged as an error
- Every CHECK the design names is in the migration, and `SchemaVocabularyIT` asserts the SQL value
  sets against the Java enums. It also covers: three tables and one view;
  `outbound_message_presence_service_version_chk` (`service_version` ⇔ `body_digest`); and the seven
  `NoteForms` forms, of which `baseline=none` and `contract=invalid` are reserved, unused
- PostgreSQL 15, production's version (row A3-105). `outbound_body` is partitioned by month on
  `created_at` (UTC) with a DEFAULT partition; its partitions are created by the service role, which
  owns the database, so the role owns them and can detach and drop them. The digest trigger and the
  `ON DELETE RESTRICT` foreign key are part of V1
- Write order: the leg's outcome UPDATE (with `body_digest`) first, then the `outbound_body` INSERT,
  in the same transaction (H20)
- The body-retention job: single runner by a transaction-scoped advisory lock on a fixed key, its
  transaction open on its own for the whole run; the cut-off is computed in UTC and compared with
  each partition's bounds read from the catalogue, never parsed from its name; an expired month is
  removed by a plain `DROP` of its partition, with no `DETACH`, each `DROP` in its own short
  transaction that commits at once, never the lock's (H35); every partition statement runs under a
  5 s `lock_timeout` and one that fails is retried on the next run; stranded DEFAULT rows older than
  the cut-off are deleted; the scheduler runs with `zone = "UTC"` (row A3-64; H35)

## Enums and Routing

- Java enums for fixed value sets (`Leg`, `LegStatus`, `LegReason`, `CompletionReason`,
  `FailureReason`, `DeadLetterReason`, `YouthSwitchUnreadableCause`, `AttemptOrigin`), and for the
  bounded sets rev 3.6 adds — the replay route (`service`, `support`, `copy`), the resend refusal
  codes, the recovery `after` (`429-in-run`, `5xx-redelivery`) and the maintenance `step` (`lock`,
  `create`, `drop`); their type names are the specs' to choose
- Switch expressions for routing — the compiler enforces exhaustive coverage
- Include a `fromValue(String)` factory when parsing wire strings; unknown values are an explicit
  non-transient failure, never a silent default

## Logging

- SLF4J with Logback (via the Spring Boot starter)
- Lombok `@Slf4j` or `private static final Logger log = LoggerFactory.getLogger(...)` — the only
  allowed forms
- MDC on every message: `requestId`, `hearingId`, `hearingDay`, `source` (cleared in a `finally`)
- `LoggingEventCompositeJsonEncoder` emits structured JSON unconditionally (`logback.xml`)
- One log line per leg outcome: status, reason, HTTP status, body digest, `runOrigin`, for a replay
  its `route` (`service`, `support`, `copy`), for a forced resend `forced=true`, the `youth_switch`
  value recorded (`ACTIVE`, `INACTIVE`, `UNREADABLE_DEFAULTED_ACTIVE`,
  `UNREADABLE_DEFAULTED_INACTIVE`) and, for a defaulted read, its bounded cause; a defaulted read is
  logged at WARN, and so is a 2xx other than 200 (design §10.2)
- NEVER use `System.out.println`, `System.err.println`, or `Throwable#printStackTrace()`
- NEVER log secrets, tokens, subscription keys or connection strings
- **NEVER log defendant PII at `info` or above**, never a payload at any level in a deployed
  environment (DV-09), and **never the outbound body — sent or stored — at any level, in any
  profile** (row A3-60). Identifiers only

## Imports

- NEVER use wildcard imports (`import java.util.*`) — always explicit imports

## Naming Conventions

| Component        | Pattern            | Example                          |
|------------------|--------------------|----------------------------------|
| Application svc  | `*Runner` / `*Action` | `DistributionRunner`, `SubmitHearingAction` |
| Port (interface) | capability noun    | `HearingPayloadSource`, `YouthFilterSource`, `HmppsSender` |
| Adapter          | `*Adapter` / `*Client` / `*Publisher` | `CachedHearingPayloadAdapter`, `FeaturePermissionClient`, `HmppsPublisher` |
| Message listener | `*MessageListener` | `ProbationMessageListener`       |
| Repository       | `*Repository`      | `ProcessedRequestRepository`, `OutboundMessageRepository` |
| Record (in)      | `*Command`         | `DistributionCommand`            |
| Exception        | `*Exception`       | `PayloadUnavailableException`    |
| Config           | `*Configuration` / `*Properties` | `ServiceBusConsumerConfig`       |
| Test             | `*Test` / `*IT`    | `DistributionRunnerTest`, `YouthSwitchUnreadableIT` |

Names the design marks "proposed" are fixed at build time; keep them unless the spec says otherwise.

## Testing Conventions

- JUnit 5 + Mockito + AssertJ for unit tests; `@ExtendWith(MockitoExtension.class)`
- `@Nested` classes with `@DisplayName` for grouped scenarios
- Method naming: `{action}_{scenario}_should_{expectation}`
- The application layer is tested with plain mocks — **no Spring context**
- Testcontainers for integration tests (suffix `*IT`): the Service Bus emulator for the consumer,
  Postgres for the processed log, Redis for the payload cache, Toxiproxy for outage cases
- WireMock for external HTTP stubs (use `dynamicPort()`), asserting exact CPP vendor media types, the
  `CJSCPPUID` header and the gateway subscription-key header
- **Parity tests**: legacy Jest fixtures copied byte-identical into `src/test/resources/fixtures/`;
  one JUnit twin per Jest case; the SIT corpus payloads compared semantically after canonicalisation,
  BigDecimal throughout, with SIT's own logged HMPPS bodies (switch off) and with the executed legacy
  (switch on, synthetic cases) — `SitLoggedBodyParityTest`, name proposed (H21; row A3-67);
  registered deviations asserted by derivation
- **No production hearing data in this repository** — it is public. Fixtures are synthetic, copied
  legacy test fixtures, or SIT data — recorded SIT payloads and SIT's logged HMPPS bodies — under
  `src/test/resources/corpus/` (SIT holds no production data; PRD/PRP payloads and bodies are never
  committed)
- TDD: write the failing test first, see it fail for the right reason, then implement
- Logging in tests: SLF4J only
- Test commands: `./gradlew test` runs the whole suite — unit, integration and `*IT` classes alike,
  since there is no separate `integrationTest` task; the Testcontainers suites run under `test` and
  need Docker only when those tests are in the selection. `./gradlew build` = compile + `test` +
  **every analysis**, because all of them are in `check`: Checkstyle
  (`config/checkstyle/google_checks.xml`, `maxWarnings = 0`) over `checkstyleMain` and
  `checkstyleTest`, with `checkstyle-suppressions.xml` on the test sources; PMD, pinned in
  `gradle/pmd.gradle`, over `pmdMain` against `.github/pmd-ruleset.xml` and `pmdTest` against
  `.github/pmd-test-ruleset.xml`; and the JaCoCo coverage gate (`jacocoTestCoverageVerification`,
  LINE ≥ 0.88 / BRANCH ≥ 0.85, with `Application` and `config/**` excluded). Naming a task is a way
  to run one of them **sooner**, never a way to run one at all.
