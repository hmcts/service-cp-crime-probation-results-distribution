package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@DisplayName("the body-retention job's own log lines")
class BodyRetentionJobLogLineTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 21, 2, 15, 0, 0, ZoneOffset.UTC);

    private final Logger logger = (Logger) LoggerFactory.getLogger(BodyRetentionJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("logs a creation at INFO naming the month, and a failed creation at ERROR with step, month and cause")
    void logs_create_success_and_failure() {
        appender.start();
        logger.addAppender(appender);

        final DatabaseClock clock = mock(DatabaseClock.class);
        final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
        final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
        final SingleRunnerLock lock = mock(SingleRunnerLock.class);
        when(clock.nowUtc()).thenReturn(NOW);
        when(catalogue.partitions()).thenReturn(List.of());
        lenient().when(lock.tryAcquire()).thenReturn(true);
        doThrow(new PartitionMaintenanceException(MaintenanceFailureCause.PARTITION_OVERLAP,
                "would overlap", new RuntimeException("ERROR: partition \"x\" would overlap \"y\"")))
                .when(repository).createPartition(org.mockito.ArgumentMatchers.eq("outbound_body_2026_11"),
                        any(), any());

        new BodyRetentionJob(clock, catalogue, repository, Period.ofMonths(6), () -> lock,
                mock(MaintenanceMetrics.class), mock(BodyMaintenanceStateRepository.class)).run();

        final List<ILoggingEvent> infoLines = appender.list.stream()
                .filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infoLines).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("outbound_body_2026_10"));
        assertThat(infoLines).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("outbound_body_2026_12"));

        final List<ILoggingEvent> errorLines = appender.list.stream()
                .filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errorLines).hasSize(1);
        final String message = errorLines.get(0).getFormattedMessage();
        assertThat(message).contains("create", "outbound_body_2026_11", "partition-overlap");
        assertThat(message).doesNotContain("ERROR: partition");
    }

    @Test
    @DisplayName("logs nothing at ERROR or WARN when the lock is not acquired")
    void logs_nothing_at_error_or_warn_when_skipped() {
        appender.start();
        logger.addAppender(appender);
        final SingleRunnerLock lock = mock(SingleRunnerLock.class);
        when(lock.tryAcquire()).thenReturn(false);

        new BodyRetentionJob(mock(DatabaseClock.class), mock(PartitionCatalogue.class),
                mock(PartitionMaintenanceRepository.class), Period.ofMonths(6), () -> lock,
                mock(MaintenanceMetrics.class), mock(BodyMaintenanceStateRepository.class)).run();

        assertThat(appender.list).noneMatch(e ->
                e.getLevel() == Level.ERROR || e.getLevel() == Level.WARN);
    }
}
