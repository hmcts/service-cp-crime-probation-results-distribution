package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@DisplayName("the months-ahead and last-success gauges (A3-118, H36, H38)")
class MaintenanceCachedGaugesTest {

    private static final OffsetDateTime NOW =
            OffsetDateTime.of(2026, 10, 21, 2, 15, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime OCT = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("reports the consecutive months ahead, read from the catalogue, never set by the job")
    void reports_months_ahead_from_the_catalogue() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final DatabaseClock clock = mock(DatabaseClock.class);
        final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
        when(clock.nowUtc()).thenReturn(NOW);
        when(catalogue.monthsAhead(OCT)).thenReturn(1);

        new MaintenanceMetrics(registry, mock(PartitionMaintenanceRepository.class), catalogue, clock,
                mock(BodyMaintenanceStateRepository.class));

        assertThat(registry.get("probation_body_partitions_ahead_months").gauge().value())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("reports -1 for months ahead on a failed catalogue read")
    void reports_minus_one_for_months_ahead_on_a_failed_read() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final DatabaseClock clock = mock(DatabaseClock.class);
        final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
        when(clock.nowUtc()).thenReturn(NOW);
        when(catalogue.monthsAhead(OCT)).thenThrow(new PartitionMaintenanceException(
                MaintenanceFailureCause.STORE_UNAVAILABLE, "partitions", null));

        new MaintenanceMetrics(registry, mock(PartitionMaintenanceRepository.class), catalogue, clock,
                mock(BodyMaintenanceStateRepository.class));

        assertThat(registry.get("probation_body_partitions_ahead_months").gauge().value())
                .isEqualTo(-1.0);
    }

    @Test
    @DisplayName("reports the last-success instant in epoch seconds, read from body_maintenance_state")
    void reports_last_success_in_epoch_seconds() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final BodyMaintenanceStateRepository stateRepository = mock(BodyMaintenanceStateRepository.class);
        final OffsetDateTime lastSuccess =
                OffsetDateTime.of(2026, 10, 20, 2, 15, 0, 0, ZoneOffset.UTC);
        when(stateRepository.lastSuccessAt()).thenReturn(lastSuccess);
        final DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.nowUtc()).thenReturn(NOW);

        new MaintenanceMetrics(registry, mock(PartitionMaintenanceRepository.class),
                mock(PartitionCatalogue.class), clock, stateRepository);

        assertThat(registry.get("probation_body_maintenance_last_success_seconds").gauge().value())
                .isEqualTo((double) lastSuccess.toEpochSecond());
    }

    @Test
    @DisplayName("reports -1 for last success on a failed state read, and caches a value once one succeeds")
    void reports_minus_one_for_last_success_on_a_failed_read_then_caches_a_success() throws InterruptedException {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final BodyMaintenanceStateRepository stateRepository = mock(BodyMaintenanceStateRepository.class);
        final OffsetDateTime lastSuccess = NOW.minusDays(1);
        when(stateRepository.lastSuccessAt())
                .thenThrow(new PartitionMaintenanceException(MaintenanceFailureCause.STORE_UNAVAILABLE,
                        "state", null))
                .thenReturn(lastSuccess);
        final DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.nowUtc()).thenReturn(NOW);

        final MaintenanceMetrics metrics = new MaintenanceMetrics(registry,
                mock(PartitionMaintenanceRepository.class), mock(PartitionCatalogue.class), clock,
                stateRepository, Duration.ofMillis(1));

        assertThat(registry.get("probation_body_maintenance_last_success_seconds").gauge().value())
                .isEqualTo(-1.0);
        Thread.sleep(5);
        assertThat(registry.get("probation_body_maintenance_last_success_seconds").gauge().value())
                .isEqualTo((double) lastSuccess.toEpochSecond());
        assertThat(metrics).isNotNull();
    }

    @Test
    @DisplayName("a cached read is reused within its TTL and refreshed once it elapses")
    void a_cached_read_is_reused_within_its_ttl() throws InterruptedException {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
        when(repository.countDefaultRows()).thenReturn(1L).thenReturn(2L);

        new MaintenanceMetrics(registry, repository, mock(PartitionCatalogue.class),
                mock(DatabaseClock.class), mock(BodyMaintenanceStateRepository.class),
                Duration.ofMillis(100));

        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(1.0);
        Thread.sleep(150);
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("owns exactly the four pre-reserved probation_body_* counter/gauge names plus its own two new ones")
    void owns_the_reserved_names_plus_the_two_new_ones() {
        // Test code may depend on config.MetricRegistry to guard against drift; production code in
        // maintenance/ never does (research.md "Metrics ownership", 2026-10-22).
        final Set<String> reservedBodyNames =
                uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry.METRIC_NAMES
                        .stream()
                        .filter(name -> name.startsWith("probation_body_"))
                        .collect(Collectors.toSet());

        assertThat(MaintenanceMetrics.METRIC_NAMES).containsAll(reservedBodyNames);
        assertThat(MaintenanceMetrics.METRIC_NAMES)
                .contains("probation_body_partitions_ahead_months",
                        "probation_body_maintenance_last_success_seconds");
        assertThat(MaintenanceMetrics.METRIC_NAMES).hasSize(reservedBodyNames.size() + 2);
    }
}
