package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.List;

/**
 * The parity run's report, value-free by construction (FR-001-039; §9.2 rows 4 and 12). It is the
 * build-time evidence for the go-live quality gate: every field names only cases, forms, switch
 * states, DV ids, rules and reasons — never a byte of corpus content (FR-001-031).
 *
 * <p>The seven lists and counts are exactly FR-001-039's required contents: the case-states
 * compared, the matches, the case-states derived under a DV id (case 13 under DV-25), the
 * case-states for which this spec sets no expectation, the case-13 derivations with no source
 * expectation, the excluded cases, and the count of unexplained differences, which must be zero.
 *
 * @param comparedStates              every case-state compared (with an expectation)
 * @param matches                     how many of them matched
 * @param casesDerivedByDv            the case-states derived under a DV id (case 13 under DV-25)
 * @param noExpectationCaseStates     the case-states with no expectation, each with its reason
 * @param caseThirteenWithoutSource   the case-13 derivations whose source expectation does not exist
 * @param excludedCases               the excluded cases, each with its reason
 * @param unexplainedDifferences      the count of unexplained differences; must be zero
 * @param unexplainedDifferenceDetail a value-free line per unexplained difference
 */
public record ParityReport(
        List<ComparedState> comparedStates,
        int matches,
        List<DerivedState> casesDerivedByDv,
        List<NoExpectationState> noExpectationCaseStates,
        List<DerivedState> caseThirteenWithoutSource,
        List<ExcludedCase> excludedCases,
        int unexplainedDifferences,
        List<String> unexplainedDifferenceDetail) {

    /**
     * One case-state compared against its expectation.
     *
     * @param caseId      the corpus case id
     * @param form        the payload form
     * @param switchState the effective switch state
     */
    public record ComparedState(String caseId, String form, String switchState) {
    }

    /**
     * One case-state derived under a DV id (case 13, the switch-read failure, under DV-25).
     *
     * @param caseId       the corpus case id
     * @param form         the payload form
     * @param defaultState the configured default the switch read resolved to
     * @param dvId         the DV id the derivation is keyed on
     * @param reason       why the derivation has no source expectation, or {@code null} when it has
     */
    public record DerivedState(
            String caseId, String form, String defaultState, String dvId, String reason) {
    }

    /**
     * One case-state for which this spec sets no expectation (FR-001-019 … FR-001-021).
     *
     * @param caseId      the corpus case id
     * @param form        the payload form
     * @param switchState the effective switch state
     * @param reason      why there is no expectation
     */
    public record NoExpectationState(
            String caseId, String form, String switchState, String reason) {
    }

    /**
     * One excluded case.
     *
     * @param caseId the corpus case id
     * @param reason why it is excluded
     */
    public record ExcludedCase(String caseId, String reason) {
    }
}
