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
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.RequestRecord;

@ExtendWith(MockitoExtension.class)
@DisplayName("the idempotency guard")
class IdempotencyGuardTest {

    private static final String BODY = "{\"source\":\"RESULTS\"}";

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

    @Test
    @DisplayName("admits a first-seen key by inserting its claim")
    void admits_a_first_seen_key() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.empty());
        when(repository.insertClaim(eq(command), eq(BODY), eq(fp), any())).thenReturn(true);

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.Admitted.class);
    }

    @Test
    @DisplayName("abandons quietly when it loses the insert race")
    void abandons_on_lost_insert_race() {
        when(repository.find("RESULTS", command.requestId())).thenReturn(Optional.empty());
        when(repository.insertClaim(eq(command), eq(BODY), eq(fp), any())).thenReturn(false);

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.Abandon.class);
    }

    @Test
    @DisplayName("completes a COMPLETED request with no run")
    void completes_a_completed_request() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("COMPLETED", fp, false)));

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.DuplicateComplete.class);
        verify(repository, never()).reclaim(any(), any(), any());
    }

    @Test
    @DisplayName("dead-letters a collision when the fingerprint differs on a known key")
    void dead_letters_a_collision() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RECEIVED", "a-different-fingerprint",
                        true)));

        final GuardDecision decision = guard.admit(command, BODY);

        assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
        assertThat(((GuardDecision.DeadLetter) decision).reason())
                .isEqualTo(DeadLetterReason.COLLISION);
    }

    @Test
    @DisplayName("abandons a delivery that meets a live claim")
    void abandons_on_live_claim() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RETRYING", fp, true)));

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.Abandon.class);
        verify(repository, never()).reclaim(any(), any(), any());
    }

    @Test
    @DisplayName("reclaims an expired claim and admits the run")
    void reclaims_an_expired_claim() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RECEIVED", fp, false)));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(true);

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.Admitted.class);
    }

    @Test
    @DisplayName("abandons when another delivery wins the reclaim race")
    void abandons_when_reclaim_is_lost() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("RETRYING", fp, false)));
        when(repository.reclaim(eq("RESULTS"), eq(command.requestId()), any())).thenReturn(false);

        assertThat(guard.admit(command, BODY)).isInstanceOf(GuardDecision.Abandon.class);
    }

    @Test
    @DisplayName("dead-letters a FAILED request as exhausted (interim FR-002-008)")
    void dead_letters_a_failed_request() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("FAILED", fp, false)));

        final GuardDecision decision = guard.admit(command, BODY);

        assertThat(decision).isInstanceOf(GuardDecision.DeadLetter.class);
        assertThat(((GuardDecision.DeadLetter) decision).reason())
                .isEqualTo(DeadLetterReason.EXHAUSTED);
    }

    @Test
    @DisplayName("refuses an unknown request status explicitly")
    void refuses_an_unknown_status() {
        when(repository.find("RESULTS", command.requestId()))
                .thenReturn(Optional.of(new RequestRecord("GONE", fp, false)));

        assertThatThrownBy(() -> guard.admit(command, BODY))
                .isInstanceOf(IllegalStateException.class);
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
}
