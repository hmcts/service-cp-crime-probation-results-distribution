package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

/** Shared retry decisions for the HMPPS gateway, adapted from YOT's retry policy. */
public final class RetryPolicy {

    private static final Pattern DELTA_SECONDS = Pattern.compile("[0-9]{1,10}");
    private static final int MIN_ATTEMPTS = 1;

    private final int attemptLimit;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration attemptWorstCase;
    private final IntPredicate retryableStatus;

    /** Creates a retry policy with a caller-owned, fixed status predicate. */
    public RetryPolicy(final int maxAttempts, final Duration initialBackoff,
                       final Duration maxBackoff, final Duration attemptWorstCase,
                       final IntPredicate retryableStatus) {
        if (maxAttempts < MIN_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
        this.attemptLimit = maxAttempts;
        this.initialBackoff = Objects.requireNonNull(initialBackoff);
        this.maxBackoff = Objects.requireNonNull(maxBackoff);
        this.attemptWorstCase = Objects.requireNonNull(attemptWorstCase);
        this.retryableStatus = Objects.requireNonNull(retryableStatus);
    }

    /** The maximum total attempts, including the first. */
    public int maxAttempts() {
        return attemptLimit;
    }

    /** Whether the caller's fixed predicate permits a retry of this response. */
    public boolean retryable(final int status) {
        return retryableStatus.test(status);
    }

    /** The bounded wait before the next attempt. */
    public Duration waitAfter(final int attemptsTaken, final String retryAfter) {
        final String header = retryAfter == null ? "" : retryAfter.trim();
        Duration wait;
        if (DELTA_SECONDS.matcher(header).matches()) {
            wait = Duration.ofSeconds(Long.parseLong(header));
        } else {
            wait = initialBackoff;
            for (int doubled = 1; doubled < attemptsTaken && wait.compareTo(maxBackoff) < 0;
                    doubled++) {
                wait = wait.multipliedBy(2);
            }
        }
        return cap(wait);
    }

    /** Whether the worst-case cost of an attempt fits before the deadline. */
    public boolean attemptFitsBefore(final Instant now, final Instant deadline) {
        return !now.plus(attemptWorstCase).isAfter(deadline);
    }

    private Duration cap(final Duration wait) {
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }
}
