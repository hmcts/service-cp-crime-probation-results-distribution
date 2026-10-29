package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutcomeMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionPropertiesFixtures;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistryConfiguration;
import uk.gov.hmcts.cp.probationresultsdistribution.config.OperationsConfiguration;
import uk.gov.hmcts.cp.probationresultsdistribution.config.ServiceBusConsumerConfig;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ResendClaim;

/**
 * Pins FR-005-015's accepted-run execution (T046, T049): accepted runs execute one at a time, in
 * acceptance order, on one executor thread fixed in code; a run that throws is logged at ERROR with
 * a bounded cause and never stops the next; on shutdown the running run claims nothing further and
 * reports the rest {@code not-run}, queued runs never start (each logged at WARN with its
 * {@code resendRunId}), and nothing more is accepted. Also pins the serving-context wiring: with
 * intake enabled a {@link LegResender} is a bean, built from the consumer's own {@link
 * DistributionRunner}, so {@link ResendLegController} is registered when deployed.
 */
@DisplayName("the accepted resend-leg run execution")
// Two captured loggers, the executor's and LegResender's, not loggers this class writes to.
@SuppressWarnings("PMD.MoreThanOneLogger")
class ResendLegExecutionIT {

    private static final long WAIT_SECONDS = 10;
    private static final UUID ID_1 = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final UUID ID_2 = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final UUID ID_3 = UUID.fromString("2d8e4a6b-99c2-4e6f-8d44-1a3b5c6d7e8f");

