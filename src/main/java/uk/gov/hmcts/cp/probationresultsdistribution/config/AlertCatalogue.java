package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.LegacyBothPathsCheck;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.LegacyBothPathsVerdict;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.LegacyTelemetryLine;

/**
 * The closed list of eleven paging alerts and five counted-not-paged classes (slice 006,
 * FR-006-014 to FR-006-022). The complete list of paging alerts this service asks the platform to
 * deploy — no other alert is requested, including 005's proposed operations-failure counter
 * (FR-006-022).
 */
public final class AlertCatalogue {

    private static final String EMAIL_ACTION_GROUP = "email, owning team, via action group";
    private static final String METRIC = "metric";
    private static final String LOG_SEARCH = "log-search";
    private static final String FIXED = "fixed";
    private static final String PROPOSAL = "proposal";
    private static final String LEG_OUTCOMES = "probation_leg_outcomes_total";
    private static final String DECOMMISSIONING = "decommissioning";
    private static final String BOTH = "both";

    private AlertCatalogue() {
    }

    /**
     * The eleven paging alerts, in design order.
     *
     * @return the alert catalogue
     */
    public static List<AlertDefinition> alerts() {
        return List.of(
                alert(1, "DLQ not empty", "DeadletteredMessages", Map.of(), "> 0",
                        Optional.empty(), FIXED, METRIC,
                        "The dead-letter runbook (004). Resend leg-exhausted and exhausted; never "
                                + "resend validation or collision. Once a replayed request is "
                                + "COMPLETED, remove the original and any re-dead-lettered copy. "
                                + "An exhausted entry whose request is COMPLETED is duplicate-copy "
                                + "noise: remove it", Optional.empty()),
                alert(2, "Leg parked too long", "probation_oldest_parked_age", Map.of(), "> 30 min",
                        Optional.of(Duration.ofMinutes(30)), FIXED, METRIC,
                        "The leg is waiting for broker redelivery. On the final delivery the leg "
                                + "becomes FAILED, the message is dead-lettered leg-exhausted, and "
                                + "the dead-letter runbook applies", Optional.empty()),
                alert(3, "Silent drop", "probation_oldest_non_terminal_age", Map.of(), "> 30 min",
                        Optional.of(Duration.ofMinutes(30)), FIXED, METRIC,
                        "The oldest request that is not terminal: the silent-drop detector",
                        Optional.empty()),
                alert(4, "Leg failed", LEG_OUTCOMES,
                        Map.of("status", "FAILED", "reason",
                                "gateway-transient|payload-unavailable|rules-exception|"
                                        + "payload-changed|rolled-back"),
                        "any origin, any count", Optional.empty(), FIXED, METRIC,
                        "A FAILED(gateway-transient) after a 5xx, a 408 or a read timeout is "
                                + "outcome-unknown: confirmed with HMPPS before any re-send "
                                + "(FR-006-017)", Optional.empty()),
                alert(5, "Youth switch unreadable", "probation_youth_switch_unreadable_total",
                        Map.of(), "not zero for > 5 min", Optional.of(Duration.ofMinutes(5)),
                        FIXED, METRIC,
                        "Each counted read is a hearing sent on an assumed switch value. Re-check "
                                + "the default against the live toggle (FR-006-033)",
                        Optional.empty()),
                alert(6, "Repeated 401s", LEG_OUTCOMES,
                        Map.of("reason", "receiver-rejected", "http", "401"),
                        "> 3 in an hour", Optional.of(Duration.ofHours(1)), PROPOSAL, METRIC,
                        "The gateway is refusing the service's credentials. A 401 is terminal at "
                                + "once", Optional.empty()),
                alert(7, "Send re-run after a crash", "probation_crash_reruns_total", Map.of(),
                        "any increase", Optional.empty(), FIXED, METRIC,
                        "A possible duplicate at HMPPS: record the hearing for HMPPS",
                        Optional.empty()),
                alert(8, "Case-hearing 400 spike", LEG_OUTCOMES,
                        Map.of("reason", "receiver-rejected", "http", "400", "shape", "case"),
                        "daily increase > 2x the environment's normal daily level",
                        Optional.of(Duration.ofDays(1)), PROPOSAL, METRIC,
                        "The daily count itself is counted, not paged (FR-006-022)",
                        Optional.empty()),
                alert(9, "Body partition maintenance", "probation_body_default_partition_rows, "
                                + "probation_body_partitions_ahead_months, "
                                + "probation_body_maintenance_last_success_seconds, "
                                + "probation_body_maintenance_failures_total",
                        Map.of("step", "lock|create|drop"),
                        "DEFAULT partition not empty; any create/drop failure; fewer than 2 "
                                + "consecutive months ahead (H37); more than 48h since the last "
                                + "success (H38); any pod reporting -1; or no sample in the window",
                        Optional.of(Duration.ofHours(2)), PROPOSAL, METRIC,
                        "003's runbook: for a DEFAULT spill, detach, create the missing month, "
                                + "move rows, re-attach; for partitions not created, check the "
                                + "scheduler and advisory lock; for -1, check the database "
                                + "connection", Optional.empty()),
                alert(10, "Results switch unreadable", "probation_flag_read_failures_total",
                        Map.of(), "any increase", Optional.empty(), FIXED, METRIC,
                        "After cutover, each failed read is a share on neither path. Reconcile, "
                                + "and repair with submit-hearing while the EXT_ payload is still "
                                + "in Redis", Optional.empty()),
                alert(11, "Legacy function app active after cutover",
                        "legacy function app telemetry (HMPPS send lines, orchestration-start "
                                + "trace)",
                        Map.of(), "any send line or orchestration-start trace at or after the "
                                + "cutover instant pages; an environment whose legacy telemetry "
                                + "resource is not established is shown as not deployable there, "
                                + "never silently absent — evaluateAlert11(), one definition "
                                + "DailyReconciliationQuery's legacy-silence check also reads",
                        Optional.of(Duration.ofMinutes(15)), PROPOSAL, LOG_SEARCH,
                        "A \"both\": check the legacy subscription's filter and set it back to "
                                + "TOGGLED-OFF", Optional.of(DECOMMISSIONING)));
    }

