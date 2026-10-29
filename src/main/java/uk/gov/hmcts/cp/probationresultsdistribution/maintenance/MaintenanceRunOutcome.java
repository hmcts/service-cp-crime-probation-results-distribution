package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.util.List;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

/**
 * One run's results, for the run's own logging and metrics only — never persisted as table rows
 * (design's "three tables and one view" in {@code persistence} is unchanged by this slice). A run
 * where {@code lockAcquired} is {@code false} is the normal "another pod has it" case (User Story 3):
 * logged at DEBUG, not a {@link MaintenanceFailure}, and not counted on the failures metric.
 *
 * @param lockAcquired       whether this run took the single-runner lock
 * @param partitionsCreated  the months created this run, by partition name
 * @param partitionsDropped  the months dropped this run, by partition name
 * @param defaultRowsDeleted the number of stranded DEFAULT rows this run deleted
 * @param failures           every statement that failed this run
 */
public record MaintenanceRunOutcome(boolean lockAcquired, List<String> partitionsCreated,
                                    List<String> partitionsDropped, long defaultRowsDeleted,
                                    List<MaintenanceFailure> failures) {

    /**
     * One failed statement, for the run's ERROR line and failure count (FR-003-017).
     *
     * @param step   the bounded step the failure is counted under
     * @param cause  the bounded cause the failure's ERROR line carries
     * @param detail the month or other detail named on the ERROR line, never a stored body
     */
    public record MaintenanceFailure(BodyMaintenanceStep step, MaintenanceFailureCause cause,
                                     String detail) {
    }
}
