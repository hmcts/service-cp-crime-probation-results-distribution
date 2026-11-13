package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * Pins the CLI-only confirmation window (review of PR #8/#23, M3 follow-up): a standalone command
 * must not treat intake as paused until a live pod is guaranteed to have noticed the request,
 * stopped and drained — not the instant {@code intake_control.paused_by_operator} is read. The
 * confirmation comparison itself is {@link IntakeControlRepository#pausedForAtLeast}'s own concern,
 * run in SQL against the database's clock (review of PR #23 round 4); this gate only has to pass the
 * configured window through and relay the answer.
 */
@DisplayName("the standalone command's confirmed intake-pause gate")
class ConfirmedIntakePauseGateTest {

    private static final Duration CONFIRMATION_WINDOW = Duration.ofSeconds(10).plus(Duration.ofMinutes(4));

    private final IntakeControlRepository repository = mock(IntakeControlRepository.class);

    @Test
    @DisplayName("confirms paused when the repository confirms the window has elapsed")
    void confirms_when_the_repository_confirms() {
        when(repository.pausedForAtLeast(CONFIRMATION_WINDOW)).thenReturn(true);

        assertThat(gate().pausedByOperator()).isTrue();
    }

    @Test
    @DisplayName("refuses as unpaused when the repository has not confirmed the window")
    void refuses_when_the_repository_has_not_confirmed() {
        when(repository.pausedForAtLeast(CONFIRMATION_WINDOW)).thenReturn(false);

        assertThat(gate().pausedByOperator()).isFalse();
    }

    @Test
    @DisplayName("passes its own configured window through unchanged, every call")
    void passes_its_configured_window_through() {
        gate().pausedByOperator();

        verify(repository).pausedForAtLeast(eq(CONFIRMATION_WINDOW));
    }

    private ConfirmedIntakePauseGate gate() {
        return new ConfirmedIntakePauseGate(repository, CONFIRMATION_WINDOW);
    }
}
