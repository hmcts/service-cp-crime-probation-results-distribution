package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.sql.SQLException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

/**
 * A partition statement failed, classified at the throw site (technical-rules.md, Error Handling)
 * into one of {@link MaintenanceFailureCause}'s bounded causes, for the caller to log at ERROR with
 * its step and count under {@code BodyMaintenanceStep} (FR-003-017).
 */
public final class PartitionMaintenanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The {@code sqlState} an ERROR line carries when the driver gives none (A3-119). */
    public static final String UNAVAILABLE_SQL_STATE = "unavailable";

    private final MaintenanceFailureCause failureCause;
    private final String failureSqlState;

    /**
     * Creates the exception with its classified cause, extracting the originating failure's
     * {@code sqlState} for the ERROR line (A3-119): the five-character PostgreSQL code found
     * anywhere in the cause chain, or {@link #UNAVAILABLE_SQL_STATE} when none is present.
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
        this.failureSqlState = extractSqlState(originalCause);
    }

    /**
     * The bounded cause this failure is classified under.
     *
     * @return the cause
     */
    public MaintenanceFailureCause cause() {
        return failureCause;
    }

    /**
     * The failure's {@code sqlState}, for the ERROR line (A3-119, FR-003-017).
     *
     * @return the five-character PostgreSQL code, or {@link #UNAVAILABLE_SQL_STATE} when none was
     *         available
     */
    public String sqlState() {
        return failureSqlState;
    }

    @SuppressWarnings("PMD.OnlyOneReturn")
    private static String extractSqlState(final Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof SQLException sqlException
                    && sqlException.getSQLState() != null && !sqlException.getSQLState().isBlank()) {
                return sqlException.getSQLState();
            }
            cursor = cursor.getCause();
        }
        return UNAVAILABLE_SQL_STATE;
    }
}
