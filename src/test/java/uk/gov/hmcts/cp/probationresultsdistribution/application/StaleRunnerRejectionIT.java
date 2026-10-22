package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * US2 Acceptance Scenarios 8-9 (FR-002-007, A2-67): an unexpected run exception and a stale-runner
 * rejection (a guarded outcome write that matched no row because another delivery took the claim over)
 * both hand the delivery back, and are dead-lettered {@code exhausted} on the final permitted delivery
 * rather than burned to the broker's own reason. The stale run never completes the message.
 *
 * <p>The scenarios are driven over the real broker with test doubles for the runner and guard, with
 * {@code maxDeliveryCount = 1} so the one delivery is the final permitted one.
 */
@DisplayName("a stale or unexpected run is handed back, exhausted on the final delivery")
class StaleRunnerRejectionIT extends EndToEndTestSupport {

    @Test
    @DisplayName("dead-letters an unexpected run failure exhausted on the final delivery, and counts it")
    void unexpected_failure_on_the_final_delivery_is_exhausted() {
        final UUID requestId = UUID.randomUUID();
        final SimpleMeterRegistry meter = new SimpleMeterRegistry();
        final DistributionRunner runner = mock(DistributionRunner.class);
        when(runner.run(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        startProcessorWith(listenerWith(meter, runner, mock(ProcessedRequestRepository.class)), 1);

        sendCommand(requestId, "{\"ignored\":\"by the stubbed parser\"}");

        assertDeadLetteredExhausted(requestId);
        assertThat(count(meter, "probation_processing_failures_total")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("dead-letters a stale-runner rejection exhausted on the final delivery, never completing")
    void stale_runner_on_the_final_delivery_is_exhausted() {
        final UUID requestId = UUID.randomUUID();
        final SimpleMeterRegistry meter = new SimpleMeterRegistry();
        final ProcessedRequestRepository repo = mock(ProcessedRequestRepository.class);
        final DistributionRunner runner = mock(DistributionRunner.class);
        final RunResult stale =
                new RunResult(AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(), false);
        when(runner.run(any(), any(), any())).thenReturn(stale);
        startProcessorWith(listenerWith(meter, runner, repo), 1);

        sendCommand(requestId, "{\"ignored\":\"by the stubbed parser\"}");

        assertDeadLetteredExhausted(requestId);
        assertThat(count(meter, "probation_stale_runner_rejections_total")).isEqualTo(1.0d);
        verify(repo, never()).markCompleted(any(), any(), any(), any());
    }

    private ProbationMessageListener listenerWith(final SimpleMeterRegistry meter,
            final DistributionRunner runner, final ProcessedRequestRepository repo) {
        final DistributionCommandParser parser = mock(DistributionCommandParser.class);
        final DistributionCommand command = new DistributionCommand("RESULTS", UUID.randomUUID(),
                UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
        when(parser.parse(any())).thenReturn(command);
        final IdempotencyGuard guard = mock(IdempotencyGuard.class);
        when(guard.admit(any(), any())).thenReturn(new GuardDecision.Admitted(RunClaim.fresh()));
        return new ProbationMessageListener(parser, guard, runner, repo, new MetricRegistry(meter),
                alwaysAvailableStore(), 1);
    }

    private void assertDeadLetteredExhausted(final UUID requestId) {
        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked)
                .as("the final permitted delivery is dead-lettered rather than burned")
                .isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason())
                .as("the service records its own exhausted reason, not the broker's")
                .isEqualTo("exhausted");
    }

    private static double count(final SimpleMeterRegistry meter, final String name) {
        final Counter counter = meter.find(name).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
