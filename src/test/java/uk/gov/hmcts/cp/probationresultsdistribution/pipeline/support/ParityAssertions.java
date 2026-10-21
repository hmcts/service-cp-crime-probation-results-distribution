package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.ArrayList;
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
 * differing JSON path and carry no value from either side (FR-001-015, SC-001-009). {@link JsonParity}
 * renders values into its message to be useful on the informant pack's synthetic goldens; here those
 * values are SIT payload content, so the raw message is caught and re-expressed as the label plus the
 * paths alone, with the cause deliberately dropped from the rethrow so no value leaks through a
 * stack-trace chain.
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
        try {
            JsonParity.assertMatches(expected, actual, label);
        } catch (final AssertionError difference) {
            throw new AssertionError(valueFreeMessage(label, difference.getMessage()));
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

    /**
     * Re-expresses {@link JsonParity}'s value-carrying message as the label plus the differing JSON
     * paths only.
     *
     * <p>Each difference line {@link JsonParity} emits begins with its JSON pointer followed by
     * {@code ": "} and a description that may quote values; only the pointer is kept. The header and
     * the truncation marker carry no path and are dropped.
     *
     * @param label      the value-free label
     * @param rawMessage the message {@link JsonParity} threw, which may carry values
     * @return a message naming the label and each differing JSON path, with no value
     */
    private static String valueFreeMessage(final String label, final String rawMessage) {
        final List<String> paths = new ArrayList<>();
        for (final String line : rawMessage.split("\n")) {
            if (!line.startsWith("  ")) {
                continue;
            }
            final String trimmed = line.strip();
            if (trimmed.startsWith("...")) {
                continue;
            }
            final int separator = trimmed.indexOf(": ");
            paths.add(separator > 0 ? trimmed.substring(0, separator) : trimmed);
        }
        return label + " does not match the expectation — " + paths.size()
                + " differing path(s): " + String.join(", ", paths);
    }
}
