package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One of the eleven paging alerts of design §10.3, as H34 amends it (slice 006, FR-006-014). This
 * repository's input to the ticket DevOps create the Azure Monitor rule from by hand (H36) — it
 * carries no executable rule logic of its own; the rule's logic lives on that ticket.
 *
 * @param id             1-11, the design's own numbering (§10.3)
 * @param name           the alert's name
 * @param series         the Micrometer series name, e.g. {@code probation_leg_outcomes_total}, or
 *                       the external series an alert reads (Azure Monitor's own metric, another
 *                       team's series, or a legacy telemetry trace) — {@link #ownedByThisService}
 *                       says which
 * @param labels         the bounded label values this alert's condition selects on
 * @param condition      the human-readable condition; the Azure Monitor rule's own logic lives on
 *                       the ticket, not here
 * @param window         the evaluation window, where fixed
 * @param thresholdStatus {@code "fixed"} or {@code "proposal"}, tuned after go-live where
 *                       {@code proposal} (Assumption 4)
 * @param ruleType       {@code "metric"} or {@code "log-search"} (H36)
 * @param routing        always {@code "email, owning team, via action group"} today (H36)
 * @param runbookEntry   what the on-call reader does
 * @param lapse          when this alert stops applying (e.g. alert 11, at decommissioning)
 */
public record AlertDefinition(
        int id,
        String name,
        String series,
        Map<String, String> labels,
        String condition,
        Optional<Duration> window,
        String thresholdStatus,
        String ruleType,
        String routing,
        String runbookEntry,
        Optional<String> lapse) {

    /** The closed set {@link #thresholdStatus} may take. */
    public static final Set<String> THRESHOLD_STATUSES = Set.of("fixed", "proposal");

    /** The closed set {@link #ruleType} may take. */
    public static final Set<String> RULE_TYPES = Set.of("metric", "log-search");

    /** Series this service does not itself emit — another team's or the platform's own. */
    public static final Set<String> EXTERNAL_SERIES = Set.of(
            "DeadletteredMessages", "probation_flag_read_failures_total",
            "legacy function app telemetry (HMPPS send lines, orchestration-start trace)");

    /** Rejects null components and a status/type outside its closed set. */
    public AlertDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(labels, "labels");
        labels = Map.copyOf(labels);
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(thresholdStatus, "thresholdStatus");
        Objects.requireNonNull(ruleType, "ruleType");
        Objects.requireNonNull(routing, "routing");
        Objects.requireNonNull(runbookEntry, "runbookEntry");
        Objects.requireNonNull(lapse, "lapse");
        if (!THRESHOLD_STATUSES.contains(thresholdStatus)) {
            throw new IllegalArgumentException("unknown threshold status: " + thresholdStatus);
        }
        if (!RULE_TYPES.contains(ruleType)) {
            throw new IllegalArgumentException("unknown rule type: " + ruleType);
        }
    }

    /**
     * Whether this alert's series is one the service itself emits, as opposed to an external
     * series named with its owner (FR-006-019).
     *
     * @return {@code true} when the series is this service's own
     */
    public boolean ownedByThisService() {
        return !EXTERNAL_SERIES.contains(series);
    }
}
