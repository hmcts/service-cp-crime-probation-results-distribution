package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/** Pins {@code contracts/cli-runners.md} §1: {@code replay --request-id <uuid>}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("the replay runner")
class ReplayRunnerTest {

    private static final UUID REQUEST_ID = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");

    @Mock
    private ReplayAction action;

    @Test
    @DisplayName("refuses missing-argument when --request-id is absent")
    void refuses_missing_request_id() {
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay")).isEqualTo(RunnerOutcome.REFUSED);
        verify(action, org.mockito.Mockito.never()).replay(any());
    }

    @Test
    @DisplayName("refuses unreadable-argument when --request-id is not a UUID")
    void refuses_non_uuid_request_id() {
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay", "--request-id", "not-a-uuid"))
                .isEqualTo(RunnerOutcome.REFUSED);
    }

    @Test
    @DisplayName("answers done when the action enqueues")
    void answers_done_on_enqueue() {
        when(action.replay(REQUEST_ID)).thenReturn(new ReplayOutcome.Enqueued("REPLAY:abc"));
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay", "--request-id", REQUEST_ID.toString()))
                .isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers done for a no-op")
    void answers_done_on_no_op() {
        when(action.replay(REQUEST_ID)).thenReturn(new ReplayOutcome.NoOp());
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay", "--request-id", REQUEST_ID.toString()))
                .isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers refused for a refusal code")
    void answers_refused() {
        when(action.replay(REQUEST_ID))
                .thenReturn(new ReplayOutcome.Refused(ResendRefusal.NOT_ELIGIBLE));
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay", "--request-id", REQUEST_ID.toString()))
                .isEqualTo(RunnerOutcome.REFUSED);
    }

    @Test
    @DisplayName("answers failed for store-unavailable")
    void answers_failed() {
        when(action.replay(REQUEST_ID)).thenReturn(new ReplayOutcome.StoreUnavailable());
        final ReplayRunner runner = new ReplayRunner(action);

        assertThat(runner.run("replay", "--request-id", REQUEST_ID.toString()))
                .isEqualTo(RunnerOutcome.FAILED);
    }
}
