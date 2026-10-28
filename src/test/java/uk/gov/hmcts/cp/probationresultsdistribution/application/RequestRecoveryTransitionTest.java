package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * Recovery after a replay (US1 half of FR-004-012/FR-004-013). A terminal leg completes the request
 * {@code completion_reason = recovered} whenever {@code audit_note} carries the
 * {@code REPLAYED_AFTER_FAILURE} marker — including when the first replayed run parks ({@code RETRYING})
 * and a later redelivery settles it, because the marker, not which run settled it, decides. The
 * transition is fenced by the request claim: a completion by a claim that no longer matches changes
 * nothing. (The leg-write fencing of FR-004-013 is pinned in {@code RollbackFencingIT}.)
 *
 * <p>The US3 half (FR-004-032–FR-004-034): a resend attempt that ends {@code SENT},
 * {@code NOT_APPLICABLE} or class-1 {@code FAILED} recovers a {@code FAILED} request to
 * {@code COMPLETED(recovered)} in the outcome's own transaction; a class-2 {@code FAILED} outcome
 * leaves it as it was; and no resend outcome moves a {@code COMPLETED} request.
 */
@DisplayName("recovery after a replay")
class RequestRecoveryTransitionTest extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4L).plusSeconds(30L));
    private final OutboundMessageRepository outbound = new OutboundMessageRepository(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())), "test-version");

    @Test
    @DisplayName("completes recovered when the admitting replayed run settles terminally")
    void completes_recovered_on_the_admitting_run() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        final RunClaim claim = RunClaim.fresh();
        assertThat(repository.admitReplay(SOURCE, requestId, claim, ReplayRoute.SERVICE)).isTrue();

        final boolean completed = repository.markCompleted(SOURCE, requestId, claim,
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(completed).isTrue();
        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    @DisplayName("completes recovered when the first replayed run parks and a later redelivery settles")
    void completes_recovered_after_a_park_then_a_later_redelivery() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        final RunClaim first = RunClaim.fresh();
        assertThat(repository.admitReplay(SOURCE, requestId, first, ReplayRoute.SERVICE)).isTrue();

        // The first replayed run parks: RETRYING, claim released, replayed_at and audit_note kept.
        assertThat(repository.markRetrying(SOURCE, requestId, first)).isTrue();
        assertThat(requestRow(requestId).get("replayed_at")).isNotNull();

        // A later redelivery reclaims (the park released the claim) and settles terminally.
        final RunClaim second = RunClaim.fresh();
        assertThat(repository.reclaim(SOURCE, requestId, second)).isTrue();
        assertThat(repository.markCompleted(SOURCE, requestId, second,
                CompletionReason.BOTH_LEGS_TERMINAL)).isTrue();

        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    @DisplayName("a completion by a claim that no longer matches changes nothing")
    void a_stale_claim_completion_changes_nothing() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        assertThat(repository.admitReplay(SOURCE, requestId, RunClaim.fresh(), ReplayRoute.SERVICE))
                .isTrue();

        final boolean completed = repository.markCompleted(SOURCE, requestId, RunClaim.fresh(),
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(completed).isFalse();
        assertThat(requestRow(requestId).get("status")).isEqualTo("RECEIVED");
    }

    @Nested
    @DisplayName("recovery by a resend (US3 half, FR-004-032-FR-004-034)")
    class ResendRecovery {

        @Test
        @DisplayName("a SENT resend recovers a FAILED request in the outcome's transaction")
        void sent_resend_recovers() {
            final UUID requestId = UUID.randomUUID();
            seedFailedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);

            assertThat(outbound.writeResendOutcome(sent(requestId), attempt, false)).isEqualTo(1);

            final Map<String, Object> row = requestRow(requestId);
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("completion_reason")).isEqualTo("recovered");
            assertThat(row.get("failure_reason")).isNull();
            assertThat(row.get("exhausted_message_id")).isNull();
            assertThat((String) row.get("audit_note")).contains("RECOVERED_BY_RESEND")
                    .contains("prior=leg-exhausted").contains("exhausted=RESULTS:" + requestId);
        }

        @Test
        @DisplayName("a NOT_APPLICABLE resend recovers a FAILED request")
        void not_applicable_resend_recovers() {
            final UUID requestId = UUID.randomUUID();
            seedFailedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);

            outbound.writeResendOutcome(outcome(requestId, LegStatus.NOT_APPLICABLE,
                    LegReason.NOTHING_LEFT_AFTER_REDACTION, false, OptionalInt.empty()), attempt, false);

            assertThat(requestRow(requestId).get("completion_reason")).isEqualTo("recovered");
        }

        @ParameterizedTest
        @EnumSource(value = LegReason.class, names = {"RECEIVER_REJECTED", "RULES_EXCEPTION"})
        @DisplayName("a class-1 FAILED resend recovers a FAILED request")
        void class_one_failed_resend_recovers(final LegReason reason) {
            final UUID requestId = UUID.randomUUID();
            seedFailedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);
            final boolean sentBody = reason == LegReason.RECEIVER_REJECTED;

            outbound.writeResendOutcome(outcome(requestId, LegStatus.FAILED, reason, sentBody,
                    sentBody ? OptionalInt.of(400) : OptionalInt.empty()), attempt, false);

            final Map<String, Object> row = requestRow(requestId);
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("completion_reason")).isEqualTo("recovered");
        }

        @ParameterizedTest
        @EnumSource(value = LegReason.class,
                names = {"GATEWAY_TRANSIENT", "PAYLOAD_UNAVAILABLE", "PAYLOAD_CHANGED"})
        @DisplayName("a class-2 FAILED resend leaves the FAILED request as it was")
        void class_two_failed_resend_leaves_the_request(final LegReason reason) {
            final UUID requestId = UUID.randomUUID();
            seedFailedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);

            assertThat(outbound.writeResendOutcome(classTwo(requestId, reason), attempt, false))
                    .isEqualTo(1);

            final Map<String, Object> row = requestRow(requestId);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("failure_reason")).isEqualTo("leg-exhausted");
            assertThat(row.get("exhausted_message_id")).isEqualTo("RESULTS:" + requestId);
            assertThat(row.get("completion_reason")).isNull();
            assertThat(legRow(attempt).get("status")).isEqualTo("FAILED");
            assertThat(legRow(attempt).get("reason")).isEqualTo(reason.wireValue());
        }

        @Test
        @DisplayName("a failed resend of a COMPLETED request's leg leaves the request COMPLETED")
        void failed_resend_never_moves_a_completed_request() {
            final UUID requestId = UUID.randomUUID();
            seedCompletedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);

            outbound.writeResendOutcome(classTwo(requestId, LegReason.GATEWAY_TRANSIENT), attempt,
                    false);

            final Map<String, Object> row = requestRow(requestId);
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
            assertThat(legRow(attempt).get("status")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("a SENT resend of a COMPLETED request's leg does not re-complete it")
        void sent_resend_never_moves_a_completed_request() {
            final UUID requestId = UUID.randomUUID();
            seedCompletedRequest(requestId);
            final ResendAttempt attempt = seedPendingResend(requestId);

            outbound.writeResendOutcome(sent(requestId), attempt, false);

            final Map<String, Object> row = requestRow(requestId);
            assertThat(row.get("status")).isEqualTo("COMPLETED");
            assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
            assertThat(row.get("audit_note")).isNull();
        }

        private ResendAttempt seedPendingResend(final UUID requestId) {
            final UUID attemptId = UUID.randomUUID();
            final RunClaim claim = RunClaim.fresh();
            jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                            + "attempt_origin, status, reason, attempts) VALUES (gen_random_uuid(), :s, "
                            + ":r, 'HMPPS', 1, 'queue', 'FAILED', 'payload-unavailable', 1)")
                    .param("s", SOURCE).param("r", requestId).update();
            jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                            + "attempt_origin, status, claim_owner, claim_token, claim_expires_at, "
                            + "resend_run_id, operator) VALUES (:id, :s, :r, 'HMPPS', 2, 'resend', "
                            + "'PENDING', :owner, :token, now() + interval '4 minutes', "
                            + "gen_random_uuid(), 'local-dev')")
                    .param("id", attemptId).param("s", SOURCE).param("r", requestId)
                    .param("owner", claim.owner().toString()).param("token", claim.token())
                    .update();
            return new ResendAttempt(attemptId, 2, claim, UUID.randomUUID());
        }

        private LegOutcome sent(final UUID requestId) {
            return outcome(requestId, LegStatus.SENT, null, true, OptionalInt.of(200));
        }

        private LegOutcome classTwo(final UUID requestId, final LegReason reason) {
            return switch (reason) {
                case GATEWAY_TRANSIENT -> outcome(requestId, LegStatus.FAILED, reason, true,
                        OptionalInt.of(503));
                case PAYLOAD_UNAVAILABLE -> new LegOutcome(command(requestId), AttemptOrigin.RESEND,
                        LegStatus.FAILED, Optional.of(reason), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty());
                default -> new LegOutcome(command(requestId), AttemptOrigin.RESEND,
                        LegStatus.FAILED, Optional.of(reason), Optional.empty(),
                        Optional.of(PayloadSource.CACHE_DAY), Optional.empty(), Optional.empty(),
                        OptionalInt.empty(), Optional.empty());
            };
        }

        private LegOutcome outcome(final UUID requestId, final LegStatus status,
                                   final LegReason reason, final boolean sentBody,
                                   final OptionalInt httpStatus) {
            final String body = "{\"sent\":true}";
            return new LegOutcome(command(requestId), AttemptOrigin.RESEND, status,
                    Optional.ofNullable(reason), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                    Optional.of(PayloadSource.CACHE_DAY), Optional.empty(),
                    sentBody ? Optional.of(sha256Hex(body)) : Optional.empty(), httpStatus,
                    sentBody ? Optional.of(body) : Optional.empty());
        }

        private DistributionCommand command(final UUID requestId) {
            return new DistributionCommand(SOURCE, requestId, UUID.randomUUID(),
                    LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                    "Hearing_Resulted", Optional.empty());
        }

        private Map<String, Object> legRow(final ResendAttempt attempt) {
            return jdbc().sql("SELECT status, reason FROM outbound_message WHERE id = :id")
                    .param("id", attempt.attemptId()).query().singleRow();
        }
    }

    private static String sha256Hex(final String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void seedCompletedRequest(final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "completion_reason) VALUES (:s, :r, gen_random_uuid(), DATE '2026-08-19', "
                        + "now(), 'Hearing_Resulted', 'fp', 'COMPLETED', 1, '{}', "
                        + "'both-legs-terminal')")
                .param("s", SOURCE).param("r", requestId)
                .update();
    }

    private void seedFailedRequest(final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "failure_reason, exhausted_message_id) VALUES (:s, :r, gen_random_uuid(), "
                        + "DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', 'FAILED', 1, '{}', "
                        + "'leg-exhausted', :exhaustedId)")
                .param("s", SOURCE).param("r", requestId)
                .param("exhaustedId", "RESULTS:" + requestId)
                .update();
    }

    private Map<String, Object> requestRow(final UUID requestId) {
        return jdbc().sql("SELECT status, completion_reason, failure_reason, replayed_at, "
                        + "exhausted_message_id, audit_note "
                        + "FROM processed_request WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
    }
}
