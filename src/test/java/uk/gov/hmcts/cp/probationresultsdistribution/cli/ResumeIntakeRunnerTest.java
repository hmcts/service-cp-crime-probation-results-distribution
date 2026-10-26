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

/** Pins {@code contracts/cli-runners.md} §3: {@code resume-intake}, no arguments. */
@ExtendWith(MockitoExtension.class)
@DisplayName("the resume-intake runner")
class ResumeIntakeRunnerTest {

    @Mock
    private IntakePauseAction action;

    @Test
    @DisplayName("answers done when the action resumes intake")
    void answers_done() {
        when(action.resume()).thenReturn(new IntakeControlOutcome.Done());

        assertThat(new ResumeIntakeRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers done for a no-op (already running)")
    void answers_done_on_no_op() {
        when(action.resume()).thenReturn(new IntakeControlOutcome.NoOp());

        assertThat(new ResumeIntakeRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers failed when the store cannot be reached")
    void answers_failed() {
        when(action.resume()).thenReturn(new IntakeControlOutcome.StoreUnavailable());

        assertThat(new ResumeIntakeRunner(action).run()).isEqualTo(RunnerOutcome.FAILED);
    }
}
