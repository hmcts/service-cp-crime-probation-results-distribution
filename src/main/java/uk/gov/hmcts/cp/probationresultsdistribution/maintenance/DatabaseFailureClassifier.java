package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.sql.SQLException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

/**
 * Classifies a raw database failure into one of this slice's bounded {@link MaintenanceFailureCause}
 * values, shared by every statement path ({@link PartitionMaintenanceRepository},
 * {@link PartitionCatalogue}, {@link SingleRunnerLock}) so the same underlying failure is reported
 * the same way regardless of which statement hit it, and its raw message never escapes
 * (technical-rules.md, Error Handling).
 */
final class DatabaseFailureClassifier {

    private DatabaseFailureClassifier() {
    }

    @SuppressWarnings("PMD.CommentDefaultAccessModifier")
    static PartitionMaintenanceException classify(final Throwable failure, final String action) {
        final String message = rootMessage(failure);
        final MaintenanceFailureCause cause;
        if (failure instanceof DataAccessResourceFailureException || connectionFailure(failure)) {
            cause = MaintenanceFailureCause.STORE_UNAVAILABLE;
        } else if (message.contains("would overlap partition")) {
            cause = MaintenanceFailureCause.PARTITION_OVERLAP;
        } else if (message.contains("default partition") && message.contains("would be violated")) {
            cause = MaintenanceFailureCause.DEFAULT_HAS_ROWS;
        } else if (failure instanceof QueryTimeoutException
                || message.contains("statement timeout") || message.contains("lock timeout")
                || message.contains("canceling statement due to lock timeout")) {
            cause = MaintenanceFailureCause.LOCK_TIMEOUT;
        } else {
            cause = MaintenanceFailureCause.OTHER;
        }
        return new PartitionMaintenanceException(cause, "Failed " + action, failure);
    }

    private static boolean connectionFailure(final Throwable failure) {
        boolean disconnected = failure instanceof CannotCreateTransactionException;
        Throwable cursor = failure;
        while (cursor != null) {
            if (cursor instanceof SQLException sql && sql.getSQLState() != null) {
                disconnected |= sql.getSQLState().startsWith("08");
            }
            cursor = cursor.getCause();
        }
        return disconnected;
    }

    private static String rootMessage(final Throwable failure) {
        Throwable cursor = failure;
        while (cursor.getCause() != null) {
            cursor = cursor.getCause();
        }
        return cursor.getMessage() == null ? "" : cursor.getMessage();
    }
}
