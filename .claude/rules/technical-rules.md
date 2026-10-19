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
  is the redacted tree (the legacy sends the filtered JSON), validated against the closed outbound
  schema before the POST — not bound to a typed model
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
- No `@ControllerAdvice` / `ProblemDetail` — there is no HTTP request surface to map errors onto
  until the operations API (design §4.8, O-1) is decided

## Messaging

- Peek-lock, auto-complete disabled; exactly one explicit `complete()` / `abandon()` / `deadLetter()`
  per delivery on every path
- `maxDeliveryCount` 5; concurrency 2 to start; lock 5 min; claim lease PT4M30S (strictly shorter
  than the lock); processing deadline 4 min
- `messageId` = `"RESULTS:{requestId}"`; `correlationId` = `hearingId`; broker duplicate detection
  on; replay re-sends the stored command under a **fresh** `messageId`
- Operator markers (`origin = REPLAY`, `origin = SUPPORT`, `operator`, `runId`, `reason`) are broker
  application properties, never body fields
- Dead-letter reasons are the closed set `validation`, `collision`, `exhausted`, `leg-exhausted`
- Persist state **before** settling
- ASB processor health is registered as a liveness/details indicator only — **never** in the
  readiness group

## Outbound HTTP

- Spring `RestClient` with connect and read timeouts on every client; no client without them
- Gateway and results-query reads use the `RetryPolicy` copied from YOT (408, 429, 5xx and
  connect/IO failures retried; `Retry-After` honoured and capped; bounded attempts; an attempt
  starts only if its worst case fits before the stage deadline, the wait included)
- The users-groups permission read uses the legacy's rule instead (`LegacyGetRetry`: 3 attempts,
  fixed 1 s wait, retry only an error without a response or a status above 429), connect 2 s /
  read 5 s (DV-24)
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
  sets against the Java enums

## Enums and Routing

- Java enums for fixed value sets (`Leg`, `LegStatus`, `LegReason`, `CompletionReason`,
  `FailureReason`, `DeadLetterReason`, `YouthSwitchUnreadableCause`, `AttemptOrigin`)
- Switch expressions for routing — the compiler enforces exhaustive coverage
- Include a `fromValue(String)` factory when parsing wire strings; unknown values are an explicit
  non-transient failure, never a silent default

## Logging

- SLF4J with Logback (via the Spring Boot starter)
- Lombok `@Slf4j` or `private static final Logger log = LoggerFactory.getLogger(...)` — the only
  allowed forms
- MDC on every message: `requestId`, `hearingId`, `hearingDay`, `source` (cleared in a `finally`)
- `LoggingEventCompositeJsonEncoder` emits structured JSON unconditionally (`logback.xml`)
- One log line per leg outcome: status, reason, HTTP status, body digest, `runOrigin`, the
  `youth_switch` state read and, for an unreadable switch, its bounded cause
- NEVER use `System.out.println`, `System.err.println`, or `Throwable#printStackTrace()`
- NEVER log secrets, tokens, subscription keys or connection strings
- **NEVER log defendant PII at `info` or above**, and never a payload or outbound body at any level
  in a deployed environment (DV-09). Identifiers only

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
  one JUnit twin per Jest case; the recorded corpus compared semantically after canonicalisation,
  BigDecimal throughout; registered deviations asserted by derivation
- **No real hearing data in this repository** — it is public. Fixtures are synthetic or copied legacy
  test fixtures
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
