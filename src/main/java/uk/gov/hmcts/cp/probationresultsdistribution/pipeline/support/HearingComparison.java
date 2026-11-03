package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One share of one hearing in the replay week's union of both sides, classed into exactly one
 * {@link HearingComparisonClass} (slice 006, FR-006-003, FR-006-005; data-model.md). Carries no
 * value taken from a payload or a body — identifiers, the class, the differing paths' names and
 * the bounded STE attempt columns only (FR-006-012).
 *
 * @param hearingId        identity, with {@code hearingDay} and {@code sharedTime} (FR-006-002)
 * @param hearingDay       the hearing day
 * @param sharedTime       compared as an instant, never as a rendered string (H17 precedent)
 * @param comparisonClass  the one class this share is in
 * @param differingPaths   the JSON paths that differ; empty for a match or a registered deviation
 * @param explanation      present once an operator has named a reason
 * @param dvId             the deviation id, present only for {@link HearingComparisonClass#REGISTERED_DEVIATION}
 * @param steStatus        the STE attempt's status, empty when there is no STE attempt
 * @param stePayloadSource the STE attempt's payload source, empty when there is no STE attempt
 * @param steYouthSwitch   the STE attempt's recorded youth switch, empty when there is no STE attempt
 * @param steServiceVersion the STE attempt's build version, empty when there is no STE attempt
 * @param unusableReason   set only for {@link HearingComparisonClass#SIT_BODY_UNUSABLE}
 */
public record HearingComparison(
        UUID hearingId,
        LocalDate hearingDay,
        Instant sharedTime,
        HearingComparisonClass comparisonClass,
        List<String> differingPaths,
        Optional<ExplanationEntry> explanation,
        Optional<String> dvId,
        Optional<String> steStatus,
        Optional<String> stePayloadSource,
        Optional<String> steYouthSwitch,
        Optional<String> steServiceVersion,
        Optional<String> unusableReason) {

    /** Rejects null components; absence is an empty optional or list, never null. */
    public HearingComparison {
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(sharedTime, "sharedTime");
        Objects.requireNonNull(comparisonClass, "comparisonClass");
        differingPaths = List.copyOf(differingPaths);
        Objects.requireNonNull(explanation, "explanation");
        Objects.requireNonNull(dvId, "dvId");
        Objects.requireNonNull(steStatus, "steStatus");
        Objects.requireNonNull(stePayloadSource, "stePayloadSource");
        Objects.requireNonNull(steYouthSwitch, "steYouthSwitch");
        Objects.requireNonNull(steServiceVersion, "steServiceVersion");
        Objects.requireNonNull(unusableReason, "unusableReason");
    }

    /**
     * Whether this share, on its own, requires the verdict to block cutover: an unexplained
     * difference, an unpaired share or a SIT-body-unusable share with no explanation covering it
     * (FR-006-011).
     *
     * @return {@code true} when this share blocks cutover
     */
    public boolean blocksCutover() {
        final boolean inherentlyBlocking = comparisonClass == HearingComparisonClass.UNEXPLAINED_DIFFERENCE
                || comparisonClass == HearingComparisonClass.UNPAIRED_SIT_ONLY
                || comparisonClass == HearingComparisonClass.UNPAIRED_STE_ONLY
                || comparisonClass == HearingComparisonClass.UNPAIRED_AMBIGUOUS
                || comparisonClass == HearingComparisonClass.SIT_BODY_UNUSABLE;
        return inherentlyBlocking && explanation.isEmpty();
    }
}
