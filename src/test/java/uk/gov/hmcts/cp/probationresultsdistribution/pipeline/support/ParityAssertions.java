package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.List;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * The parity harness's one entry point for asserting that two HMPPS bodies are the same.
 *
 * <p>It is the single place the corpus harness and slice 006's STE replay (FR-001-016) ask the one
 * comparator, {@link JsonParity}, whether a produced body matches its expectation, so both report a
 * mismatch identically. Its job beyond delegating is privacy: this repository is public and the
 * corpus holds SIT data, so a failure MUST name the case, the form, the switch state and every
 * differing JSON path and carry no value from either side (FR-001-015, SC-001-009). It asks the
 * comparator directly for paths, without constructing or parsing a value-bearing error message.
 *
 * <p>Test-only utility: it lives under {@code src/test/java} because nothing in production calls it
 * in this slice (tasks.md T023).
 */
public final class ParityAssertions {

    private ParityAssertions() {
    }

    /**
     * Asserts that a produced body matches its expectation, reporting any difference value-free.
     *
     * @param caseId      the corpus case id, named in a failure
     * @param form        the payload form (cache or query-API fallback), named in a failure
     * @param switchState the effective youth-switch state, named in a failure
     * @param expected    the expected body (the oracle's recording)
     * @param actual      the produced body
     * @throws AssertionError naming the case, form, switch state and each differing JSON path, with
     *                        no value from either side, when the bodies differ
     */
    public static void assertSameBody(
            final String caseId,
            final String form,
            final EffectiveSwitchState switchState,
            final JsonNode expected,
            final JsonNode actual) {

        final String label = label(caseId, form, switchState);
        final List<String> paths = JsonParity.differencePaths(expected, actual);
        if (!paths.isEmpty()) {
            throw new AssertionError(label + " does not match the expectation — " + paths.size()
                    + " differing path(s): " + String.join(", ", paths));
        }
    }

    /**
     * Builds the value-free label that identifies a case-state in a failure.
     *
     * @param caseId      the corpus case id
     * @param form        the payload form
     * @param switchState the effective youth-switch state
     * @return the label
     */
    private static String label(
            final String caseId, final String form, final EffectiveSwitchState switchState) {
        return "parity mismatch [case=" + caseId + ", form=" + form
                + ", switch=" + switchState + "]";
    }
}
