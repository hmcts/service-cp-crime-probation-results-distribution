package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.List;

/**
 * Composes {@link GoNoGoEvidenceQuery} (User Story 2) and {@link DailyReconciliationQuery} (User
 * Story 5) results into §9.6 row 1's post-soak acceptance evidence (slice 006, FR-006-047). Adds
 * no new read path of its own: every input is a result the caller already obtained from those two
 * queries, one call per soak day.
 */
public final class DecommissioningEvidenceQuery {

    private DecommissioningEvidenceQuery() {
    }

    /**
     * Composes the soak period's daily counts and the externally-obtained conditions into §9.6
     * row 1's evidence.
     *
     * @param soakDays             the soak period's daily reconciliation counts, one per day
     * @param section95Green       whether every §9.5 row read green across the soak period
     * @param deadLetterQueueEmpty whether the dead-letter queue was empty at the check
     * @param alertingDeployed     whether P36's rules, routing, owner and runbook are deployed
     * @return the composed evidence
     */
    public static DecommissioningEvidence compose(final List<DailyCounts> soakDays,
            final boolean section95Green, final boolean deadLetterQueueEmpty,
            final boolean alertingDeployed) {
        final int unexplainedGap =
                soakDays.stream().mapToInt(DailyCounts::expectedGap).sum();
        return new DecommissioningEvidence(section95Green, unexplainedGap, deadLetterQueueEmpty,
                alertingDeployed);
    }
}
