package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;

/**
 * The resend-leg request after {@link OperationsRequestMapper} has parsed and bound-checked every
 * field (FR-005-010, FR-005-011, FR-005-016). {@code requestIds} is exactly as sent — no collapse,
 * no cap, no reorder (FR-005-011, FR-005-013): slice 004's resend-leg capability applies those
 * rules.
 *
 * @param requestIds            the list as sent, each parsed to its canonical {@link UUID}
 * @param leg                   the leg, {@link Leg#HMPPS} only
 * @param operator              the caller's name for the record, 1 to 200 characters
 * @param reason                the reason for the record, 1 to 200 characters
 * @param allowTerminalSuccess  the forced-resend override
 * @param allowChangedPayload   the changed-payload override
 */
public record ParsedResendLegCommand(List<UUID> requestIds, Leg leg, String operator, String reason,
                                      boolean allowTerminalSuccess, boolean allowChangedPayload) {
}
