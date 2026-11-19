package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.util.Objects;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * The {@code close-expired-resend} statement (H40; slice 004, FR-004-059). In one transaction,
 * under the request-row then leg-row lock order already fixed for the rollback close-in-flight
 * statement ({@link RollbackFencingRepository}, FR-004-049a) and for resend's own claim
 * ({@link ProcessedRequestRepository#claimResend}), it closes the single leg row named by
 * {@code requestId} when it is a {@code PENDING} resend row with an expired lease: the row
 * {@code FAILED(rolled-back)}, exactly as the rollback statement's own resend-row case, then
 * appends the operator and the sanitised reason to {@code processed_request.audit_note}.
 * {@code processed_request.status} is never touched.
 */
public interface CloseExpiredResendRepository {

    /**
     * Closes the one row, or refuses, changing nothing.
     *
     * @param requestId the request id
     * @param operator  the operator, already sanitised to one line of at most 200 characters
     * @param reason    the reason, already sanitised to one line of at most 200 characters
     * @return the outcome
     */
    CloseExpiredResendResult close(UUID requestId, String operator, String reason);

    /**
     * The result of one {@link #close} call: the row was closed, or the call was refused with
     * nothing changed.
     */
    sealed interface CloseExpiredResendResult
            permits CloseExpiredResendResult.Closed, CloseExpiredResendResult.Refused {

        /** The row was closed {@code FAILED(rolled-back)}. */
        record Closed() implements CloseExpiredResendResult {
        }

        /**
         * The call was refused; nothing changed.
         *
         * @param reason the refusal code ({@code unknown-request}, {@code not-eligible} or
         *               {@code resend-in-progress})
         */
        record Refused(ResendRefusal reason) implements CloseExpiredResendResult {

            /** Rejects a null reason. */
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }
}
