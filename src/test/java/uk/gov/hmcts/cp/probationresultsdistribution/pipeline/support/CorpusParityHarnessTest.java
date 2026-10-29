package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two corpus/oracle parity harness gaps found in code review (FR-001-018 … FR-001-021, FR-001-029,
 * FR-001-039): a case-state absent from {@code meta.json} entirely must fail the gate rather than
 * silently shrinking the run, and {@code index.json}'s {@code excludedCases} must actually be read
 * and reported, not hard-coded empty. Both are exercised over hand-built scratch corpora, since the
 * committed corpus has neither a missing case-state nor an exclusion today.
 */
@DisplayName("CorpusParityHarness — missing expectations and excluded cases")
class CorpusParityHarnessTest {

    private static final String SJP_PAYLOAD = "{\"hearing\":{\"isSJPHearing\":true}}";
    private final CorpusParityHarness harness = new CorpusParityHarness();

    @Test
    @DisplayName("a case-state missing from meta.json fails the gate; an explicit NONE does not")
    void run_whenACaseStateIsMissingEntirely_should_fail_but_an_explicit_NONE_should_not(
            @TempDir final Path corpusRoot) throws IOException {

        writeCase(corpusRoot, "case-missing-one-state", """
                {
                  "caseId": "case-missing-one-state",
                  "environment": "SYNTHETIC",
                  "formsHeld": ["CACHE"],
                  "expectations": {
                    "ACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    }
                  }
                }
                """);
        writeCase(corpusRoot, "case-with-explicit-none", """
                {
                  "caseId": "case-with-explicit-none",
                  "environment": "SYNTHETIC",
                  "formsHeld": ["CACHE"],
                  "expectations": {
                    "ACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    },
                    "INACTIVE": {
                      "CACHE": {"kind": "NONE", "reason": "share source form unknown (FR-001-019)"}
                    }
                  }
                }
                """);

        final ParityReport report = harness.run(corpusRoot);

        assertThat(report.unexplainedDifferences())
                .as("the silently-missing INACTIVE/CACHE case-state must fail the gate")
                .isEqualTo(1);
        assertThat(report.unexplainedDifferenceDetail())
                .anyMatch(line -> line.contains("case-missing-one-state")
                        && line.contains("INACTIVE")
                        && line.contains("no expectation"));
        assertThat(report.unexplainedDifferenceDetail())
                .as("an explicit NONE case-state must never be counted as an unexplained difference")
                .noneMatch(line -> line.contains("case-with-explicit-none"));
        assertThat(report.noExpectationCaseStates())
                .as("an explicit NONE case-state is reported, not silently dropped either")
                .anySatisfy(state -> {
                    assertThat(state.caseId()).isEqualTo("case-with-explicit-none");
                    assertThat(state.switchState()).isEqualTo("INACTIVE");
                    assertThat(state.reason()).contains("FR-001-019");
                });
    }

    @Test
    @DisplayName("an excluded case recorded in index.json is read and reported, not hard-coded empty")
    void run_whenIndexRecordsAnExcludedCase_should_report_it(@TempDir final Path corpusRoot)
            throws IOException {
        writeCase(corpusRoot, "case-present", """
                {
                  "caseId": "case-present",
                  "environment": "SYNTHETIC",
                  "formsHeld": ["CACHE"],
                  "expectations": {
                    "ACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    },
                    "INACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    }
                  }
                }
                """);
        Files.writeString(corpusRoot.resolve("index.json"), """
                {
                  "excludedCases": [
                    {"caseId": "case-excluded-example", "reason": "logged body cut short at the trace limit"}
                  ]
                }
                """);

        final ParityReport report = harness.run(corpusRoot);

        assertThat(report.excludedCases())
                .as("index.json's excludedCases must be read, not returned as a hard-coded empty list")
                .anySatisfy(excluded -> {
                    assertThat(excluded.caseId()).isEqualTo("case-excluded-example");
                    assertThat(excluded.reason()).isEqualTo("logged body cut short at the trace limit");
                });
    }

    @Test
    @DisplayName("a missing or malformed index.json reports no exclusions rather than failing the run")
    void run_whenIndexIsMissing_should_report_no_exclusions(@TempDir final Path corpusRoot)
            throws IOException {
        writeCase(corpusRoot, "case-present", """
                {
                  "caseId": "case-present",
                  "environment": "SYNTHETIC",
                  "formsHeld": ["CACHE"],
                  "expectations": {
                    "ACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    },
                    "INACTIVE": {
                      "CACHE": {"kind": "NO_MESSAGE", "noMessageKind": "EMPTIED_BY_R1", "source": "EXECUTED_LEGACY"}
                    }
                  }
                }
                """);

        final ParityReport report = harness.run(corpusRoot);

        assertThat(report.excludedCases()).isEmpty();
    }

    private static void writeCase(final Path corpusRoot, final String caseId, final String meta)
            throws IOException {
        final Path caseDir = Files.createDirectories(corpusRoot.resolve(caseId));
        Files.writeString(caseDir.resolve("meta.json"), meta);
        Files.createDirectories(caseDir.resolve("inputs"));
        Files.writeString(caseDir.resolve("inputs").resolve("cache.json"), SJP_PAYLOAD);
    }
}
