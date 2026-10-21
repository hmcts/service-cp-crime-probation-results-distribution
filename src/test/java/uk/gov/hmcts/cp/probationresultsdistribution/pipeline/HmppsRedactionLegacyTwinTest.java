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
