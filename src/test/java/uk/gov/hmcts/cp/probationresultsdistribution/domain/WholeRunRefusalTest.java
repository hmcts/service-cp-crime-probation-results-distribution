package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pins the closed wire-value mapping for the whole-run and whole-action refusal codes this slice
 * names (spec Assumption 3; FR-004-019 to FR-004-021, FR-004-053): round trips cleanly, and an
 * unrecognised wire value is rejected rather than silently mapped.
 */
class WholeRunRefusalTest {

    @ParameterizedTest
    @EnumSource(WholeRunRefusal.class)
    void value_everyRefusal_roundTripsThroughFromValue(final WholeRunRefusal refusal) {
        assertThat(WholeRunRefusal.fromValue(refusal.value())).isEqualTo(refusal);
    }

    @ParameterizedTest
    @EnumSource(WholeRunRefusal.class)
    void value_isLowerCaseHyphenSeparated(final WholeRunRefusal refusal) {
        assertThat(refusal.value()).matches("[a-z]+(-[a-z]+)*");
    }

    @Test
    void fromValue_unknownWireValue_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> WholeRunRefusal.fromValue("not-a-real-code"));
    }
}
