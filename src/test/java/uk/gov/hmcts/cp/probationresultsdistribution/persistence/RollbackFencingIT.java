package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository.CloseInFlightResult;

/**
 * Pins the rollback's close-in-flight statement (FR-004-049a; provisional runner ownership,
 * FR-004-049) over the four acceptance-scenario rows (spec.md US7 scenario 7), plus the "late
 * runner" fencing cases this file also carries for US1 (T015's second bullet — a late queue or
 * replay runner's leg-outcome write, guarded by the request claim) now that both exist as real,
 * unchanged production code (slice 002's {@code writeOutcome}/{@code writeReplayOutcome}). A late
 * resend runner's own completion write (US3, T041) is not pinned here: that guarded-write method
 * does not exist until Phase 6 (T046) lands.
 */
@Testcontainers
@DisplayName("the rollback close-in-flight statement")
class RollbackFencingIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private JdbcProcessedRequestRepository requests;
    private OutboundMessageRepository outbound;
    private RollbackFencingRepository fencing;

    @BeforeEach
    void setUp() {
        final JdbcClient client = JdbcClient.create(datasource());
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(datasource()));
        requests = new JdbcProcessedRequestRepository(client, tx, Duration.ofSeconds(270));
        outbound = new OutboundMessageRepository(client, tx, "test-service-1", requests);
        fencing = new JdbcRollbackFencingRepository(client, tx);
    }

    @Nested
    @DisplayName("the four acceptance-scenario rows (FR-004-049a)")
    class CloseInFlightRows {

        @Test
        @DisplayName("closes a RECEIVED request's PENDING attempt-1 row, clearing its claim")
        void closes_received_pending() {
            final DistributionCommand command = freshCommand();
            final RunClaim claim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", claim);

            final CloseInFlightResult result = fencing.closeInFlight();

            assertThat(result.pendingClosed()).isEqualTo(1);
            assertLegClosed(command.requestId(), "prev=PENDING/-");
            assertRequestStatus(command.requestId(), "FAILED", "rolled-back", null);
            assertRequestClaimCleared(command.requestId());
        }

        @Test
        @DisplayName("closes a RETRYING request's PARKED attempt-1 row, keeping other note forms")
        void closes_retrying_parked() {
            final DistributionCommand command = freshCommand();
            final RunClaim claim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", claim);
            // writeOutcome now applies the RETRYING transition in the same write (review of PR #8),
            // so this explicit markRetrying is redundant, surviving as a no-op the write's own
            // claim-clearing already fences out; left here to document the state it used to set.
            outbound.writeOutcome(parked(command, "body-1"), claim, "unit-test");
            requests.markRetrying(SOURCE, command.requestId(), claim);

            final CloseInFlightResult result = fencing.closeInFlight();

            assertThat(result.parkedClosed()).isEqualTo(1);
            assertLegClosed(command.requestId(), "prev=PARKED/gateway-transient");
            assertRequestStatus(command.requestId(), "FAILED", "rolled-back", null);
            assertRequestClaimCleared(command.requestId());
        }

        @Test
        @DisplayName("a FAILED(leg-exhausted) request's PENDING resend row is closed; the request "
                + "keeps its own reason and exhausted id, and the resend row's claim is rotated")
        void closes_failed_resend_pending_leaving_request_untouched() {
            final DistributionCommand command = freshCommand();
            final RunClaim claim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", claim);
            // The attempt-1 row must itself be terminal before the request can be FAILED(leg-exhausted)
            // (design settlement order) — left PENDING, it would be a second in-flight row the close
            // statement also closes, which is not this scenario.
            // The write itself now applies FAILED(leg-exhausted) with this exhausted_message_id in
            // the same transaction (review of PR #8): an exhaustion-reason outcome always implies
            // this transition, so there is no longer a separate markFailed step to call.
            outbound.writeOutcome(failed(command, "exhausted body"), claim, "RESULTS:exhausted-id");
            final UUID originalToken = insertResendPendingRow(command.requestId());

            final CloseInFlightResult result = fencing.closeInFlight();

            assertThat(result.pendingClosed()).isEqualTo(1);
            assertLegClosed(command.requestId(), "prev=PENDING/-");
            assertThat(resendClaimToken(command.requestId())).isNotEqualTo(originalToken);
            assertRequestStatus(command.requestId(), "FAILED", "leg-exhausted", "RESULTS:exhausted-id");
        }

        @Test
        @DisplayName("a COMPLETED request's PENDING resend row is closed; the request is untouched")
        void closes_completed_resend_pending_leaving_request_untouched() {
            final DistributionCommand command = freshCommand();
            final RunClaim claim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", claim);
            // As above: attempt 1 must be terminal (SENT) before the request can be COMPLETED.
            // The write itself now applies both-legs-terminal COMPLETED in the same transaction
            // (review of PR #8), so there is no longer a separate markCompleted step to call.
            outbound.writeOutcome(sent(command, "first body"), claim, "unit-test");
            insertResendPendingRow(command.requestId());

            final CloseInFlightResult result = fencing.closeInFlight();

            assertThat(result.pendingClosed()).isEqualTo(1);
            assertLegClosed(command.requestId(), "prev=PENDING/-");
            assertRequestStatus(command.requestId(), "COMPLETED", null, null);
        }
    }

    @Nested
    @DisplayName("lock order against a racing write (P2 follow-up, PR #23 review)")
    class LockOrder {

        /**
         * A genuine cross-connection race, not merely sequenced via a callback (mirrors
         * {@code OutboundMessageRequestTransitionIT#locks_the_request_row_before_the_leg_row}): a
         * separate connection takes and holds the request row's lock, while
         * {@code closeInFlight()} blocks on it from another connection/thread. A third, uncontended
         * connection proves {@code closeOneRow} has not touched the leg row at all while it waits —
         * the pre-fix order (leg row first, request row second) would instead have already updated
         * and locked the leg row by this point, and a late queue or replay write overrunning the
         * drain window, racing this close for the same request, would deadlock instead of one
         * waiting on the other.
         */
        @Test
        @DisplayName("locks the request row before the leg row, so a racing write waits instead of "
                + "deadlocking")
        void locks_the_request_row_before_the_leg_row() throws Exception {
            final DistributionCommand command = freshCommand();
            requests.insertClaim(command, "{}", "fp", RunClaim.fresh());
            final CountDownLatch closeSubmitted = new CountDownLatch(1);

            try (ExecutorService pool = Executors.newSingleThreadExecutor();
                    Connection competitor = datasource().getConnection()) {
                competitor.setAutoCommit(false);
                try (PreparedStatement lock = competitor.prepareStatement(
                        "SELECT status FROM processed_request WHERE source = ? AND request_id = ? "
                                + "FOR UPDATE")) {
                    lock.setString(1, SOURCE);
                    lock.setObject(2, command.requestId());
                    lock.executeQuery();
                }

                final Future<CloseInFlightResult> close = pool.submit(() -> {
                    closeSubmitted.countDown();
                    return fencing.closeInFlight();
                });
                assertThat(closeSubmitted.await(5, TimeUnit.SECONDS)).isTrue();
                // Give closeInFlight's own lock-wait time to actually start blocking on the request
                // row this connection holds, before probing whether it has touched the leg row yet.
                Thread.sleep(300);

                try (Connection prober = datasource().getConnection()) {
                    prober.setAutoCommit(false);
                    try (Statement timeout = prober.createStatement()) {
                        // Bounds the probe itself: were the fix broken, closeOneRow would already
                        // hold the leg row's lock and this would otherwise hang until the competitor
                        // below commits, which this test's own main thread has not done yet - a
                        // self-deadlock in the test, not just a failed assertion.
                        timeout.execute("SET LOCAL lock_timeout = '500ms'");
                    }
                    try (PreparedStatement legLock = prober.prepareStatement(
                            "SELECT id FROM outbound_message WHERE source = ? AND request_id = ? "
                                    + "FOR UPDATE")) {
                        legLock.setString(1, SOURCE);
                        legLock.setObject(2, command.requestId());
                        assertThatCode(legLock::executeQuery)
                                .as("closeOneRow must not have touched the leg row yet while it is "
                                        + "blocked on the request row")
                                .doesNotThrowAnyException();
                    } finally {
                        prober.rollback();
                    }
                }

                competitor.commit();

                assertThat(close.get(5, TimeUnit.SECONDS).pendingClosed()).isEqualTo(1);
            }
        }
    }

    @Nested
    @DisplayName("a late runner's write after the close (US1's T015, pinned here)")
    class LateRunnerFencing {

        @Test
        @DisplayName("a late queue runner's leg-outcome write, guarded by the cleared request "
                + "claim, changes nothing")
        void late_queue_runner_write_is_fenced() {
            final DistributionCommand command = freshCommand();
            final RunClaim staleClaim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", staleClaim);

            fencing.closeInFlight();

            final int rows = outbound.writeOutcome(sent(command, "late body"), staleClaim, "unit-test");

            assertThat(rows).isZero();
            assertLegClosed(command.requestId(), "prev=PENDING/-");
        }

        @Test
        @DisplayName("a late replay runner's re-run write, guarded by the cleared request claim, "
                + "changes nothing")
        void late_replay_runner_write_is_fenced() {
            final DistributionCommand command = freshCommand();
            final RunClaim staleClaim = RunClaim.fresh();
            requests.insertClaim(command, "{}", "fp", staleClaim);
            outbound.writeOutcome(parked(command, "body-1"), staleClaim, "unit-test");
            requests.markRetrying(SOURCE, command.requestId(), staleClaim);

            fencing.closeInFlight();

            final int rows = outbound.writeReplayOutcome(sent(command, "late replay body"),
                    staleClaim, "RESULTS:a-replay-message-id", "unit-test");

            assertThat(rows).isZero();
            assertLegClosed(command.requestId(), "prev=PARKED/gateway-transient");
        }
    }

    private DistributionCommand freshCommand() {
        return new DistributionCommand(SOURCE, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private LegOutcome parked(final DistributionCommand command, final String body) {
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.PARKED,
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason.GATEWAY_TRANSIENT),
                Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource.CACHE_DAY),
                Optional.of(command.sharedTime()), Optional.of(sha256Hex(body)), OptionalInt.of(503),
                Optional.of(body));
    }

    private LegOutcome failed(final DistributionCommand command, final String body) {
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.FAILED,
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason.GATEWAY_TRANSIENT),
                Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource.CACHE_DAY),
                Optional.of(command.sharedTime()), Optional.of(sha256Hex(body)), OptionalInt.of(503),
                Optional.of(body));
    }

    private LegOutcome sent(final DistributionCommand command, final String body) {
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(),
                Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource.CACHE_DAY),
                Optional.of(command.sharedTime()), Optional.of(sha256Hex(body)), OptionalInt.of(200),
                Optional.of(body));
    }

    private static String sha256Hex(final String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Inserts a resend attempt-2 {@code PENDING} row directly (no {@code LegResender} exists yet;
     * Phase 6, T043, will insert these in production). Returns the claim token it was seeded with.
     */
    private UUID insertResendPendingRow(final UUID requestId) {
        final UUID token = UUID.randomUUID();
        execute("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                + "attempt_origin, status, claim_owner, claim_token, claim_expires_at, resend_run_id) "
                + "VALUES (gen_random_uuid(), '" + SOURCE + "', '" + requestId + "', 'HMPPS', 2, "
                + "'resend', 'PENDING', 'an-operator', '" + token + "', "
                + "now() + interval '4 minutes', gen_random_uuid())");
        return token;
    }

    private void assertLegClosed(final UUID requestId, final String expectedNote) {
        final Object[] row = jdbc().sql("SELECT status, reason, note FROM outbound_message_latest "
                        + "WHERE request_id = :id AND leg = 'HMPPS'")
                .param("id", requestId)
                .query((rs, rowNum) -> new Object[] {rs.getString("status"), rs.getString("reason"),
                        rs.getString("note")})
                .single();
        assertThat(row[0]).isEqualTo("FAILED");
        assertThat(row[1]).isEqualTo("rolled-back");
        assertThat(row[2]).isEqualTo(expectedNote);
    }

    private void assertRequestStatus(final UUID requestId, final String status,
                                     final String failureReason, final String exhaustedMessageId) {
        final String[] row = jdbc().sql("SELECT status, failure_reason, exhausted_message_id "
                        + "FROM processed_request WHERE request_id = :id")
                .param("id", requestId)
                .query((rs, rowNum) -> new String[] {rs.getString("status"),
                        rs.getString("failure_reason"), rs.getString("exhausted_message_id")})
                .single();
        assertThat(row[0]).isEqualTo(status);
        assertThat(row[1]).isEqualTo(failureReason);
        assertThat(row[2]).isEqualTo(exhaustedMessageId);
    }

    private void assertRequestClaimCleared(final UUID requestId) {
        final Integer nonNullClaimColumns = jdbc().sql(
                        "SELECT num_nonnulls(claim_owner, claim_token, claim_expires_at) "
                        + "FROM processed_request WHERE request_id = :id")
                .param("id", requestId)
                .query(Integer.class).single();
        assertThat(nonNullClaimColumns).isZero();
    }

    private UUID resendClaimToken(final UUID requestId) {
        return jdbc().sql("SELECT claim_token FROM outbound_message_latest "
                        + "WHERE request_id = :id AND leg = 'HMPPS'")
                .param("id", requestId)
                .query(UUID.class).single();
    }
}
