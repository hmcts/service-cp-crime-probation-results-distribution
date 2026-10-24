package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

/**
 * A partition statement failed, classified at the throw site (technical-rules.md, Error Handling)
 * into one of {@link MaintenanceFailureCause}'s bounded causes, for the caller to log at ERROR with
 * its step and count under {@code BodyMaintenanceStep} (FR-003-017).
 */
public final class PartitionMaintenanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final MaintenanceFailureCause failureCause;

    /**
     * Creates the exception with its classified cause.
     *
     * @param failureCause  the bounded cause this failure is classified under
     * @param message       a human-readable description of what failed
     * @param originalCause the underlying exception this was classified from
     */
    // Raw database messages and causes may quote a stored body; retain only the bounded code.
    public PartitionMaintenanceException(final MaintenanceFailureCause failureCause,
                                         final String message, final Throwable originalCause) {
        super("Body maintenance failed: " + failureCause.wireValue());
        this.failureCause = failureCause;
    }

    /**
     * The bounded cause this failure is classified under.
     *
     * @return the cause
     */
    public MaintenanceFailureCause cause() {
        return failureCause;
    }
}
