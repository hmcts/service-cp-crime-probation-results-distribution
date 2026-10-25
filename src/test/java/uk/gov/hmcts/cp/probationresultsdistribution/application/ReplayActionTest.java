package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ReplayCandidate;

/**
 * Pins FR-004-014–FR-004-017: the replay action decides under the request's current state and
 * writes nothing to the store itself; a {@code FAILED} request with no {@code PENDING} resend row
 * is re-sent byte for byte under a fresh message identity.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the replay action")
class ReplayActionTest {

    private static final UUID REQUEST_ID = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final UUID HEARING_ID = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final String STORED_COMMAND = "{\"source\":\"RESULTS\",\"requestId\":\""
            + REQUEST_ID + "\"}";

    @Mock
    private ProcessedRequestRepository repository;
    @Mock
    private ReplayEnqueuer enqueuer;

    private ReplayAction action;

    @BeforeEach
    void setUp() {
        action = new ReplayAction(repository, enqueuer);
    }

    @Test
    @DisplayName("refuses unknown-request for a requestId that names no request")
    void refuses_unknown_request() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.empty());

        final ReplayOutcome outcome = action.replay(REQUEST_ID);

        assertThat(outcome).isEqualTo(new ReplayOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST));
        verify(enqueuer, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("answers no-op for a COMPLETED request, enqueuing nothing")
    void no_op_for_completed() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                new ReplayCandidate("COMPLETED", HEARING_ID, STORED_COMMAND, false)));

        assertThat(action.replay(REQUEST_ID)).isEqualTo(new ReplayOutcome.NoOp());
        verify(enqueuer, never()).enqueue(any(), any(), any());
    }

    @Nested
    @DisplayName("a RECEIVED or RETRYING request")
    class NotEligible {

        @Test
        @DisplayName("refuses not-eligible when RECEIVED")
        void refuses_received() {
            when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                    new ReplayCandidate("RECEIVED", HEARING_ID, STORED_COMMAND, false)));

            assertThat(action.replay(REQUEST_ID))
                    .isEqualTo(new ReplayOutcome.Refused(ResendRefusal.NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("refuses not-eligible when RETRYING")
        void refuses_retrying() {
            when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                    new ReplayCandidate("RETRYING", HEARING_ID, STORED_COMMAND, false)));

            assertThat(action.replay(REQUEST_ID))
                    .isEqualTo(new ReplayOutcome.Refused(ResendRefusal.NOT_ELIGIBLE));
        }
    }

    @Test
    @DisplayName("refuses resend-in-progress when the leg's latest row is a PENDING resend row")
    void refuses_resend_in_progress() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                new ReplayCandidate("FAILED", HEARING_ID, STORED_COMMAND, true)));

        assertThat(action.replay(REQUEST_ID))
                .isEqualTo(new ReplayOutcome.Refused(ResendRefusal.RESEND_IN_PROGRESS));
        verify(enqueuer, never()).enqueue(any(), any(), any());
    }

    @Test
    @DisplayName("enqueues the stored command byte for byte under a fresh messageId, "
            + "correlated by hearingId")
    void enqueues_the_stored_command() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                new ReplayCandidate("FAILED", HEARING_ID, STORED_COMMAND, false)));

        final ReplayOutcome outcome = action.replay(REQUEST_ID);

        assertThat(outcome).isInstanceOf(ReplayOutcome.Enqueued.class);
        final String messageId = ((ReplayOutcome.Enqueued) outcome).messageId();
        assertThat(messageId).isNotEqualTo("RESULTS:" + REQUEST_ID);
        verify(enqueuer).enqueue(eq(messageId), eq(HEARING_ID.toString()), eq(STORED_COMMAND));
    }

    @Test
    @DisplayName("mints a fresh messageId on every call, never repeating an earlier replay's")
    void mints_a_fresh_message_id_each_time() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                new ReplayCandidate("FAILED", HEARING_ID, STORED_COMMAND, false)));

        final String first = ((ReplayOutcome.Enqueued) action.replay(REQUEST_ID)).messageId();
        final String second = ((ReplayOutcome.Enqueued) action.replay(REQUEST_ID)).messageId();

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("answers store-unavailable when the processed log cannot be reached")
    void answers_store_unavailable() {
        when(repository.findForReplay("RESULTS", REQUEST_ID))
                .thenThrow(new QueryTimeoutException("boom"));

        assertThat(action.replay(REQUEST_ID)).isEqualTo(new ReplayOutcome.StoreUnavailable());
    }

    @Test
    @DisplayName("answers broker-unavailable when the enqueue fails, with nothing changed in the store")
    void answers_broker_unavailable() {
        when(repository.findForReplay("RESULTS", REQUEST_ID)).thenReturn(Optional.of(
                new ReplayCandidate("FAILED", HEARING_ID, STORED_COMMAND, false)));
        doThrow(new ReplayEnqueuer.ReplayEnqueueException(new RuntimeException("broker down")))
                .when(enqueuer).enqueue(anyString(), anyString(), anyString());

        assertThat(action.replay(REQUEST_ID)).isEqualTo(new ReplayOutcome.BrokerUnavailable());
    }
}