    private final Logger executorLogger = (Logger) LoggerFactory.getLogger(ResendLegExecutor.class);
    private final Logger resenderLogger = (Logger) LoggerFactory.getLogger(LegResender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        executorLogger.addAppender(appender);
        resenderLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        executorLogger.detachAppender(appender);
        resenderLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("accepted runs")
    class Queueing {

        @Test
        @DisplayName("execute one at a time, in acceptance order, on the one executor thread")
        void runs_execute_one_at_a_time_in_order() throws InterruptedException {
            final LegResender resender = mock(LegResender.class);
            final List<UUID> order = new CopyOnWriteArrayList<>();
            final List<String> threads = new CopyOnWriteArrayList<>();
            final AtomicInteger running = new AtomicInteger();
            final AtomicInteger mostAtOnce = new AtomicInteger();
            final CountDownLatch done = new CountDownLatch(3);
            when(resender.run(any(), any())).thenAnswer(invocation -> {
                mostAtOnce.accumulateAndGet(running.incrementAndGet(), Math::max);
                final ResendAcceptance.Accepted accepted = invocation.getArgument(0);
                order.add(accepted.resendRunId());
                threads.add(Thread.currentThread().getName());
                TimeUnit.MILLISECONDS.sleep(50);
                running.decrementAndGet();
                done.countDown();
                return null;
            });
            final ResendAcceptance.Accepted first = accepted(ID_1);
            final ResendAcceptance.Accepted second = accepted(ID_2);
            final ResendAcceptance.Accepted third = accepted(ID_3);

            try (ResendLegExecutor executor = new ResendLegExecutor(resender)) {
                executor.submit(first);
                executor.submit(second);
                executor.submit(third);

                assertThat(done.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(order).containsExactly(first.resendRunId(), second.resendRunId(),
                    third.resendRunId());
            assertThat(threads).containsOnly(ResendLegExecutor.THREAD_NAME);
            assertThat(mostAtOnce).hasValue(1);
        }

        @Test
        @DisplayName("a run that throws is logged at ERROR with a bounded cause and the next still runs")
        void a_thrown_run_is_logged_and_the_next_runs() throws InterruptedException {
            final LegResender resender = mock(LegResender.class);
            final ResendAcceptance.Accepted failing = accepted(ID_1);
            final ResendAcceptance.Accepted next = accepted(ID_2);
            final CountDownLatch nextRan = new CountDownLatch(1);
            when(resender.run(eq(failing), any()))
                    .thenThrow(new IllegalStateException("text that must not be logged"));
            when(resender.run(eq(next), any())).thenAnswer(invocation -> {
                nextRan.countDown();
                return null;
            });

            try (ResendLegExecutor executor = new ResendLegExecutor(resender)) {
                executor.submit(failing);
                executor.submit(next);

                assertThat(nextRan.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            }

            assertThat(lines(Level.ERROR)).anySatisfy(line -> assertThat(line)
                    .contains("resendRunId=" + failing.resendRunId(), "cause=IllegalStateException")
                    .doesNotContain("text that must not be logged"));
        }
    }

    @Nested
    @DisplayName("on shutdown")
    class Shutdown {

        @Test
        @DisplayName("the running run claims nothing further, reports the rest not-run; queued runs never start")
        // The executor is closed on its own thread, which is the behaviour under test.
        @SuppressWarnings("PMD.CloseResource")
        void shutdown_stops_claiming_and_drops_queued_runs() throws InterruptedException {
            final IntakePauseGate pauseGate = mock(IntakePauseGate.class);
            final ProcessedRequestRepository store = mock(ProcessedRequestRepository.class);
            final DistributionRunner runner = mock(DistributionRunner.class);
            final LegResender resender = new LegResender(pauseGate, store, runner,
                    mock(OutcomeMetrics.class), 100);
            final CountDownLatch inFlight = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            when(pauseGate.pausedByOperator()).thenReturn(false);
            when(store.claimResend(eq("RESULTS"), eq(ID_1), any(), any(), any(), any()))
                    .thenAnswer(invocation -> claimed(ID_1, invocation.getArgument(2)));
            when(runner.runResend(any(), any(), anyBoolean())).thenAnswer(invocation -> {
                inFlight.countDown();
                assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return new RunResult(AttemptOrigin.RESEND, LegStatus.SENT, Optional.empty(), true);
            });
            final ResendAcceptance.Accepted running = acceptedBy(resender, ID_1, ID_2);
            final ResendAcceptance.Accepted queued = acceptedBy(resender, ID_3);
            final ResendLegExecutor executor = new ResendLegExecutor(resender,
                    Duration.ofSeconds(WAIT_SECONDS));
            executor.submit(running);
            executor.submit(queued);
            assertThat(inFlight.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

            final Thread closing = Thread.ofPlatform().start(executor::close);
            assertThat(awaitLine(Level.WARN, queued.resendRunId().toString())).isTrue();
            release.countDown();
            closing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

            assertThat(closing.isAlive()).isFalse();
            verify(store, never()).claimResend(eq("RESULTS"), eq(ID_2), any(), any(), any(), any());
            verify(store, never()).claimResend(eq("RESULTS"), eq(ID_3), any(), any(), any(), any());
            assertThat(lines(Level.INFO)).anySatisfy(line -> assertThat(line)
                    .contains("resend-leg report", "resendRunId=" + running.resendRunId(),
                            ID_2 + "=not-run"));
            assertThat(lines(Level.INFO)).noneSatisfy(line -> assertThat(line)
                    .contains("resendRunId=" + queued.resendRunId()));
            assertThatThrownBy(() -> executor.submit(accepted(ID_1)))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("a run still in flight when the shutdown wait runs out is logged at ERROR")
        // close() is the call under test, made explicitly rather than by try-with-resources.
        @SuppressWarnings("PMD.CloseResource")
        void close_shutdownWaitElapses_should_logErrorWithBoundedCause() throws InterruptedException {
            final LegResender resender = mock(LegResender.class);
            final CountDownLatch inFlight = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            when(resender.run(any(), any())).thenAnswer(invocation -> {
                inFlight.countDown();
                assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return null;
            });
            final ResendLegExecutor executor = new ResendLegExecutor(resender, Duration.ofMillis(50));
            try {
                executor.submit(accepted(ID_1));
                assertThat(inFlight.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                executor.close();

                assertThat(lines(Level.ERROR)).anySatisfy(line -> assertThat(line)
                        .contains("resend-leg run still in flight at shutdown",
                                "cause=shutdown-wait-elapsed"));
            } finally {
                release.countDown();
            }
        }

        @Test
        @DisplayName("an interrupted shutdown wait is logged at ERROR and the interrupt flag is kept")
        // close() is the call under test, made explicitly rather than by try-with-resources.
        @SuppressWarnings("PMD.CloseResource")
        void close_waitInterrupted_should_logErrorAndRestoreInterruptFlag() throws InterruptedException {
            final LegResender resender = mock(LegResender.class);
            final CountDownLatch inFlight = new CountDownLatch(1);
            final CountDownLatch release = new CountDownLatch(1);
            when(resender.run(any(), any())).thenAnswer(invocation -> {
                inFlight.countDown();
                assertThat(release.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return null;
            });
            final AtomicBoolean stillInterrupted = new AtomicBoolean();
            final ResendLegExecutor executor = new ResendLegExecutor(resender,
                    Duration.ofSeconds(WAIT_SECONDS));
            try {
                executor.submit(accepted(ID_1));
                assertThat(inFlight.await(WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();

                final Thread closing = Thread.ofPlatform().start(() -> {
                    Thread.currentThread().interrupt();
                    executor.close();
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                });
                closing.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));

                assertThat(closing.isAlive()).isFalse();
                assertThat(stillInterrupted).isTrue();
                assertThat(lines(Level.ERROR)).anySatisfy(line -> assertThat(line)
                        .contains("resend-leg shutdown wait interrupted",
                                "cause=InterruptedException"));
            } finally {
                release.countDown();
            }
        }
    }

    @Nested
    @DisplayName("in the serving context")
    class ServingContext {

        @Test
        @DisplayName("with intake enabled LegResender is a bean, so ResendLegController is registered")
        void leg_resender_and_controller_are_beans_when_deployed() {
            EndToEndTestSupport.startSharedContainers();
            servingContext().run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(LegResender.class);
                assertThat(context).hasSingleBean(ResendLegExecutor.class);
                assertThat(context).hasSingleBean(ResendLegController.class);
            });
        }

        @Test
        @DisplayName("with intake disabled there is no LegResender, no executor and no ResendLegController")
        void servingContext_intakeDisabled_should_registerNoResendLegBeans() {
            EndToEndTestSupport.startSharedContainers();
            servingContext().withPropertyValues("distribution.consumer.enabled=false").run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(LegResender.class);
                assertThat(context).doesNotHaveBean(ResendLegExecutor.class);
                assertThat(context).doesNotHaveBean(ResendLegController.class);
            });
        }
    }

    private static ApplicationContextRunner servingContext() {
        // OperationsConfiguration first: its @ConditionalOnBean must not depend on scan order.
        return new ApplicationContextRunner()
                .withUserConfiguration(OperationsConfiguration.class, ServiceBusConsumerConfig.class,
                        MetricRegistryConfiguration.class)
                .withBean(DataSource.class, EndToEndTestSupport::sharedDataSource)
                .withBean(JdbcClient.class,
                        () -> JdbcClient.create(EndToEndTestSupport.sharedDataSource()))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(EndToEndTestSupport.sharedDataSource()))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DistributionProperties.class, DistributionPropertiesFixtures::defaults)
                .withPropertyValues(
                        "distribution.consumer.enabled=true",
                        "distribution.servicebus.connection-string="
                                + EndToEndTestSupport.serviceBusConnectionString(),
                        "distribution.servicebus.namespace=",
                        "distribution.servicebus.queue-name=probation.requests",
                        "distribution.servicebus.max-concurrent-calls=2",
                        "distribution.redis.host=" + EndToEndTestSupport.redisHost(),
                        "distribution.redis.port=" + EndToEndTestSupport.redisPort(),
                        "distribution.redis.ssl=false",
                        "distribution.redis.verify-peer=false",
                        "distribution.redis.key-prefix=EXT_",
                        "distribution.usersgroups.youth-filter-key=EMYouthFilter",
                        "distribution.gateway.subscription-key=test-subscription-key");
    }

    private static ResendAcceptance.Accepted accepted(final UUID... ids) {
        return new ResendAcceptance.Accepted(UUID.randomUUID(), List.of(ids), "an-operator", false,
                Optional.empty());
    }

    private static ResendAcceptance.Accepted acceptedBy(final LegResender resender,
                                                        final UUID... ids) {
        final ResendAcceptance acceptance = resender.accept(
                new ResendRequest(List.of(ids), "an-operator", "a reason", false));
        assertThat(acceptance).isInstanceOf(ResendAcceptance.Accepted.class);
        return (ResendAcceptance.Accepted) acceptance;
    }

    private static ResendClaim claimed(final UUID requestId, final UUID resendRunId) {
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId,
                UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
        return new ResendClaim.Claimed(new ResendAttempt(UUID.randomUUID(), 2, RunClaim.fresh(),
                resendRunId), command);
    }

    private boolean awaitLine(final Level level, final String fragment) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        boolean found = false;
        while (!found && System.nanoTime() < deadline) {
            found = lines(level).stream().anyMatch(line -> line.contains(fragment));
            if (!found) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
        }
        return found;
    }

    private List<String> lines(final Level level) {
        return List.copyOf(appender.list).stream().filter(event -> level.equals(event.getLevel()))
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
