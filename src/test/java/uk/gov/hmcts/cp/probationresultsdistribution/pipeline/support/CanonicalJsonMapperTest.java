package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the canonical mapper's one job: floating-point values are read as {@code BigDecimal}, so a
 * number keeps its exact decimal value and scale, and two renderings of the same number compare
 * equal (design §6.2, FR-001-013).
 */
@DisplayName("CanonicalJsonMapper")
class CanonicalJsonMapperTest {

    @Test
    @DisplayName("parses 1, 1.0 and 1.00 as the same number, preserving decimal scale")
    void create_decimalScales_should_parse_to_numerically_equal_big_decimals() {
        final ObjectMapper mapper = CanonicalJsonMapper.create();

        final JsonNode one = mapper.readTree("1");
        final JsonNode oneDotZero = mapper.readTree("1.0");
        final JsonNode oneDotZeroZero = mapper.readTree("1.00");

        assertThat(oneDotZeroZero.decimalValue().scale())
                .as("1.00 must keep scale 2, which only BigDecimal-backed parsing preserves")
                .isEqualTo(2);
        assertThat(one.decimalValue().compareTo(oneDotZero.decimalValue()))
                .as("1 and 1.0 are the same number")
                .isZero();
        assertThat(oneDotZero.decimalValue().compareTo(oneDotZeroZero.decimalValue()))
                .as("1.0 and 1.00 are the same number")
                .isZero();
    }
}
