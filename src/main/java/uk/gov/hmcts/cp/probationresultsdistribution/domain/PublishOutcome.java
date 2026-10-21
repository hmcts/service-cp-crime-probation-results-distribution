package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.OptionalInt;

/**
 * The outcome the HMPPS sender returns, never throwing past it (design §9, FR-002-037). Only the
 * runner maps these to a leg (status, reason). {@link Sent} and {@link FailedNonTransient} always
 * carry the gateway status; {@link TransientExhausted} may have none, for a connection failure that
 * never reached the gateway.
 */
public sealed interface PublishOutcome
        permits PublishOutcome.Sent,
                PublishOutcome.FailedNonTransient,
                PublishOutcome.TransientExhausted {

    /**
     * A 2xx answer: the body was sent.
     *
     * @param httpStatus the 2xx status the gateway answered
     */
    record Sent(int httpStatus) implements PublishOutcome {
    }

    /**
     * A 4xx other than 408/429: the receiver rejected the body, terminally.
     *
     * @param httpStatus the 4xx status the gateway answered
     */
    record FailedNonTransient(int httpStatus) implements PublishOutcome {
    }

    /**
     * An outcome-unknown answer, or a connection failure or 429 that did not recover in-run: the
     * leg parks, or fails on the final permitted delivery. The HMPPS POST is not idempotent, so it
     * is never retried in-run past this outcome.
     *
     * @param httpStatus the last status the gateway answered, or empty for a connection failure
     */
    record TransientExhausted(OptionalInt httpStatus) implements PublishOutcome {
    }
}
