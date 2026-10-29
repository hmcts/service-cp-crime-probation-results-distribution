package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/** Pins {@code contracts/cli-runners.md} §4: {@code purge-queue}, no arguments. */
@ExtendWith(MockitoExtension.class)
@DisplayName("the purge-queue runner")
class PurgeQueueRunnerTest {

    @Mock
    private PurgeQueueAction action;

    @Test
    @DisplayName("answers done, reporting the count and cut-off")
    void answers_done() {
        when(action.purge())
                .thenReturn(new PurgeQueueOutcome.Done(3L, Instant.parse("2026-10-25T12:00:00Z")));

        assertThat(new PurgeQueueRunner(action).run()).isEqualTo(RunnerOutcome.DONE);
    }

    @Test
    @DisplayName("answers refused when intake is not paused")
    void answers_refused() {
        when(action.purge())
                .thenReturn(new PurgeQueueOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED));

        assertThat(new PurgeQueueRunner(action).run()).isEqualTo(RunnerOutcome.REFUSED);
    }

    @Test
    @DisplayName("answers failed when the broker cannot be reached")
    void answers_failed() {
        when(action.purge()).thenReturn(new PurgeQueueOutcome.BrokerUnavailable());

        assertThat(new PurgeQueueRunner(action).run()).isEqualTo(RunnerOutcome.FAILED);
    }
}
