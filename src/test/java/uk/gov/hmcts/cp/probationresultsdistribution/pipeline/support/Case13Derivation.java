package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.HmppsRedaction;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase.Expectation;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase.Form;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport.DerivedState;

/**
 * Case 13 — the switch read fails — derived for every corpus payload and form under DV-25
 * (FR-001-038; §6.3 case 13; H8). The legacy sends nothing when the switch read fails
 * (P16, {@code PF/index.js:29-32}); under DV-25 the leg instead applies
 * {@code distribution.youth-switch.default-when-unreadable} and sends the body for that default
 * state. Case 13's expectation is therefore derived — never recorded as its own fixture — from the
 * case's expectation for the configured default: its {@code ACTIVE} expectation when the default is
 * {@code ACTIVE} (the default unless configured otherwise), and its {@code INACTIVE} expectation when
 * the default is {@code INACTIVE}.
 *
 * <p>The derivation is run for both default values the switch can resolve to, so withdrawing DV-25
 * will break every case-13 derivation (the withdrawal test lands in user story 5, T046/T048; the
 * register-driven wiring of the permitted outcome is T048). A derivation whose source expectation
 * does not exist (FR-001-019 … FR-001-021) is listed with no source and never counted as a pass.
 *
 * <p>The causes of an unreadable read and the run that records
 * {@code UNREADABLE_DEFAULTED_&lt;value&gt;} belong to slice 002; this is only the build-time
 * derivation of the expected output.
 */
public final class Case13Derivation {

    private static final String DV_ID = "DV-25";
    private static final Path DEFAULT_REGISTER = Path.of("doc", "DEVIATIONS.md");

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
     * Derives case 13 for every case, form and configured-default value against the service's own
     * deviation register at {@code doc/DEVIATIONS.md}.
     *
     * @param cases     the loaded corpus cases
     * @param redaction the port under test
     * @return the derivation outcome
     */
    public static Result derive(final List<ParityCase> cases, final HmppsRedaction redaction) {
        return derive(cases, redaction, DEFAULT_REGISTER);
    }

    /**
     * Derives case 13 for every case, form and configured-default value, reading DV-25's permitted
     * outcome from the given deviation register (FR-001-037, FR-001-038). The register states, per
     * configured default, which switch-state expectation the port must produce; withdrawing DV-25's
     * row (its status no longer {@code Active}) leaves nothing to derive against, so every derivation
     * fails quoting DV-25 — the load-bearing wiring User Story 5 proves (T046, T048).
     *
     * @param cases        the loaded corpus cases
     * @param redaction    the port under test
     * @param registerPath the deviation register to read DV-25's permitted outcome from
     * @return the derivation outcome
     */
    public static Result derive(
            final List<ParityCase> cases,
            final HmppsRedaction redaction,
            final Path registerPath) {
        Objects.requireNonNull(registerPath, "registerPath");
        final DeviationRegister register = DeviationRegister.load(registerPath);
        final boolean active = register.isActive(DV_ID);
        final List<DerivedState> derived = new ArrayList<>();
        final List<DerivedState> withoutSource = new ArrayList<>();
        final List<String> differences = new ArrayList<>();

        for (final ParityCase parityCase : cases) {
            for (final Form form : parityCase.formsHeld()) {
                for (final EffectiveSwitchState defaultState : EffectiveSwitchState.values()) {
                    if (!active) {
                        differences.add("case 13 (" + DV_ID + "): derivation refused — " + DV_ID
                                + " is not an active row of the deviation register");
                        continue;
                    }
                    deriveOne(register, parityCase, form, defaultState, redaction,
                            derived, withoutSource, differences);
                }
            }
        }
        return new Result(
                List.copyOf(derived), List.copyOf(withoutSource), List.copyOf(differences));
    }

    private static void deriveOne(
            final DeviationRegister register,
            final ParityCase parityCase,
            final Form form,
            final EffectiveSwitchState defaultState,
            final HmppsRedaction redaction,
            final List<DerivedState> derived,
            final List<DerivedState> withoutSource,
            final List<String> differences) {

        final EffectiveSwitchState sourceState = register.sourceStateForDefault(DV_ID, defaultState);
        if (sourceState == null) {
            differences.add("case 13 (" + DV_ID + "): derivation refused — " + DV_ID
                    + " does not describe the configured default " + defaultState.name());
            return;
        }

        final Expectation source = parityCase.expectationFor(form, sourceState);
        if (source == null || source instanceof Expectation.NoneExpectation) {
            final String reason = source instanceof Expectation.NoneExpectation none
                    ? none.reason() : "the case holds no expectation for the configured default";
            withoutSource.add(new DerivedState(
                    parityCase.caseId(), form.name(), defaultState.name(), DV_ID, reason));
            return;
        }

        derived.add(new DerivedState(
                parityCase.caseId(), form.name(), defaultState.name(), DV_ID, null));
        final RedactionResult result = redaction.redact(parityCase.inputs().get(form), defaultState);
        final String difference = CorpusParityHarness.compareOutcome(
                parityCase.caseId(), form, defaultState, source, result);
        if (difference != null) {
            differences.add("case 13 (" + DV_ID + "): " + difference);
        }
    }
}
