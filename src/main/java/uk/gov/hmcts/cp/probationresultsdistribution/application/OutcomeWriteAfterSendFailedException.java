package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The leg outcome write failed after a real send attempt to HMPPS (review of PR #8). Distinguished
 * from any other failure in the pipeline so a caller — {@link uk.gov.hmcts.cp.probationresultsdistribution
 * .inbound.ProbationMessageListener} — never treats it as safe to release the request claim and let
 * an immediate redelivery retry: the broker redelivers an abandoned message with no backoff of its
 * own, and the HMPPS POST is not idempotent (design H15), so retrying here could resend a hearing
 * HMPPS already received. The claim is left live, falling back to its lease's natural expiry, exactly
 * as it already does.
 */
public final class OutcomeWriteAfterSendFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Wraps the outcome write's own failure.
     *
     * @param cause the write failure
     */
    public OutcomeWriteAfterSendFailedException(final RuntimeException cause) {
        super("the leg outcome write failed after a completed send attempt", cause);
    }
}
