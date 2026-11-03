package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.List;

/**
 * Evaluates FR-006-008's five pre-run checks over a caller-supplied {@link PreRunContext}. Every
 * check MUST be recorded, whether it passes or fails; the replay does not start while any check
 * has failed (User Story 1, acceptance scenario 7).
 */
public final class PreRunChecks {

    private PreRunChecks() {
    }

    /**
     * Evaluates all five checks.
     *
     * @param context what was read from STE and the service's pod
     * @return one result per check, in order (a)-(e)
     */
    public static List<PreRunCheckResult> evaluate(final PreRunContext context) {
        return List.of(
                new PreRunCheckResult("a", !context.steEmYouthFilterActive(),
                        "EMYouthFilter active=" + context.steEmYouthFilterActive()),
                new PreRunCheckResult("b",
                        context.configuredDefaultActive() == context.steEmYouthFilterActive(),
                        "configuredDefaultActive=" + context.configuredDefaultActive()
                                + " liveReadingActive=" + context.steEmYouthFilterActive()),
                new PreRunCheckResult("c", context.switchReadableNotDefaulted(),
                        "switchReadableNotDefaulted=" + context.switchReadableNotDefaulted()),
                new PreRunCheckResult("d", context.hmppsTargetIsStubAnswering200NoForward(),
                        "hmppsTargetIsStub=" + context.hmppsTargetIsStubAnswering200NoForward()),
                new PreRunCheckResult("e", context.stubReachedOverHttps(),
                        "stubReachedOverHttps=" + context.stubReachedOverHttps()));
    }

    /**
     * Whether every check passed.
     *
     * @param results the evaluated checks
     * @return {@code true} only when none failed
     */
    public static boolean allPassed(final List<PreRunCheckResult> results) {
        return results.stream().allMatch(PreRunCheckResult::passed);
    }
}
