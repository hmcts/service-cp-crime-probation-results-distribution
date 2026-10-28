package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * One claimed resend attempt row (design §4.4 claim contract (b), (d); slice 004, FR-004-025): the
 * {@code outbound_message} row resend-leg inserted {@code PENDING} at {@code max(attempt) + 1}, with
 * {@code attempt_origin = resend}, its own leg claim and the run's {@code resend_run_id}, committed
 * before any POST. The attempt's completion is guarded by {@link #legClaim()}'s token (FR-004-028):
 * a completion whose token no longer matches the row's changes nothing.
 *
 * @param attemptId   the {@code outbound_message.id} of the claimed row
 * @param attempt     the attempt number the row was inserted at
 * @param legClaim    the leg claim (owner and token) taken on the row
 * @param resendRunId the resend run that claimed it
 */
public record ResendAttempt(UUID attemptId, int attempt, RunClaim legClaim, UUID resendRunId) {

    /** Rejects null components. */
    public ResendAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(legClaim, "legClaim");
        Objects.requireNonNull(resendRunId, "resendRunId");
    }
}
