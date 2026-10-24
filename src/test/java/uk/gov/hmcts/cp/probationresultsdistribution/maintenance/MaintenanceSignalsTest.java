package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

class MaintenanceSignalsTest {

    @ParameterizedTest
    @ValueSource(strings = {"create", "drop", "delete", "lock"})
    void failures_should_emit_one_bounded_error_and_counter(final String operation) {
        final Logger logger = (Logger) LoggerFactory.getLogger(BodyRetentionJob.class);
        final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
            final DatabaseClock clock = mock(DatabaseClock.class);
            final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
            final SingleRunnerLock lock = mock(SingleRunnerLock.class);
            final OffsetDateTime now = OffsetDateTime.parse("2026-10-21T02:15:00Z");
            when(clock.nowUtc()).thenReturn(now);
            when(lock.tryAcquire()).thenReturn(true);
            when(catalogue.partitions()).thenReturn(List.of(
                    new PartitionBound("outbound_body_2026_10", now.withDayOfMonth(1), now.plusMonths(1)),
                    new PartitionBound("outbound_body_2026_11", now.plusMonths(1), now.plusMonths(2)),
                    new PartitionBound("outbound_body_2026_12", now.plusMonths(2), now.plusMonths(3)),
                    new PartitionBound("outbound_body_2020_01", now.minusYears(6), now.minusYears(5))));
            final java.sql.SQLException sqlCause = new java.sql.SQLException(
                    "database detail: synthetic-body-marker", "55P03");
            final PartitionMaintenanceException failure = new PartitionMaintenanceException(
                    MaintenanceFailureCause.LOCK_TIMEOUT, "synthetic-body-marker", sqlCause);
            final BodyMaintenanceStateRepository stateRepository =
                    mock(BodyMaintenanceStateRepository.class);
            switch (operation) {
                case "create" -> doThrow(failure).when(repository).createPartition(any(), any(), any());
                case "drop" -> doThrow(failure).when(repository).dropPartition(any());
                case "delete" -> when(repository.deleteDefaultRowsBefore(any())).thenThrow(failure);
                case "lock" -> when(lock.tryAcquire()).thenThrow(failure);
                default -> throw new IllegalArgumentException("Unknown test operation");
            }
            new BodyRetentionJob(clock, catalogue, repository, Period.ofMonths(6), () -> lock,
                    new MaintenanceMetrics(registry, repository, catalogue, clock, stateRepository),
                    stateRepository).run();
            final String step = "delete".equals(operation) ? "drop" : operation;
            assertThat(registry.get("probation_body_maintenance_failures_total").tag("step", step)
                    .counter().count()).isEqualTo(1.0);
            final List<ILoggingEvent> errors = appender.list.stream()
                    .filter(e -> e.getLevel() == Level.ERROR).toList();
            assertThat(errors).hasSize(1);
            assertThat(errors.get(0).getFormattedMessage())
                    .contains("step=" + step, "cause=", "sqlState=55P03");
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage()).doesNotContain("synthetic-body-marker");
                assertThat(event.getThrowableProxy()).isNull();
            });
            assertThat(registry.getMeters()).allSatisfy(meter ->
                    assertThat(meter.getId().getTags().toString())
                            .doesNotContain("synthetic-body-marker", "cause="));
        } finally {
            registry.close();
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
