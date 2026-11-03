package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.ShareIdentity;

/**
 * A reason a named role attached to one hearing's differing paths, or to that hearing's unpaired
 * or SIT-body-unusable share as a whole (slice 006, FR-006-006). Names the hearing it applies to
 * by the same pairing identity {@link HearingPairing} uses, so the comparator can match an entry
 * to its share without guessing. Moves a difference from unexplained to explained only for the
 * paths it names; a difference at any other path stays unexplained. An entry matching no current
 * difference or share is reported stale, never silently dropped.
 *
 * @param identity     the hearing this entry explains
 * @param role         the named person/role attaching the reason
 * @param reason       free text; never echoed into a metric label, sanitised the way {@code
 *                     audit_note} already is
 * @param coveredPaths the JSON paths this entry explains; empty when it covers an unpaired or
 *                     SIT-body-unusable share as a whole
 * @param recordedAt   when this entry was recorded
 */
public record ExplanationEntry(ShareIdentity identity, String role, String reason,
                               List<String> coveredPaths, Instant recordedAt) {

    /** Rejects null components. */
    public ExplanationEntry {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(coveredPaths, "coveredPaths");
        coveredPaths = List.copyOf(coveredPaths);
        Objects.requireNonNull(recordedAt, "recordedAt");
    }

    /**
     * Whether this entry covers a share as a whole (an unpaired or SIT-body-unusable share),
     * rather than specific differing paths.
     *
     * @return {@code true} when it names no path
     */
    public boolean coversWhole() {
        return coveredPaths.isEmpty();
    }
}
