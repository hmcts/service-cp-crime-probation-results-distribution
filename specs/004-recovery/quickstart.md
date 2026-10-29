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
   # Default local artefact version; use the corresponding jar if ARTEFACT_VERSION was overridden.
   operator_jar=build/libs/service-cp-crime-probation-results-distribution-0.0.999.jar
   java -jar "$operator_jar" pause-intake
   # Wait for probation_intake_suspended{cause="operator"}=1 on every running replica.
   # Enqueue a message — confirm it is not taken.
   # Each following command runs only if the previous one succeeded.
   java -jar "$operator_jar" purge-queue &&
       java -jar "$operator_jar" rollback-close-in-flight &&
       java -jar "$operator_jar" resume-intake
   # Confirm deliveries resume only when the whole sequence succeeded.
   ```
   These separate processes inherit the same datasource configuration as the running service.
   Each command exits without starting HTTP, the consumer or body maintenance. `pause-intake` and
   `resume-intake` persist the requested state; running replicas reconcile it by their next
   successful store probe (default 10 seconds). Rollback is refused with exit 1 unless paused;
   when paused it reports the pending/parked rows closed and exits 0.

   **T037 is still open for successful purge**: the installed Java SDK exposes no native
   cutoff-delete operation. While paused, `purge-queue` currently exits 2 with
   `unsupported-broker-operation`/`broker-unavailable` and leaves the queues untouched. It refuses
   with exit 1 while unpaused. Do not treat that failure as a completed purge or resume a rollback
   before the required purge and reconciliation have actually completed.

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
- Decisions 1–3 in `research.md` were confirmed on 2026-10-26: refuse expired resend claims,
  use the guarded rollback CLI, and persist operator pause. Any subsequent change requires an
  explicit decision, updated acceptance criteria and the affected quickstart checks.
