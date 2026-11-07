package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
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
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

/**
 * Pins FR-002-042 (H39): with no database trigger, the match between a stored
 * {@code outbound_body} row and its attempt's {@code body_digest} is the single write path's own
 * guarantee, never the database's. Asserted directly — the stored body's SHA-256 equals the
 * attempt's recorded digest — after a first send, an in-place re-run and a resend.
 */
@Testcontainers
@DisplayName("outbound_body matches body_digest with no trigger (H39, FR-002-042)")
class OutboundBodyDigestConsistencyIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final OutboundMessageRepository repository = new OutboundMessageRepository(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())), "test-version");

    @Test
    @DisplayName("a first send stores the exact bytes digested")
    void first_send_stores_the_bytes_digested() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = seedPendingRequestAndAttempt(requestId);

        repository.writeOutcome(outcome(requestId, LegStatus.SENT, "the first body"), claim, "unit-test");

        assertStoredBodyMatchesDigest(requestId);
    }

    @Test
    @DisplayName("an in-place re-run replaces the body, still matching its new digest")
    void in_place_rerun_replaces_the_body_matching_its_new_digest() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = seedPendingRequestAndAttempt(requestId);
        repository.writeOutcome(
                parkedOutcome(requestId, "the parked body"), claim, "unit-test");
        // The PARKED outcome's RETRYING transition clears the request claim in the same write
        // (review of PR #8); a real redelivery would reclaim it afresh through the guard first.
        reclaimRequest(requestId, claim);

        repository.writeOutcome(outcome(requestId, LegStatus.SENT, "the re-run body"), claim, "unit-test");

        assertThat(storedBody(requestId)).isEqualTo("the re-run body");
        assertStoredBodyMatchesDigest(requestId);
    }

    @Test
    @DisplayName("a resend attempt stores its own body, matching its own digest")
    void resend_stores_its_own_body_matching_its_own_digest() {
        final UUID requestId = UUID.randomUUID();
        seedPendingRequestAndAttempt(requestId);
        final ResendAttempt resendAttempt = seedResendAttempt(requestId);

        repository.writeResendOutcome(resendOutcome(requestId, "the resend body"), resendAttempt,
                false);

        assertThat(storedBody(requestId)).isEqualTo("the resend body");
        assertStoredBodyMatchesDigest(requestId);
    }

    private void assertStoredBodyMatchesDigest(final UUID requestId) {
        final String storedBody = storedBody(requestId);
        final String recordedDigest = jdbc().sql("SELECT body_digest FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(String.class).single();
        assertThat(recordedDigest).isEqualTo(sha256Hex(storedBody));
    }

    private String storedBody(final UUID requestId) {
        return jdbc().sql("SELECT ob.body FROM outbound_body ob "
                        + "JOIN outbound_message_latest oml ON oml.id = ob.outbound_message_id "
                        + "WHERE oml.source = :source AND oml.request_id = :requestId")
                .param("source", SOURCE).param("requestId", requestId)
                .query(String.class).single();
    }

    private LegOutcome outcome(final UUID requestId, final LegStatus status, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.QUEUE, status, Optional.empty(),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.empty(), Optional.of(sha256Hex(body)), OptionalInt.of(200),
                Optional.of(body));
    }

    /** A {@code PARKED(gateway-transient)} outcome, the one re-runnable PARKED state with a body. */
    private LegOutcome parkedOutcome(final UUID requestId, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.QUEUE, LegStatus.PARKED,
                Optional.of(LegReason.GATEWAY_TRANSIENT), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.empty(),
                Optional.of(sha256Hex(body)), OptionalInt.of(500), Optional.of(body));
    }

    private LegOutcome resendOutcome(final UUID requestId, final String body) {
        return new LegOutcome(command(requestId), AttemptOrigin.RESEND, LegStatus.SENT,
                Optional.empty(), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.empty(),
                Optional.of(sha256Hex(body)), OptionalInt.of(200), Optional.of(body));
    }

    private DistributionCommand command(final UUID requestId) {
        return new DistributionCommand(SOURCE, requestId, UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    /**
     * Re-establishes the request's claim, as a redelivery's reclaim through {@code IdempotencyGuard}
     * would in production, after an atomic write's own request transition cleared it (review of PR
     * #8). This test exercises the repository directly, with no guard in the loop.
     */
    private void reclaimRequest(final UUID requestId, final RunClaim claim) {
        jdbc().sql("UPDATE processed_request SET status = 'RETRYING', claim_owner = :owner, "
                        + "claim_token = :token, claim_expires_at = now() + interval '1 hour' "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .param("source", SOURCE).param("requestId", requestId)
                .update();
    }

    /** Seeds a {@code RECEIVED} request, claimed, with a {@code PENDING} attempt-1 row. */
    private RunClaim seedPendingRequestAndAttempt(final UUID requestId) {
        final RunClaim claim = RunClaim.fresh();
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES "
                        + "(:source, :requestId, gen_random_uuid(), DATE '2026-08-19', "
                        + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', "
                        + "'RECEIVED', '{}', :owner, :token, now() + interval '1 hour')")
                .param("source", SOURCE).param("requestId", requestId)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts) VALUES (gen_random_uuid(), :source, "
                        + ":requestId, 'HMPPS', 1, 'queue', 'PENDING', 0)")
                .param("source", SOURCE).param("requestId", requestId)
                .update();
        return claim;
    }

    /** Seeds a claimed {@code resend} attempt row at attempt 2, {@code PENDING}. */
    private ResendAttempt seedResendAttempt(final UUID requestId) {
        final UUID attemptId = UUID.randomUUID();
        final UUID legToken = UUID.randomUUID();
        final UUID resendRunId = UUID.randomUUID();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts, claim_owner, claim_token, "
                        + "claim_expires_at, resend_run_id) VALUES (:id, :source, :requestId, "
                        + "'HMPPS', 2, 'resend', 'PENDING', 0, gen_random_uuid(), :token, "
                        + "now() + interval '1 hour', :runId)")
                .param("id", attemptId).param("source", SOURCE).param("requestId", requestId)
                .param("token", legToken).param("runId", resendRunId)
                .update();
        return new ResendAttempt(attemptId, 2, new RunClaim(UUID.randomUUID(), legToken),
                resendRunId);
    }

    private static String sha256Hex(final String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
