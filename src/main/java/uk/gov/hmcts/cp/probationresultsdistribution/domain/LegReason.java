package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The bounded reason paired with a non-{@code PENDING}, non-{@code SENT} leg status (design §4.5).
 * A reason never quotes a payload value. {@code contract-invalid} is reserved and unused while
 * outbound schema validation is parked (H13); {@code payload-changed} and {@code rolled-back} are
 * written only by slice 004.
 */
public enum LegReason implements WireValued {

    /** R1 emptied the payload: an SJP, box or virtual-box hearing. */
    SJP_OR_BOX_HEARING("sjp-or-box-hearing"),
    /** R11 left nothing after redaction. */
    NOTHING_LEFT_AFTER_REDACTION("nothing-left-after-redaction"),
    /** A 4xx other than 408/429 (400 and 401 included). */
    RECEIVER_REJECTED("receiver-rejected"),
    /** A rule threw; carried null guards (P19). */
    RULES_EXCEPTION("rules-exception"),
    /** No source yielded a payload. */
    PAYLOAD_UNAVAILABLE("payload-unavailable"),
    /** A 5xx, 408, read timeout after write, or a connection failure or 429 after the retries. */
    GATEWAY_TRANSIENT("gateway-transient"),
    /** A resend found the share's {@code sharedTime} changed (slice 004). */
    PAYLOAD_CHANGED("payload-changed"),
    /** A rollback step-4 outcome (slice 004). */
    ROLLED_BACK("rolled-back"),
    /** Reserved and unused while outbound validation is parked (H13). */
    CONTRACT_INVALID("contract-invalid");

    private final String wire;

    LegReason(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link LegReason} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static LegReason fromValue(final String value) {
        return Vocabularies.fromValue(LegReason.class, value);
    }
}
