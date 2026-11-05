package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakeControlOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/** Pins {@code contracts/cli-runners.md} §3: {@code pause-intake}, no arguments. */
@ExtendWith(MockitoExtension.class)
@DisplayName("the pause-intake runner")
class PauseIntakeRunnerTest {

    @Mock
    private IntakePauseAction action;

    @Test
    @DisplayName("answers done when the action pauses intake")
    void answers_done() {
        when(action.pause()).thenReturn(new IntakeControlOutcome.Done());

        assertThat(new PauseIntakeRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers done for a no-op (already paused)")
    void answers_done_on_no_op() {
        when(action.pause()).thenReturn(new IntakeControlOutcome.NoOp());

        assertThat(new PauseIntakeRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers failed when the store cannot be reached")
    void answers_failed() {
        when(action.pause()).thenReturn(new IntakeControlOutcome.StoreUnavailable());

        assertThat(new PauseIntakeRunner(action).run()).isEqualTo(RunnerOutcome.FAILED);
    }
}