    /**
     * Alert 11's full query definition (User Story 6): the one telemetry-line definition the
     * daily reconciliation's legacy-silence check also reads ({@link
     * LegacyBothPathsCheck#evaluate}) — never a second definition that can drift.
     *
     * @param telemetryResourceEstablished whether this environment's legacy telemetry resource
     *                                     exists at all
     * @param lines                        the legacy app's own telemetry lines
     * @param cutoverInstant               the recorded cutover instant
     * @return the verdict: pages, clear, or not deployable here
     */
    public static LegacyBothPathsVerdict evaluateAlert11(
            final boolean telemetryResourceEstablished, final List<LegacyTelemetryLine> lines,
            final Instant cutoverInstant) {
        return LegacyBothPathsCheck.evaluate(telemetryResourceEstablished, lines, cutoverInstant);
    }

    /**
     * The five known-issue classes, counted never paged.
     *
     * @return the counted-class catalogue
     */
    public static List<CountedClass> countedClasses() {
        return List.of(
                new CountedClass("Application-only 400s",
                        LEG_OUTCOMES + "{reason=\"receiver-rejected\", shape=\"application-only\"}",
                        BOTH),
                new CountedClass("Case-hearing 400s",
                        LEG_OUTCOMES + "{reason=\"receiver-rejected\", http=\"400\", shape=\"case\"}",
                        BOTH),
                new CountedClass("500s recovered by a later delivery",
                        "probation_gateway_recovered_total{after=\"5xx-redelivery\"}", BOTH),
                new CountedClass("429s recovered by an in-run retry",
                        "probation_gateway_recovered_total{after=\"429-in-run\"}", BOTH),
                new CountedClass("2xx answers other than 200",
                        "probation_gateway_non200_success_total{status}", BOTH));
    }

    private static AlertDefinition alert(final int id, final String name, final String series,
            final Map<String, String> labels, final String condition,
            final Optional<Duration> window, final String thresholdStatus, final String ruleType,
            final String runbookEntry, final Optional<String> lapse) {
        return new AlertDefinition(id, name, series, labels, condition, window, thresholdStatus,
                ruleType, EMAIL_ACTION_GROUP, runbookEntry, lapse);
    }
}
