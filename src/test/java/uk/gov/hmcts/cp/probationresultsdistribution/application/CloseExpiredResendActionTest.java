package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.CloseExpiredResendRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.CloseExpiredResendRepository.CloseExpiredResendResult;

/**
 * Pins {@link CloseExpiredResendAction}'s own translation of {@link CloseExpiredResendRepository}'s
 * outcome (H40; slice 004, FR-004-059): the fixed refusal order ({@code unknown-request} →
 * {@code not-eligible} → {@code resend-in-progress}, all three the repository's own statement
 * decides), the success case, and the operator/reason sanitisation this action applies before the
 * repository ever sees them. The statement's own correctness — the {@code FAILED(rolled-back)}
 * transition, the {@code prev=PENDING/-} note, the fresh {@code claim_token} and the
 * {@code audit_note} append, and the guarantee that a late runner's stale-claim completion write
 * afterwards updates no row and counts {@code probation_stale_runner_rejections_total} — is
 * {@code CloseExpiredResendIT}'s job (T050b), proven against a real row and a real guarded write;
 * this test only pins the action's own wrapper over a mocked repository.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the close-expired-resend action")
class CloseExpiredResendActionTest {

    private static final UUID REQUEST_ID = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final String OPERATOR = "local-dev";
    private static final String REASON = "closing an abandoned resend lease";

    @Mock
    private CloseExpiredResendRepository repository;

    private CloseExpiredResendAction action;

    @BeforeEach
    void setUp() {
        action = new CloseExpiredResendAction(repository);
    }

    @Nested
    @DisplayName("the three refusal codes, in the fixed order the statement decides them")
    class Refusals {

        @Test
        @DisplayName("unknown-request: no request row exists")
        void refuses_unknown_request() {
            when(repository.close(eq(REQUEST_ID), eq(OPERATOR), eq(REASON)))
                    .thenReturn(new CloseExpiredResendResult.Refused(ResendRefusal.UNKNOWN_REQUEST));

            final CloseExpiredResendOutcome outcome = action.close(REQUEST_ID, OPERATOR, REASON);

            assertThat(outcome)
                    .isEqualTo(new CloseExpiredResendOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST));
        }

        @Test
        @DisplayName("not-eligible: the leg's latest row is not a PENDING resend row")
        void refuses_not_eligible() {
            when(repository.close(eq(REQUEST_ID), eq(OPERATOR), eq(REASON)))
                    .thenReturn(new CloseExpiredResendResult.Refused(ResendRefusal.NOT_ELIGIBLE));

            final CloseExpiredResendOutcome outcome = action.close(REQUEST_ID, OPERATOR, REASON);

            assertThat(outcome)
                    .isEqualTo(new CloseExpiredResendOutcome.Refused(ResendRefusal.NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("resend-in-progress: the row's lease is still live")
        void refuses_resend_in_progress() {
            when(repository.close(eq(REQUEST_ID), eq(OPERATOR), eq(REASON)))
                    .thenReturn(new CloseExpiredResendResult.Refused(
                            ResendRefusal.RESEND_IN_PROGRESS));

            final CloseExpiredResendOutcome outcome = action.close(REQUEST_ID, OPERATOR, REASON);

            assertThat(outcome)
                    .isEqualTo(new CloseExpiredResendOutcome.Refused(
                            ResendRefusal.RESEND_IN_PROGRESS));
        }
    }

    @Test
    @DisplayName("an eligible expired row is closed")
    void closes_the_eligible_row() {
        when(repository.close(eq(REQUEST_ID), eq(OPERATOR), eq(REASON)))
                .thenReturn(new CloseExpiredResendResult.Closed());

        final CloseExpiredResendOutcome outcome = action.close(REQUEST_ID, OPERATOR, REASON);

        assertThat(outcome).isEqualTo(new CloseExpiredResendOutcome.Done());
    }

    @Test
    @DisplayName("the operator and the reason are sanitised to one line before the statement runs")
    void sanitises_operator_and_reason() {
        when(repository.close(eq(REQUEST_ID), anyString(), anyString()))
                .thenReturn(new CloseExpiredResendResult.Closed());
        final ArgumentCaptor<String> operatorCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

        action.close(REQUEST_ID, " local-dev\n", "a reason\twith control\r\nchars  ");

        verify(repository).close(eq(REQUEST_ID), operatorCaptor.capture(), reasonCaptor.capture());
        assertThat(operatorCaptor.getValue()).isEqualTo("local-dev");
        assertThat(reasonCaptor.getValue()).isEqualTo("a reason with control chars");
    }

    @Test
    @DisplayName("a reason containing the REPLAYED_AFTER_FAILURE marker is escaped before the "
            + "statement runs, so it can never collide with markCompleted's own unanchored scan "
            + "of audit_note for that literal marker (FR-004-012)")
    void escapes_the_replay_marker_in_operator_and_reason() {
        when(repository.close(eq(REQUEST_ID), anyString(), anyString()))
                .thenReturn(new CloseExpiredResendResult.Closed());
        final ArgumentCaptor<String> operatorCaptor = ArgumentCaptor.forClass(String.class);
        final ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);

        action.close(REQUEST_ID, "replayed_after_failure", "see REPLAYED_AFTER_FAILURE ticket");

        verify(repository).close(eq(REQUEST_ID), operatorCaptor.capture(), reasonCaptor.capture());
        assertThat(operatorCaptor.getValue()).isEqualTo("REPLAYED-AFTER-FAILURE");
        assertThat(reasonCaptor.getValue()).isEqualTo("see REPLAYED-AFTER-FAILURE ticket");
    }

    @Nested
    @DisplayName("a null operator or reason is rejected before it ever reaches the sanitiser")
    class NullArguments {

        @Test
        @DisplayName("a null operator throws NullPointerException, not one thrown deep inside "
                + "Pattern.matcher(null) with no context")
        void rejects_a_null_operator() {
            assertThatNullPointerException()
                    .isThrownBy(() -> action.close(REQUEST_ID, null, REASON))
                    .withMessageContaining("operator");
            verify(repository, never()).close(any(), any(), any());
        }

        @Test
        @DisplayName("a null reason throws NullPointerException")
        void rejects_a_null_reason() {
            assertThatNullPointerException()
                    .isThrownBy(() -> action.close(REQUEST_ID, OPERATOR, null))
                    .withMessageContaining("reason");
            verify(repository, never()).close(any(), any(), any());
        }
    }
}
