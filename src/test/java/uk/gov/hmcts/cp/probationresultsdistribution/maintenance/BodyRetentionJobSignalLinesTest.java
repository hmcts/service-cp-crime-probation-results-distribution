package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@DisplayName("the body-retention job's start/skip/summary lines and months-ahead WARNs (A3-119, H37)")
class BodyRetentionJobSignalLinesTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 21, 2, 15, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime OCT = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NOV = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DEC = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime JAN = OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FEB = OffsetDateTime.of(2027, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final Logger logger = (Logger) LoggerFactory.getLogger(BodyRetentionJob.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final DatabaseClock clock = mock(DatabaseClock.class);
    private final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
    private final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
    private final SingleRunnerLock lock = mock(SingleRunnerLock.class);
    private final BodyMaintenanceStateRepository stateRepository =
            mock(BodyMaintenanceStateRepository.class);
    private final MaintenanceMetrics metrics = mock(MaintenanceMetrics.class);

    @BeforeEach
    void start() {
        appender.start();
        logger.addAppender(appender);
        lenient().when(clock.nowUtc()).thenReturn(NOW);
    }

    @AfterEach
    void stop() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("logs one start line and one skip line, and no summary line, when the lock is not acquired")
    void logs_start_and_skip_when_not_acquired() {
        when(lock.tryAcquire()).thenReturn(false);

        job().run();

        assertThat(infoMessages()).hasSize(2);
        assertThat(infoMessages().get(0)).isEqualTo("body-retention maintenance triggered");
        assertThat(infoMessages().get(1)).contains("skipped");
        assertThat(infoMessages()).noneMatch(m -> m.contains("finished"));
    }

    @Test
    @DisplayName("logs one start line and one summary line, naming created/dropped/failed/monthsAhead/defaultRows/durationMs, on a run that holds the lock")
    void logs_start_and_summary_when_held() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(infoMessages().get(0)).isEqualTo("body-retention maintenance triggered");
        final String summary = infoMessages().stream().filter(m -> m.contains("finished"))
                .findFirst().orElseThrow();
        assertThat(summary).contains("created=0", "dropped=0", "failed=0", "monthsAhead=3",
                "defaultRows=0", "durationMs=");
    }

    @Test
    @DisplayName("logs a start WARN when fewer than two whole months ahead have a partition at the start")
    void logs_a_start_warn_below_two_months_ahead() {
        when(lock.tryAcquire()).thenReturn(true);
        // Only the current month is present: zero whole months after it are covered.
        when(catalogue.partitions()).thenReturn(List.of(new PartitionBound(
                "outbound_body_2026_10", OCT, NOV)));
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(warnMessages()).anySatisfy(m ->
                assertThat(m).contains("0", "start", "fewer than 2"));
    }

    @Test
    @DisplayName("logs no start WARN when two or more whole months ahead already have a partition")
    void logs_no_start_warn_at_or_above_two_months_ahead() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(warnMessages()).noneMatch(m -> m.contains("start"));
    }

    @Test
    @DisplayName("logs an end WARN when a run with no failure ends with fewer than three whole months ahead")
    void logs_an_end_warn_below_three_months_ahead() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(2);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(warnMessages()).anySatisfy(m ->
                assertThat(m).contains("2", "end", "fewer than 3"));
    }

    @Test
    @DisplayName("logs no end WARN when a run with no failure ends with three or more whole months ahead")
    void logs_no_end_warn_at_or_above_three_months_ahead() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(warnMessages()).noneMatch(m -> m.contains("end"));
    }

    @Test
    @DisplayName("logs no end WARN when the run has a failure, even if it ends with fewer than three months ahead")
    void logs_no_end_warn_when_the_run_has_a_failure() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(List.of());
        lenient().doThrow(new PartitionMaintenanceException(
                        uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause
                                .PARTITION_OVERLAP, "overlap", new RuntimeException()))
                .when(repository).createPartition(
                        org.mockito.ArgumentMatchers.eq("outbound_body_2026_11"),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(1);
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(warnMessages()).noneMatch(m -> m.contains("end"));
    }

    @Test
    @DisplayName("a failed fresh end-of-run months-ahead read is logged step=read, summarised as failed, and leaves success unrecorded")
    void logs_a_failed_fresh_months_ahead_read_as_step_read() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenThrow(new PartitionMaintenanceException(
                uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause
                        .STORE_UNAVAILABLE, "months ahead", new java.sql.SQLException("x", "08006")));
        when(repository.countDefaultRows()).thenReturn(0L);

        job().run();

        assertThat(errorMessages()).anySatisfy(m ->
                assertThat(m).contains("step=read", "cause=store-unavailable", "sqlState=08006"));
        assertThat(summaryMessage()).contains("failed=1", "monthsAhead=-1");
        assertThat(warnMessages()).noneMatch(m -> m.contains("end"));
        org.mockito.Mockito.verify(stateRepository, org.mockito.Mockito.never())
                .recordSuccessOnConnection(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a failed fresh end-of-run DEFAULT-rows read is logged step=read and leaves success unrecorded")
    void logs_a_failed_fresh_default_rows_read_as_step_read() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenThrow(new PartitionMaintenanceException(
                uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause.OTHER,
                "default rows", null));

        job().run();

        assertThat(errorMessages()).anySatisfy(m ->
                assertThat(m).contains("step=read", "cause=other", "sqlState=unavailable"));
        assertThat(summaryMessage()).contains("failed=1", "defaultRows=-1");
        org.mockito.Mockito.verify(stateRepository, org.mockito.Mockito.never())
                .recordSuccessOnConnection(org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(metrics);
    }

    @Test
    @DisplayName("a failed body_maintenance_state update is logged step=state, summarised as failed, and never counted on the failures metric")
    void logs_a_failed_state_update_as_step_state() {
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenReturn(allFourMonthsPresent());
        when(catalogue.monthsAhead(currentMonth())).thenReturn(3);
        when(repository.countDefaultRows()).thenReturn(0L);
        org.mockito.Mockito.doThrow(new PartitionMaintenanceException(
                        uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause
                                .LOCK_TIMEOUT, "state", new java.sql.SQLException("x", "55P03")))
                .when(stateRepository)
                .recordSuccessOnConnection(org.mockito.ArgumentMatchers.any());

        job().run();

        assertThat(errorMessages()).anySatisfy(m ->
                assertThat(m).contains("step=state", "cause=lock-timeout", "sqlState=55P03"));
        assertThat(summaryMessage()).contains("failed=1");
        org.mockito.Mockito.verifyNoInteractions(metrics);
    }

    private BodyRetentionJob job() {
        return new BodyRetentionJob(clock, catalogue, repository, Period.ofMonths(6), () -> lock,
                metrics, stateRepository);
    }

    private String summaryMessage() {
        return infoMessages().stream().filter(m -> m.contains("finished")).findFirst().orElseThrow();
    }

    private List<String> errorMessages() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static OffsetDateTime currentMonth() {
        return OCT;
    }

    private static List<PartitionBound> allFourMonthsPresent() {
        return List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2026_12", DEC, JAN),
                new PartitionBound("outbound_body_2027_01", JAN, FEB));
    }

    private List<String> infoMessages() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.INFO)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> warnMessages() {
        return appender.list.stream().filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
