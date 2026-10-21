package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.HmppsRedaction;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase.Expectation;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase.Form;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport.ComparedState;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport.ExcludedCase;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport.NoExpectationState;

/**
 * The corpus/oracle parity harness (FR-001-026, FR-001-039; §9.2 row 4). It runs the port's
 * redaction over every committed corpus case, in each switch state and each form that has an
 * expectation, compares the result against the oracle's recording via {@link ParityAssertions}, and
 * returns a {@link ParityReport} — the build-time evidence for the quality gate. Any difference that
 * no registered derivation explains is counted as an unexplained difference, which the test asserts
 * is zero.
 *
 * <p>A case-state with no expectation (FR-001-019 … FR-001-021) is listed and never counted as a
 * pass. Case 13 — the switch-read failure — is derived under DV-25 by {@link Case13Derivation},
 * never compared against a recorded fixture.
 *
 * <p>Every line the harness produces names only a case, a form, a switch state, a rule or a result
 * kind — never a corpus value (FR-001-031). Body comparisons go through {@link ParityAssertions},
 * which is value-free by construction.
 */
public final class CorpusParityHarness {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();
    private static final String INDEX = "index.json";

    private final HmppsRedaction redaction = new HmppsRedaction();

    /**
     * Runs the parity suite over a corpus root and reports it.
     *
     * @param corpusRoot the corpus directory
     * @return the parity report
     */
    public ParityReport run(final Path corpusRoot) {
        final List<ParityCase> cases = ParityCase.loadAll(corpusRoot);
        final List<ComparedState> compared = new ArrayList<>();
        final List<NoExpectationState> noExpectation = new ArrayList<>();
        final List<String> differences = new ArrayList<>();
        int matches = 0;

        for (final ParityCase parityCase : cases) {
            for (final Form form : parityCase.formsHeld()) {
                for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
                    final Expectation expectation = parityCase.expectationFor(form, state);
                    if (expectation == null) {
                        // Every held form x switch state MUST carry an expectation entry, even if
                        // that entry is an explicit NONE with a reason (FR-001-018 ... FR-001-021).
                        // A case-state absent from meta.json entirely is a corpus-authoring defect,
                        // never a silent gap, so it fails the gate rather than shrinking the run.
                        differences.add(label(parityCase.caseId(), form, state)
                                + " has no expectation and no explicit NONE entry recording why "
                                + "(FR-001-018)");
                        continue;
                    }
                    if (expectation instanceof Expectation.NoneExpectation none) {
                        noExpectation.add(new NoExpectationState(
                                parityCase.caseId(), form.name(), state.name(), none.reason()));
                        continue;
                    }
                    compared.add(new ComparedState(parityCase.caseId(), form.name(), state.name()));
                    final RedactionResult result =
                            redaction.redact(parityCase.inputs().get(form), state);
                    final String difference = compareOutcome(
                            parityCase.caseId(), form, state, expectation, result);
                    if (difference == null) {
                        matches++;
                    } else {
                        differences.add(difference);
                    }
                }
            }
        }

        final Case13Derivation.Result caseThirteen = Case13Derivation.derive(cases, redaction);
        differences.addAll(caseThirteen.differences());

