# Quickstart: Operator recovery

This slice is implemented against slice 002's merged pipeline; it reuses slice 002's local stack
unchanged (`docker compose up -d`: the Service Bus emulator, PostgreSQL, Redis, WireMock) and adds
no new container.

## Build and test

```bash
./gradlew build              # compile + the full suite + PMD + Checkstyle + the JaCoCo gate
./gradlew test                # unit and *IT alike; Testcontainers suites need Docker
```

New test classes this slice adds, named per Dependencies → "Pinning tests the design names"
(`ReplayActionTest`, `DlqResubmissionReplayIT`, `RequestReplayStatementTest`, `LegRerunTest`,
`OutcomeMetricsTest`, `RequestRecoveryTransitionTest`, `ResendByRequestIdsIT`,
`ResendSharedTimeCheckIT`, `ResendConcurrencyIT`, `ForcedResendIT`, `RollbackFencingIT`,
`RequestIdRecipeTest`, and a `PropertiesValidatorTest` case for `max-ids-per-run`) all run under
`./gradlew test`, since there is no separate `integrationTest` task in this repository.

## Try it locally

With the local stack running and the service started normally (`./gradlew bootRun`):

1. **Seed a `FAILED` request.** Send a `probation.requests` message, let the gateway stub
   (WireMock) answer 500 on every delivery until `maxDeliveryCount` is reached, and confirm the
   request lands `FAILED(leg-exhausted)` and the message is on the dead-letter queue.

2. **Replay it via the runner**:
   ```bash
   java -jar build/libs/app.jar replay --request-id <the requestId>
   ```
   Then point WireMock's HMPPS stub at `200` and watch the replay message process: the leg's
   attempt-1 row re-runs in place to `SENT`, and the request becomes `COMPLETED(recovered)`.

3. **Resubmit the original dead-letter entry** from step 1 using the Service Bus emulator's tools
   (or the broker CLI against a real namespace) and confirm it is a no-op once the request above is
   `COMPLETED` — the runbook's "remove it, do not resend it" rule (FR-004-046).

4. **Resend a leg explicitly**:
   ```bash
   java -jar build/libs/app.jar resend-leg --leg HMPPS \
       --request-ids <requestId> --operator "local-dev" --reason "manual smoke test"
   ```
   Observe a new `resend` attempt row and the run's report on stdout/the log line.

5. **Force a duplicate** on a `SENT` leg:
   ```bash
   java -jar build/libs/app.jar resend-leg --leg HMPPS --request-ids <requestId> \
       --operator "local-dev" --reason "deliberate duplicate for HMPPS" --force-resend-sent
   ```
   Confirm the request stays `COMPLETED`, a new `resend` attempt row with `override=force-resend`
   is created, and `forced=true` appears on the outcome's log line.

6. **Pause, purge, resume**:
   ```bash
   java -jar build/libs/app.jar pause-intake
   # enqueue a message — confirm it is not taken
   java -jar build/libs/app.jar purge-queue
   java -jar build/libs/app.jar resume-intake
   # confirm deliveries resume
   ```

7. **Submit a hearing never admitted**: seed only a Redis `EXT_` key (no corresponding queue
   message, no request row), then:
   ```bash
   java -jar build/libs/app.jar submit-hearing --hearing-id <uuid> --hearing-day <YYYY-MM-DD> \
       --operator "local-dev" --reason "share never admitted"
   ```
   Confirm one command is enqueued, processed as a first delivery, and the resulting request's
   `audit_note` carries the operator, run id and reason.

8. **List, to choose IDs**:
   ```bash
   java -jar build/libs/app.jar list-requests --status FAILED
   java -jar build/libs/app.jar list-dlq-completed
   ```

## What "done" looks like for this slice

- Every acceptance scenario of the spec's eight user stories passes as a test, named per the
  Dependencies table.
- `SchemaVocabularyIT` still passes unchanged (no new CHECK, no new enum value beyond what slice 002
  already defined).
- The privacy sweeps (`TelemetryPrivacyTest`, `LogStatementSweepTest`) extend cleanly to every new log
  line, report and listing this slice adds, with zero findings.
- `./gradlew build` is green with the coverage thresholds unchanged (SC-004-013).
- If `research.md`'s provisional decisions (1, 2, 3) are later overturned by the design owner, each
  is isolated to the single component `research.md` names — confirmed by re-running this quickstart
  after making that change.
