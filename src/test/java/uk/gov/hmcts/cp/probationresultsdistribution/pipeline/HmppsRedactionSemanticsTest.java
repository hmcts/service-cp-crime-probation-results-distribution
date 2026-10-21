package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CanonicalJsonMapper;

/**
 * The JavaScript-truthiness and guard semantics the legacy evaluates (FR-001-005), and the
 * spec's Edge Cases, over small synthetic payloads. These pin the behaviour the nine legacy
 * fixtures do not reach.
 */
@DisplayName("HmppsRedaction — JavaScript semantics and edge cases")
class HmppsRedactionSemanticsTest {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();

    /** A minimal payload that survives redaction as a body under either switch state. */
    private static final String SURVIVING_CASE =
            "\"prosecutionCases\":[{\"defendants\":[{\"offences\":[{\"judicialResults\":[{}]}]}]}]";

    private final HmppsRedaction redaction = new HmppsRedaction();

    @Nested
    @DisplayName("R0 / truthiness of policeCases")
    class PoliceCasesTruthiness {

        @ParameterizedTest(name = "policeCases={0} kept={1}")
        @CsvSource({
            "false, true",
            "0, true",
            "'\"\"', true",
            "null, true",
            "true, false",
            "5, false",
            "'\"x\"', false",
            "'[]', false",
            "'{}', false",
        })
        @DisplayName("a falsy policeCases is kept; a truthy one is removed")
        void police_cases_is_removed_only_when_truthy(final String value, final boolean kept) {
            final JsonNode payload = parse(
                    "{\"policeCases\":" + value + ",\"hearing\":{" + SURVIVING_CASE + "}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).has("policeCases")).isEqualTo(kept);
        }
    }

    @Nested
    @DisplayName("R1 and the hearing guard")
    class HearingGuard {

        @Test
        @DisplayName("a virtual-box hearing is emptied by R1 (the third flag)")
        void virtual_box_hearing_should_be_emptied_by_r1() {
            final JsonNode payload = parse("{\"hearing\":{\"isVirtualBoxHearing\":true}}");

            assertThat(redaction.redact(payload, EffectiveSwitchState.INACTIVE))
                    .isInstanceOf(RedactionResult.EmptiedByR1.class);
        }

        @Test
        @DisplayName("a string flag \"false\" is truthy, so R1 still empties")
        void string_false_flag_is_truthy() {
            final JsonNode payload = parse("{\"hearing\":{\"isBoxHearing\":\"false\"}}");

            assertThat(redaction.redact(payload, EffectiveSwitchState.INACTIVE))
                    .isInstanceOf(RedactionResult.EmptiedByR1.class);
        }

        @Test
        @DisplayName("a missing hearing is a rules failure in R1")
        void missing_hearing_should_fail_in_r1() {
            assertRulesFailure(parse("{}"), EffectiveSwitchState.INACTIVE, "R1");
        }

        @Test
        @DisplayName("a null hearing is a rules failure in R1")
        void null_hearing_should_fail_in_r1() {
            assertRulesFailure(parse("{\"hearing\":null}"), EffectiveSwitchState.INACTIVE, "R1");
        }

        @Test
        @DisplayName("a payload with no truthy top-level member is emptied by R11")
        void all_falsy_top_level_should_be_emptied_by_r11() {
            // _.some is false everywhere: every rule guard and the final survival test short out.
            assertThat(redaction.redact(parse("{\"hearing\":false}"), EffectiveSwitchState.ACTIVE))
                    .isInstanceOf(RedactionResult.EmptiedByR11.class);
        }
    }

    @Nested
    @DisplayName("key-presence vs truthiness in R8, R9, R10")
    class PresenceRules {

