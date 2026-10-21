package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CanonicalJsonMapper;

/**
 * The twins of the fifteen legacy filter Jest cases (`PF/index.test.js` at `122e2152`), run over the
 * byte-identical fixtures in `src/test/resources/fixtures/`, each in the switch state the legacy
 * case used, asserting what the legacy case asserts (FR-001-011, FR-001-012).
 */
@DisplayName("HmppsRedaction — legacy Jest-case twins")
class HmppsRedactionLegacyTwinTest {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();

    private final HmppsRedaction redaction = new HmppsRedaction();

    @Nested
    @DisplayName("R1 collapse (SJP / box hearing)")
    class R1Collapse {

        @Test
        @DisplayName("T1: an SJP hearing is emptied by R1")
        void t1_sjp_hearing_should_be_emptied_by_r1() {
            final RedactionResult result = redaction.redact(
                    fixture("hearing.sjp.cases.resulted.in.sjp.session.json"),
                    EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.EmptiedByR1.class);
        }

        @Test
        @DisplayName("T2: a box hearing is emptied by R1")
        void t2_box_hearing_should_be_emptied_by_r1() {
            final RedactionResult result = redaction.redact(
                    fixture("box.hearing.json"), EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.EmptiedByR1.class);
        }
    }

    @Nested
    @DisplayName("laaApplnReference removal (R5, R6, R7)")
    class LaaReferenceRemoval {

