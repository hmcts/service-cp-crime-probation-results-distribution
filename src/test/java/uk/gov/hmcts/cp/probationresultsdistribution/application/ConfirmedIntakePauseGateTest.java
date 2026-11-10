package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * Pins the CLI-only confirmation window (review of PR #8/#23, M3 follow-up): a standalone command
 * must not treat intake as paused until a live pod is guaranteed to have noticed the request,
 * stopped and drained — not the instant {@code intake_control.paused_by_operator} is read.
 */
@DisplayName("the standalone command's confirmed intake-pause gate")
class ConfirmedIntakePauseGateTest {

    private static final Instant NOW = Instant.parse("2026-08-19T16:42:07.512Z");
    private static final Duration CONFIRMATION_WINDOW = Duration.ofSeconds(10).plus(Duration.ofMinutes(4));

    private final IntakeControlRepository repository = mock(IntakeControlRepository.class);

    @Test
    @DisplayName("refuses as unpaused when the row is not paused at all")
    void refuses_when_not_paused() {
        when(repository.pausedByOperator()).thenReturn(false);

        assertThat(gate().pausedByOperator()).isFalse();
    }

    @Test
    @DisplayName("refuses as unpaused before the confirmation window has elapsed, even though the "
            + "row already says paused")
    void refuses_before_the_window_elapses() {
        when(repository.pausedByOperator()).thenReturn(true);
        when(repository.pausedSince()).thenReturn(Optional.of(NOW.minus(CONFIRMATION_WINDOW)
                .plusSeconds(1)));

        assertThat(gate().pausedByOperator()).isFalse();
    }

    @Test
    @DisplayName("confirms paused exactly once the confirmation window has elapsed")
    void confirms_once_the_window_has_elapsed() {
        when(repository.pausedByOperator()).thenReturn(true);
        when(repository.pausedSince()).thenReturn(Optional.of(NOW.minus(CONFIRMATION_WINDOW)));

        assertThat(gate().pausedByOperator()).isTrue();
    }

    @Test
    @DisplayName("confirms paused well after the confirmation window has elapsed")
    void confirms_long_after_the_window_has_elapsed() {
        when(repository.pausedByOperator()).thenReturn(true);
        when(repository.pausedSince()).thenReturn(Optional.of(NOW.minus(CONFIRMATION_WINDOW)
                .minusSeconds(60)));

        assertThat(gate().pausedByOperator()).isTrue();
    }

    @Test
    @DisplayName("refuses as unpaused when the row says paused but carries no paused_at, rather "
            + "than confirming on a null")
    void refuses_when_paused_with_no_timestamp() {
        when(repository.pausedByOperator()).thenReturn(true);
        when(repository.pausedSince()).thenReturn(Optional.empty());

        assertThat(gate().pausedByOperator()).isFalse();
    }

    private ConfirmedIntakePauseGate gate() {
        return new ConfirmedIntakePauseGate(repository, CONFIRMATION_WINDOW,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}
