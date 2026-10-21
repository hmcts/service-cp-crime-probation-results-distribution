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
