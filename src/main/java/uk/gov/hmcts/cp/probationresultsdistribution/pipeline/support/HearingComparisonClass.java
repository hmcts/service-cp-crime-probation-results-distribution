package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

/**
 * The closed set of classes the one-week SIT → STE replay comparison gives every share in the
 * union of both sides (slice 006, FR-006-001, FR-006-003, FR-006-005 to FR-006-007). A share
 * classed {@code NOT_APPLICABLE} in STE with no SIT log line ("neither side sent") is not a
 * comparison at all and never takes one of these values — it is counted only in the week's STE
 * outcome totals.
 */
public enum HearingComparisonClass {

    /** The STE body equals SIT's logged body, with no defaulted switch read. */
    MATCH,

    /**
     * The STE body equals the expected body derived for a defaulted switch read (DV-25) — SIT's
     * logged body, since STE's configured default equals the week's live reading (FR-006-004).
     */
    REGISTERED_DEVIATION,

    /** A difference exists, and every differing path is covered by an {@link ExplanationEntry}. */
    EXPLAINED_DIFFERENCE,

    /** A difference exists at a path no explanation entry covers. Blocks cutover. */
    UNEXPLAINED_DIFFERENCE,

    /** SIT logged this share; STE recorded nothing for it. Blocks cutover until explained. */
    UNPAIRED_SIT_ONLY,

    /** STE recorded this share; SIT logged nothing for it. Blocks cutover until explained. */
    UNPAIRED_STE_ONLY,

    /** More than one share landed on the same pairing identity, on either side. */
    UNPAIRED_AMBIGUOUS,

    /** SIT's logged body is cut short or incomplete JSON; it is never the expected body. */
    SIT_BODY_UNUSABLE
}
