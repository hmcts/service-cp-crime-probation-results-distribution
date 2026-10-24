package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@DisplayName("the last-success-age gauge")
class LastSuccessAgeGaugeTest {

    @Test
    @DisplayName("default-rows gauge should survive a scrape that hits a lock timeout or lost connection")
    void default_rows_gauge_should_survive_a_failed_scrape() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
        when(repository.countDefaultRows()).thenThrow(
                new PartitionMaintenanceException(MaintenanceFailureCause.LOCK_TIMEOUT, "count", null));
        new MaintenanceMetrics(registry, repository);

        assertThatCode(() -> registry.get("probation_body_default_partition_rows").gauge().value())
                .doesNotThrowAnyException();
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isNaN();
    }

    @Test
    void stopped_job_should_exceed_two_day_threshold_without_default_rows() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final PartitionMaintenanceRepository repository = mock(PartitionMaintenanceRepository.class);
        final MaintenanceMetrics metrics = new MaintenanceMetrics(registry, repository);
        metrics.recordSuccess(java.time.Instant.now().minus(java.time.Duration.ofDays(3)));

        assertThat(registry.get("probation_body_job_last_success_age_seconds").gauge().value())
                .isGreaterThan(java.time.Duration.ofDays(2).toSeconds());
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isZero();
    }

    @Test
    @DisplayName("reports a very large age before any run has ever completed successfully")
    void reports_a_sentinel_high_age_before_any_success() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MaintenanceMetrics(registry, mock(PartitionMaintenanceRepository.class));

        final double age = registry.get("probation_body_job_last_success_age_seconds").gauge().value();

        assertThat(age).isGreaterThan(java.time.Duration.ofDays(365).toSeconds());
    }

    @Test
    @DisplayName("drops to near zero immediately after recordSuccess, then keeps climbing without a further call")
    void drops_to_near_zero_then_climbs() throws InterruptedException {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MaintenanceMetrics metrics = new MaintenanceMetrics(registry,
                mock(PartitionMaintenanceRepository.class));

        // The gauge reports whole seconds, so the sleep must cross a full second boundary to show
        // any difference at all.
        metrics.recordSuccess(java.time.Instant.now());
        final double justAfter = registry.get("probation_body_job_last_success_age_seconds")
                .gauge().value();

        Thread.sleep(1_200);
        final double later = registry.get("probation_body_job_last_success_age_seconds")
                .gauge().value();

        assertThat(justAfter).isLessThan(2.0);
        assertThat(later).isGreaterThan(justAfter);
    }

    @Test
    @DisplayName("owns exactly the four pre-reserved probation_body_* names plus the new fifth one")
    void owns_the_reserved_names_plus_the_new_one() {
        // Test code may depend on config.MetricRegistry to guard against drift; production code in
        // maintenance/ never does (research.md "Metrics ownership", 2026-10-22).
        final java.util.Set<String> reservedBodyNames =
                uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry.METRIC_NAMES
                        .stream()
                        .filter(name -> name.startsWith("probation_body_"))
                        .collect(java.util.stream.Collectors.toSet());

        assertThat(MaintenanceMetrics.METRIC_NAMES).containsAll(reservedBodyNames);
        assertThat(MaintenanceMetrics.METRIC_NAMES)
                .contains("probation_body_job_last_success_age_seconds");
        assertThat(MaintenanceMetrics.METRIC_NAMES).hasSize(reservedBodyNames.size() + 1);
    }
}
