package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.CloseExpiredResendRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.CloseExpiredResendRepository.CloseExpiredResendResult;

/**
 * {@code close-expired-resend} (H40; slice 004, FR-004-059; research.md Decision 1, amended
 * 2026-11-09): the single-row route back for a {@code PENDING} resend leg row a dead runner left
 * behind, with no intake pause required — the guarded, whole-intake rollback close-in-flight
 * procedure ({@link RollbackCloseInFlightAction}, FR-004-049a) is no longer the only recovery for
 * this one case. {@code processed_request.status} is never touched: the request's own state
 * machine is untouched by this statement.
 *
 * <p>The operator and the reason are sanitised to one line each, exactly as {@link LegResender}'s
 * own {@code sanitised} rule, before being appended to {@code audit_note} by the repository
 * statement. The shape of the arguments themselves (missing, too long, an unreadable
 * {@code requestId}) is the command-line runner's own check (FR-004-020), run before this action is
 * ever reached.
 */
public final class CloseExpiredResendAction {

    private static final Logger LOG = LoggerFactory.getLogger(CloseExpiredResendAction.class);
    private static final Pattern LINE_BREAKS = Pattern.compile("\\p{Cntrl}+");

    private final CloseExpiredResendRepository repository;

    /**
     * Creates the action.
     *
     * @param repository the close-expired-resend statement
     */
    public CloseExpiredResendAction(final CloseExpiredResendRepository repository) {
        this.repository = repository;
    }

    /**
     * Runs the statement for one request id.
     *
     * @param requestId the request id
     * @param operator  the operator, not yet sanitised
     * @param reason    the reason, not yet sanitised
     * @return the bounded outcome
     */
    public CloseExpiredResendOutcome close(final UUID requestId, final String operator,
                                           final String reason) {
        Objects.requireNonNull(requestId, "requestId");
        final CloseExpiredResendResult result = repository.close(requestId, sanitised(operator),
                sanitised(reason));
        return switch (result) {
            case CloseExpiredResendResult.Closed() -> {
                LOG.info("close-expired-resend done requestId={}", requestId);
                yield new CloseExpiredResendOutcome.Done();
            }
            case CloseExpiredResendResult.Refused refused -> {
                LOG.warn("close-expired-resend refused requestId={} reason={}", requestId,
                        refused.reason().wireValue());
                yield new CloseExpiredResendOutcome.Refused(refused.reason());
            }
        };
    }

    private static String sanitised(final String text) {
        return LINE_BREAKS.matcher(text).replaceAll(" ").strip();
    }
}
