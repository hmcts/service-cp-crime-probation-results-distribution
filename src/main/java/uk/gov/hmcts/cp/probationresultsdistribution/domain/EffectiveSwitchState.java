package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The effective youth-switch state the redaction takes as its second input (FR-001-001, §4.4).
 *
 * <p>There are only two values here: the youth rules R3 and R4 either run or do not (design H7).
 * The four recorded {@code youth_switch} values — {@code ACTIVE}, {@code INACTIVE},
 * {@code UNREADABLE_DEFAULTED_ACTIVE}, {@code UNREADABLE_DEFAULTED_INACTIVE} — and the collapse of
 * the two {@code UNREADABLE_DEFAULTED_*} values onto their suffix belong to the HMPPS leg in slice
 * 002 (FR-001-006, H8); the redaction only ever sees the effective state.
 */
public enum EffectiveSwitchState {

    /** The youth rules R3 and R4 run. */
    ACTIVE,

    /** The youth rules R3 and R4 do not run. */
    INACTIVE
}
