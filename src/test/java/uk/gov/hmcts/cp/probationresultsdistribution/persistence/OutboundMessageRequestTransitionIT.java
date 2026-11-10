package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

/**
 * Pins the design digest's "Record" step 4 (A2-67, A3-63; review of PR #8): the leg outcome write
 * and the request-level transition it implies happen in one transaction, so a guarded request
 * transition that matches no row rolls the leg write back too — a crash (or any other process)
 * between the two writes can never strand the request at {@code RECEIVED}/{@code RETRYING} with a
 * terminal leg already written.
 *
 * <p>The failing request transition is forced directly, by seeding {@code processed_request} in a
 * state the leg write's own claim-only guard admits but the request transition's
 * {@code status IN ('RECEIVED', 'RETRYING')} guard refuses — the same inconsistency a second,
 * independent write between the two statements would leave behind were they not one transaction.
 */
@Testcontainers
@DisplayName("the leg write and its request transition commit or roll back together (review of PR #8)")
class OutboundMessageRequestTransitionIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final TransactionTemplate tx =
            new TransactionTemplate(new DataSourceTransactionManager(datasource()));
    private final JdbcProcessedRequestRepository processedRequests =
            new JdbcProcessedRequestRepository(jdbc(), tx, Duration.ofMinutes(4).plusSeconds(30));
    private final OutboundMessageRepository repository = new OutboundMessageRepository(jdbc(), tx,
            "test-version", processedRequests);

    @Test
    @DisplayName("rolls back a SENT leg write when the COMPLETED transition matches no row")
    void rolls_back_the_leg_write_when_the_completed_transition_is_fenced() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, claim, "COMPLETED");

        final int rows = repository.writeOutcome(sentOutcome(requestId, "the body"), claim,
                "unit-test");

        assertThat(rows).as("the whole write is fenced out, not just the request transition")
                .isZero();
        assertThat(legStatus(requestId)).as("the leg write itself rolled back with it")
                .isEqualTo("PENDING");
        assertThat(legAttempts(requestId)).as("attempts was not incremented either").isZero();
    }

    @Test
    @DisplayName("rolls back a PARKED leg write when the RETRYING transition matches no row")
    void rolls_back_the_leg_write_when_the_retrying_transition_is_fenced() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, claim, "FAILED");

        final int rows = repository.writeOutcome(parkedOutcome(requestId, "the body"), claim,
                "unit-test");

        assertThat(rows).isZero();
        assertThat(legStatus(requestId)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("fails the request leg-exhausted, recording the delivery's own messageId")
    void fails_the_request_exhausted_with_the_delivery_message_id() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, claim, "RECEIVED");
        final String deliveryMessageId = "RESULTS:" + requestId;

        final int rows = repository.writeOutcome(exhaustedOutcome(requestId, "the body"), claim,
                deliveryMessageId);

        assertThat(rows).isEqualTo(1);
        assertThat(legStatus(requestId)).isEqualTo("FAILED");
        assertThat(requestState(requestId)).containsExactly("FAILED", "leg-exhausted",
                deliveryMessageId);
    }

    @Test
    @DisplayName("rolls back the leg write and the stored body when the request transition throws, "
            + "not just when it matches no row")
    void rolls_back_the_leg_write_and_body_when_the_request_update_throws() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, claim, "RECEIVED");

        // A null deliveryMessageId on an exhaustion-eligible outcome leaves exhausted_message_id
        // NULL while failure_reason is written 'leg-exhausted', violating
        // processed_request_exhausted_id_chk: a genuine CHECK-constraint failure during the request
        // UPDATE, not merely a guarded UPDATE matching no row.
        assertThatThrownBy(() -> repository.writeOutcome(exhaustedOutcome(requestId, "the body"),
                claim, null)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(legStatus(requestId)).as("the leg write rolled back with the failed request update")
                .isEqualTo("PENDING");
        assertThat(legAttempts(requestId)).isZero();
        assertThat(outboundBodyCount(requestId)).as("no body row survives the rollback").isZero();
    }

    @Test
    @DisplayName("rolls back an in-place replay re-run when the COMPLETED transition matches no row")
    void rolls_back_an_in_place_replay_rerun_when_the_completed_transition_is_fenced() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedFailedAttemptOne(requestId, claim, "COMPLETED");

        final int rows = repository.writeReplayOutcome(sentOutcome(requestId, "the body"), claim,
                "RESULTS:a-replay-message-id", "unit-test");

        assertThat(rows).as("the whole replay write is fenced out, not just the request transition")
                .isZero();
        assertThat(legStatus(requestId)).as("the in-place leg rewrite rolled back with it")
                .isEqualTo("FAILED");
        assertThat(legAttempts(requestId)).as("attempts was not incremented either").isZero();
    }

    @Test
    @DisplayName("rolls back an appended replay row when the COMPLETED transition matches no row")
    void rolls_back_an_appended_replay_row_when_the_completed_transition_is_fenced() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, claim, "COMPLETED");
        seedFailedResendAttemptTwo(requestId);

        final int rows = repository.writeReplayOutcome(sentOutcome(requestId, "the body"), claim,
                "RESULTS:a-replay-message-id", "unit-test");

        assertThat(rows).as("the whole append-over write is fenced out, not just the request "
                + "transition").isZero();
        assertThat(legAttemptNumber(requestId)).as("no attempt 3 survives the rollback")
                .isEqualTo(2);
        assertThat(legStatus(requestId)).as("attempt 2 is untouched").isEqualTo("FAILED");
    }

    private void seedPendingAttempt(final UUID requestId, final RunClaim claim,
                                    final String requestStatus) {
        // A claim this far outside RECEIVED/RETRYING never occurs honestly — processed_request's
        // own CHECK constraints and settlement rules never leave a claim on a COMPLETED or FAILED
        // row. It is forced here only to exercise applyRequestTransition's guard in isolation,
        // standing in for "another write landed between the leg write and the request transition".
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, command, "
                        + "completion_reason, failure_reason, claim_owner, claim_token, "
                        + "claim_expires_at) VALUES "
                        + "(:source, :requestId, gen_random_uuid(), DATE '2026-08-19', "
                        + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', "
                        + ":status, '{}', :completionReason, :failureReason, :owner, :token, "
                        + "now() + interval '1 hour')")
                .param("source", SOURCE).param("requestId", requestId).param("status", requestStatus)
                .param("completionReason", "COMPLETED".equals(requestStatus)
                        ? "both-legs-terminal" : null)
                .param("failureReason", "FAILED".equals(requestStatus) ? "rolled-back" : null)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts) VALUES (gen_random_uuid(), :source, "
                        + ":requestId, 'HMPPS', 1, 'queue', 'PENDING', 0)")
                .param("source", SOURCE).param("requestId", requestId)
                .update();
    }

    private String legStatus(final UUID requestId) {
        return jdbc().sql("SELECT status FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(String.class).single();
    }

    private int legAttempts(final UUID requestId) {
        return jdbc().sql("SELECT attempts FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(Integer.class).single();
    }

    private int legAttemptNumber(final UUID requestId) {
        return jdbc().sql("SELECT attempt FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(Integer.class).single();
    }

    private int outboundBodyCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM outbound_body ob "
                        + "JOIN outbound_message om ON om.id = ob.outbound_message_id "
                        + "WHERE om.source = :source AND om.request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(Integer.class).single();
    }

    private List<String> requestState(final UUID requestId) {
        return jdbc().sql("SELECT status, failure_reason, exhausted_message_id "
                        + "FROM processed_request WHERE source = :source AND request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query((rs, rowNum) -> List.of(rs.getString("status"),
                        rs.getString("failure_reason"), rs.getString("exhausted_message_id")))
                .single();
    }

    /**
     * Seeds a claimed request and an attempt-1 {@code FAILED(gateway-transient)} row — the one
     * re-runnable {@code FAILED} shape the replay path re-runs in place (attempt 1 is always
     * {@code inPlaceEligible}).
     */
    private void seedFailedAttemptOne(final UUID requestId, final RunClaim claim,
                                      final String requestStatus) {
        seedPendingAttempt(requestId, claim, requestStatus);
        jdbc().sql("UPDATE outbound_message SET status = 'FAILED', reason = 'gateway-transient', "
                        + "youth_switch = 'ACTIVE', payload_source = 'cache-day', "
                        + "body_digest = 'seed-digest', service_version = 'seed-version', "
                        + "http_status = 503 "
                        + "WHERE source = :source AND request_id = :requestId AND attempt = 1")
                .param("source", SOURCE).param("requestId", requestId)
                .update();
    }

    /**
     * Appends an attempt-2 {@code resend}-origin {@code FAILED(receiver-rejected)} row directly —
     * the one shape the replay path's append-over route creates attempt 3 over (never re-running it
     * in place), guarded only by the request's own claim, never the leg's.
     */
    private void seedFailedResendAttemptTwo(final UUID requestId) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, youth_switch, http_status, "
                        + "payload_source, body_digest, service_version, claim_owner, claim_token, "
                        + "claim_expires_at, resend_run_id, attempts) VALUES (gen_random_uuid(), "
                        + ":source, :requestId, 'HMPPS', 2, 'resend', 'FAILED', 'receiver-rejected', "
                        + "'ACTIVE', 404, 'cache-day', 'seed-digest', 'seed-version', 'an-operator', "
                        + "gen_random_uuid(), now() + interval '4 minutes', gen_random_uuid(), 1)")
                .param("source", SOURCE).param("requestId", requestId)
                .update();
    }

    private LegOutcome exhaustedOutcome(final UUID requestId, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.QUEUE, LegStatus.FAILED,
                Optional.of(LegReason.GATEWAY_TRANSIENT), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.empty(), Optional.of(sha256(body)),
                OptionalInt.of(503), Optional.of(body));
    }

    private LegOutcome sentOutcome(final UUID requestId, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.QUEUE, LegStatus.SENT,
                Optional.empty(), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.empty(), Optional.of(sha256(body)),
                OptionalInt.of(200), Optional.of(body));
    }

    private LegOutcome parkedOutcome(final UUID requestId, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.QUEUE, LegStatus.PARKED,
                Optional.of(LegReason.GATEWAY_TRANSIENT), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.empty(), Optional.of(sha256(body)),
                OptionalInt.of(503), Optional.of(body));
    }

    private DistributionCommand command(final UUID requestId) {
        return new DistributionCommand(SOURCE, requestId, UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private static String sha256(final String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
