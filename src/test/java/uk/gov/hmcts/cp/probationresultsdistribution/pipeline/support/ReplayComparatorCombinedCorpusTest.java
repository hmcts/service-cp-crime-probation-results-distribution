package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.ShareIdentity;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SitLoggedShare;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SteAttempt;

/**
 * Pins SC-006-001, SC-006-002 over one combined run: every {@link HearingComparisonClass} and
 * every unpaired sub-reason at once, not only T004's per-class fixtures in isolation. Every share
 * in the union of both sides appears in exactly one class, none dropped, and the verdict follows
 * the comparisons as a whole.
 */
@DisplayName("ReplayComparator combined corpus (SC-006-001, SC-006-002)")
class ReplayComparatorCombinedCorpusTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final AtomicInteger SHARE_COUNTER = new AtomicInteger();

    @Test
    @DisplayName("every class and every unpaired sub-reason appears exactly once, none dropped")
    void every_class_and_sub_reason_appears_once_and_verdict_blocks_cutover() {
        final ShareIdentity matchId = identity();
        final ShareIdentity deviationId = identity();
        final ShareIdentity unexplainedId = identity();
        final ShareIdentity explainedId = identity();
        final ShareIdentity sitOnlyId = identity();
        final ShareIdentity steOnlyId = identity();
        final ShareIdentity ambiguousId = identity();
        final ShareIdentity unusableId = identity();

        final List<SitLoggedShare> sitShares = new ArrayList<>(List.of(
                sitShare(matchId, "{\"a\":1}"), sitShare(deviationId, "{\"a\":1}"),
                sitShare(unexplainedId, "{\"a\":1}"), sitShare(explainedId, "{\"a\":1}"),
                sitShare(sitOnlyId, "{\"a\":1}"), sitShare(ambiguousId, "{\"a\":1}"),
                sitShare(ambiguousId, "{\"a\":1}"),
                new SitLoggedShare(unusableId, Optional.empty(), Optional.of("cut-short"))));
        final List<SteAttempt> steAttempts = new ArrayList<>(List.of(
                steAttempt(matchId, "{\"a\":1}", "ACTIVE"),
                steAttempt(deviationId, "{\"a\":1}", "UNREADABLE_DEFAULTED_ACTIVE"),
                steAttempt(unexplainedId, "{\"a\":2}", "ACTIVE"),
                steAttempt(explainedId, "{\"a\":2}", "ACTIVE"),
                steAttempt(steOnlyId, "{\"a\":1}", "ACTIVE"),
                steAttempt(ambiguousId, "{\"a\":1}", "ACTIVE"),
                steAttempt(unusableId, "{\"a\":1}", "ACTIVE")));

        final List<ExplanationEntry> explanations = List.of(
                new ExplanationEntry(explainedId, "second-line-support", "STE's own reference data",
                        List.of("/a"), Instant.parse("2026-08-25T09:00:00Z")));

        final ReplayReport report = ReplayComparator.compare("2026-W34",
                List.of(LocalDate.parse("2026-08-24")), passingChecks(), sitShares, steAttempts,
                explanations);

        final int totalShares = 8;
        assertThat(report.comparisons()).hasSize(totalShares);
        assertThat(report.comparisons()).extracting(HearingComparison::comparisonClass)
                .containsExactlyInAnyOrder(
                        HearingComparisonClass.MATCH, HearingComparisonClass.REGISTERED_DEVIATION,
                        HearingComparisonClass.UNEXPLAINED_DIFFERENCE,
                        HearingComparisonClass.EXPLAINED_DIFFERENCE,
                        HearingComparisonClass.UNPAIRED_SIT_ONLY,
                        HearingComparisonClass.UNPAIRED_STE_ONLY,
                        HearingComparisonClass.UNPAIRED_AMBIGUOUS,
                        HearingComparisonClass.SIT_BODY_UNUSABLE);
        // Every share's identity is distinct, so every comparisonClass value above corresponds to
        // exactly one share — none counted twice, none dropped (SC-006-001).
        assertThat(report.comparisons()).extracting(HearingComparison::hearingId).doesNotHaveDuplicates();

        // SC-006-002: an unexplained difference, and unpaired/unusable shares with no explanation,
        // remain — the verdict blocks cutover.
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("with nothing left unexplained, the verdict is no unexplained differences")
    void nothing_unexplained_gives_the_clean_verdict() {
        final ShareIdentity matchId = identity();
        final ShareIdentity deviationId = identity();

        final ReplayReport report = ReplayComparator.compare("2026-W34",
                List.of(LocalDate.parse("2026-08-24")), passingChecks(),
                List.of(sitShare(matchId, "{\"a\":1}"), sitShare(deviationId, "{\"a\":1}")),
                List.of(steAttempt(matchId, "{\"a\":1}", "ACTIVE"),
                        steAttempt(deviationId, "{\"a\":1}", "UNREADABLE_DEFAULTED_INACTIVE")),
                List.of());

        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    private static List<PreRunCheckResult> passingChecks() {
        return List.of(new PreRunCheckResult("a", true, "ok"), new PreRunCheckResult("b", true, "ok"),
                new PreRunCheckResult("c", true, "ok"), new PreRunCheckResult("d", true, "ok"),
                new PreRunCheckResult("e", true, "ok"));
    }

    private static ShareIdentity identity() {
        return new ShareIdentity(UUID.randomUUID(), DAY,
                Instant.parse("2026-08-19T16:00:00Z").plusSeconds(60L * SHARE_COUNTER.getAndIncrement()));
    }

    private static SitLoggedShare sitShare(final ShareIdentity identity, final String json) {
        return new SitLoggedShare(identity, Optional.of(node(json)), Optional.empty());
    }

    private static SteAttempt steAttempt(final ShareIdentity identity, final String json,
            final String youthSwitch) {
        return new SteAttempt(identity, node(json), "SENT", "cache-day", youthSwitch, "1.2.0");
    }

    private static JsonNode node(final String json) {
        return MAPPER.readTree(json);
    }
}
