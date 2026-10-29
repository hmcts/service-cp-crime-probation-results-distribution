package uk.gov.hmcts.cp.probationresultsdistribution.api.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The resend-leg endpoint's request body (design row A3-56; slice 005, FR-005-010). Every field
 * arrives untyped (a raw string, not a {@code UUID} or a closed enum) so that {@link
 * uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsRequestMapper} — not Jackson's own
 * binding failure — produces the specific, bounded refusal FR-005-011 names for an unreadable
 * entry.
 *
 * <p>The body is read through {@link #fromJson}. Its overrides are nullable, so that an absent
 * override is {@code false} (FR-005-010): Jackson 3 enables {@code FAIL_ON_NULL_FOR_PRIMITIVES} by
 * default, and a record's primitive {@code boolean} components could not be left out otherwise.
 * Every field the contract does not name is collected by name only, never by value, into
 * {@code unknownFields}, which the mapper refuses {@code unknown-field} (FR-005-033): the serving
 * context's JSON converter ignores unknown properties, so Jackson itself never rejects one.
 *
 * @param requestIds           the list as sent — no collapse, no cap, no reorder here
 *                             (FR-005-011, FR-005-013)
 * @param leg                  the leg, read as sent; only {@code "HMPPS"} is valid (FR-005-010)
 * @param operator             the caller's name for the record, required (FR-005-016)
 * @param reason               the reason for the record, required (FR-005-016)
 * @param allowTerminalSuccess the forced-resend override, {@code false} when absent (FR-005-017)
 * @param allowChangedPayload  the changed-payload override, {@code false} when absent
 *                             (FR-005-010)
 * @param unknownFields        the names of any fields the contract does not name, in body order
 */
public record ResendLegRequest(List<String> requestIds, String leg, String operator, String reason,
                                boolean allowTerminalSuccess, boolean allowChangedPayload,
                                List<String> unknownFields) {

    /** Defensively copies the unknown field names; {@code null} is none. */
    public ResendLegRequest {
        unknownFields = unknownFields == null ? List.of() : List.copyOf(unknownFields);
    }

    /**
     * A request naming only the contract's fields.
     *
     * @param requestIds           the list as sent
     * @param leg                  the leg as sent
     * @param operator             the operator as sent
     * @param reason               the reason as sent
     * @param allowTerminalSuccess the forced-resend override
     * @param allowChangedPayload  the changed-payload override
     */
    public ResendLegRequest(final List<String> requestIds, final String leg, final String operator,
                            final String reason, final boolean allowTerminalSuccess,
                            final boolean allowChangedPayload) {
        this(requestIds, leg, operator, reason, allowTerminalSuccess, allowChangedPayload, List.of());
    }

    /**
     * Reads the body as sent, an absent override being {@code false} (FR-005-010).
     *
     * @param requestIds           the list as sent
     * @param leg                  the leg as sent
     * @param operator             the operator as sent
     * @param reason               the reason as sent
     * @param allowTerminalSuccess the forced-resend override, or {@code null} when absent
     * @param allowChangedPayload  the changed-payload override, or {@code null} when absent
     * @param unknown              every other field, keyed by name; only the names are kept
     * @return the request
     */
    @JsonCreator
    public static ResendLegRequest fromJson(@JsonProperty("requestIds") final List<String> requestIds,
                                            @JsonProperty("leg") final String leg,
                                            @JsonProperty("operator") final String operator,
                                            @JsonProperty("reason") final String reason,
                                            @JsonProperty("allowTerminalSuccess")
                                            final Boolean allowTerminalSuccess,
                                            @JsonProperty("allowChangedPayload")
                                            final Boolean allowChangedPayload,
                                            @JsonAnySetter final Map<String, Object> unknown) {
        return new ResendLegRequest(requestIds, leg, operator, reason,
                Boolean.TRUE.equals(allowTerminalSuccess), Boolean.TRUE.equals(allowChangedPayload),
                unknown == null ? List.of() : List.copyOf(unknown.keySet()));
    }
}
