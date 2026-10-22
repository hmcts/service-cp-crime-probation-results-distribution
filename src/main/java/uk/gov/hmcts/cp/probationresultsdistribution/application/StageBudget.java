package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A stage's time budget, measured from the stage's start (design §4.7, FR-002-013). A stage — the
 * payload fetch, the switch read, the send — runs within its own budget; an attempt or a retry may
 * start only if the wait before it plus the client's connect and read timeouts fits the remaining
 * time, the attempt-fits rule checked at the end of the wait. The budget carries only a deadline
 * instant, so it is a pure value: no clock, no mutation.
 */
public final class StageBudget {

    private final Instant deadlineAt;

    private StageBudget(final Instant deadline) {
        this.deadlineAt = Objects.requireNonNull(deadline, "deadline");
    }

    /**
     * A budget of {@code total} starting at {@code start}.
     *
     * @param start the stage's start instant
     * @param total the stage's total budget
     * @return the stage budget
     */
    public static StageBudget startingAt(final Instant start, final Duration total) {
        return new StageBudget(start.plus(total));
    }

    /**
     * A budget of {@code total} starting now.
     *
     * @param total the stage's total budget
     * @return the stage budget
     */
    public static StageBudget startingNow(final Duration total) {
        return new StageBudget(Instant.now().plus(total));
    }

    /**
     * Whether an attempt whose worst case is {@code worstCase}, starting at {@code now}, fits the
     * budget.
     *
     * @param now       the instant the attempt would start
     * @param worstCase the attempt's worst-case duration (wait plus connect plus read)
     * @return whether the attempt finishes on or before the deadline
     */
    public boolean attemptFits(final Instant now, final Duration worstCase) {
        return !now.plus(worstCase).isAfter(deadlineAt);
    }

    /**
     * The deadline instant the stage must finish by.
     *
     * @return the deadline
     */
    public Instant deadline() {
        return deadlineAt;
    }
}
