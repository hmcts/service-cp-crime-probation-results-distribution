package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

    private final OutboundMessageRepository repository = new OutboundMessageRepository(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())), "test-version");

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
