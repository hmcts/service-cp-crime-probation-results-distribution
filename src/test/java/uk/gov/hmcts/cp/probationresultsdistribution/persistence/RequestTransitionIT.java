package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
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
