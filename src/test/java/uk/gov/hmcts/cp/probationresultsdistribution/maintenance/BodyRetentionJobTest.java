package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@ExtendWith(MockitoExtension.class)
@DisplayName("the body-retention job")
class BodyRetentionJobTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 21, 2, 15, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime OCT = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NOV = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DEC = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime JAN = OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private DatabaseClock clock;
    @Mock
    private PartitionCatalogue catalogue;
    @Mock
    private PartitionMaintenanceRepository repository;
    @Mock
    private SingleRunnerLock lock;
    @Mock
    private MaintenanceMetrics metrics;

    private BodyRetentionJob job;

    @BeforeEach
    void setUp() {
        lenient().when(clock.nowUtc()).thenReturn(NOW);
        lenient().when(lock.tryAcquire()).thenReturn(true);
        job = new BodyRetentionJob(clock, catalogue, repository, Period.ofMonths(6), () -> lock,
                metrics);
    }

    @Test
    @DisplayName("creates only the months absent among the current one and the next two")
    void creates_only_the_absent_months() {
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV)));

        job.run();

        verify(repository, never()).createPartition(eq("outbound_body_2026_10"), any(), any());
        verify(repository).createPartition("outbound_body_2026_11", NOV, DEC);
        verify(repository).createPartition("outbound_body_2026_12", DEC, JAN);
        verify(repository, never()).createPartition(eq("outbound_body_2027_01"), any(), any());
    }

    @Test
    @DisplayName("creates nothing when the current month and the next two already exist")
    void creates_nothing_when_all_present() {
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2026_12", DEC, JAN)));

        final MaintenanceRunOutcome outcome = job.run();

        verify(repository, never()).createPartition(any(), any(), any());
        assertThat(outcome.partitionsCreated()).isEmpty();
    }

    @Test
    @DisplayName("counts a created partition and keeps going when a creation fails")
    void counts_creations_and_survives_a_failure() {
        when(catalogue.partitions()).thenReturn(List.of());
        lenient().doThrow(new PartitionMaintenanceException(MaintenanceFailureCause.PARTITION_OVERLAP,
                "overlap", new RuntimeException()))
                .when(repository).createPartition(eq("outbound_body_2026_11"), any(), any());

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.partitionsCreated())
                .containsExactlyInAnyOrder("outbound_body_2026_10", "outbound_body_2026_12");
        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).step()).isEqualTo(BodyMaintenanceStep.CREATE);
        assertThat(outcome.failures().get(0).cause())
                .isEqualTo(MaintenanceFailureCause.PARTITION_OVERLAP);
        assertThat(outcome.failures().get(0).detail()).isEqualTo("outbound_body_2026_11");
        verify(repository).createPartition("outbound_body_2026_12", DEC, JAN);
    }

    @Test
    @DisplayName("drops every partition whose upper bound is on or before the retention cut-off")
    void drops_partitions_on_or_before_the_cutoff() {
        // NOW is 21 October 2026; P6M retention -> cut-off is 1 April 2026.
        final OffsetDateTime feb = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime mar = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime apr = OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime may = OffsetDateTime.of(2026, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_2026_02", feb, mar),
                new PartitionBound("outbound_body_2026_03", mar, apr),
                new PartitionBound("outbound_body_2026_04", apr, may),
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2026_12", DEC, JAN)));

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.partitionsDropped())
                .containsExactlyInAnyOrder("outbound_body_2026_02", "outbound_body_2026_03");
        verify(repository).dropPartition("outbound_body_2026_02");
        verify(repository).dropPartition("outbound_body_2026_03");
        verify(repository, never()).dropPartition("outbound_body_2026_04");
    }

    @Test
    @DisplayName("never drops the DEFAULT partition, and decides by bounds, never by name")
    void never_drops_default_and_decides_by_bounds() {
        final OffsetDateTime feb = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime mar = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        // Misleadingly named "2026_04" but its bounds actually cover March: must be removed because
        // the bounds say so, never because of the name (spec.md Edge Cases).
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_default", null, null),
                new PartitionBound("outbound_body_2026_04", feb, mar)));

        final MaintenanceRunOutcome outcome = job.run();

        verify(repository, never()).dropPartition("outbound_body_default");
        verify(repository).dropPartition("outbound_body_2026_04");
        assertThat(outcome.partitionsDropped()).containsExactly("outbound_body_2026_04");
    }

    @Test
    @DisplayName("deletes stranded DEFAULT rows before the same cut-off after every removal")
    void deletes_default_rows_before_the_cutoff() {
        when(catalogue.partitions()).thenReturn(List.of());
        when(repository.deleteDefaultRowsBefore(OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0,
                ZoneOffset.UTC))).thenReturn(3L);

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.defaultRowsDeleted()).isEqualTo(3L);
    }

    @Test
    @DisplayName("counts a DEFAULT-delete failure as step=drop (AS-7), without failing the whole run")
    void counts_a_default_delete_failure_as_drop() {
        when(catalogue.partitions()).thenReturn(List.of());
        when(repository.deleteDefaultRowsBefore(any())).thenThrow(new PartitionMaintenanceException(
                MaintenanceFailureCause.LOCK_TIMEOUT, "timed out", new RuntimeException()));

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.defaultRowsDeleted()).isZero();
        assertThat(outcome.failures()).anySatisfy(f -> {
            assertThat(f.step()).isEqualTo(BodyMaintenanceStep.DROP);
            assertThat(f.cause()).isEqualTo(MaintenanceFailureCause.LOCK_TIMEOUT);
        });
    }

    @Test
    @DisplayName("counts a dropped partition and keeps going when a removal fails")
    void counts_drops_and_survives_a_failure() {
        final OffsetDateTime feb = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime mar = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_2026_02", feb, mar)));
        doThrowFromDrop("outbound_body_2026_02");

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.partitionsDropped()).isEmpty();
        assertThat(outcome.failures()).anySatisfy(f -> {
            assertThat(f.step()).isEqualTo(BodyMaintenanceStep.DROP);
            assertThat(f.cause()).isEqualTo(MaintenanceFailureCause.LOCK_TIMEOUT);
            assertThat(f.detail()).isEqualTo("outbound_body_2026_02");
        });
    }

    private void doThrowFromDrop(final String partitionName) {
        doThrow(new PartitionMaintenanceException(
                        MaintenanceFailureCause.LOCK_TIMEOUT, "timed out", new RuntimeException()))
                .when(repository).dropPartition(partitionName);
    }

    @Test
    @DisplayName("skips the run entirely, touching no partition statement, when the lock is not acquired")
    void skips_the_run_when_the_lock_is_not_acquired() {
        when(lock.tryAcquire()).thenReturn(false);

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.lockAcquired()).isFalse();
        assertThat(outcome.partitionsCreated()).isEmpty();
        assertThat(outcome.partitionsDropped()).isEmpty();
        assertThat(outcome.failures()).isEmpty();
        verifyNoInteractions(catalogue, repository);
        verify(lock, never()).release(anyBoolean());
    }

    @Test
    @DisplayName("releases the lock by committing once the run completes normally")
    void releases_by_committing_on_success() {
        when(catalogue.partitions()).thenReturn(List.of());

        job.run();

        verify(lock).release(true);
    }

    @Test
    @DisplayName("releases the lock by rolling back, and rethrows, when the run fails unexpectedly")
    void releases_by_rolling_back_on_an_unexpected_failure() {
        when(catalogue.partitions()).thenThrow(new IllegalStateException("store unreachable"));

        assertThatThrownBy(job::run)
                .isInstanceOf(PartitionMaintenanceException.class);

        verify(lock).release(false);
    }

    @Test
    @DisplayName("classifies a lock-acquisition failure as store-unavailable, touching no partition statement")
    void classifies_a_lock_acquisition_failure() {
        when(lock.tryAcquire()).thenThrow(new PartitionMaintenanceException(
                MaintenanceFailureCause.STORE_UNAVAILABLE, "lock", new RuntimeException()));

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.lockAcquired()).isFalse();
        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).step()).isEqualTo(BodyMaintenanceStep.LOCK);
        assertThat(outcome.failures().get(0).cause())
                .isEqualTo(MaintenanceFailureCause.STORE_UNAVAILABLE);
        verifyNoInteractions(catalogue, repository);
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.LOCK);
    }

    @Test
    @DisplayName("reports a lock-acquisition failure's real classified cause, not a hardcoded one")
    void reports_the_lock_acquisition_failures_real_cause() {
        when(lock.tryAcquire()).thenThrow(new PartitionMaintenanceException(
                MaintenanceFailureCause.LOCK_TIMEOUT, "lock", new RuntimeException()));

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).cause())
                .isEqualTo(MaintenanceFailureCause.LOCK_TIMEOUT);
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.LOCK);
    }

    @Test
    @DisplayName("falls back to OTHER for a lock-acquisition failure that was never classified")
    void falls_back_to_other_for_an_unclassified_lock_acquisition_failure() {
        when(lock.tryAcquire()).thenThrow(new IllegalStateException("not classified"));

        final MaintenanceRunOutcome outcome = job.run();

        assertThat(outcome.failures()).hasSize(1);
        assertThat(outcome.failures().get(0).cause()).isEqualTo(MaintenanceFailureCause.OTHER);
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.LOCK);
    }

    @Test
    @DisplayName("records a creation and a creation failure on the metrics")
    void records_creation_metrics() {
        when(catalogue.partitions()).thenReturn(List.of());
        doNothing().when(repository).createPartition(eq("outbound_body_2026_10"), any(), any());
        doThrow(new PartitionMaintenanceException(MaintenanceFailureCause.PARTITION_OVERLAP,
                "overlap", new RuntimeException()))
                .when(repository).createPartition(eq("outbound_body_2026_11"), any(), any());

        job.run();

        verify(metrics, org.mockito.Mockito.times(2)).partitionCreated();
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.CREATE);
    }

    @Test
    @DisplayName("records a drop and a drop failure on the metrics")
    void records_drop_metrics() {
        final OffsetDateTime feb = OffsetDateTime.of(2026, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime mar = OffsetDateTime.of(2026, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        when(catalogue.partitions()).thenReturn(List.of(
                new PartitionBound("outbound_body_2026_02", feb, mar),
                new PartitionBound("outbound_body_2026_03", mar,
                        OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC))));
        doNothing().when(repository).dropPartition("outbound_body_2026_02");
        doThrow(new PartitionMaintenanceException(MaintenanceFailureCause.LOCK_TIMEOUT,
                "timed out", new RuntimeException()))
                .when(repository).dropPartition("outbound_body_2026_03");

        job.run();

        verify(metrics).partitionDropped();
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.DROP);
    }

    @Test
    @DisplayName("records success only once the run completes, never when skipped or failed unexpectedly")
    void records_success_only_on_completion() {
        when(catalogue.partitions()).thenReturn(List.of());

        job.run();

        final org.mockito.InOrder order = org.mockito.Mockito.inOrder(repository, lock, metrics);
        order.verify(repository).deleteDefaultRowsBefore(any());
        order.verify(repository).countDefaultRows();
        order.verify(lock).release(true);
        order.verify(metrics).recordSuccess(any());
    }

    @Test
    void commit_failure_should_be_counted_and_sanitised_without_recording_success() {
        when(catalogue.partitions()).thenReturn(List.of());
        doThrow(new IllegalStateException("synthetic-body-marker")).when(lock).release(true);

        assertThatThrownBy(job::run).isInstanceOf(PartitionMaintenanceException.class)
                .hasNoCause().hasMessageNotContaining("synthetic-body-marker");
        verify(metrics).maintenanceFailure(BodyMaintenanceStep.LOCK);
        verify(metrics, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("never records success when the lock is not acquired")
    void never_records_success_when_skipped() {
        when(lock.tryAcquire()).thenReturn(false);

        job.run();

        verify(metrics, never()).recordSuccess(any());
    }

    @Test
    @DisplayName("never records success when the run fails unexpectedly")
    void never_records_success_on_unexpected_failure() {
        when(catalogue.partitions()).thenThrow(new IllegalStateException("boom"));

        assertThatThrownBy(job::run).isInstanceOf(PartitionMaintenanceException.class);

        verify(metrics, never()).recordSuccess(any());
    }
}
