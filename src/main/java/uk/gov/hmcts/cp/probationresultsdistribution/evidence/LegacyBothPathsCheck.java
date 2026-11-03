package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.time.Instant;
import java.util.List;

/**
 * The one telemetry-line definition both alert 11 ({@code config.AlertCatalogue}) and the daily
 * reconciliation's legacy-silence check ({@link DailyReconciliationQuery}) read — a single
 * definition, never two that can drift (slice 006, FR-006-020, User Story 6's own Independent
 * Test). Pure: no I/O, no clock — the caller supplies the telemetry lines and the cutover instant.
 */
public final class LegacyBothPathsCheck {

    private LegacyBothPathsCheck() {
    }

    /**
     * Evaluates whether the legacy app has sent, or started orchestrating, since cutover.
     *
     * @param telemetryResourceEstablished whether this environment's legacy telemetry resource
     *                                     exists at all
     * @param lines                        the legacy app's own telemetry lines
     * @param cutoverInstant               the recorded cutover instant
     * @return the verdict
     */
    @SuppressWarnings("PMD.OnlyOneReturn")
    public static LegacyBothPathsVerdict evaluate(final boolean telemetryResourceEstablished,
            final List<LegacyTelemetryLine> lines, final Instant cutoverInstant) {
        if (!telemetryResourceEstablished) {
            return LegacyBothPathsVerdict.NOT_DEPLOYABLE_HERE;
        }
        final boolean sawSomethingSinceCutover = lines.stream()
                .anyMatch(line -> !line.timestamp().isBefore(cutoverInstant));
        return sawSomethingSinceCutover ? LegacyBothPathsVerdict.PAGES : LegacyBothPathsVerdict.CLEAR;
    }
}
