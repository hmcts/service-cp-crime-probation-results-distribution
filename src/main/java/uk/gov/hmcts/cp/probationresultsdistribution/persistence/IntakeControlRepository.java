package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

/**
 * The {@code intake_control} single-row store (slice 004, provisional FR-004-052; research.md
 * Decision 3; data-model.md). It is the durable record of an operator's pause, read by every pod on
 * startup and on a schedule to reconcile {@code ConsumerLifecycleController}'s in-memory flag, and
 * written only by the pause and resume actions.
 */
public interface IntakeControlRepository {

    /**
     * The persisted pause state.
     *
     * @return {@code true} while an operator has paused intake
     */
    boolean pausedByOperator();

    /**
     * Pauses intake, recording {@code paused_at}. A no-op when already paused.
     *
     * @return {@code true} when this call actually paused intake; {@code false} when it was already
     *     paused (FR-004-051's no-op)
     */
    boolean pause();

    /**
     * Resumes intake, clearing {@code paused_at}. A no-op when already running.
     *
     * @return {@code true} when this call actually resumed intake; {@code false} when it was already
     *     running (FR-004-051's no-op)
     */
    boolean resume();
}