        @Test
        @DisplayName("T3: the reference is removed from a prosecution-case offence")
        void t3_prosecution_case_offence_should_lose_its_laa_reference() {
            final RedactionResult result = redaction.redact(
                    fixture("hearing.prosection.case.with.laa.reference.json"),
                    EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode offence = body(result)
                    .get("hearing").get("prosecutionCases").get(0)
                    .get("defendants").get(0).get("offences").get(0);
            assertThat(offence.has("laaApplnReference")).isFalse();
        }

        @Test
        @DisplayName("T4: the reference is removed from a court-application-case offence")
        void t4_application_case_offence_should_lose_its_laa_reference() {
            final RedactionResult result = redaction.redact(
                    fixture("hearing.only.court.application.json"),
                    EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode offence = body(result)
                    .get("hearing").get("courtApplications").get(0)
                    .get("courtApplicationCases").get(0).get("offences").get(0);
            assertThat(offence.has("laaApplnReference")).isFalse();
        }

        @Test
        @DisplayName("T5: the reference is removed from a court-order offence")
        void t5_court_order_offence_should_lose_its_laa_reference() {
            final RedactionResult result = redaction.redact(
                    fixture("hearing.multiple.court.applications.courtorder.json"),
                    EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode offence = body(result)
                    .get("hearing").get("courtApplications").get(0)
                    .get("courtOrder").get("courtOrderOffences").get(0).get("offence");
            assertThat(offence.has("laaApplnReference")).isFalse();
        }
    }

    @Nested
    @DisplayName("youth rules R3, R4 and the R11 collapse")
    class YouthRules {

        private static final String YOUTH_DEFENDANTS = "hearing.prosection.case.with.youth.defendant.json";
        private static final String ALL_YOUTH = "hearing.prosection.case.with.all.youth.defendant.json";
        private static final String SINGLE_ALL_YOUTH = "hearing.single.prosection.case.with.all.youth.defendant.json";
        private static final String APPLICATION_YOUTH = "hearing.only.court.application.with.youth.defendant.json";

        @Test
        @DisplayName("T6: INACTIVE keeps every youth defendant and every case")
        void t6_inactive_should_keep_every_defendant() {
            final JsonNode cases = redactedProsecutionCases(YOUTH_DEFENDANTS, EffectiveSwitchState.INACTIVE);

            assertThat(cases.size()).isEqualTo(2);
            assertThat(cases.get(0).get("defendants").size()).isEqualTo(3);
        }

        @Test
        @DisplayName("T7: ACTIVE removes the youth defendant and keeps both cases")
        void t7_active_should_remove_the_youth_defendant() {
            final JsonNode cases = redactedProsecutionCases(YOUTH_DEFENDANTS, EffectiveSwitchState.ACTIVE);

            assertThat(cases.size()).isEqualTo(2);
            assertThat(cases.get(0).get("defendants").size()).isEqualTo(2);
        }

        @Test
        @DisplayName("T8: ACTIVE removes the all-youth case and keeps the adult case")
        void t8_active_should_remove_the_all_youth_case() {
            final JsonNode cases = redactedProsecutionCases(ALL_YOUTH, EffectiveSwitchState.ACTIVE);

            assertThat(cases.size()).isEqualTo(1);
            assertThat(cases.get(0).get("id").stringValue())
                    .isEqualTo("c10e3b71-6a6d-45ef-9b62-34df4d54971b");
            assertThat(cases.get(0).get("defendants").size()).isEqualTo(3);
        }

        @Test
        @DisplayName("T9: INACTIVE sends the single all-youth case")
        void t9_inactive_should_keep_the_single_all_youth_case() {
            final JsonNode cases = redactedProsecutionCases(SINGLE_ALL_YOUTH, EffectiveSwitchState.INACTIVE);

            assertThat(cases.size()).isEqualTo(1);
            assertThat(cases.get(0).get("defendants").size()).isEqualTo(3);
        }

        @Test
        @DisplayName("T10: ACTIVE empties the single all-youth case by R11")
        void t10_active_should_be_emptied_by_r11() {
            final RedactionResult result = redaction.redact(
                    fixture(SINGLE_ALL_YOUTH), EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.EmptiedByR11.class);
        }

        @Test
        @DisplayName("T11: INACTIVE sends the youth court application")
        void t11_inactive_should_keep_the_court_application() {
            final RedactionResult result = redaction.redact(
                    fixture(APPLICATION_YOUTH), EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).get("hearing").get("courtApplications").size()).isEqualTo(1);
        }

        @Test
        @DisplayName("T12: ACTIVE empties the youth court application by R11")
        void t12_active_should_be_emptied_by_r11() {
            final RedactionResult result = redaction.redact(
                    fixture(APPLICATION_YOUTH), EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.EmptiedByR11.class);
        }

        private JsonNode redactedProsecutionCases(final String name, final EffectiveSwitchState state) {
            final RedactionResult result = redaction.redact(fixture(name), state);
            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            return body(result).get("hearing").get("prosecutionCases");
        }
    }

    @Nested
    @DisplayName("sensitive-data removal R8, R9, R10")
    class SensitiveDataRemoval {

        private static final String LAA = "hearing.prosection.case.with.laa.reference.json";

        @Test
        @DisplayName("T13: a top-level judiciary is absent (the fixture never carried one — P34)")
        void t13_top_level_judiciary_should_be_absent() {
            final RedactionResult result = redaction.redact(fixture(LAA), EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).has("judiciary")).isFalse();
        }

        @Test
        @DisplayName("T14: a top-level associatedPersons is absent (the fixture never carried one — P34)")
        void t14_top_level_associated_persons_should_be_absent() {
            final RedactionResult result = redaction.redact(fixture(LAA), EffectiveSwitchState.INACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            assertThat(body(result).has("associatedPersons")).isFalse();
        }

        @Test
        @DisplayName("T15: hearing.judiciary, a defendant's associatedPersons and a judicial result's delegatedPowers are gone")
        void t15_sensitive_members_should_be_removed() {
            final RedactionResult result = redaction.redact(fixture(LAA), EffectiveSwitchState.ACTIVE);

            assertThat(result).isInstanceOf(RedactionResult.Body.class);
            final JsonNode hearing = body(result).get("hearing");
            final JsonNode defendant = hearing.get("prosecutionCases").get(0).get("defendants").get(0);
            final JsonNode judicialResult = defendant.get("offences").get(0).get("judicialResults").get(0);

            assertThat(hearing.has("judiciary")).isFalse();
            assertThat(defendant.has("associatedPersons")).isFalse();
            assertThat(judicialResult.has("delegatedPowers")).isFalse();
        }
    }

    private static JsonNode body(final RedactionResult result) {
        return ((RedactionResult.Body) result).payload();
    }

    private static JsonNode fixture(final String name) {
        try (InputStream in =
                     HmppsRedactionLegacyTwinTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not on the test classpath: " + name);
            }
            return MAPPER.readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read fixture " + name, e);
        }
    }
}
