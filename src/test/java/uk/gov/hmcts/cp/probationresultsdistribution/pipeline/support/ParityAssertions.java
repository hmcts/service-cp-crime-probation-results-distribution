package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * The parity harness's one entry point for asserting that two HMPPS bodies are the same.
 *
 * <p>Compile-safe seam for T021. The real implementation (T023) delegates to {@link JsonParity}
 * and reports failures value-free. This stub exists only so the failing test records an assertion
 * failure rather than a compilation error, per this repo's TDD convention.
 */
public final class ParityAssertions {

    private ParityAssertions() {
    }

    /**
     * Asserts that two bodies are the same, labelling any failure safely.
     *
     * @param caseId      the corpus case id, named in a failure
     * @param form        the payload form, named in a failure
     * @param switchState the effective youth-switch state, named in a failure
     * @param expected    the expected body
     * @param actual      the produced body
     */
    public static void assertSameBody(
            final String caseId,
            final String form,
            final EffectiveSwitchState switchState,
            final JsonNode expected,
            final JsonNode actual) {

        throw new UnsupportedOperationException("ParityAssertions.assertSameBody lands in T023");
    }
}
