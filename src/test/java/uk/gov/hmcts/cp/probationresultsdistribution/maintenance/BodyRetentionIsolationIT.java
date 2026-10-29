package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@Timeout(60)
class BodyRetentionIsolationIT extends MaintenanceTestStore {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2044-10-21T02:15:00Z");
    private static final String CREATE_STEP = "create";
    private static final String DROP_STEP = "drop";
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MaintenanceMetrics metrics = metrics(registry, NOW);

    @Test
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    void two_runners_should_serialise_twenty_controlled_overlaps() throws Exception {
        for (int repeat = 0; repeat < 20; repeat++) {
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch finish = new CountDownLatch(1);
            final PartitionCatalogue held = spy(catalogue);
            final PartitionMaintenanceRepository observed = spy(repository);
            doAnswer(invocation -> {
                entered.countDown();
                assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                return invocation.callRealMethod();
            }).when(held).partitions();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                final Future<MaintenanceRunOutcome> winner = executor.submit(() ->
                        controlledJob(held, observed).run());
                try {
                    assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                    final MaintenanceRunOutcome loser = controlledJob(held, observed).run();
                    assertThat(loser.lockAcquired()).isFalse();
                    assertThat(loser.failures()).isEmpty();
                    verifyNoInteractions(observed);
                } finally {
                    finish.countDown();
                }
                assertThat(winner.get(10, TimeUnit.SECONDS).failures()).isEmpty();
            }
        }
        assertThat(registry.find("probation_body_maintenance_failures_total").counters()).isEmpty();
    }

    @Test
    void drop_should_release_table_locks_while_runner_lock_is_still_held() throws Exception {
        job(NOW, Period.ofMonths(6), metrics).run();
        final OffsetDateTime old = NOW.withDayOfMonth(1).minusYears(2);
        repository.createPartition(name(old), old, old.plusMonths(1));
        final CountDownLatch dropped = new CountDownLatch(1);
        final CountDownLatch finish = new CountDownLatch(1);
        final PartitionMaintenanceRepository held = spy(repository);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            dropped.countDown();
            assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(held).dropPartition(name(old));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            final Future<MaintenanceRunOutcome> runner = executor.submit(() ->
                    controlledJob(catalogue, held).run());
            try {
                assertThat(dropped.await(5, TimeUnit.SECONDS)).isTrue();
                final SingleRunnerLock challenger = new SingleRunnerLock(source);
                assertThat(challenger.tryAcquire()).isFalse();
                assertThat(sql.sql("SELECT to_regclass(:name) IS NULL").param("name", name(old))
                        .query(Boolean.class).single()).isTrue();
                assertThat(runner.isDone()).isFalse();
                final long started = System.nanoTime();
                final java.util.UUID body = insertBody(NOW);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
                assertThat(sql.sql("SELECT count(*) FROM outbound_body WHERE outbound_message_id=:id")
                        .param("id", body).query(Long.class).single()).isEqualTo(1);
            } finally {
                finish.countDown();
            }
            assertThat(runner.get(10, TimeUnit.SECONDS).failures()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"create", "drop"})
    void conflicting_statement_should_timeout_in_five_seconds_and_retry_next_run(final String step)
            throws Exception {
        job(NOW, Period.ofMonths(6), metrics).run();
        final OffsetDateTime month = NOW.withDayOfMonth(1).withHour(0).withMinute(0)
                .withSecond(0).withNano(0);
        final OffsetDateTime target = CREATE_STEP.equals(step) ? month.plusMonths(2) : month.minusYears(2);
        if (CREATE_STEP.equals(step)) {
            repository.dropPartition(name(target));
        } else {
            repository.createPartition(name(target), target, target.plusMonths(1));
        }
        try (Connection blocker = source.getConnection(); Statement statement = blocker.createStatement()) {
            blocker.setAutoCommit(false);
            try {
                statement.execute("LOCK TABLE outbound_body IN ACCESS SHARE MODE");
                final long started = System.nanoTime();
                final MaintenanceRunOutcome outcome = job(NOW, Period.ofMonths(6), metrics).run();
                final Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
                assertThat(elapsed).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
                assertThat(outcome.failures()).hasSize(1).allSatisfy(failure -> {
                    assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.LOCK_TIMEOUT);
                    assertThat(failure.detail()).isEqualTo(name(target));
                });
                assertThat(registry.get("probation_body_maintenance_failures_total").tag("step", step)
                        .counter().count()).isEqualTo(1);
                if (DROP_STEP.equals(step)) {
                    assertThat(catalogue.partitions()).extracting(PartitionBound::partitionName)
                            .contains(name(target));
                }
            } finally {
                blocker.rollback();
            }
        }
        final MaintenanceRunOutcome retry = job(NOW, Period.ofMonths(6), metrics).run();
        assertThat(retry.failures()).isEmpty();
        assertThat(CREATE_STEP.equals(step) ? retry.partitionsCreated() : retry.partitionsDropped())
                .contains(name(target));
    }

    @Test
    void metrics_count_should_bound_its_wait_without_acquiring_runner_lock() throws Exception {
        try (Connection blocker = source.getConnection(); Statement statement = blocker.createStatement();
                ExecutorService executor = Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            statement.execute("LOCK TABLE outbound_body_default IN ACCESS EXCLUSIVE MODE");
            final long started = System.nanoTime();
            final Future<Long> count = executor.submit(repository::countDefaultRows);
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> count.get(7, TimeUnit.SECONDS))
                        .isInstanceOf(java.util.concurrent.ExecutionException.class)
                        .hasCauseInstanceOf(PartitionMaintenanceException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - started))
                        .isBetween(Duration.ofSeconds(4), Duration.ofSeconds(6));
            } finally {
                blocker.rollback();
            }
        }
    }

    private BodyRetentionJob controlledJob(final PartitionCatalogue selectedCatalogue,
                                            final PartitionMaintenanceRepository selectedRepository) {
        final DatabaseClock clock = org.mockito.Mockito.mock(DatabaseClock.class);
        org.mockito.Mockito.when(clock.nowUtc()).thenReturn(NOW);
        return new BodyRetentionJob(clock, selectedCatalogue, selectedRepository, Period.ofMonths(6),
                () -> new SingleRunnerLock(source), metrics, stateRepository);
    }
}
