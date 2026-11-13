package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Duration;

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
     * Whether the current pause, if any, has lasted at least {@code window} (review of PR #8/#23, M3
     * and round-4 follow-ups): a standalone operator command has no running pod's confirmed,
     * drain-aware flag to read, so it derives the same confirmation from {@code paused_at} instead
     * ({@code application.ConfirmedIntakePauseGate}). The comparison is computed entirely in SQL,
     * against the database's own clock, so a confirmed read never depends on clock agreement
     * between the caller and the database.
     *
     * @param window how long {@code paused_at} must already be in the past to confirm the pause —
     *               the store probe interval plus the processing deadline
     * @return {@code true} only when paused and {@code paused_at + window <= now()}
     */
    boolean pausedForAtLeast(Duration window);

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
