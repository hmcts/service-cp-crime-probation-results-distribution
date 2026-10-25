package uk.gov.hmcts.cp.probationresultsdistribution.api.dto;

import java.util.List;

/**
 * The resend-leg endpoint's request body (design row A3-56; slice 005, FR-005-010). Every field
 * arrives untyped (a raw string, not a {@code UUID} or a closed enum) so that {@link
 * uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsRequestMapper} — not Jackson's own
 * binding failure — produces the specific, bounded refusal FR-005-011 names for an unreadable
 * entry; Jackson is left only to reject an unknown field (FR-005-033).
 *
 * @param requestIds           the list as sent — no collapse, no cap, no reorder here
 *                             (FR-005-011, FR-005-013)
 * @param leg                  the leg, read as sent; only {@code "HMPPS"} is valid (FR-005-010)
 * @param operator             the caller's name for the record, required (FR-005-016)
 * @param reason               the reason for the record, required (FR-005-016)
 * @param allowTerminalSuccess the forced-resend override, {@code false} when absent (FR-005-017)
 * @param allowChangedPayload  the changed-payload override, {@code false} when absent
 *                             (FR-005-010)
 */
public record ResendLegRequest(List<String> requestIds, String leg, String operator, String reason,
                                boolean allowTerminalSuccess, boolean allowChangedPayload) {
}
