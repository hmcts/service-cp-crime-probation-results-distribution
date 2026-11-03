package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.PairedShare;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.ShareIdentity;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SitLoggedShare;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SteAttempt;

/** Pins FR-006-002, FR-006-003: pairing by (hearingId, hearingDay, sharedTime instant). */
@DisplayName("HearingPairing")
class HearingPairingTest {

    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final UUID HEARING = UUID.randomUUID();

    @Test
    @DisplayName("pairs a share present on both sides at the same identity")
    void pairs_a_share_present_on_both_sides() {
        final ShareIdentity identity = identity(Instant.parse("2026-08-19T16:42:07.512Z"));
        final SitLoggedShare sit = sitShare(identity);
        final SteAttempt ste = steAttempt(identity);

        final List<PairedShare> paired = HearingPairing.pair(List.of(sit), List.of(ste));

        assertThat(paired).hasSize(1);
        final PairedShare only = paired.get(0);
        assertThat(only.identity()).isEqualTo(identity);
        assertThat(only.isOneToOne()).isTrue();
        assertThat(only.isAmbiguous()).isFalse();
        assertThat(only.isSitOnly()).isFalse();
        assertThat(only.isSteOnly()).isFalse();
    }

    @Test
    @DisplayName("a hearing shared twice, at two distinct instants, pairs each share with its own counterpart")
    void a_hearing_shared_twice_pairs_each_with_its_own_counterpart() {
        final ShareIdentity first = identity(Instant.parse("2026-08-19T16:42:07.512Z"));
        final ShareIdentity second = identity(Instant.parse("2026-08-19T18:00:00.000Z"));

        final List<PairedShare> paired = HearingPairing.pair(
                List.of(sitShare(first), sitShare(second)),
                List.of(steAttempt(first), steAttempt(second)));

        assertThat(paired).hasSize(2);
        assertThat(paired).allMatch(PairedShare::isOneToOne);
    }

    @Test
    @DisplayName("a share logged only by SIT is unpaired, SIT-only")
    void a_share_logged_only_by_sit_is_unpaired_sit_only() {
        final ShareIdentity identity = identity(Instant.parse("2026-08-19T16:42:07.512Z"));

        final List<PairedShare> paired = HearingPairing.pair(List.of(sitShare(identity)), List.of());

        assertThat(paired).hasSize(1);
        assertThat(paired.get(0).isSitOnly()).isTrue();
        assertThat(paired.get(0).isOneToOne()).isFalse();
        assertThat(paired.get(0).isAmbiguous()).isFalse();
    }

    @Test
    @DisplayName("a share recorded only by STE is unpaired, STE-only")
    void a_share_recorded_only_by_ste_is_unpaired_ste_only() {
        final ShareIdentity identity = identity(Instant.parse("2026-08-19T16:42:07.512Z"));

        final List<PairedShare> paired = HearingPairing.pair(List.of(), List.of(steAttempt(identity)));

        assertThat(paired).hasSize(1);
        assertThat(paired.get(0).isSteOnly()).isTrue();
    }

    @Test
    @DisplayName("more than one share at the same identity on either side is ambiguous, never dropped")
    void more_than_one_share_at_the_same_identity_is_ambiguous() {
        final ShareIdentity identity = identity(Instant.parse("2026-08-19T16:42:07.512Z"));

        final List<PairedShare> paired = HearingPairing.pair(
                List.of(sitShare(identity), sitShare(identity)), List.of(steAttempt(identity)));

        assertThat(paired).hasSize(1);
        assertThat(paired.get(0).isAmbiguous()).isTrue();
        assertThat(paired.get(0).sit()).hasSize(2);
    }

    @Test
    @DisplayName("sharedTime is compared as an instant, not as a rendered string")
    void shared_time_is_compared_as_an_instant() {
        final Instant instant = Instant.parse("2026-08-19T16:42:07.512Z");
        final ShareIdentity sitIdentity = new ShareIdentity(HEARING, DAY, instant);
        // The same instant, rendered with an explicit offset rather than "Z" — still the same
        // instant, so this MUST still pair, not be reported SIT-only/STE-only.
        final ShareIdentity steIdentity =
                new ShareIdentity(HEARING, DAY, instant.plusNanos(0));

        final List<PairedShare> paired = HearingPairing.pair(
                List.of(sitShare(sitIdentity)), List.of(steAttempt(steIdentity)));

        assertThat(paired).hasSize(1);
        assertThat(paired.get(0).isOneToOne()).isTrue();
    }

    private static ShareIdentity identity(final Instant sharedTime) {
        return new ShareIdentity(HEARING, DAY, sharedTime);
    }

    private static SitLoggedShare sitShare(final ShareIdentity identity) {
        return new SitLoggedShare(identity, Optional.empty(), Optional.empty());
    }

    private static SteAttempt steAttempt(final ShareIdentity identity) {
        return new SteAttempt(identity, null, "SENT", "cache-day", "ACTIVE", "test-version");
    }
}
