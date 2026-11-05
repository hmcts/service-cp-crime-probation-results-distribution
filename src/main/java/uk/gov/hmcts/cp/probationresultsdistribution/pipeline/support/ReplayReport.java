package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * The one-week SIT → STE replay comparison's output (slice 006, FR-006-005, FR-006-011). A read
 * over already-collected data — nothing here settles a delivery or writes an outcome.
 *
 * @param week              the chosen SIT week (FR-006-009)
 * @param exportDates       when the SIT bodies/payloads were exported
 * @param preRunChecks      FR-006-008 (a)-(e), each recorded whether it passed or failed
 * @param steBuilds         every {@code service_version} seen across the week (Edge Cases:
 *                          a mid-week redeploy)
 * @param comparisons       one entry per share in the union of both sides (FR-006-003)
 * @param staleExplanations entries matching no current difference or share (FR-006-006)
 * @param verdict           {@link #VERDICT_BLOCKS_CUTOVER} or
 *                          {@link #VERDICT_NO_UNEXPLAINED_DIFFERENCES} (FR-006-011)
 */
public record ReplayReport(
        String week,
        List<LocalDate> exportDates,
        List<PreRunCheckResult> preRunChecks,
        List<String> steBuilds,
        List<HearingComparison> comparisons,
        List<ExplanationEntry> staleExplanations,
        String verdict) {

    /** The verdict while any unexplained difference, or any unexplained unpaired/unusable share, remains. */
    public static final String VERDICT_BLOCKS_CUTOVER = "blocks cutover";

    /** The verdict once nothing in {@link #comparisons} blocks cutover. */
    public static final String VERDICT_NO_UNEXPLAINED_DIFFERENCES = "no unexplained differences";

    /** Rejects null components; every list is defensively copied. */
    public ReplayReport {
        Objects.requireNonNull(week, "week");
        exportDates = List.copyOf(exportDates);
        preRunChecks = List.copyOf(preRunChecks);
        steBuilds = List.copyOf(steBuilds);
        comparisons = List.copyOf(comparisons);
        staleExplanations = List.copyOf(staleExplanations);
        Objects.requireNonNull(verdict, "verdict");
    }
}
