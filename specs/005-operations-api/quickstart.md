# Quickstart: Operations API

This slice is implemented against slice 004's application services. Until 004's `ReplayAction` and
`LegResender` land for real, the controller/authorisation/audit/error-handling layer is developed
and tested against 004's committed interface shape with Mockito standing in for the real classes
(research.md Decision 5) — this quickstart's manual steps need 004 actually implemented to run
end-to-end.

## Build and test

```bash
./gradlew build    # compile + the full suite + PMD + Checkstyle + the JaCoCo gate
./gradlew test     # @WebMvcTest controller tests run with no Docker; OperationsAuthorisationIT and
                    # OperationsAuditIT need the real filters wired, users-groups and the audit
                    # transport stubbed at their boundaries; OperationsAuditTransportIT runs the
                    # real JMS/Artemis wiring against an Artemis container (Docker)
```

## Try it locally (once 004 is implemented)

With the local stack running (`docker compose up -d`) and the service started with
`AUTHZ_HTTP_ENABLED=true HTTP_AUDIT_ENABLED=true CP_AUDIT_ENABLED=true` and a local users-groups
stub answering `Second Line Support` for the test caller:

`build/libs/app.jar` below is shorthand: the jar `./gradlew bootJar` builds is
`build/libs/service-cp-crime-probation-results-distribution-0.0.999.jar` at the default artefact
version. `CP_AUDIT_ENABLED=true` also needs an Artemis broker the compose stack does not provide
(for example a throwaway `apache/activemq-artemis` container on `localhost:61616`) and
`cp.audit.hosts[0]`, `CP_AUDIT_PORT`, `CP_AUDIT_USER` and `CP_AUDIT_PASSWORD` set; without
`cp.audit.hosts` the service does not start. With `CP_AUDIT_ENABLED` left off (the default),
both endpoints run unaudited and answer as normal; the start-up WARN says so. The users-groups caller read is
`GET /usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions` answering
`{"groups":[{"groupName":"Second Line Support"}],"switchableRoles":[],"permissions":[]}`. A resend
compares the cached payload's top-level `sharedTime` with the stored one (H17), so a seeded payload
without it resends as `FAILED(payload-changed)`.

1. **Seed a `FAILED` request** exactly as in 004's quickstart.

2. **Replay it over HTTP**:
   ```bash
   curl -i -X POST http://localhost:8082/operations/requests/<requestId>/replay \
       -H "CJSCPPUID: <test-caller-id>"
   ```
   Expect `200 {"requestId": "...", "outcome": "enqueued", "messageId": "..."}`. Compare against
   `java -jar build/libs/app.jar replay --request-id <requestId>` on an identically seeded second
   request — both must agree (story 7).

3. **Call without an identity header**:
   ```bash
   curl -i -X POST http://localhost:8082/operations/requests/<requestId>/replay
   ```
   Expect `401`.

4. **Resend a leg over HTTP**:
   ```bash
   curl -i -X POST http://localhost:8082/operations/resend-leg \
       -H "CJSCPPUID: <test-caller-id>" -H "Content-Type: application/json" \
       -d '{"requestIds": ["<requestId>"], "leg": "HMPPS", "operator": "local-dev", "reason": "smoke test"}'
   ```
   Expect `202 {"resendRunId": "..."}` immediately — not a wait for the run to finish. Read the
   actual outcome from 004's run-report log line or `java -jar build/libs/app.jar list-requests
   --status FAILED`.

5. **Force a duplicate over HTTP**:
   ```bash
   curl -i -X POST http://localhost:8082/operations/resend-leg \
       -H "CJSCPPUID: <test-caller-id>" -H "Content-Type: application/json" \
       -d '{"requestIds": ["<a SENT requestId>"], "leg": "HMPPS", "operator": "local-dev", "reason": "deliberate duplicate", "allowTerminalSuccess": true}'
   ```
   Confirm the audit event and the attempt row both say the override was requested.

6. **Check the contract test**: `./gradlew test --tests "*OperationsContractTest*"` asserts the
   served routes/reasons/enums against `probation-results-distribution-openapi.yaml` in both
   directions.

## What "done" looks like for this slice

- Every acceptance scenario of the seven user stories passes as a test.
- `OperationsContractTest` passes: the OpenAPI document and the served API agree exactly.
- `doc/API_CONTRACTS.md` §3 describes the two endpoints, updated in the same commit as the
  controllers.
- The privacy sweeps (`TelemetryPrivacyTest`, `LogStatementSweepTest`) extend cleanly to every new
  log line and audit event this slice adds, with zero findings.
- `./gradlew build` is green with the coverage thresholds unchanged (SC-005-010).
- Research Decision 2 (how `cp-auth-rules-filter` derives the action) is confirmed against the real
  library in `OperationsAuthorisationIT` before this slice is considered complete — if it needs a
  different wiring than assumed, that is a small, isolated fix, not a redesign.
