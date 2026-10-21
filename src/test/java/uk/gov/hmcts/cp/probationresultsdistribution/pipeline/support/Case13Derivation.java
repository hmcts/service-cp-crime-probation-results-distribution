package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.List;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.HmppsRedaction;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport.DerivedState;

/**
 * Case 13 — the switch read fails — derived for every corpus payload and form under DV-25
 * (FR-001-038; §6.3 case 13; H8). The legacy sends nothing when the switch read fails
 * (P16, {@code PF/index.js:29-32}); under DV-25 the leg instead applies
 * {@code distribution.youth-switch.default-when-unreadable} and sends the body for that default
 * state. Case 13's expectation is therefore derived — never recorded as its own fixture — from the
 * case's expectation for the configured default (its {@code ACTIVE} expectation unless the default
 * is set to {@code INACTIVE}).
 *
 * <p>Compile-safe seam for T029: it derives nothing until the derivation lands in T043. The causes
 * of an unreadable read and the run that records {@code UNREADABLE_DEFAULTED_&lt;value&gt;} belong
 * to slice 002; this is only the build-time derivation of the expected output.
 */
public final class Case13Derivation {

    private Case13Derivation() {
    }

    /**
     * The derivation's outcome over the whole corpus.
     *
     * @param derivedStates the case-states derived under DV-25 (reason {@code null})
     * @param withoutSource the derivations whose source expectation does not exist (reason set)
     * @param differences   a value-free line per derived case-state whose port result does not match
     */
    public record Result(
            List<DerivedState> derivedStates,
            List<DerivedState> withoutSource,
            List<String> differences) {
    }

    /**
     * Derives case 13 for every case, form and configured-default value.
     *
     * @param cases     the loaded corpus cases
     * @param redaction the port under test
     * @return the derivation outcome
     */
    public static Result derive(final List<ParityCase> cases, final HmppsRedaction redaction) {
        return new Result(List.of(), List.of(), List.of());
    }
}
