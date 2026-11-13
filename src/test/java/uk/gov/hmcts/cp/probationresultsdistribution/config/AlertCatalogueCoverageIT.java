package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;

/**
 * Pins FR-006-014 to FR-006-022, SC-006-005: exactly 11 {@link AlertDefinition} entries and 5
 * {@link CountedClass} entries, every series the service itself claims to emit checked against
 * the real Micrometer registries' own published name sets — so a renamed or removed series fails
 * this test rather than silently going unnoticed (FR-006-019).
 */
@DisplayName("AlertCatalogue coverage")
class AlertCatalogueCoverageIT {

    private static final Set<String> EMITTED_SERIES = Stream.concat(
            MetricRegistry.METRIC_NAMES.stream(), MaintenanceMetrics.METRIC_NAMES.stream())
            .collect(Collectors.toUnmodifiableSet());

    @Test
    @DisplayName("carries exactly 11 paging alerts and 5 counted classes")
    void carries_eleven_alerts_and_five_counted_classes() {
        assertThat(AlertCatalogue.alerts()).hasSize(11);
        assertThat(AlertCatalogue.countedClasses()).hasSize(5);
    }

    @Test
    @DisplayName("every alert id 1-11 appears exactly once")
    void every_alert_id_appears_once() {
        final List<Integer> ids =
                AlertCatalogue.alerts().stream().map(AlertDefinition::id).toList();
        assertThat(ids).containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11);
    }

    @Test
    @DisplayName("every alert series this service owns is one it actually emits")
    void every_owned_alert_series_is_actually_emitted() {
        AlertCatalogue.alerts().stream()
                .filter(AlertDefinition::ownedByThisService)
                .forEach(alert -> Arrays.stream(alert.series().split(",\\s*"))
                        .forEach(series -> assertThat(EMITTED_SERIES)
                                .as("alert %d (%s)'s series '%s'", alert.id(), alert.name(), series)
                                .contains(series)));
    }

    @Test
    @DisplayName("every counted class series is one this service actually emits")
    void every_counted_class_series_is_actually_emitted() {
        AlertCatalogue.countedClasses().forEach(countedClass -> {
            final String seriesName = countedClass.series().split("\\{")[0];
            assertThat(EMITTED_SERIES).as("counted class %s", countedClass.name())
                    .contains(seriesName);
        });
    }

    @Test
    @DisplayName("every external series is named, never silently claimed as this service's own")
    void external_series_are_named() {
        final List<AlertDefinition> external = AlertCatalogue.alerts().stream()
                .filter(alert -> !alert.ownedByThisService()).toList();
        assertThat(external).extracting(AlertDefinition::id).contains(1, 10, 11);
        external.forEach(alert -> assertThat(AlertDefinition.EXTERNAL_SERIES)
                .contains(alert.series()));
    }

    @Test
    @DisplayName("the leg-failed alert (4) selects exactly the five non-receiver-rejected reasons, every origin")
    void leg_failed_alert_selects_the_right_reasons() {
        final AlertDefinition legFailed =
                AlertCatalogue.alerts().stream().filter(a -> a.id() == 4).findFirst().orElseThrow();

        assertThat(legFailed.series()).isEqualTo("probation_leg_outcomes_total");
        assertThat(legFailed.labels()).containsEntry("status", "FAILED");
        assertThat(legFailed.labels().get("reason")).contains("gateway-transient")
                .contains("payload-unavailable").contains("rules-exception")
                .contains("payload-changed").contains("rolled-back")
                .doesNotContain("receiver-rejected");
        assertThat(legFailed.condition()).contains("any origin");
    }

    @Test
    @DisplayName("case-hearing 400s page only above double the baseline; application-only 400s never page")
    void four_hundreds_page_only_the_case_hearing_spike() {
        final AlertDefinition caseSpike =
                AlertCatalogue.alerts().stream().filter(a -> a.id() == 8).findFirst().orElseThrow();
        assertThat(caseSpike.labels()).containsEntry("shape", "case").containsEntry("http", "400");

        assertThat(AlertCatalogue.countedClasses()).extracting(CountedClass::name)
                .contains("Application-only 400s", "Case-hearing 400s");
    }

    @Test
    @DisplayName("more than three 401s, 403s, 404s or other 4xx refusals in an hour pages, fixed "
            + "not a proposal (H41)")
    void gateway_refusing_alert_pages_on_401_403_404_and_other_4xx() {
        final AlertDefinition gatewayRefusing =
                AlertCatalogue.alerts().stream().filter(a -> a.id() == 6).findFirst().orElseThrow();
        assertThat(gatewayRefusing.name()).isEqualTo("Gateway refusing");
        assertThat(gatewayRefusing.thresholdStatus()).isEqualTo("fixed");
        assertThat(gatewayRefusing.labels()).containsEntry("reason", "receiver-rejected")
                .containsEntry("http", "401|4xx-other");
    }

    @Test
    @DisplayName("alert 9 pages on fewer than two months ahead, any -1 or missing sample, "
            + "or more than 48h since the last success")
    void alert_nine_covers_its_four_conditions() {
        final AlertDefinition alert9 =
                AlertCatalogue.alerts().stream().filter(a -> a.id() == 9).findFirst().orElseThrow();
        assertThat(alert9.condition()).contains("fewer than 2").contains("48h")
                .contains("-1").contains("no sample");
        assertThat(alert9.series()).contains("probation_body_partitions_ahead_months")
                .contains("probation_body_maintenance_last_success_seconds");
    }

    @Test
    @DisplayName("alert 11 lapses at decommissioning")
    void alert_eleven_lapses_at_decommissioning() {
        final AlertDefinition alert11 =
                AlertCatalogue.alerts().stream().filter(a -> a.id() == 11).findFirst().orElseThrow();
        assertThat(alert11.lapse()).contains("decommissioning");
        assertThat(alert11.ruleType()).isEqualTo("log-search");
    }

    @Test
    @DisplayName("only alert 1 is a metric rule; every other alert is a log-search rule (FR-006-053)")
    void only_alert_one_is_a_metric_rule() {
        AlertCatalogue.alerts().forEach(alert -> {
            final String expected = alert.id() == 1 ? "metric" : "log-search";
            assertThat(alert.ruleType()).as("alert %d (%s)", alert.id(), alert.name())
                    .isEqualTo(expected);
        });
    }
}
