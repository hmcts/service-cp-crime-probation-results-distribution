package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CorpusCustodyChecker;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CorpusParityHarness;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityReport;

/**
 * The corpus/oracle parity suite (FR-001-026, FR-001-039; SC-001-003, SC-001-004; §9.2 row 4): the
 * port's redaction is run over every committed corpus case, in each switch state and each form that
 * has an expectation, and compared with the oracle's recording with zero unexplained differences —
 * the build-time half of the quality gate (H21).
 *
 * <p>The report is value-free (FR-001-031): every assertion below names only cases, forms, switch
 * states, DV ids and reasons. A case-state with no expectation (FR-001-019 … FR-001-021) is listed
 * and never counted as a pass; case 13, the switch-read failure, is derived under DV-25
 * (FR-001-038), never recorded as its own fixture.
 */
@DisplayName("HmppsRedaction — corpus/oracle parity")
class HmppsRedactionCorpusParityIT {

    private static final List<String> REQUIRED_BLIND_SPOT_CASES = List.of(
            "case-01-virtual-box-hearing",
            "case-02-top-level-members",
            "case-03-no-masterdefendant",
            "case-04-no-judicial-results",
            "case-05-no-court-order-offences",
            "case-08-delegated-powers",
            "case-09-non-boolean-isyouth",
            "case-11-youth-among-adults",
            "case-12-everything-removed");

    private static final List<String> REQUIRED_RULE_COVERAGE_CASES = List.of(
            "case-r0-policecases",
            "case-p21-application-keeps-associated");

    private final CorpusParityHarness harness = new CorpusParityHarness();

    @Test
    @DisplayName("every required blind-spot, R0 and P21 case is present (SC-001-004, FR-001-025)")
    void run_overTheCorpus_should_hold_every_required_case() {
        final List<String> caseIds = ParityCase.loadAll(corpusRoot()).stream()
                .map(ParityCase::caseId)
                .toList();

        assertThat(caseIds)
                .as("the nine recorded blind-spot cases must be present in the corpus")
                .containsAll(REQUIRED_BLIND_SPOT_CASES);
        assertThat(caseIds)
                .as("the R0 and P21 proving cases must be present (FR-001-025)")
                .containsAll(REQUIRED_RULE_COVERAGE_CASES);
    }

    @Test
    @DisplayName("the parity run reports zero unexplained differences (SC-001-003)")
    void run_overTheCorpus_should_report_zero_unexplained_differences() {
        final ParityReport report = harness.run(corpusRoot());

        assertThat(report.unexplainedDifferences())
                .as("unexplained differences: %s", report.unexplainedDifferenceDetail())
                .isZero();
    }

    @Test
    @DisplayName("every compared case-state matches its expectation")
    void run_overTheCorpus_should_match_every_compared_case_state() {
        final ParityReport report = harness.run(corpusRoot());

        assertThat(report.comparedStates())
                .as("the corpus must compare at least one case-state per switch state")
                .isNotEmpty();
        assertThat(report.matches())
                .as("every compared case-state must match: %s", report.unexplainedDifferenceDetail())
                .isEqualTo(report.comparedStates().size());
    }

    @Test
    @DisplayName("case 13 is derived under DV-25 for the corpus (FR-001-038, SC-001-004)")
    void run_overTheCorpus_should_derive_case_13_under_dv25() {
        final ParityReport report = harness.run(corpusRoot());

        assertThat(report.casesDerivedByDv())
                .as("case 13 must be derived for the corpus payloads, forms and default values")
                .isNotEmpty();
        assertThat(report.casesDerivedByDv())
                .allSatisfy(derived -> assertThat(derived.dvId()).isEqualTo("DV-25"));
    }

    @Test
    @DisplayName("no case-state is left without an expectation, and no case is excluded")
    void run_overTheCorpus_should_have_no_missing_expectation_or_exclusion() {
        final ParityReport report = harness.run(corpusRoot());

        assertThat(report.noExpectationCaseStates())
                .as("every synthetic case-state carries an oracle expectation")
                .isEmpty();
        assertThat(report.caseThirteenWithoutSource())
                .as("every case-13 derivation has a source expectation")
                .isEmpty();
        assertThat(report.excludedCases())
                .as("no case is excluded from the synthetic corpus")
                .isEmpty();
    }

    @Test
    @DisplayName("the committed corpus is custody-clean (FR-001-030, FR-001-032)")
    void corpus_should_pass_the_custody_gate() {
        assertThat(CorpusCustodyChecker.findViolations(corpusRoot()))
                .as("the committed corpus must carry pinned digests and complete provenance")
                .isEmpty();
    }

    private static Path corpusRoot() {
        final URL url = HmppsRedactionCorpusParityIT.class.getResource("/corpus");
        if (url == null) {
            throw new IllegalStateException("the corpus is not on the test classpath: /corpus");
        }
        try {
            return Path.of(url.toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("corpus URL is not a valid URI", e);
        }
    }
}
