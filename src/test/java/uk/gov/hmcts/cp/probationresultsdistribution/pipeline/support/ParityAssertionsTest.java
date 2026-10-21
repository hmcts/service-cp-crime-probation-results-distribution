package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * Pins {@link ParityAssertions}: the harness's one way to assert two HMPPS bodies are the same.
 *
 * <p>It wraps {@link JsonParity} so the corpus harness (and slice 006's STE replay) report a
 * mismatch the same way, and — because this repository is public and the corpus holds SIT data —
 * with a message that names the case, the form, the switch state and every differing JSON path but
 * carries no value from either side (FR-001-015, SC-001-009). Both halves are pinned here: what it
 * must accept, and the exact shape of what it must say when it rejects.
 */
@DisplayName("ParityAssertions")
class ParityAssertionsTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Nested
    @DisplayName("on a mismatch")
    class OnAMismatch {

        @Test
        @DisplayName("assertSameBody_mismatch_should_name_case_form_switch_and_paths")
        void assertSameBody_mismatch_should_name_case_form_switch_and_paths() {
            final JsonNode expected = tree(
                    "{\"hearing\":{\"id\":\"H1\"},\"amount\":\"leftonly\",\"status\":\"keptleft\"}");
            final JsonNode actual = tree(
                    "{\"hearing\":{\"id\":\"H1\"},\"amount\":\"rightonly\",\"status\":\"keptright\"}");

            assertThatThrownBy(() -> ParityAssertions.assertSameBody(
                    "case-07", "cache", EffectiveSwitchState.ACTIVE, expected, actual))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("case-07")
                    .hasMessageContaining("cache")
                    .hasMessageContaining("ACTIVE")
                    .hasMessageContaining("/amount")
                    .hasMessageContaining("/status");
        }

        @Test
        @DisplayName("assertSameBody_mismatch_should_carry_no_value_from_either_side")
        void assertSameBody_mismatch_should_carry_no_value_from_either_side() {
            final JsonNode expected = tree(
                    "{\"hearing\":{\"id\":\"H1\"},\"amount\":\"leftonly\",\"status\":\"keptleft\"}");
            final JsonNode actual = tree(
                    "{\"hearing\":{\"id\":\"H1\"},\"amount\":\"rightonly\",\"status\":\"keptright\"}");

            assertThatThrownBy(() -> ParityAssertions.assertSameBody(
                    "case-07", "cache", EffectiveSwitchState.ACTIVE, expected, actual))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageNotContaining("leftonly")
                    .hasMessageNotContaining("rightonly")
                    .hasMessageNotContaining("keptleft")
                    .hasMessageNotContaining("keptright");
        }
    }

    @Nested
    @DisplayName("on a match")
    class OnAMatch {

        @Test
        @DisplayName("assertSameBody_equalBodies_should_not_throw")
        void assertSameBody_equalBodies_should_not_throw() {
            final JsonNode expected = tree("{\"a\":1,\"b\":[1,2,3]}");
            final JsonNode actual = tree("{\"b\":[1,2,3],\"a\":1.0}");

            assertThatCode(() -> ParityAssertions.assertSameBody(
                    "case-07", "query", EffectiveSwitchState.INACTIVE, expected, actual))
                    .doesNotThrowAnyException();
        }
    }

    /**
     * Parses JSON text into a tree, with the service's contract numerics.
     *
     * @param json the text
     * @return the tree
     */
    private JsonNode tree(final String json) {
        return mapper.readTree(json);
    }
}
