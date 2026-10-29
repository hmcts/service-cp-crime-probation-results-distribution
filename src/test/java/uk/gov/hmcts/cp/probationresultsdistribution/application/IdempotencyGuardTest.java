package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.SupportSubmission;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.RequestRecord;

@ExtendWith(MockitoExtension.class)
@DisplayName("the idempotency guard")
class IdempotencyGuardTest {

    private static final String BODY = "{\"source\":\"RESULTS\"}";
    private static final String MESSAGE_ID = "RESULTS:6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34";

    @Mock
    private ProcessedRequestRepository repository;

    private final RequestFingerprint fingerprint = new RequestFingerprint();
    private IdempotencyGuard guard;
    private DistributionCommand command;
    private String fp;

    @BeforeEach
    void setUp() {
        guard = new IdempotencyGuard(repository, fingerprint);
        command = new DistributionCommand(
                "RESULTS",
                UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34"),
                UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa"),
                LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted",
                Optional.empty());
        fp = fingerprint.compute(command);
    }

    private GuardDecision admit() {
        return guard.admit(command, BODY, MESSAGE_ID, 0L, ReplayRoute.COPY);
    }

    private GuardDecision admit(final long deliveryCount, final String messageId) {
        return guard.admit(command, BODY, messageId, deliveryCount, ReplayRoute.COPY);
    }

