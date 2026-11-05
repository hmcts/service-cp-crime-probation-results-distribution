package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The bounded result of one command-line runner invocation (design §4.4 "Output" row;
 * FR-004-045).
 *
 * <p>A runner invocation performs its action and ends: it answers exactly one of these three in a
 * form a runbook step can test without reading free text, and maps to the process exit code a shell
 * script branches on ({@code contracts/cli-runners.md}).
 */
public enum RunnerOutcome {

    /** The action completed — enqueued, paused/resumed, purged, or printed a listing. */
    DONE(0),

    /** The action was refused, with nothing read, claimed or changed. */
    REFUSED(1),

    /** The action could not complete because of a store or broker failure. */
    FAILED(2);

    private final int code;

    RunnerOutcome(final int code) {
        this.code = code;
    }

    /**
     * The process exit code this outcome maps to.
     *
     * @return {@code 0} for {@link #DONE}, {@code 1} for {@link #REFUSED}, {@code 2} for
     *     {@link #FAILED}
     */
    public int exitCode() {
        return code;
    }
}
