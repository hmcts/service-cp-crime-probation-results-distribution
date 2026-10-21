package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CanonicalJsonMapper;

/**
 * Where a rule reads through an absent or null value the legacy iterates or measures, the port
 * reports {@link RedactionResult.RulesFailure} naming that rule — no guard is added (P19,
 * FR-001-007(d), FR-001-008). A court application with no {@code masterDefendant} is removed under
 * {@code ACTIVE} and kept under {@code INACTIVE} (P18, carried).
 */
@DisplayName("HmppsRedaction — carried null-guard failures (P19) and P18")
class HmppsRedactionRulesFailureTest {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();

    private final HmppsRedaction redaction = new HmppsRedaction();

    @Nested
    @DisplayName("P19: a read through a missing list is a rules failure naming its rule")
    class MissingGuards {

        @Test
        @DisplayName("missing defendants fails in R3 under ACTIVE")
        void missing_defendants_should_fail_in_r3() {
            final JsonNode payload = parse("{\"hearing\":{\"prosecutionCases\":[{}]}}");

            assertRulesFailure(payload, EffectiveSwitchState.ACTIVE, "R3");
        }

        @Test
        @DisplayName("missing offences fails in R5 under INACTIVE")
        void missing_offences_should_fail_in_r5() {
            final JsonNode payload =
                    parse("{\"hearing\":{\"prosecutionCases\":[{\"defendants\":[{}]}]}}");

            assertRulesFailure(payload, EffectiveSwitchState.INACTIVE, "R5");
        }

        @Test
        @DisplayName("missing courtOrderOffences fails in R7 under INACTIVE")
        void missing_court_order_offences_should_fail_in_r7() {
            final JsonNode payload =
                    parse("{\"hearing\":{\"courtApplications\":[{\"courtOrder\":{}}]}}");

            assertRulesFailure(payload, EffectiveSwitchState.INACTIVE, "R7");
        }

        @Test
        @DisplayName("missing judicialResults fails in R10 under INACTIVE")
        void missing_judicial_results_should_fail_in_r10() {
            final JsonNode payload = parse(
                    "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":[{\"offences\":[{}]}]}]}}");

            assertRulesFailure(payload, EffectiveSwitchState.INACTIVE, "R10");
        }
    }

    @Nested
    @DisplayName("P18: an application with no masterDefendant")
    class NoMasterDefendant {

        private static final String PAYLOAD =
                "{\"hearing\":{"
                        + "\"prosecutionCases\":[{\"defendants\":[{\"isYouth\":false,"
                        + "\"offences\":[{\"judicialResults\":[{}]}]}]}],"
                        + "\"courtApplications\":[{\"subject\":{}}]}}";

        @Test
        @DisplayName("is removed under ACTIVE (P18, carried)")
        void application_with_no_master_defendant_should_be_removed_under_active() {
            final RedactionResult result =
                    redaction.redact(parse(PAYLOAD), EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode body = ((RedactionResult.Body) result).payload();
            assertThat(body.get("hearing").get("courtApplications").size()).isZero();
        }

        @Test
        @DisplayName("is kept under INACTIVE")
        void application_with_no_master_defendant_should_be_kept_under_inactive() {
            final RedactionResult result =
                    redaction.redact(parse(PAYLOAD), EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode body = ((RedactionResult.Body) result).payload();
            assertThat(body.get("hearing").get("courtApplications").size()).isEqualTo(1);
        }
    }

    private void assertRulesFailure(
            final JsonNode payload, final EffectiveSwitchState state, final String ruleId) {
        final RedactionResult result = redaction.redact(payload, state);

        assertThat(result).isInstanceOf(RedactionResult.RulesFailure.class);
        assertThat(((RedactionResult.RulesFailure) result).ruleId()).isEqualTo(ruleId);
    }

    private static JsonNode parse(final String json) {
        return MAPPER.readTree(json);
    }
}
