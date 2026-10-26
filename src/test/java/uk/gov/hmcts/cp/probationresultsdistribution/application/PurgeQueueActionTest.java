package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins FR-004-053: purge-queue is refused {@code intake-not-paused} unless intake is paused by an
 * operator (the reconciled in-memory flag, research.md Decision 3 — read-before-remove, never a
 * fresh store read); while paused it removes every message enqueued before the run's start
 * instant, leaving the dead-letter queue untouched (the port's own job — this action issues no
 * dead-letter call of its own).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the purge-queue action")
class PurgeQueueActionTest {

    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");

    @Mock
    private IntakePauseGate pauseGate;
    @Mock
    private QueuePurger purger;

    private PurgeQueueAction action;

    @BeforeEach
    void setUp() {
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        action = new PurgeQueueAction(pauseGate, purger, clock);
    }

    @Test
    @DisplayName("refuses intake-not-paused while intake is running, removing nothing")
    void refuses_while_not_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(false);

        final PurgeQueueOutcome outcome = action.purge();

        assertThat(outcome)
                .isEqualTo(new PurgeQueueOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED));
        verify(purger, never()).purgeBefore(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("while paused, purges before the run's start instant and reports count + cut-off")
    void purges_while_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(true);
        when(purger.purgeBefore(NOW)).thenReturn(new QueuePurger.PurgeResult(7L));

        final PurgeQueueOutcome outcome = action.purge();

        assertThat(outcome).isEqualTo(new PurgeQueueOutcome.Done(7L, NOW));
    }

    @Test
    @DisplayName("answers broker-unavailable when the purge port fails")
    void answers_broker_unavailable() {
        when(pauseGate.pausedByOperator()).thenReturn(true);
        when(purger.purgeBefore(NOW))
                .thenThrow(new QueuePurger.QueuePurgeException(new RuntimeException("down")));

        assertThat(action.purge()).isEqualTo(new PurgeQueueOutcome.BrokerUnavailable());
    }
}
