package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

/**
 * §9.6 row 1's post-soak acceptance evidence (slice 006, FR-006-047): §9.5 green for the agreed
 * soak period; no unexplained reconciliation gap; the dead-letter queue empty; and P36's alerting
 * deployed (its rules, routing, an owner and a runbook).
 *
 * @param section95Green           whether every §9.5 row read green across the soak period
 * @param unexplainedGap           the summed expected gap (PARKED+PENDING) across the soak
 *                                 period's days; zero is the accepting value
 * @param deadLetterQueueEmpty     whether the dead-letter queue was empty at the check
 * @param alertingDeployed         whether P36's rules, routing, owner and runbook are deployed
 */
public record DecommissioningEvidence(
        boolean section95Green,
        int unexplainedGap,
        boolean deadLetterQueueEmpty,
        boolean alertingDeployed) {

    /** Rejects a negative gap. */
    public DecommissioningEvidence {
        if (unexplainedGap < 0) {
            throw new IllegalArgumentException("unexplainedGap must not be negative");
        }
    }

    /**
     * Whether every §9.6 row 1 condition is met.
     *
     * @return {@code true} only when all four conditions hold
     */
    public boolean accepted() {
        return section95Green && unexplainedGap == 0 && deadLetterQueueEmpty && alertingDeployed;
    }
}
