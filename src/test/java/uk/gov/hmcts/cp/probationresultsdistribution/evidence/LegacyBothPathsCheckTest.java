package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-006-020: alert 11's own query definition. Synthetic telemetry only (Principle VII) —
 * User Story 6's own Independent Test.
 */
@DisplayName("LegacyBothPathsCheck")
class LegacyBothPathsCheckTest {

    private static final Instant CUTOVER = Instant.parse("2026-08-22T02:00:00Z");

    @Test
    @DisplayName("a send line at or after cutover pages (scenario 1)")
    void send_line_after_cutover_pages() {
        final LegacyTelemetryLine line =
                new LegacyTelemetryLine(CUTOVER.plusSeconds(60), LegacyTelemetryLineKind.HMPPS_SEND);

        final LegacyBothPathsVerdict verdict =
                LegacyBothPathsCheck.evaluate(true, List.of(line), CUTOVER);

        assertThat(verdict).isEqualTo(LegacyBothPathsVerdict.PAGES);
    }

    @Test
    @DisplayName("an orchestration-start trace at or after cutover pages (scenario 2)")
    void orchestration_start_after_cutover_pages() {
        final LegacyTelemetryLine line = new LegacyTelemetryLine(CUTOVER,
                LegacyTelemetryLineKind.ORCHESTRATION_START);

        final LegacyBothPathsVerdict verdict =
                LegacyBothPathsCheck.evaluate(true, List.of(line), CUTOVER);

        assertThat(verdict).isEqualTo(LegacyBothPathsVerdict.PAGES);
    }

    @Test
    @DisplayName("nothing since cutover does not page (scenario 3)")
    void nothing_since_cutover_is_clear() {
        final LegacyTelemetryLine beforeCutover = new LegacyTelemetryLine(
                CUTOVER.minusSeconds(60), LegacyTelemetryLineKind.HMPPS_SEND);

        final LegacyBothPathsVerdict verdict =
                LegacyBothPathsCheck.evaluate(true, List.of(beforeCutover), CUTOVER);

        assertThat(verdict).isEqualTo(LegacyBothPathsVerdict.CLEAR);
    }

    @Test
    @DisplayName("no lines at all does not page")
    void no_lines_is_clear() {
        assertThat(LegacyBothPathsCheck.evaluate(true, List.of(), CUTOVER))
                .isEqualTo(LegacyBothPathsVerdict.CLEAR);
    }

    @Test
    @DisplayName("an environment with no established legacy telemetry resource is not-deployable, never silently absent (scenario 4)")
    void unestablished_resource_is_not_deployable_here() {
        final LegacyTelemetryLine line =
                new LegacyTelemetryLine(CUTOVER.plusSeconds(1), LegacyTelemetryLineKind.HMPPS_SEND);

        final LegacyBothPathsVerdict verdict =
                LegacyBothPathsCheck.evaluate(false, List.of(line), CUTOVER);

        assertThat(verdict).isEqualTo(LegacyBothPathsVerdict.NOT_DEPLOYABLE_HERE);
    }
}