        return new ParityReport(
                List.copyOf(compared),
                matches,
                caseThirteen.derivedStates(),
                List.copyOf(noExpectation),
                caseThirteen.withoutSource(),
                readExcludedCases(corpusRoot),
                differences.size(),
                List.copyOf(differences));
    }

    /**
     * Reads the excluded cases {@code index.json} records, each with its reason (FR-001-029,
     * FR-001-039). The custody gate ({@link CorpusCustodyChecker}) is authoritative for whether
     * {@code index.json} is present and well formed; this method is defensive only, so a missing or
     * malformed index reports no exclusions rather than failing the parity run itself.
     *
     * @param corpusRoot the corpus directory
     * @return the excluded cases the index records, each with its reason
     */
    private static List<ExcludedCase> readExcludedCases(final Path corpusRoot) {
        final Path indexPath = corpusRoot.resolve(INDEX);
        if (!Files.isRegularFile(indexPath)) {
            return List.of();
        }
        final JsonNode index;
        try {
            index = MAPPER.readTree(Files.readAllBytes(indexPath));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        } catch (final JacksonException e) {
            return List.of();
        }
        final JsonNode excluded = index.get("excludedCases");
        if (excluded == null || !excluded.isArray()) {
            return List.of();
        }
        final List<ExcludedCase> result = new ArrayList<>();
        for (final JsonNode entry : excluded) {
            final JsonNode caseId = entry.get("caseId");
            if (caseId == null || !caseId.isString()) {
                continue;
            }
            final JsonNode reason = entry.get("reason");
            result.add(new ExcludedCase(
                    caseId.stringValue(),
                    reason != null && reason.isString() ? reason.stringValue() : "no reason recorded"));
        }
        return List.copyOf(result);
    }

    /**
     * Compares one case-state's port result with its expectation, value-free. Package-visible so the
     * case-13 derivation ({@link Case13Derivation}) compares the same way against the configured
     * default's expectation.
     *
     * @param caseId      the corpus case id
     * @param form        the payload form
     * @param state       the effective switch state
     * @param expectation the expectation (a body or no message)
     * @param result      the port's result
     * @return {@code null} on a match, or a value-free difference line
     */
    static String compareOutcome(
            final String caseId,
            final Form form,
            final EffectiveSwitchState state,
            final Expectation expectation,
            final RedactionResult result) {

        if (expectation instanceof Expectation.ExpectedBody expectedBody) {
            if (result instanceof RedactionResult.Body produced) {
                try {
                    ParityAssertions.assertSameBody(
                            caseId, form.name(), state, expectedBody.body(), produced.payload());
                    return null;
                } catch (final AssertionError difference) {
                    return difference.getMessage();
                }
            }
            return label(caseId, form, state)
                    + " expected a body but the port produced " + kindOf(result);
        }
        if (expectation instanceof Expectation.NoMessage noMessage) {
            return compareNoMessage(caseId, form, state, noMessage, result);
        }
        return null;
    }

    /**
     * Compares a no-message expectation with the port result, value-free.
     *
     * @param caseId     the corpus case id
     * @param form       the payload form
     * @param state      the effective switch state
     * @param noMessage  the no-message expectation
     * @param result     the port's result
     * @return {@code null} on a match, or a value-free difference line
     */
    private static String compareNoMessage(
            final String caseId,
            final Form form,
            final EffectiveSwitchState state,
            final Expectation.NoMessage noMessage,
            final RedactionResult result) {

        final String label = label(caseId, form, state);
        return switch (noMessage.kind()) {
            case EMPTIED_BY_R1 -> result instanceof RedactionResult.EmptiedByR1
                    ? null : label + " expected emptied-by-R1 but the port produced " + kindOf(result);
            case EMPTIED_BY_R11 -> result instanceof RedactionResult.EmptiedByR11
                    ? null : label + " expected emptied-by-R11 but the port produced " + kindOf(result);
            case RULES_FAILURE -> compareRulesFailure(label, noMessage, result);
            case UNKNOWN -> result instanceof RedactionResult.Body
                    ? label + " expected no message but the port produced a body" : null;
        };
    }

    private static String compareRulesFailure(
            final String label,
            final Expectation.NoMessage noMessage,
            final RedactionResult result) {

        if (!(result instanceof RedactionResult.RulesFailure failure)) {
            return label + " expected a rules failure but the port produced " + kindOf(result);
        }
        if (noMessage.ruleId() != null && !noMessage.ruleId().equals(failure.ruleId())) {
            return label + " expected a rules failure in " + noMessage.ruleId()
                    + " but the port failed in " + failure.ruleId();
        }
        return null;
    }

    private static String kindOf(final RedactionResult result) {
        return switch (result) {
            case RedactionResult.Body ignored -> "a body";
            case RedactionResult.EmptiedByR1 ignored -> "emptied-by-R1";
            case RedactionResult.EmptiedByR11 ignored -> "emptied-by-R11";
            case RedactionResult.RulesFailure failure -> "a rules failure in " + failure.ruleId();
        };
    }

    private static String label(
            final String caseId, final Form form, final EffectiveSwitchState state) {
        return "case-state [case=" + caseId + ", form=" + form + ", switch=" + state + "]";
    }
}
