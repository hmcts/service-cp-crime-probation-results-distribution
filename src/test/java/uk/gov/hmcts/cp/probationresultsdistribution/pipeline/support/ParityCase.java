package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * One corpus case loaded from {@code src/test/resources/corpus/&lt;caseId&gt;/} for the parity
 * harness (data-model.md {@code CorpusCase}; FR-001-017, FR-001-018). It holds the payload in each
 * form it carries and, for each (form, switch state), the oracle's expectation.
 *
 * <p>The expectation is the legacy's own recorded output (FR-001-019, FR-001-022): a body, no
 * message (with the kind the oracle recorded), or — where this spec sets none — no expectation, with
 * its reason (FR-001-019 … FR-001-021). The harness compares the port's {@code redact(...)} result
 * against it; a case-state with no expectation is listed and never counted as a pass.
 *
 * <p>Loading never prints a corpus value: the payloads and expectation bodies stay in the returned
 * {@link JsonNode} trees and are compared value-free by {@link ParityAssertions} (FR-001-031).
 */
public record ParityCase(
        String caseId,
        String environment,
        Set<Form> formsHeld,
        Map<Form, JsonNode> inputs,
        Map<Form, Map<EffectiveSwitchState, Expectation>> expectations) {

    /** A payload form the corpus can hold for a share (data-model.md). */
    public enum Form {
        /** The cache form: the {@code EXT_} value the legacy read from Redis. */
        CACHE,
        /** The query-API fallback form. */
        FALLBACK
    }

    /** The kind of a no-message expectation, as the oracle recorded it (data-model.md). */
    public enum NoMessageKind {
        /** R1 emptied the payload (SJP, box or virtual-box hearing). */
        EMPTIED_BY_R1,
        /** R11 emptied the payload (no prosecution case and no court application left). */
        EMPTIED_BY_R11,
        /** A rule threw on the payload's shape (a carried missing guard, P19). */
        RULES_FAILURE,
        /** The source shows no message but not which kind. */
        UNKNOWN
    }

    /**
     * A case-state's expectation: a body, no message, or no expectation at all (data-model.md).
     * The {@code source} on the first two is one of {@code EXECUTED_LEGACY}, {@code SIT_LOG} or
     * {@code DERIVED:&lt;dvId&gt;} (FR-001-018).
     */
    public sealed interface Expectation
            permits Expectation.ExpectedBody, Expectation.NoMessage, Expectation.NoneExpectation {

        /**
         * The oracle's recorded body to compare against (FR-001-019, FR-001-022).
         *
         * @param body   the recorded body
         * @param source the expectation's source
         */
        record ExpectedBody(JsonNode body, String source) implements Expectation {
        }

        /**
         * The oracle recorded no message for this case-state (FR-001-018).
         *
         * @param kind   the no-message kind
         * @param ruleId the failing rule where the kind is a rules failure and the source shows it,
         *               otherwise {@code null}
         * @param source the expectation's source
         */
        record NoMessage(NoMessageKind kind, String ruleId, String source) implements Expectation {
        }

        /**
         * This spec sets no expectation for this case-state (FR-001-019 … FR-001-021).
         *
         * @param reason why there is no expectation
         */
        record NoneExpectation(String reason) implements Expectation {
        }
    }

    /**
     * The expectation for one case-state, or {@code null} when the case does not hold that
     * (form, state).
     *
     * @param form        the payload form
     * @param switchState the effective switch state
     * @return the expectation, or {@code null}
     */
    public Expectation expectationFor(final Form form, final EffectiveSwitchState switchState) {
        final Map<EffectiveSwitchState, Expectation> perState = expectations.get(form);
        return perState == null ? null : perState.get(switchState);
    }

    /**
     * Loads every corpus case under a corpus root (its {@code index.json} and one sub-directory per
     * case). Compile-safe seam for T029: it returns no cases until the loader lands in T031, so the
     * harness builds an empty report and the corpus-coverage assertions fail on an assertion rather
     * than a compile error.
     *
     * @param corpusRoot the corpus directory
     * @return the cases found
     */
    public static List<ParityCase> loadAll(final Path corpusRoot) {
        return List.of();
    }
}
