package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
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
 * Pins FR-006-012, SC-006-003: a sentinel value planted in every seeded payload and body across
 * every {@link HearingComparisonClass} MUST NOT appear anywhere in the resulting {@link
 * ReplayReport}. The comparison carries identifiers, classes, DV ids, JSON paths and bounded
 * column values only — never a value taken from the compared bytes themselves.
 */
@DisplayName("ReplayReport privacy")
class ReplayReportPrivacyTest {

    private static final String SENTINEL = "SENTINEL-F6D21A7C-DEFENDANT-SURNAME";
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();
    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final String WEEK = "2026-W34";
    private static final List<LocalDate> EXPORT_DATES = List.of(LocalDate.parse("2026-08-24"));
    private static final List<PreRunCheckResult> PASSING_CHECKS = List.of(
            new PreRunCheckResult("a", true, "ok"), new PreRunCheckResult("b", true, "ok"),
            new PreRunCheckResult("c", true, "ok"), new PreRunCheckResult("d", true, "ok"),
            new PreRunCheckResult("e", true, "ok"));
    private static final AtomicInteger SHARE_COUNTER = new AtomicInteger();

    @Test
    @DisplayName("no sentinel planted in any body reaches the report, across every comparison class")
    void no_sentinel_body_value_reaches_the_report() {
        final ShareIdentity matchId = identity();
        final ShareIdentity deviationId = identity();
        final ShareIdentity unexplainedId = identity();
        final ShareIdentity explainedId = identity();
        final ShareIdentity sitOnlyId = identity();
        final ShareIdentity steOnlyId = identity();
        final ShareIdentity unusableId = identity();

        final List<SitLoggedShare> sitShares = List.of(
                sitShare(matchId, sentinelBody("match")),
                sitShare(deviationId, sentinelBody("deviation")),
                sitShare(unexplainedId, sentinelBody("unexplained-sit")),
                sitShare(explainedId, sentinelBody("explained-sit")),
                sitShare(sitOnlyId, sentinelBody("sit-only")),
                new SitLoggedShare(unusableId, Optional.empty(), Optional.of(SENTINEL + "-cut-short")));

        final List<SteAttempt> steAttempts = List.of(
                steAttempt(matchId, sentinelBody("match"), "ACTIVE"),
                steAttempt(deviationId, sentinelBody("deviation"), "UNREADABLE_DEFAULTED_ACTIVE"),
                steAttempt(unexplainedId, sentinelBody("unexplained-ste"), "ACTIVE"),
                steAttempt(explainedId, sentinelBody("explained-ste"), "ACTIVE"),
                steAttempt(steOnlyId, sentinelBody("ste-only"), "ACTIVE"));

        final List<ExplanationEntry> explanations = List.of(
                new ExplanationEntry(explainedId, "second-line-support",
                        "STE's own reference data", List.of("/explained-sit", "/explained-ste"),
                        Instant.parse("2026-08-25T09:00:00Z")));

        final ReplayReport report = ReplayComparator.compare(WEEK, EXPORT_DATES, PASSING_CHECKS,
                sitShares, steAttempts, explanations);

        assertThat(report.comparisons()).hasSizeGreaterThanOrEqualTo(6);
        assertThat(report.toString()).doesNotContain(SENTINEL);
        report.comparisons().forEach(comparison -> {
            assertThat(comparison.toString()).doesNotContain(SENTINEL);
            comparison.differingPaths().forEach(path -> assertThat(path).doesNotContain(SENTINEL));
        });
        report.staleExplanations().forEach(entry -> assertThat(entry.toString()).doesNotContain(SENTINEL));
    }

    private static String sentinelBody(final String fieldName) {
        return "{\"" + fieldName + "\":\"" + SENTINEL + "\"}";
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
