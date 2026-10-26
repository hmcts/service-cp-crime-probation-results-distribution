package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * Pins the startup reconciliation half of slice 004's provisional FR-004-052 (research.md Decision
 * 3; T009/T010): a pod reads {@code intake_control.paused_by_operator} before it ever calls
 * {@code resume()}, so a pod that starts — or restarts — while an operator pause is in force never
 * takes a first delivery.
 */
@Testcontainers
@DisplayName("intake reconciles the persisted operator pause before taking its first delivery")
class IntakeControlReconciliationIT extends SchemaTestSupport {

    private static final Duration PROBE_INTERVAL = Duration.ofMillis(20L);
    private static final Duration OBSERVE = Duration.ofSeconds(2L);

    private ConsumerLifecycleController controller;

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.stop();
        }
    }

    @Test
    @DisplayName("a pod starting with the row paused stays paused and never starts the processor")
    @SuppressWarnings("PMD.CloseResource")
    // A Mockito mock of the Azure client, not a real broker connection: nothing to close.
    void starting_pod_reconciles_to_paused() {
        pauseRow();
        final ServiceBusProcessorClient processor = mock(ServiceBusProcessorClient.class);
        when(processor.getQueueName()).thenReturn("probation.requests");
        controller = newController(processor);

        controller.start();
        sleep(OBSERVE);

        assertThat(controller.pausedByOperator()).isTrue();
        assertThat(controller.intakeStarted()).isFalse();
        verify(processor, never()).start();
    }

    @Test
    @DisplayName("a pod restarting mid-pause (a fresh controller instance) stays paused too")
    @SuppressWarnings("PMD.CloseResource")
    // Mockito mocks of the Azure client, not real broker connections: nothing to close.
    void restarting_pod_reconciles_to_paused() {
        pauseRow();
        final ServiceBusProcessorClient first = mock(ServiceBusProcessorClient.class);
        when(first.getQueueName()).thenReturn("probation.requests");
        controller = newController(first);
        controller.start();
        sleep(OBSERVE);
        controller.stop();

        final ServiceBusProcessorClient restarted = mock(ServiceBusProcessorClient.class);
        when(restarted.getQueueName()).thenReturn("probation.requests");
        controller = newController(restarted);

        controller.start();
        sleep(OBSERVE);

        assertThat(controller.pausedByOperator()).isTrue();
        assertThat(controller.intakeStarted()).isFalse();
        verify(restarted, never()).start();
    }

    private ConsumerLifecycleController newController(final ServiceBusProcessorClient processor) {
        final ProcessedLogProbe storeProbe = new ProcessedLogProbe(jdbc());
        final IntakeControlRepository intakeControl = new JdbcIntakeControlRepository(jdbc());
        final MetricRegistry metrics = new MetricRegistry(new SimpleMeterRegistry());
        return new ConsumerLifecycleController(processor, storeProbe, intakeControl, () -> null,
                metrics, PROBE_INTERVAL);
    }

    private void pauseRow() {
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = now() "
                + "WHERE id = 1");
    }

    private static void sleep(final Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while observing the controller", interrupted);
        }
    }
}
