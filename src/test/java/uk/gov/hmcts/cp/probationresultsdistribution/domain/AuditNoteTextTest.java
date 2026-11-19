package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the one escape rule every {@code audit_note} writer shares (FR-004-012; PR #29 review): a
 * run of control characters collapses to one space, the result is stripped to one line, and the
 * literal {@code REPLAYED_AFTER_FAILURE} marker is escaped out of it, case-insensitively.
 */
@DisplayName("the shared audit_note text rule")
class AuditNoteTextTest {

    @Test
    @DisplayName("collapses control characters to one space and strips the result")
    void collapses_control_characters() {
        assertThat(AuditNoteText.oneLine("a\tb\r\nc  ")).isEqualTo("a b c");
    }

    @Test
    @DisplayName("escapes the marker regardless of case")
    void escapes_the_marker_case_insensitively() {
        assertThat(AuditNoteText.oneLine("see REPLAYED_AFTER_FAILURE ticket"))
                .isEqualTo("see REPLAYED-AFTER-FAILURE ticket");
        assertThat(AuditNoteText.oneLine("replayed_after_failure"))
                .isEqualTo("REPLAYED-AFTER-FAILURE");
    }

    @Test
    @DisplayName("text with no marker and no control characters is unchanged")
    void leaves_ordinary_text_unchanged() {
        assertThat(AuditNoteText.oneLine("local-dev")).isEqualTo("local-dev");
    }
}
