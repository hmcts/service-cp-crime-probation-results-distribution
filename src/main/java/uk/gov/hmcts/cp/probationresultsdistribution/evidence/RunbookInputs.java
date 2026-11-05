package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.List;
import java.util.Optional;

/**
 * The 21 service-side steps of the DevOps cutover/rollback runbook, across its four procedures
 * (slice 006, FR-006-037 to FR-006-046; data-model.md). Source of truth for
 * {@code doc/operations/cutover-rollback-runbook-inputs.md}, which mirrors it for the owning team
 * and operations to read without reading Java.
 *
 * <p>Planned rollback step 5 names {@code DailyReconciliationQuery}'s legacy-silence check, which
 * also needs {@code AlertCatalogue} alert 11's query definition (User Story 6, later in this same
 * slice, per FR-006 affirmation that the two share one telemetry-line definition); its read-back
 * is corrected in place, same as one row of {@link GoNoGoEvidenceIndex}, once that story lands.
 * Urgent rollback step 5 and roll-forward step 2 need only {@code DailyReconciliationQuery}
 * itself (User Story 5), already built.
 */
public final class RunbookInputs {

    private static final String CUTOVER = "cutover";
    private static final String PLANNED_ROLLBACK = "planned-rollback";
    private static final String URGENT_ROLLBACK = "urgent-rollback";
    private static final String ROLL_FORWARD = "roll-forward";
    private static final String OPERATIONS = "operations";
    private static final String DEVOPS = "DevOps";

    private RunbookInputs() {
    }

