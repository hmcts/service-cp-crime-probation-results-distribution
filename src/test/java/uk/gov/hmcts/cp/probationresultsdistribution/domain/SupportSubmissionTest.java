package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the sanitising of the submit-hearing markers before they reach {@code audit_note} (slice
 * 004, FR-004-057): control characters become a space, the value is stripped and cut at 200
 * characters without splitting a character, and an absent, non-text or blank value is {@code -}.
 */
@DisplayName("the submit-hearing markers' sanitising")
class SupportSubmissionTest {

    private static final String SMILE = "😀";

    static Stream<Arguments> values() {
        return Stream.of(
                Arguments.of("leading and trailing whitespace", "  local-dev \t", "local-dev"),
                Arguments.of("a tab inside", "a\tb", "a b"),
                Arguments.of("a NUL inside", "a\u0000b", "a b"),
                Arguments.of("a CRLF run inside", "a\r\nb", "a b"),
                Arguments.of("exactly 200 characters", "x".repeat(200), "x".repeat(200)),
                Arguments.of("201 characters", "x".repeat(201), "x".repeat(200)),
                Arguments.of("blank", " \t ", "-"),
                Arguments.of("empty", "", "-"),
                Arguments.of("absent", null, "-"),
                Arguments.of("not text", 42, "-"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("values")
    void fromProperties_should_sanitise_each_value(final String scenario, final Object raw,
                                                   final String expected) {
        final SupportSubmission markers = SupportSubmission.fromProperties(raw, raw, raw);

        assertThat(markers.operator()).isEqualTo(expected);
        assertThat(markers.runId()).isEqualTo(expected);
        assertThat(markers.reason()).isEqualTo(expected);
    }

    @Test
    @DisplayName("a cut that would split a surrogate pair drops the whole character")
    void fromProperties_cutInsideSurrogatePair_should_not_split_it() {
        final String raw = "x".repeat(199) + SMILE + "tail";

        final String reason = SupportSubmission.fromProperties(null, null, raw).reason();

        assertThat(reason).isEqualTo("x".repeat(199));
        assertThat(Character.isHighSurrogate(reason.charAt(reason.length() - 1))).isFalse();
    }

    @Test
    @DisplayName("a surrogate pair that ends exactly at the cut is kept whole")
    void fromProperties_surrogatePairEndingAtCut_should_keep_it() {
        final String raw = "x".repeat(198) + SMILE + "tail";

        assertThat(SupportSubmission.fromProperties(null, null, raw).reason())
                .isEqualTo("x".repeat(198) + SMILE);
    }

    @Test
    @DisplayName("the REPLAYED_AFTER_FAILURE marker is escaped out of every value before it "
            + "reaches audit_note (FR-004-012; PR #29 review): this is the actual admission write "
            + "path, reached from whatever application properties a delivery marked "
            + "origin = SUPPORT carries, not only submit-hearing's own runner")
    void fromProperties_escapesTheReplayMarker() {
        final SupportSubmission markers = SupportSubmission.fromProperties("replayed_after_failure",
                "run-id", "see REPLAYED_AFTER_FAILURE ticket");

        assertThat(markers.operator()).isEqualTo("REPLAYED-AFTER-FAILURE");
        assertThat(markers.reason()).isEqualTo("see REPLAYED-AFTER-FAILURE ticket");
    }
}
