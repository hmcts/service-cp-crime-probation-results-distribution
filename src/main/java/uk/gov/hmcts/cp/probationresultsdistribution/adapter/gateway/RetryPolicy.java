package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.function.IntPredicate;

/** Shared retry decisions for the HMPPS gateway, adapted from YOT's retry policy. */
public final class RetryPolicy {

    /** Creates a retry policy with a caller-owned, fixed status predicate. */
    public RetryPolicy(final int maxAttempts, final Duration initialBackoff,
                       final Duration maxBackoff, final Duration attemptWorstCase,
                       final IntPredicate retryableStatus) {
    }

    /** The maximum total attempts, including the first. */
    public int maxAttempts() {
        throw new UnsupportedOperationException("retry policy not implemented");
    }

    /** Whether the caller's fixed predicate permits a retry of this response. */
    public boolean retryable(final int status) {
        throw new UnsupportedOperationException("retry policy not implemented");
    }

    /** The bounded wait before the next attempt. */
    public Duration waitAfter(final int attemptsTaken, final String retryAfter) {
        throw new UnsupportedOperationException("retry policy not implemented");
    }

    /** Whether the worst-case cost of an attempt fits before the deadline. */
    public boolean attemptFitsBefore(final Instant now, final Instant deadline) {
        throw new UnsupportedOperationException("retry policy not implemented");
    }
}
