package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.config.AlertCatalogue;

/**
 * Pins User Story 6's own Independent Test: {@code DailyReconciliationQuery}'s legacy-silence
 * check and alert 11's query ({@code AlertCatalogue.evaluateAlert11}) read the identical
 * telemetry-line definition — one definition, never two that can drift.
 */
@DisplayName("Legacy-silence check and alert 11 share one definition")
class LegacySilenceCheckSharedDefinitionTest {

    private static final Instant CUTOVER = Instant.parse("2026-08-22T02:00:00Z");

    @Test
    @DisplayName("agree on a send line after cutover")
    void agree_on_a_send_line_after_cutover() {
        final List<LegacyTelemetryLine> lines = List.of(
                new LegacyTelemetryLine(CUTOVER.plusSeconds(1), LegacyTelemetryLineKind.HMPPS_SEND));

        assertThat(DailyReconciliationQuery.legacySilenceCheck(true, lines, CUTOVER))
                .isEqualTo(AlertCatalogue.evaluateAlert11(true, lines, CUTOVER))
                .isEqualTo(LegacyBothPathsVerdict.PAGES);
    }

    @Test
    @DisplayName("agree on silence since cutover")
    void agree_on_silence_since_cutover() {
        final List<LegacyTelemetryLine> lines = List.of();

        assertThat(DailyReconciliationQuery.legacySilenceCheck(true, lines, CUTOVER))
                .isEqualTo(AlertCatalogue.evaluateAlert11(true, lines, CUTOVER))
                .isEqualTo(LegacyBothPathsVerdict.CLEAR);
    }

    @Test
    @DisplayName("agree on an unestablished telemetry resource")
    void agree_on_an_unestablished_resource() {
        assertThat(DailyReconciliationQuery.legacySilenceCheck(false, List.of(), CUTOVER))
                .isEqualTo(AlertCatalogue.evaluateAlert11(false, List.of(), CUTOVER))
                .isEqualTo(LegacyBothPathsVerdict.NOT_DEPLOYABLE_HERE);
    }
}
