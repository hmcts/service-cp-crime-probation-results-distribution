package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository.CloseInFlightResult;

/**
 * Pins {@code contracts/cli-runners.md} §6: {@code rollback-close-in-flight}, no arguments —
 * provisional (research.md Decision 2).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the rollback-close-in-flight runner")
class RollbackCloseInFlightRunnerTest {

    @Mock
    private RollbackCloseInFlightAction action;

    @Test
    @DisplayName("answers done, reporting the rows closed by prior status")
    void answers_done() {
        when(action.run())
                .thenReturn(new RollbackCloseInFlightOutcome.Done(new CloseInFlightResult(2, 1)));

        assertThat(new RollbackCloseInFlightRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers refused when intake is not paused")
    void answers_refused() {
        when(action.run()).thenReturn(
                new RollbackCloseInFlightOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED));

        assertThat(new RollbackCloseInFlightRunner(action).run()).isEqualTo(RunnerOutcome.REFUSED);
    }
}
