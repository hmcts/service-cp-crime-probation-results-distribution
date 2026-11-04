package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

@DisplayName("the per-request state transition")
class RequestTransitionIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4).plusSeconds(30));

    @Test
    @DisplayName("moves a claimed request to COMPLETED(both-legs-terminal) and releases the claim")
    void completes_a_claimed_request() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReceived(requestId, claim);

        final boolean moved = repository.markCompleted(SOURCE, requestId, claim,
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(moved).isTrue();
        final java.util.Map<String, Object> row = status(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(row.get("claim_owner")).isNull();
    }

    @Test
    @DisplayName("refuses to complete when the claim no longer matches")
    void refuses_complete_for_a_stale_claim() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());

        final boolean moved = repository.markCompleted(SOURCE, requestId, RunClaim.fresh(),
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(moved).isFalse();
        assertThat(status(requestId).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("moves a claimed request to RETRYING and releases the claim")
    void retries_a_claimed_request() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReceived(requestId, claim);

        final boolean moved = repository.markRetrying(SOURCE, requestId, claim);

        assertThat(moved).isTrue();
        final java.util.Map<String, Object> row = status(requestId);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("claim_owner")).isNull();
    }

    @Test
    @DisplayName("refuses to retry when the claim no longer matches")
    void refuses_retry_for_a_stale_claim() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());

        assertThat(repository.markRetrying(SOURCE, requestId, RunClaim.fresh())).isFalse();
    }

    @Test
    @DisplayName("a replay admission leaves find() reporting replayedAtSet, round-tripped through "
            + "replayed_at (FR-004-007)")
    void admitReplay_should_make_find_report_replayed_at_set() {
        final UUID requestId = seedFailed();

        repository.admitReplay(SOURCE, requestId, RunClaim.fresh(), ReplayRoute.COPY);

        assertThat(repository.find(SOURCE, requestId))
                .get()
                .extracting(ProcessedRequestRepository.RequestRecord::replayedAtSet)
                .isEqualTo(true);
    }

    @Test
    @DisplayName("an ordinary RECEIVED request leaves find() reporting replayedAtSet false")
    void seedReceived_should_leave_find_reporting_replayed_at_not_set() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());

        assertThat(repository.find(SOURCE, requestId))
                .get()
                .extracting(ProcessedRequestRepository.RequestRecord::replayedAtSet)
                .isEqualTo(false);
    }

    /**
     * FR-002-012's eligibility table marks attempt-1 {@code FAILED} "skip it" on a non-replay
     * delivery, for either terminal reason: the leg is never re-run, and the request only needs
     * claim-guarded completion. {@code hasTerminalFailure} is what the plain reclaim path reads to
     * make that call — {@code hasTerminalSuccess} must never match a {@code FAILED} row at all, since
     * {@code decideReplayAdmission} also consults it and FR-004-007 requires a replay to re-run a
     * {@code FAILED} leg of any reason.
     */
    @Test
    @DisplayName("recognises an attempt-1 FAILED(receiver-rejected) row as a terminal failure "
            + "(FR-002-012)")
    void hasTerminalFailure_recognises_an_attempt_one_failed_receiver_rejected_row() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());
        seedLeg(requestId, "FAILED", "receiver-rejected");

        assertThat(repository.hasTerminalFailure(SOURCE, requestId)).isTrue();
        assertThat(repository.hasTerminalSuccess(SOURCE, requestId)).isFalse();
    }

    @Test
    @DisplayName("recognises an attempt-1 FAILED(rules-exception) row as a terminal failure "
            + "(FR-002-012)")
    void hasTerminalFailure_recognises_an_attempt_one_failed_rules_exception_row() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());
        seedLeg(requestId, "FAILED", "rules-exception");

        assertThat(repository.hasTerminalFailure(SOURCE, requestId)).isTrue();
        assertThat(repository.hasTerminalSuccess(SOURCE, requestId)).isFalse();
    }

    @Test
    @DisplayName("does not treat an attempt-1 FAILED(gateway-transient) row as a terminal failure: "
            + "it is exhaustion-eligible, not a skip (FR-002-012)")
    void hasTerminalFailure_leaves_an_exhaustion_eligible_failed_row_unrecognised() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());
        seedLeg(requestId, "FAILED", "gateway-transient");

        assertThat(repository.hasTerminalFailure(SOURCE, requestId)).isFalse();
        assertThat(repository.hasTerminalSuccess(SOURCE, requestId)).isFalse();
    }

    @Test
    @DisplayName("recognises an attempt-1 FAILED(gateway-transient) row as a recorded exhaustion "
            + "(FR-002-012, FR-002-047)")
    void hasRecordedExhaustion_recognises_an_attempt_one_failed_gateway_transient_row() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());
        seedLeg(requestId, "FAILED", "gateway-transient");

        assertThat(repository.hasRecordedExhaustion(SOURCE, requestId)).isTrue();
    }

    @Test
    @DisplayName("recognises an attempt-1 FAILED(payload-unavailable) row as a recorded exhaustion "
            + "(FR-002-012, FR-002-047)")
    void hasRecordedExhaustion_recognises_an_attempt_one_failed_payload_unavailable_row() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());
        seedLeg(requestId, "FAILED", "payload-unavailable");

        assertThat(repository.hasRecordedExhaustion(SOURCE, requestId)).isTrue();
        assertThat(repository.hasTerminalFailure(SOURCE, requestId)).isFalse();
        assertThat(repository.hasTerminalSuccess(SOURCE, requestId)).isFalse();
    }

    /**
     * FR-004-035: a plain queue reclaim must never run concurrently with an in-flight resend. The
     * same crash window that leaves a request's own claim to expire is exactly what also makes it
     * eligible for resend-leg's own claim, so the exclusion has to be atomic in the {@code reclaim}
     * statement itself — a Java-side pre-check would leave a race window between the read and the
     * write.
     */
    @Test
    @DisplayName("never reclaims a request whose leg has a PENDING resend row, live or expired "
            + "(FR-004-035)")
    void reclaim_never_matches_a_pending_resend_row() {
        final UUID requestId = UUID.randomUUID();
        seedRetryingWithExpiredClaim(requestId);
        seedPendingResendAttempt(requestId);

        final boolean reclaimed = repository.reclaim(SOURCE, requestId, RunClaim.fresh());

        assertThat(reclaimed).isFalse();
        assertThat(status(requestId).get("status")).isEqualTo("RETRYING");
    }

    /**
     * A genuine cross-connection race, not merely sequenced via a callback: a separate connection
     * takes and holds the row lock {@code checkAndClaim} itself takes first, while {@code reclaim}
     * blocks on it from another connection/thread, proving the lock-first shape actually forces
     * {@code reclaim}'s own statement to re-evaluate against the resend row once it has committed —
     * not just that the final SQL text happens to mention it (FR-004-035).
     */
    @Test
    @DisplayName("reclaim blocks on a resend claim's row lock and then correctly refuses once that "
            + "resend's PENDING row has committed")
    void reclaim_blocks_on_and_then_refuses_a_concurrent_resend_claim() throws Exception {
        final UUID requestId = UUID.randomUUID();
        seedRetryingWithExpiredClaim(requestId);
        final CountDownLatch reclaimStarted = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor();
                Connection resendConnection = datasource().getConnection()) {
            resendConnection.setAutoCommit(false);
            try (PreparedStatement lock = resendConnection.prepareStatement(
                    "SELECT status FROM processed_request WHERE source = ? AND request_id = ? "
                            + "FOR UPDATE")) {
                lock.setString(1, SOURCE);
                lock.setObject(2, requestId);
                lock.executeQuery();
            }

            final Future<Boolean> reclaimResult = pool.submit(() -> {
                reclaimStarted.countDown();
                return repository.reclaim(SOURCE, requestId, RunClaim.fresh());
            });
            assertThat(reclaimStarted.await(5, TimeUnit.SECONDS)).isTrue();
            // Give reclaim's own lock-wait time to actually start blocking on the row this
            // connection holds, before the resend row below is inserted and committed — the exact
            // window the pre-fix single-statement UPDATE would have evaluated its NOT EXISTS in.
            Thread.sleep(300);
            try (PreparedStatement insert = resendConnection.prepareStatement(
                    "INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                            + "attempt_origin, status, attempts, claim_owner, claim_token, "
                            + "claim_expires_at, resend_run_id, operator) VALUES "
                            + "(gen_random_uuid(), ?, ?, 'HMPPS', 2, 'resend', 'PENDING', 0, "
                            + "gen_random_uuid()::text, gen_random_uuid(), "
                            + "now() + interval '5 minutes', gen_random_uuid(), 'an-operator')")) {
                insert.setString(1, SOURCE);
                insert.setObject(2, requestId);
                insert.executeUpdate();
            }
            resendConnection.commit();

            assertThat(reclaimResult.get(5, TimeUnit.SECONDS)).isFalse();
        }
    }

    @Test
    @DisplayName("reclaims normally once the resend attempt is no longer PENDING")
    void reclaim_succeeds_once_the_resend_row_is_no_longer_pending() {
        final UUID requestId = UUID.randomUUID();
        seedRetryingWithExpiredClaim(requestId);
        seedPendingResendAttempt(requestId);
        jdbc().sql("UPDATE outbound_message SET status = 'FAILED', reason = 'rolled-back' "
                        + "WHERE request_id = :r AND attempt_origin = 'resend'")
                .param("r", requestId)
                .update();

        assertThat(repository.reclaim(SOURCE, requestId, RunClaim.fresh())).isTrue();
    }

    private void seedRetryingWithExpiredClaim(final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:s, :r, "
                        + "gen_random_uuid(), DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', "
                        + "'RETRYING', 1, '{}', NULL, NULL, NULL)")
                .param("s", SOURCE).param("r", requestId)
                .update();
    }

    private void seedPendingResendAttempt(final UUID requestId) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts, claim_owner, claim_token, "
                        + "claim_expires_at, resend_run_id, operator) VALUES (gen_random_uuid(), "
                        + ":s, :r, 'HMPPS', 2, 'resend', 'PENDING', 0, gen_random_uuid()::text, "
                        + "gen_random_uuid(), now() + interval '5 minutes', gen_random_uuid(), "
                        + "'an-operator')")
                .param("s", SOURCE).param("r", requestId)
                .update();
    }

    // A body is digested before send, so it is present for receiver-rejected and gateway-transient
    // (the reason the presence checks tie it to); rules-exception throws during redaction, before the
    // digest, so it stays NULL. payload-unavailable fails at the pre-leg payload fetch, before the
    // switch is even read, so neither payload_source, youth_switch nor body_digest is set —
    // service_version tracks body_digest's presence (outbound_message's own CHECK constraints,
    // V1__create_processed_log.sql).
    private void seedLeg(final UUID requestId, final String status, final String reason) {
        final boolean payloadUnavailable = "payload-unavailable".equals(reason);
        final boolean digested = "receiver-rejected".equals(reason) || "gateway-transient".equals(reason);
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, payload_source, youth_switch, "
                        + "body_digest, service_version, http_status) VALUES (gen_random_uuid(), "
                        + ":s, :r, 'HMPPS', 1, 'queue', :status, :reason, 1, :payloadSource, "
                        + ":youthSwitch, :digest, :version, :httpStatus)")
                .param("s", SOURCE).param("r", requestId)
                .param("status", status).param("reason", reason)
                .param("payloadSource", payloadUnavailable ? null : "cache-day")
                .param("youthSwitch", payloadUnavailable ? null : "ACTIVE")
                .param("digest", digested ? "deadbeef" : null)
                .param("version", digested ? "test" : null)
                .param("httpStatus", "receiver-rejected".equals(reason) ? 403 : null)
                .update();
    }

    private UUID seedFailed() {
        final UUID requestId = UUID.randomUUID();
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, "
                        + "failure_reason, exhausted_message_id, command) VALUES ('RESULTS', :id, "
                        + "gen_random_uuid(), '2026-08-19', now(), 'Hearing_Resulted', 'fp', 'FAILED', "
                        + "5, 'leg-exhausted', :exhausted, '{}')")
                .param("id", requestId)
                .param("exhausted", "RESULTS:" + requestId)
                .update();
        return requestId;
    }

    private void seedReceived(final UUID requestId, final RunClaim claim) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:s, :r, "
                        + "gen_random_uuid(), DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', "
                        + "'RECEIVED', 1, '{}', :owner, :token, now() + interval '5 minutes')")
                .param("s", SOURCE).param("r", requestId)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
    }

    private java.util.Map<String, Object> status(final UUID requestId) {
        return jdbc().sql("SELECT status, completion_reason, claim_owner FROM processed_request "
                        + "WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
    }
}
