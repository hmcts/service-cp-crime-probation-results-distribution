package uk.gov.hmcts.cp.probationresultsdistribution.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * Pause and resume intake (design §4.8 action 4; slice 004, FR-004-051 — provisional, FR-004-052).
 * The service's only stop: it writes {@code intake_control.paused_by_operator} first, inside the
 * repository's own transactional discipline, and is a no-op when intake is already in the
 * requested state. The store's own recovery from an outage never touches this row — pausing and a
 * database outage are separate holds, so a store outage can never lift an operator pause.
 */
public final class IntakePauseAction {

    private static final Logger LOG = LoggerFactory.getLogger(IntakePauseAction.class);

    private final IntakeControlRepository repository;

    /**
     * Creates the action.
     *
     * @param repository the persisted intake-control store
     */
    public IntakePauseAction(final IntakeControlRepository repository) {
        this.repository = repository;
    }

    /**
     * Pauses intake.
     *
     * @return {@code Done}, or {@code NoOp} when already paused
     */
    public IntakeControlOutcome pause() {
        return write(repository::pause, "pause");
    }

    /**
     * Resumes intake.
     *
     * @return {@code Done}, or {@code NoOp} when already running
     */
    public IntakeControlOutcome resume() {
        return write(repository::resume, "resume");
    }

    private IntakeControlOutcome write(final BooleanWrite write, final String action) {
        IntakeControlOutcome outcome;
        try {
            if (write.apply()) {
                LOG.info("{} outcome=done", action);
                outcome = new IntakeControlOutcome.Done();
            } else {
                LOG.info("{} outcome=no-op", action);
                outcome = new IntakeControlOutcome.NoOp();
            }
        } catch (final DataAccessException storeFailure) {
            LOG.error("{} failed reason=store-unavailable", action);
            outcome = new IntakeControlOutcome.StoreUnavailable();
        }
        return outcome;
    }

    /** A repository write that answers whether it actually changed the persisted state. */
    @FunctionalInterface
    private interface BooleanWrite {
        boolean apply();
    }
}