        @Test
        @DisplayName("a top-level judiciary is removed whatever its value, but a falsy hearing.judiciary is kept")
        void judiciary_presence_and_truthiness() {
            final JsonNode payload = parse(
                    "{\"judiciary\":null,\"hearing\":{\"judiciary\":false," + SURVIVING_CASE + "}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).has("judiciary")).isFalse();
            assertThat(body(result).get("hearing").has("judiciary")).isTrue();
        }

        @Test
        @DisplayName("a top-level associatedPersons is removed by key presence")
        void top_level_associated_persons_removed() {
            final JsonNode payload = parse(
                    "{\"associatedPersons\":[],\"hearing\":{" + SURVIVING_CASE + "}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            assertThat(body(result).has("associatedPersons")).isFalse();
        }

        @Test
        @DisplayName("a null delegatedPowers is removed by key presence (R10)")
        void null_delegated_powers_removed() {
            final JsonNode payload = parse("{\"hearing\":{\"prosecutionCases\":[{\"defendants\":"
                    + "[{\"offences\":[{\"judicialResults\":[{\"delegatedPowers\":null}]}]}]}]}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            final JsonNode judicialResult = body(result).get("hearing").get("prosecutionCases")
                    .get(0).get("defendants").get(0).get("offences").get(0)
                    .get("judicialResults").get(0);
            assertThat(judicialResult.has("delegatedPowers")).isFalse();
        }
    }

    @Nested
    @DisplayName("R11 survival and length reads")
    class SurvivalAndLength {

        @Test
        @DisplayName("an empty courtApplications array with no case is emptied by R11")
        void empty_court_applications_should_be_emptied_by_r11() {
            final JsonNode payload = parse("{\"hearing\":{\"courtApplications\":[]}}");

            assertThat(redaction.redact(payload, EffectiveSwitchState.INACTIVE))
                    .isInstanceOf(RedactionResult.EmptiedByR11.class);
        }

        @Test
        @DisplayName("a present-but-null prosecutionCases fails when its length is read in R11")
        void null_prosecution_cases_should_fail_in_r11() {
            final JsonNode payload = parse(
                    "{\"hearing\":{\"prosecutionCases\":null,\"courtApplications\":[{}]}}");

            assertRulesFailure(payload, EffectiveSwitchState.INACTIVE, "R11");
        }

        @Test
        @DisplayName("a falsy non-null prosecutionCases reads as zero length and a court application survives")
        void falsy_prosecution_cases_reads_zero_length() {
            final JsonNode payload = parse(
                    "{\"hearing\":{\"prosecutionCases\":false,\"courtApplications\":[{}]}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).get("hearing").get("courtApplications").size()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("non-array and non-object shapes")
    class OddShapes {

        @Test
        @DisplayName("a truthy non-array prosecutionCases fails in R3 under ACTIVE")
        void non_array_prosecution_cases_should_fail_in_r3() {
            assertRulesFailure(parse("{\"hearing\":{\"prosecutionCases\":\"x\"}}"),
                    EffectiveSwitchState.ACTIVE, "R3");
        }

        @Test
        @DisplayName("a non-object offence is left untouched, as the legacy's delete is a no-op")
        void non_object_offence_is_a_delete_noop() {
            // R5 removeField on the string offence is a no-op; R10 then reads its judicialResults
            // off a non-object and fails, exactly as the legacy throws.
            assertRulesFailure(parse("{\"hearing\":{\"prosecutionCases\":"
                            + "[{\"defendants\":[{\"offences\":[\"x\"]}]}]}}"),
                    EffectiveSwitchState.INACTIVE, "R10");
        }

        @Test
        @DisplayName("R6 skips a court application case with no offences")
        void r6_skips_application_case_without_offences() {
            final JsonNode payload = parse("{\"hearing\":{\"courtApplications\":"
                    + "[{\"courtApplicationCases\":[{}]}]}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
        }
    }

    @Nested
    @DisplayName("R4 masterDefendant")
    class MasterDefendant {

        @Test
        @DisplayName("an application whose masterDefendant is not a youth is kept under ACTIVE")
        void non_youth_master_defendant_kept_under_active() {
            final JsonNode payload = parse("{\"hearing\":{\"courtApplications\":"
                    + "[{\"subject\":{\"masterDefendant\":{\"isYouth\":false}}}]}}");

            final RedactionResult result = redaction.redact(payload, EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).get("hearing").get("courtApplications").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("an application whose masterDefendant is a youth is removed under ACTIVE")
        void youth_master_defendant_removed_under_active() {
            final JsonNode payload = parse("{\"hearing\":{\"courtApplications\":"
                    + "[{\"subject\":{\"masterDefendant\":{\"isYouth\":true}}}]}}");

            assertThat(redaction.redact(payload, EffectiveSwitchState.ACTIVE))
                    .isInstanceOf(RedactionResult.EmptiedByR11.class);
        }

        @Test
        @DisplayName("a missing subject fails in R4 under ACTIVE")
        void missing_subject_should_fail_in_r4() {
            assertRulesFailure(parse("{\"hearing\":{\"courtApplications\":[{}]}}"),
                    EffectiveSwitchState.ACTIVE, "R4");
        }
    }

    private void assertRulesFailure(
            final JsonNode payload, final EffectiveSwitchState state, final String ruleId) {
        final RedactionResult result = redaction.redact(payload, state);

        assertThat(result).isInstanceOf(RedactionResult.RulesFailure.class);
        assertThat(((RedactionResult.RulesFailure) result).ruleId()).isEqualTo(ruleId);
    }

    private static JsonNode body(final RedactionResult result) {
        return ((RedactionResult.Body) result).payload();
    }

    private static JsonNode parse(final String json) {
        return MAPPER.readTree(json);
    }
}
