package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The canonical JSON mapper: a Jackson 3 {@link ObjectMapper} that reads every floating-point value
 * as a {@code BigDecimal} so {@code 1}, {@code 1.0} and {@code 1.00} are the same number compared by
 * value, never collapsed to a lossy {@code double} (design §6.2, research.md R3). The parity
 * comparator depends on this: parse a fixture with a default mapper and the numeric-scale vectors
 * pass trivially.
 */
public final class CanonicalJsonMapper {

    private CanonicalJsonMapper() {
    }

    /**
     * Builds a mapper carrying this service's JSON numeric contract.
     *
     * @return a Jackson 3 mapper that reads floating-point values as {@code BigDecimal}
     */
    public static ObjectMapper create() {
        return JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }
}