    @Test
    @DisplayName("admits a first-seen key by inserting its claim")
    void admits_a_first_seen_key() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.empty());
        when(repository.insertClaim(eq(command), eq(BODY), eq(fp), any())).thenReturn(true);

        assertThat(admit()).isInstanceOf(GuardDecision.Admitted.class);
    }

    @Test
    @DisplayName("abandons quietly when it loses the insert race")
    void abandons_on_lost_insert_race() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.empty());
        when(repository.insertClaim(eq(command), eq(BODY), eq(fp), any())).thenReturn(false);

        assertThat(admit()).isInstanceOf(GuardDecision.Abandon.class);
    }

    @Test
    @DisplayName("completes a COMPLETED request with no run")
    void completes_a_completed_request() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("COMPLETED", fp, false)));

        assertThat(admit()).isInstanceOf(GuardDecision.DuplicateComplete.class);
        verify(repository, never()).reclaim(any(), any(), any());
    }

    @Test
    @DisplayName("dead-letters a collision when the fingerprint differs on a known key")
    void dead_letters_a_collision() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RECEIVED", "a-different-fingerprint",
                        true)));

        final GuardDecision decision = admit();

        assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
        assertThat(((GuardDecision.DeadLetter) decision).reason())
                .isEqualTo(DeadLetterReason.COLLISION);
    }

    @Test
    @DisplayName("abandons a delivery that meets a live claim")
    void abandons_on_live_claim() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RETRYING", fp, true)));

        assertThat(admit()).isInstanceOf(GuardDecision.Abandon.class);
        verify(repository, never()).reclaim(any(), any(), any());
    }

    @Test
    @DisplayName("reclaims an expired claim and admits the run")
    void reclaims_an_expired_claim() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RECEIVED", fp, false)));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(true);

        assertThat(admit()).isInstanceOf(GuardDecision.Admitted.class);
    }

    @Test
    @DisplayName("marks only an expired claim with a PENDING attempt-1 row as a crash rerun")
    void identifies_pending_attempt_as_crash_rerun() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(
                new RequestRecord("RETRYING", fp, false, Optional.of("PENDING"),
                        Optional.empty(), OptionalInt.empty())));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(true);

        final GuardDecision.Admitted decision = (GuardDecision.Admitted) admit();

        assertThat(decision.crashRerun()).isTrue();
        assertThat(decision.gatewayParked()).isFalse();
    }

    @Test
    @DisplayName("does not count a parked row as a crash rerun and identifies only a 5xx recovery")
    void distinguishes_gateway_5xx_reclaim_from_crash_rerun() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(
                new RequestRecord("RETRYING", fp, false, Optional.of("PARKED"),
                        Optional.of("gateway-transient"), OptionalInt.of(503))));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(true);

        final GuardDecision.Admitted decision = (GuardDecision.Admitted) admit();

        assertThat(decision.crashRerun()).isFalse();
        assertThat(decision.gatewayParked()).isTrue();
    }

    @Test
    @DisplayName("abandons when another delivery wins the reclaim race")
    void abandons_when_reclaim_is_lost() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RETRYING", fp, false)));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(false);

        assertThat(admit()).isInstanceOf(GuardDecision.Abandon.class);
    }

    @Test
    @DisplayName("refuses an unknown request status explicitly")
    void refuses_an_unknown_status() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("GONE", fp, false)));

        assertThatThrownBy(this::admit).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fingerprints over the four identity fields, excluding userId")
    void fingerprints_exclude_user_id() {
        final DistributionCommand withUser = new DistributionCommand(
                command.source(), command.requestId(), command.hearingId(), command.hearingDay(),
                command.sharedTime(), command.eventType(),
                Optional.of(UUID.fromString("0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48")));
        final DistributionCommand reshared = new DistributionCommand(
                command.source(), command.requestId(), command.hearingId(), command.hearingDay(),
                Instant.parse("2026-08-20T09:00:00Z"), command.eventType(), Optional.empty());

        assertThat(fingerprint.compute(withUser)).isEqualTo(fp);
        assertThat(fingerprint.compute(reshared)).isNotEqualTo(fp);
    }

    /**
     * The DV-26 predicate for a {@code FAILED} request's delivery (design A2-62, A3-30; slice 004
     * FR-004-001–FR-004-003, replacing the interim FR-002-008).
     */
    @Nested
    @DisplayName("reclaiming a request with a terminal-success latest leg")
    class ReclaimTerminalSuccess {

        @Test
        @DisplayName("returns a completion-only admission after a successful expired-claim reclaim")
        void marks_terminal_success_after_expired_claim_reclaim() {
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(
                    new RequestRecord("RETRYING", fp, false)));
            when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(true);
            when(repository.hasTerminalSuccess("RESULTS", command.requestId())).thenReturn(true);

            final GuardDecision decision = guard.admit(command, "{}", MESSAGE_ID, 1L,
                    ReplayRoute.COPY);

            assertThat(decision).isInstanceOfSatisfying(GuardDecision.Admitted.class,
                    admitted -> assertThat(admitted.terminalSuccess()).isTrue());
            verify(repository).hasTerminalSuccess("RESULTS", command.requestId());
        }
    }

    @Nested
    @DisplayName("admitting a FAILED request's delivery (DV-26)")
    class ReplayAdmission {

        @Test
        @DisplayName("admits as a replay when exhausted_message_id is NULL")
        void admits_as_replay_when_exhausted_id_is_null() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("FAILED", fp, false)));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(),
                    eq(ReplayRoute.COPY))).thenReturn(true);

            final GuardDecision decision = admit();

            assertThat(decision).isInstanceOf(GuardDecision.Admitted.class);
            assertThat(((GuardDecision.Admitted) decision).replayMessageId())
                    .contains(MESSAGE_ID);
        }

        @Test
        @DisplayName("admits as a replay when messageId differs from exhausted_message_id")
        void admits_as_replay_when_message_id_differs() {
            final RequestRecord row = new RequestRecord("FAILED", fp, false, Optional.empty(),
                    Optional.empty(), OptionalInt.empty(), Optional.of("RESULTS:old-id"), false);
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(row));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(), any()))
                    .thenReturn(true);

            assertThat(admit(0L, "a-fresh-replay-message-id"))
                    .isInstanceOf(GuardDecision.Admitted.class);
        }

        @Test
        @DisplayName("admits as a replay under the exhausted messageId at delivery count 1 "
                + "(this listener's zero-based first delivery)")
        void admits_same_exhausted_id_at_first_delivery() {
            final RequestRecord row = new RequestRecord("FAILED", fp, false, Optional.empty(),
                    Optional.empty(), OptionalInt.empty(), Optional.of(MESSAGE_ID), false);
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(row));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(), any()))
                    .thenReturn(true);

            assertThat(admit(0L, MESSAGE_ID)).isInstanceOf(GuardDecision.Admitted.class);
        }

        @Test
        @DisplayName("dead-letters exhausted when the same exhausted messageId repeats above "
                + "delivery count 0, leaving the request unchanged")
        void dead_letters_exhausted_on_repeat_of_the_exhausted_id() {
            final RequestRecord row = new RequestRecord("FAILED", fp, false, Optional.empty(),
                    Optional.empty(), OptionalInt.empty(), Optional.of(MESSAGE_ID), false);
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(row));

            final GuardDecision decision = admit(1L, MESSAGE_ID);

            assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
            assertThat(((GuardDecision.DeadLetter) decision).reason())
                    .isEqualTo(DeadLetterReason.EXHAUSTED);
            verify(repository, never()).admitReplay(any(), any(), any(), any());
        }

        @Test
        @DisplayName("defers over a PENDING resend row, live or expired, before admitting as a replay")
        void defers_over_a_pending_resend_row() {
            final RequestRecord row = new RequestRecord("FAILED", fp, false, Optional.empty(),
                    Optional.empty(), OptionalInt.empty(), Optional.empty(), true);
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(row));

            final GuardDecision decision = admit();

            assertThat(decision).isInstanceOf(GuardDecision.ReplayDeferred.class);
            verify(repository, never()).admitReplay(any(), any(), any(), any());
        }

        @Test
        @DisplayName("runs the exhausted-identity test before the deferral check")
        void exhausted_identity_test_runs_before_deferral() {
            final RequestRecord row = new RequestRecord("FAILED", fp, false, Optional.empty(),
                    Optional.empty(), OptionalInt.empty(), Optional.of(MESSAGE_ID), true);
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.of(row));

            final GuardDecision decision = admit(1L, MESSAGE_ID);

            assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
        }

        @Test
        @DisplayName("abandons when the replay statement loses a race to another admission")
        void abandons_when_replay_statement_matches_no_row() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("FAILED", fp, false)));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(), any()))
                    .thenReturn(false);

            assertThat(admit()).isInstanceOf(GuardDecision.Abandon.class);
        }

        @Test
        @DisplayName("records the route the delivery would be admitted under")
        void records_the_admission_route() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("FAILED", fp, false)));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(),
                    eq(ReplayRoute.SERVICE))).thenReturn(true);

            final GuardDecision decision = guard.admit(command, BODY, MESSAGE_ID, 0L,
                    ReplayRoute.SERVICE);

            assertThat(decision).isInstanceOf(GuardDecision.Admitted.class);
        }
    }

    @Nested
    @DisplayName("admitting a delivery marked origin = SUPPORT (slice 004, FR-004-057)")
    class SupportAdmission {

        private final SupportSubmission support = new SupportSubmission("local-dev",
                "7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77", "share never admitted");

        @Test
        @DisplayName("a first delivery inserts its claim with the submit-hearing markers to audit")
        void first_delivery_inserts_with_the_markers() {
            when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.empty());
            when(repository.insertClaim(eq(command), eq(BODY), eq(fp), any(), eq(support)))
                    .thenReturn(true);

            final GuardDecision decision = guard.admit(command, BODY, MESSAGE_ID, 0L,
                    ReplayRoute.SUPPORT, support);

            assertThat(decision).isInstanceOf(GuardDecision.Admitted.class);
            assertThat(((GuardDecision.Admitted) decision).replayMessageId()).isEmpty();
            verify(repository, never()).insertClaim(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a FAILED request is admitted as a replay, route support, with the markers")
        void failed_request_replays_with_the_markers() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("FAILED", fp, false)));
            when(repository.admitReplay(eq("RESULTS"), eq(command.requestId()), any(),
                    eq(ReplayRoute.SUPPORT), eq(support))).thenReturn(true);

            final GuardDecision decision = guard.admit(command, BODY, MESSAGE_ID, 0L,
                    ReplayRoute.SUPPORT, support);

            assertThat(decision).isInstanceOf(GuardDecision.Admitted.class);
            assertThat(((GuardDecision.Admitted) decision).replayMessageId()).contains(MESSAGE_ID);
            verify(repository, never()).admitReplay(any(), any(), any(), any());
        }

        @Test
        @DisplayName("a COMPLETED request is a no-op, auditing nothing")
        void completed_request_is_a_no_op() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("COMPLETED", fp, false)));

            assertThat(guard.admit(command, BODY, MESSAGE_ID, 0L, ReplayRoute.SUPPORT, support))
                    .isInstanceOf(GuardDecision.DuplicateComplete.class);
            verify(repository, never()).insertClaim(any(), any(), any(), any(), any());
            verify(repository, never()).admitReplay(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("a live claim abandons, auditing nothing")
        void live_claim_abandons() {
            when(repository.find("RESULTS", command.requestId()))
                    .thenReturn(Optional.of(new RequestRecord("RECEIVED", fp, true)));

            assertThat(guard.admit(command, BODY, MESSAGE_ID, 0L, ReplayRoute.SUPPORT, support))
                    .isInstanceOf(GuardDecision.Abandon.class);
        }
    }
}
