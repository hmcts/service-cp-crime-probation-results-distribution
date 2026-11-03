package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

/** The outcome of {@link LegacyBothPathsCheck#evaluate} (slice 006, FR-006-020). */
public enum LegacyBothPathsVerdict {
    /** A send line or an orchestration-start trace was seen at or after the cutover instant. */
    PAGES,
    /** Nothing was seen at or after the cutover instant. */
    CLEAR,
    /** The environment's legacy telemetry resource is not established — shown as such, never silently absent. */
    NOT_DEPLOYABLE_HERE
}
