package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository.CloseInFlightResult;

/**
 * Pins the runner-ownership answer of research.md Decision 2 (provisional FR-004-049): refused
 * {@code intake-not-paused} unless intake is paused — the same read-before-run guard purge-queue
 * uses — otherwise the statement runs. The statement's own correctness (FR-004-049a) is
 * {@code RollbackFencingIT}'s job (T030); this test only pins the action's pause-gate wrapper.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the rollback close-in-flight action")
class RollbackCloseInFlightActionTest {

    @Mock
    private IntakePauseGate pauseGate;
    @Mock
    private RollbackFencingRepository repository;

    private RollbackCloseInFlightAction action;

    @BeforeEach
    void setUp() {
        action = new RollbackCloseInFlightAction(pauseGate, repository);
    }

    @Test
    @DisplayName("refuses intake-not-paused while intake is running, closing nothing")
    void refuses_while_not_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(false);

        final RollbackCloseInFlightOutcome outcome = action.run();

        assertThat(outcome)
                .isEqualTo(new RollbackCloseInFlightOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED));
        verify(repository, never()).closeInFlight();
    }

    @Test
    @DisplayName("while paused, runs the statement and reports the rows closed by prior status")
    void runs_the_statement_while_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(true);
        final CloseInFlightResult result = new CloseInFlightResult(2, 1);
        when(repository.closeInFlight()).thenReturn(result);

        final RollbackCloseInFlightOutcome outcome = action.run();

        assertThat(outcome).isEqualTo(new RollbackCloseInFlightOutcome.Done(result));
    }
}
