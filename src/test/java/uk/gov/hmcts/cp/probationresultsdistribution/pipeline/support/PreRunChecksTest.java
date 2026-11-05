package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins FR-006-008's five pre-run checks (a)-(e): any failure stops the run and is recorded. */
@DisplayName("PreRunChecks")
class PreRunChecksTest {

    private static final PreRunContext ALL_GOOD = new PreRunContext(
            false, false, true, true, true);

    @Test
    @DisplayName("every check passes and is recorded when STE is correctly configured")
    void every_check_passes_when_correctly_configured() {
        final List<PreRunCheckResult> results = PreRunChecks.evaluate(ALL_GOOD);

        assertThat(results).hasSize(5);
        assertThat(results).allMatch(PreRunCheckResult::passed);
        assertThat(PreRunChecks.allPassed(results)).isTrue();
    }

    @Test
    @DisplayName("check (a) fails when STE's EMYouthFilter reads active")
    void check_a_fails_when_youth_filter_reads_active() {
        final PreRunContext context = new PreRunContext(true, true, true, true, true);

        final List<PreRunCheckResult> results = PreRunChecks.evaluate(context);

        assertThat(PreRunChecks.allPassed(results)).isFalse();
        assertThat(results).filteredOn(result -> "a".equals(result.checkId()))
                .extracting(PreRunCheckResult::passed).containsExactly(false);
    }

    @Test
    @DisplayName("check (b) fails when the configured default does not equal the live reading")
    void check_b_fails_when_default_does_not_equal_live_reading() {
        final PreRunContext context = new PreRunContext(false, true, true, true, true);

        final List<PreRunCheckResult> results = PreRunChecks.evaluate(context);

        assertThat(PreRunChecks.allPassed(results)).isFalse();
        assertThat(results).filteredOn(result -> "b".equals(result.checkId()))
                .extracting(PreRunCheckResult::passed).containsExactly(false);
    }

    @Test
    @DisplayName("check (c) fails when the switch is unreadable or was defaulted")
    void check_c_fails_when_unreadable_or_defaulted() {
        final PreRunContext context = new PreRunContext(false, false, false, true, true);

        final List<PreRunCheckResult> results = PreRunChecks.evaluate(context);

        assertThat(PreRunChecks.allPassed(results)).isFalse();
        assertThat(results).filteredOn(result -> "c".equals(result.checkId()))
                .extracting(PreRunCheckResult::passed).containsExactly(false);
    }

    @Test
    @DisplayName("check (d) fails when the HMPPS target is not the stub")
    void check_d_fails_when_target_is_not_the_stub() {
        final PreRunContext context = new PreRunContext(false, false, true, false, true);

        final List<PreRunCheckResult> results = PreRunChecks.evaluate(context);

        assertThat(PreRunChecks.allPassed(results)).isFalse();
        assertThat(results).filteredOn(result -> "d".equals(result.checkId()))
                .extracting(PreRunCheckResult::passed).containsExactly(false);
    }

    @Test
    @DisplayName("check (e) fails when the stub is not reached over https")
    void check_e_fails_when_stub_not_reached_over_https() {
        final PreRunContext context = new PreRunContext(false, false, true, true, false);

        final List<PreRunCheckResult> results = PreRunChecks.evaluate(context);

        assertThat(PreRunChecks.allPassed(results)).isFalse();
        assertThat(results).filteredOn(result -> "e".equals(result.checkId()))
                .extracting(PreRunCheckResult::passed).containsExactly(false);
    }
}
