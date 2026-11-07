package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedLogProbe;

/**
 * Drives the controller's failure, rollback and retry branches over mocked collaborators — the paths a
 * real-broker, real-Postgres outage IT only drives on their happy edge. No Spring context and no
 * Testcontainers: a mock processor is made to refuse to start and to refuse to stop, and the probe is
 * made to throw, so the rollback and the "retry on the next probe" rule are observed directly.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the consumer lifecycle controller")
class ConsumerLifecycleControllerTest {

    private static final Duration PROBE_INTERVAL = Duration.ofMillis(20L);
    private static final Duration AWAIT_LIMIT = Duration.ofSeconds(5L);

    @Mock
    private ServiceBusProcessorClient processor;
    @Mock
    private ProcessedLogProbe storeProbe;
    @Mock
    private IntakeControlRepository intakeControl;

    private SimpleMeterRegistry meter;
    private MetricRegistry metrics;
    private ConsumerLifecycleController controller;

    @BeforeEach
    void setUp() {
        meter = new SimpleMeterRegistry();
        metrics = new MetricRegistry(meter);
        lenient().when(processor.getQueueName()).thenReturn("probation.requests");
        lenient().when(intakeControl.pausedByOperator()).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.stop();
        }
    }

    @Nested
    @DisplayName("when the processor refuses to start")
    class ProcessorRefusesToStart {

        @Test
        @DisplayName("rolls the state back so the next probe retries the gated start")
        void rolls_back_and_retries() {
            when(storeProbe.available()).thenReturn(true);
            doThrow(new IllegalStateException("start refused")).doNothing().when(processor).start();
            controller = newController();

            controller.start();

            // The gated start only completes if the first failure rolled the state back to its prior
            // value: left at RUNNING, the probe would never resume and intake would never start.
            awaitUntil(controller::intakeStarted);
            verify(processor, atLeast(2)).start();
            assertThat(gauge("probation_intake_suspended")).isEqualTo(0.0d);
        }
    }

    @Nested
    @DisplayName("when the processor refuses to stop during suspension")
    class ProcessorRefusesToStop {

        @Test
        @DisplayName("stays suspending so the next suspend request retries and eventually suspends")
        void stays_suspending_and_retries() {
            final AtomicBoolean storeUp = new AtomicBoolean(true);
            when(storeProbe.available()).thenAnswer(invocation -> storeUp.get());
            doNothing().when(processor).start();
            doThrow(new IllegalStateException("stop refused")).doNothing().when(processor).stop();
            controller = newController();
            controller.start();
            awaitUntil(controller::intakeStarted);

            storeUp.set(false);
            controller.suspendIntake();
            controller.suspendIntake();

            // The first stop() throws and leaves the state SUSPENDING; the second request retries and
            // succeeds, so the suspension is counted exactly once.
            awaitUntil(() -> counter("probation_intake_suspensions_total") == 1.0d);
            verify(processor, atLeast(2)).stop();
            assertThat(gauge("probation_intake_suspended")).isEqualTo(1.0d);
        }
    }

    @Nested
    @DisplayName("when the probe itself fails unexpectedly")
    class ProbeFails {

        @Test
        @DisplayName("survives the failure and starts intake on the next probe")
        void survives_and_retries() {
            when(storeProbe.available())
                    .thenThrow(new IllegalStateException("probe blew up"))
                    .thenReturn(true);
            doNothing().when(processor).start();
            controller = newController();

            controller.start();

            // The first probe throws and is swallowed loudly; the schedule outlives it and the next
            // probe starts intake.
            awaitUntil(controller::intakeStarted);
            verify(processor, atLeast(1)).start();
        }
    }

    @Nested
    @DisplayName("when a delivery is in flight at shutdown")
    class RunInFlightAtShutdown {

        @Test
        @DisplayName("stop() blocks until the in-flight delivery finishes, then returns")
        void waits_for_the_in_flight_delivery() throws InterruptedException {
            when(storeProbe.available()).thenReturn(true);
            doNothing().when(processor).start();
            doNothing().when(processor).stop();
            final AtomicInteger inFlight = new AtomicInteger();
            controller = newController(inFlight, Duration.ofSeconds(5L));
            controller.start();
            awaitUntil(controller::intakeStarted);

            inFlight.incrementAndGet();
            final CountDownLatch stopReturned = new CountDownLatch(1);
            final Thread stopper = new Thread(() -> {
                controller.stop();
                stopReturned.countDown();
            });
            stopper.start();

            // A rolling deploy must not kill a run mid-POST (design budget up to 215s): stop() has to
            // still be blocked a moment later, not have returned while the delivery was in flight.
            assertThat(stopReturned.await(200L, TimeUnit.MILLISECONDS)).isFalse();

            inFlight.decrementAndGet();

            assertThat(stopReturned.await(AWAIT_LIMIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("gives up after the drain timeout rather than blocking for ever")
        void gives_up_after_the_drain_timeout() {
            when(storeProbe.available()).thenReturn(true);
            doNothing().when(processor).start();
            doNothing().when(processor).stop();
            final AtomicInteger inFlight = new AtomicInteger(1);
            final Duration drainTimeout = Duration.ofMillis(200L);
            controller = newController(inFlight, drainTimeout);
            controller.start();
            awaitUntil(controller::intakeStarted);

            final long startedAt = System.nanoTime();
            controller.stop();
            final Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(elapsed).isGreaterThanOrEqualTo(drainTimeout);
        }
    }

    private ConsumerLifecycleController newController() {
        return newController(new AtomicInteger(), Duration.ofSeconds(5L));
    }

    private ConsumerLifecycleController newController(final AtomicInteger inFlightRuns,
                                                       final Duration drainTimeout) {
        return new ConsumerLifecycleController(processor, storeProbe, intakeControl, () -> null,
                metrics, PROBE_INTERVAL, inFlightRuns, drainTimeout);
    }

    private double gauge(final String name) {
        final Gauge found = meter.find(name).tag("cause", "database").gauge();
        return found == null ? Double.NaN : found.value();
    }

    private double counter(final String name) {
        final Counter found = meter.find(name).counter();
        return found == null ? 0.0d : found.count();
    }

    private static void awaitUntil(final BooleanSupplier condition) {
        final long deadline = System.nanoTime() + AWAIT_LIMIT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep();
        }
        throw new AssertionError("the condition was not met within " + AWAIT_LIMIT);
    }

    private static void sleep() {
        try {
            Thread.sleep(10L);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting a condition", interrupted);
        }
    }
}
