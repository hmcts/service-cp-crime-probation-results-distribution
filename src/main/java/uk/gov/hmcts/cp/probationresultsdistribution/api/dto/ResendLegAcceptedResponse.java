package uk.gov.hmcts.cp.probationresultsdistribution.api.dto;

import java.util.UUID;

/**
 * The resend-leg endpoint's {@code 202} body (design row A3-56; slice 005, FR-005-015; research.md
 * Decision 4). Carries only the run id: no per-ID detail is necessarily known yet when the call is
 * accepted. The full report lives in slice 004's run-report log line and the attempt rows.
 *
 * @param resendRunId the run's id, assigned by slice 004 at claim time
 */
public record ResendLegAcceptedResponse(UUID resendRunId) {
}
