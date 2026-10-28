package uk.gov.hmcts.cp.probationresultsdistribution.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository.CloseInFlightResult;

/**
 * {@code rollback-close-in-flight} (design §9.4 step 4; slice 004, confirmed FR-004-049;
 * research.md Decision 2): refused {@code intake-not-paused} unless intake is paused — the same
 * guard purge-queue already uses — otherwise runs {@link RollbackFencingRepository#closeInFlight()}
 * exactly as FR-004-049a states. Takes no argument beyond confirming intake is paused; it closes
 * every in-flight row in the database, never a subset.
 */
public final class RollbackCloseInFlightAction {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackCloseInFlightAction.class);

    private final IntakePauseGate pauseGate;
    private final RollbackFencingRepository repository;

    /**
     * Creates the action.
     *
     * @param pauseGate  the reconciled operator-pause read
     * @param repository the close-in-flight statement
     */
    public RollbackCloseInFlightAction(final IntakePauseGate pauseGate,
                                       final RollbackFencingRepository repository) {
        this.pauseGate = pauseGate;
        this.repository = repository;
    }

    /**
     * Runs the close-in-flight statement.
     *
     * @return the bounded outcome
     */
    public RollbackCloseInFlightOutcome run() {
        final RollbackCloseInFlightOutcome outcome;
        if (pauseGate.pausedByOperator()) {
            final CloseInFlightResult result = repository.closeInFlight();
            LOG.info("rollback-close-in-flight done pendingClosed={} parkedClosed={}",
                    result.pendingClosed(), result.parkedClosed());
            outcome = new RollbackCloseInFlightOutcome.Done(result);
        } else {
            LOG.warn("rollback-close-in-flight refused reason=intake-not-paused");
            outcome = new RollbackCloseInFlightOutcome.Refused(WholeRunRefusal.INTAKE_NOT_PAUSED);
        }
        return outcome;
    }
}