    /**
     * All 21 steps, in the design's order: cutover (2), planned rollback (9, including 2a and
     * 6a), urgent rollback (6), roll-forward (4) — SC-006-007.
     *
     * @return the 21-step catalogue
     */
    public static List<RunbookInputStep> steps() {
        return List.of(
                // Cutover — FR-006-038
                step(CUTOVER, "1", DEVOPS,
                        "Record the legacy subscription's current filter (included event types, "
                                + "subject filters, advanced filters), then set its included event "
                                + "type to TOGGLED-OFF",
                        Optional.empty(),
                        "the subscription's filter read back TOGGLED-OFF (DevOps' own evidence, "
                                + "external to this repository)",
                        Optional.of("2")),
                step(CUTOVER, "2", OPERATIONS,
                        "Turn the Results flag on under the environment's label, and read it back "
                                + "for that label; wait at least 10 minutes before Common Platform "
                                + "comes back up",
                        Optional.empty(),
                        "the flag read back ON for the label (the results team's own evidence); "
                                + "routing is proven by GoNoGoEvidenceQuery.testCallEvidence() once "
                                + "Common Platform is up",
                        Optional.empty()),
                // Planned rollback — FR-006-039
                step(PLANNED_ROLLBACK, "1", OPERATIONS, "Pause intake",
                        Optional.of("pause-intake (CLI runner / operations API)"),
                        "probation_intake_suspended{cause=\"operator\"} reads 1 "
                                + "(IntakePauseResumeTest)",
                        Optional.of("2")),
                step(PLANNED_ROLLBACK, "2", OPERATIONS,
                        "Turn the Results flag off, read it back for the label, and wait at least "
                                + "10 minutes",
                        Optional.empty(),
                        "the flag read back OFF for the label (the results team's own evidence)",
                        Optional.of("2a")),
                step(PLANNED_ROLLBACK, "2a", DEVOPS, "Restore exactly the filter recorded before cutover",
                        Optional.empty(),
                        "the subscription's filter read back matching step 1's cutover recording "
                                + "(DevOps' own evidence)",
                        Optional.of("3")),
                step(PLANNED_ROLLBACK, "3", OPERATIONS, "Record the cut-off instant", Optional.empty(),
                        "the recorded cut-off instant (operator's own record)", Optional.of("4")),
                step(PLANNED_ROLLBACK, "4", OPERATIONS,
                        "Run rollback-close-in-flight, closing the in-flight rows "
                                + "FAILED(rolled-back), one transaction per row, fencing late runners",
                        Optional.of("rollback-close-in-flight (CLI runner)"),
                        "the runner's own recorded outcome (pendingClosed/parkedClosed counts); "
                                + "RollbackFencingIT's pinned invariant — no leg PENDING/PARKED, "
                                + "RECEIVED/RETRYING now FAILED(rolled-back), a FAILED request keeps "
                                + "its reason and exhausted id, no COMPLETED request changed",
                        Optional.of("5")),
                step(PLANNED_ROLLBACK, "5", OPERATIONS,
                        "Reconcile the period the service was live, from cutover to the start of "
                                + "the rollback window; the legacy app must show no HMPPS send or "
                                + "orchestration in that period",
                        Optional.of("DailyReconciliationQuery's legacy-silence check (evidence "
                                + "query, User Story 6)"),
                        "DailyReconciliationQuery's legacy-silence check reads zero lines in that "
                                + "period (not yet built — corrected once User Story 6 lands)",
                        Optional.of("6")),
                step(PLANNED_ROLLBACK, "6", OPERATIONS,
                        "Purge the queue, recording the count removed and the cut-off; only after "
                                + "the reconciliation, and only while intake is paused",
                        Optional.of("purge-queue (CLI runner / operations API)"),
                        "the action's own recorded output: removed count and cutoff instant "
                                + "(PurgeQueueActionTest)",
                        Optional.of("6a")),
                step(PLANNED_ROLLBACK, "6a", OPERATIONS,
                        "Once the Results switch is on again, replay goes only through the "
                                + "service's replay action: the operations API (005), with its "
                                + "runner as the fallback; the dead-letter queue holds no copy",
                        Optional.of("replay (operations API POST "
                                + "/operations/requests/{requestId}/replay; CLI runner fallback)"),
                        "the replay action's own recorded outcome: Enqueued with messageId, or a "
                                + "bounded refusal (ReplayActionTest)",
                        Optional.of("7")),
                step(PLANNED_ROLLBACK, "7", OPERATIONS,
                        "Repair only the hearings not known to be delivered",
                        Optional.of("submit-hearing / resend-leg (CLI runners / operations API), "
                                + "per FR-006-041's routing rules"),
                        "the chosen repair action's own recorded outcome "
                                + "(SubmitHearingActionTest / LegResenderTest)",
                        Optional.empty()),
                // Urgent rollback — FR-006-042
                step(URGENT_ROLLBACK, "1", OPERATIONS, "Pause intake",
                        Optional.of("pause-intake (CLI runner / operations API)"),
                        "probation_intake_suspended{cause=\"operator\"} reads 1 "
                                + "(IntakePauseResumeTest)",
                        Optional.of("2")),
                step(URGENT_ROLLBACK, "2", DEVOPS,
                        "Restore the recorded filter at once, and record the time T; from T the "
                                + "function app handles every new share",
                        Optional.empty(),
                        "the subscription's filter read back restored; the recorded instant T "
                                + "(DevOps' own record)",
                        Optional.of("3")),
                step(URGENT_ROLLBACK, "3", OPERATIONS,
                        "Turn the Results flag off and wait at least 10 minutes; meanwhile Results "
                                + "keeps publishing to the paused queue",
                        Optional.empty(),
                        "the flag read back OFF for the label (the results team's own evidence)",
                        Optional.of("4")),
                step(URGENT_ROLLBACK, "4", OPERATIONS, "Close the in-flight sends, as in planned step 4",
                        Optional.of("rollback-close-in-flight (CLI runner)"),
                        "the runner's own recorded outcome; RollbackFencingIT's pinned invariant",
                        Optional.of("5")),
                step(URGENT_ROLLBACK, "5", OPERATIONS,
                        "Reconcile the paused queue against T: messages for shares before T reached "
                                + "neither path and are repaired by a court re-share; messages for "
                                + "shares after T were handled by the function app and are purged; "
                                + "intake is not resumed; then do planned steps 5-7",
                        Optional.of("DailyReconciliationQuery (evidence query, User Story 5)"),
                        "DailyReconciliationQuery.countsFor(), partitioned at T "
                                + "(DailyReconciliationQueryIT)",
                        Optional.of("6")),
                step(URGENT_ROLLBACK, "6", OPERATIONS,
                        "Purge the queue, recording the count and the cut-off",
                        Optional.of("purge-queue (CLI runner / operations API)"),
                        "the action's own recorded output: removed count and cutoff instant "
                                + "(PurgeQueueActionTest)",
                        Optional.empty()),
                // Roll-forward — FR-006-043
                step(ROLL_FORWARD, "1", OPERATIONS, "purge-queue, with intake still paused",
                        Optional.of("purge-queue (CLI runner / operations API)"),
                        "the action's own recorded output: removed count and cutoff instant "
                                + "(PurgeQueueActionTest)",
                        Optional.of("2")),
                step(ROLL_FORWARD, "2", OPERATIONS,
                        "Reconcile every message received since the recorded cut-off",
                        Optional.of("DailyReconciliationQuery (evidence query, User Story 5)"),
                        "DailyReconciliationQuery.countsFor(), since the cut-off "
                                + "(DailyReconciliationQueryIT)",
                        Optional.of("3")),
                step(ROLL_FORWARD, "3", DEVOPS,
                        "Run a full new cutover in a weekend window: the subscription off, then the "
                                + "Results flag on, then the 10-minute wait",
                        Optional.empty(),
                        "the subscription's filter read back TOGGLED-OFF; the flag read back ON for "
                                + "the label (DevOps'/the results team's own evidence)",
                        Optional.of("4")),
                step(ROLL_FORWARD, "4", OPERATIONS, "Only then resume intake",
                        Optional.of("resume-intake (CLI runner / operations API)"),
                        "probation_intake_suspended{cause=\"operator\"} reads 0 "
                                + "(IntakePauseResumeTest)",
                        Optional.empty()));
    }

    private static RunbookInputStep step(final String procedure, final String stepNumber,
            final String actor, final String action, final Optional<String> serviceCapability,
            final String readBack, final Optional<String> precedes) {
        return new RunbookInputStep(procedure, stepNumber, actor, action, serviceCapability,
                readBack, precedes);
    }
}
