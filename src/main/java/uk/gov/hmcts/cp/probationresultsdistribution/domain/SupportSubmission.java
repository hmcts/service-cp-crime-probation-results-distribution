package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;

/**
 * The operator markers a submit-hearing command carries as broker application properties
 * ({@code operator}, {@code runId}, {@code reason}; slice 004, FR-004-056, FR-004-057), read at
 * admission only for a delivery marked {@code origin = SUPPORT}.
 *
 * @param operator the operator
 * @param runId    the submit-hearing run id
 * @param reason   the reason
 */
public record SupportSubmission(String operator, String runId, String reason) {

    /** Rejects null components. */
    public SupportSubmission {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Reads the markers from the delivery's application property values.
     *
     * @param operator the {@code operator} property value, or null
     * @param runId    the {@code runId} property value, or null
     * @param reason   the {@code reason} property value, or null
     * @return the markers
     */
    public static SupportSubmission fromProperties(final Object operator, final Object runId,
                                                   final Object reason) {
        return new SupportSubmission(String.valueOf(operator), String.valueOf(runId),
                String.valueOf(reason));
    }
}
