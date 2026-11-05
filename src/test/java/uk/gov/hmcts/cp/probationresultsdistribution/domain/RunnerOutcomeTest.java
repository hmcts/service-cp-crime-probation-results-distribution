package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Pins the runner exit-code mapping this slice's command-line interface depends on (FR-004-045;
 * {@code contracts/cli-runners.md}): a runbook step branches on the exit code alone.
 */
class RunnerOutcomeTest {

    @Test
    void exitCode_done_isZero() {
        assertThat(RunnerOutcome.DONE.exitCode()).isZero();
    }

    @Test
    void exitCode_refused_isOne() {
        assertThat(RunnerOutcome.REFUSED.exitCode()).isOne();
    }

    @Test
    void exitCode_failed_isTwo() {
        assertThat(RunnerOutcome.FAILED.exitCode()).isEqualTo(2);
    }
}
