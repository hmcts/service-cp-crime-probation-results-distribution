package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Purge-queue (design §4.8 action 6; slice 004, FR-004-053). Refused {@code intake-not-paused}
 * unless intake is paused by an operator, read against the reconciled in-memory flag
 * ({@link IntakePauseGate}, research.md Decision 3) — the read-before-remove check — then removes
 * every message on {@code probation.requests} enqueued before the run's start instant, leaving the
 * dead-letter queue untouched.
 */
public final class PurgeQueueAction {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeQueueAction.class);

    private final IntakePauseGate pauseGate;
    private final QueuePurger purger;
    private final Clock clock;

    /**
     * Creates the action.
     *
     * @param pauseGate the reconciled operator-pause read
     * @param purger    the broker purge port
     * @param clock     the clock the run's cut-off instant is read from
     */
    public PurgeQueueAction(final IntakePauseGate pauseGate, final QueuePurger purger,
                            final Clock clock) {
        this.pauseGate = pauseGate;
        this.purger = purger;
        this.clock = clock;
    }

    /**
     * Runs the purge.
     *
     * @return the bounded outcome
     */
    public PurgeQueueOutcome purge() {
        PurgeQueueOutcome outcome;
        if (pauseGate.pausedByOperator()) {
            final Instant cutoff = clock.instant();
            try {
                final QueuePurger.PurgeResult result = purger.purgeBefore(cutoff);
                LOG.info("purge-queue done removed={} cutoff={}", result.removed(), cutoff);
                outcome = new PurgeQueueOutcome.Done(result.removed(), cutoff);
            } catch (final QueuePurger.QueuePurgeException brokerFailure) {
                LOG.error("purge-queue failed reason=broker-unavailable");
                outcome = new PurgeQueueOutcome.BrokerUnavailable();
            }
        } else {
            LOG.warn("purge-queue refused reason=intake-not-paused");
            outcome = new PurgeQueueOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED);
        }
        return outcome;
    }
}
