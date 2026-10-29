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
import org.springframework.dao.QueryTimeoutException;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * Pins FR-004-051, FR-004-052 (provisional): pausing stops intake and setting the gauge is the
 * repository's and {@code ConsumerLifecycleController}'s job; this action only decides {@code
 * done}/{@code no-op} from the persisted write, and a store outage is an entirely separate hold
 * from an operator pause — this action never reads or reacts to store availability at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the intake pause and resume action")
class IntakePauseResumeTest {

    @Mock
    private IntakeControlRepository repository;

    private IntakePauseAction action;

    @BeforeEach
    void setUp() {
        action = new IntakePauseAction(repository);
    }

    @Test
    @DisplayName("pausing a running intake answers done")
    void pause_answers_done_when_it_changes_state() {
        when(repository.pause()).thenReturn(true);

        assertThat(action.pause()).isEqualTo(new IntakeControlOutcome.Done());
    }

    @Test
    @DisplayName("pausing an already-paused intake answers no-op")
    void pause_answers_no_op_when_already_paused() {
        when(repository.pause()).thenReturn(false);

        assertThat(action.pause()).isEqualTo(new IntakeControlOutcome.NoOp());
    }

    @Test
    @DisplayName("resuming a paused intake answers done")
    void resume_answers_done_when_it_changes_state() {
        when(repository.resume()).thenReturn(true);

        assertThat(action.resume()).isEqualTo(new IntakeControlOutcome.Done());
    }

    @Test
    @DisplayName("resuming an already-running intake answers no-op")
    void resume_answers_no_op_when_already_running() {
        when(repository.resume()).thenReturn(false);

        assertThat(action.resume()).isEqualTo(new IntakeControlOutcome.NoOp());
    }

    @Test
    @DisplayName("answers store-unavailable when the write fails, on pause and on resume alike")
    void answers_store_unavailable_on_either_action() {
        when(repository.pause()).thenThrow(new QueryTimeoutException("boom"));
        when(repository.resume()).thenThrow(new QueryTimeoutException("boom"));

        assertThat(action.pause()).isEqualTo(new IntakeControlOutcome.StoreUnavailable());
        assertThat(action.resume()).isEqualTo(new IntakeControlOutcome.StoreUnavailable());
    }

    @Test
    @DisplayName("never reads or reacts to store availability: a database outage is a separate hold")
    void never_touches_store_availability() {
        when(repository.pause()).thenReturn(true);

        action.pause();

        verify(repository, never()).pausedByOperator();
    }
}
