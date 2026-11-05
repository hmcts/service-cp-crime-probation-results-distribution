package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Pairs a SIT logged share with its STE counterpart by {@code (hearingId, hearingDay,
 * sharedTime)}, the same identity 001 already uses, with {@code sharedTime} compared as an
 * instant rather than a rendered string, because the two sides may render it differently
 * (slice 006, FR-006-002; H17 precedent). A hearing shared more than once in the replay week is
 * still one pairing key per actual share, since each real share carries its own {@code
 * sharedTime}; a key that more than one share lands on, on either side, cannot be paired
 * one-to-one and is reported ambiguous rather than dropped (FR-006-003).
 */
public final class HearingPairing {

    private HearingPairing() {
    }

    /** A share's pairing identity. */
    public record ShareIdentity(UUID hearingId, LocalDate hearingDay, Instant sharedTime) {
    }

    /** One hearing as SIT logged it, or unusable with the reason why. */
    public record SitLoggedShare(ShareIdentity identity, Optional<JsonNode> loggedBody,
                                 Optional<String> unusableReason) {
    }

    /** One hearing as STE's latest HMPPS attempt recorded it. */
    public record SteAttempt(ShareIdentity identity, JsonNode body, String status,
                             String payloadSource, String youthSwitch, String serviceVersion) {
    }

    /** One pairing outcome: both sides present and unambiguous, one side only, or ambiguous. */
    public record PairedShare(ShareIdentity identity, List<SitLoggedShare> sit,
                              List<SteAttempt> ste) {

        /** Whether this key paired exactly one share on each side. */
        public boolean isOneToOne() {
            return sit.size() == 1 && ste.size() == 1;
        }

        /** Whether this key is ambiguous: more than one share on at least one side. */
        public boolean isAmbiguous() {
            return sit.size() > 1 || ste.size() > 1;
        }

        /** Whether SIT logged nothing for this key. */
        public boolean isSteOnly() {
            return sit.isEmpty() && !ste.isEmpty();
        }

        /** Whether STE recorded nothing for this key. */
        public boolean isSitOnly() {
            return !sit.isEmpty() && ste.isEmpty();
        }
    }

    /**
     * Pairs every SIT logged share with its STE counterpart.
     *
     * @param sitShares    the week's SIT logged shares
     * @param steAttempts  STE's latest HMPPS attempts for the same week
     * @return one {@link PairedShare} per distinct identity in the union of both sides
     */
    public static List<PairedShare> pair(final List<SitLoggedShare> sitShares,
                                         final List<SteAttempt> steAttempts) {
        final Map<ShareIdentity, List<SitLoggedShare>> sitByIdentity = new LinkedHashMap<>();
        sitShares.forEach(share -> sitByIdentity
                .computeIfAbsent(share.identity(), key -> new ArrayList<>()).add(share));
        final Map<ShareIdentity, List<SteAttempt>> steByIdentity = new LinkedHashMap<>();
        steAttempts.forEach(attempt -> steByIdentity
                .computeIfAbsent(attempt.identity(), key -> new ArrayList<>()).add(attempt));

        final List<ShareIdentity> identities = new ArrayList<>(sitByIdentity.keySet());
        steByIdentity.keySet().stream().filter(key -> !sitByIdentity.containsKey(key))
                .forEach(identities::add);

        return identities.stream()
                .map(identity -> new PairedShare(identity,
                        sitByIdentity.getOrDefault(identity, List.of()),
                        steByIdentity.getOrDefault(identity, List.of())))
                .toList();
    }
}
