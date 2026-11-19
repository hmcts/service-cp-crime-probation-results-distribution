package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
 * Pins FR-006-002 to FR-006-007, FR-006-011: one fixture per {@link HearingComparisonClass}, over
 * synthetic shares only (Principle VII; FR-006-012). User Story 1's own Independent Test.
 */
@DisplayName("ReplayComparator")
class ReplayComparatorTest {

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

    @Test
    @DisplayName("an STE body equal to SIT's logged body, switch active, is a match")
    void equal_bodies_are_a_match() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "ACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        assertThat(report.comparisons()).hasSize(1);
        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.MATCH);
        assertThat(only.differingPaths()).isEmpty();
        assertThat(only.dvId()).isEmpty();
        assertThat(only.explanation()).isEmpty();
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    @Test
    @DisplayName("a defaulted switch read whose body equals SIT's is a registered deviation under DV-25")
    void defaulted_switch_with_equal_body_is_registered_deviation() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "UNREADABLE_DEFAULTED_INACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.REGISTERED_DEVIATION);
        assertThat(only.dvId()).contains("DV-25");
        assertThat(only.differingPaths()).isEmpty();
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    @Test
    @DisplayName("a defaulted switch read whose body differs is an unexplained difference, never waved through")
    void defaulted_switch_with_differing_body_is_unexplained() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":2}", "UNREADABLE_DEFAULTED_ACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNEXPLAINED_DIFFERENCE);
        assertThat(only.dvId()).isEmpty();
        assertThat(only.differingPaths()).containsExactly("/a");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a differing path with no covering explanation is unexplained and blocks cutover")
    void differing_path_with_no_explanation_blocks_cutover() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":2}", "ACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNEXPLAINED_DIFFERENCE);
        assertThat(only.differingPaths()).containsExactly("/a");
        assertThat(only.explanation()).isEmpty();
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("the same difference, covered by an explanation naming that path, becomes explained")
    void differing_path_covered_by_explanation_becomes_explained() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":2}", "ACTIVE");
        final ExplanationEntry explanation = new ExplanationEntry(identity, "second-line-support",
                "STE's own reference data", List.of("/a"), Instant.parse("2026-08-25T09:00:00Z"));

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of(explanation));

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.EXPLAINED_DIFFERENCE);
        assertThat(only.explanation()).contains(explanation);
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
        assertThat(report.staleExplanations()).isEmpty();
    }

    @Test
    @DisplayName("a difference at a path the explanation does not name stays unexplained")
    void differing_path_not_named_by_explanation_stays_unexplained() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1,\"b\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":2,\"b\":2}", "ACTIVE");
        final ExplanationEntry explanation = new ExplanationEntry(identity, "second-line-support",
                "STE's own reference data", List.of("/a"), Instant.parse("2026-08-25T09:00:00Z"));

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of(explanation));

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNEXPLAINED_DIFFERENCE);
        assertThat(only.differingPaths()).containsExactlyInAnyOrder("/a", "/b");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a share logged only by SIT is unpaired SIT-only and blocks cutover until explained")
    void sit_only_share_blocks_until_explained() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");

        final ReplayReport unexplained = compare(List.of(sit), List.of(), List.of());
        final HearingComparison unexplainedOnly = unexplained.comparisons().get(0);
        assertThat(unexplainedOnly.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(unexplained.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);

        final ExplanationEntry wholeExplanation = new ExplanationEntry(identity, "second-line-support",
                "retried after the export, never reached STE", List.of(),
                Instant.parse("2026-08-25T09:00:00Z"));
        final ReplayReport explained = compare(List.of(sit), List.of(), List.of(wholeExplanation));
        final HearingComparison explainedOnly = explained.comparisons().get(0);
        assertThat(explainedOnly.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(explainedOnly.explanation()).contains(wholeExplanation);
        assertThat(explained.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    @Test
    @DisplayName("a share recorded only by STE is unpaired STE-only and blocks cutover until explained")
    void ste_only_share_blocks_until_explained() {
        final ShareIdentity identity = identity();
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "ACTIVE");

        final ReplayReport report = compare(List.of(), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_STE_ONLY);
        assertThat(only.steStatus()).contains("SENT");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a FAILED STE attempt whose stored body matches SIT's is still unpaired SIT-only, "
            + "never a match (Edge Cases: STE parked or failed a hearing that SIT sent)")
    void failed_attempt_with_matching_body_is_not_a_match() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = new SteAttempt(identity, node("{\"a\":1}"), "FAILED", "cache-day",
                "ACTIVE", "1.2.0");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(only.steStatus()).contains("FAILED");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a PARKED STE attempt is also treated as undelivered, unpaired SIT-only")
    void parked_attempt_is_also_undelivered() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = new SteAttempt(identity, node("{\"a\":1}"), "PARKED", "cache-day",
                "ACTIVE", "1.2.0");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(only.steStatus()).contains("PARKED");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a paired NOT_APPLICABLE STE attempt is never treated as undelivered: a genuine "
            + "divergence against a SIT-sent share still surfaces through the ordinary body-diff path")
    void paired_not_applicable_attempt_is_not_undelivered() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = new SteAttempt(identity, node("{\"a\":2}"), "NOT_APPLICABLE",
                "cache-day", "ACTIVE", "1.2.0");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNEXPLAINED_DIFFERENCE);
        assertThat(only.steStatus()).contains("NOT_APPLICABLE");
        assertThat(only.differingPaths()).containsExactly("/a");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a bodyless NOT_APPLICABLE attempt against a SIT share that also sent nothing is a "
            + "genuine match, never reaching the body-diff path (M14)")
    void bodyless_not_applicable_against_an_equally_empty_sit_share_is_a_match() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = new SitLoggedShare(identity, Optional.empty(), Optional.empty());
        // A real bodyless NOT_APPLICABLE attempt has no service_version either (the CHECK
        // constraint ties it to body_digest, and neither was ever written for this attempt): null
        // here, not an artificial version, is what would have caught Optional.of(null) throwing
        // (P2, PR #23 review).
        final SteAttempt ste = new SteAttempt(identity, null, "NOT_APPLICABLE", "cache-day", "ACTIVE",
                null);

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.MATCH);
        assertThat(only.differingPaths()).isEmpty();
        assertThat(only.steServiceVersion()).isEmpty();
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    @Test
    @DisplayName("a bodyless NOT_APPLICABLE attempt against a SIT share that did send something is "
            + "unpaired SIT-only, not a crash in JsonParity (M14)")
    void bodyless_not_applicable_against_a_sent_sit_share_is_unpaired_sit_only() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = new SteAttempt(identity, null, "NOT_APPLICABLE", "cache-day", "ACTIVE",
                "1.2.0");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(only.steStatus()).contains("NOT_APPLICABLE");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("an undelivered STE attempt is explained the same way as any unpaired share, by a "
            + "whole-covering entry")
    void undelivered_attempt_is_explained_like_any_unpaired_share() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = new SteAttempt(identity, node("{\"a\":1}"), "FAILED", "cache-day",
                "ACTIVE", "1.2.0");
        final ExplanationEntry wholeExplanation = new ExplanationEntry(identity,
                "second-line-support", "gateway outage during the replay window", List.of(),
                Instant.parse("2026-08-25T09:00:00Z"));

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of(wholeExplanation));

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.UNPAIRED_SIT_ONLY);
        assertThat(only.explanation()).contains(wholeExplanation);
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES);
    }

    @Test
    @DisplayName("more than one share at the same identity is unpaired ambiguous, never dropped")
    void ambiguous_share_is_reported_never_dropped() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste1 = steAttempt(identity, "{\"a\":1}", "ACTIVE");
        final SteAttempt ste2 = steAttempt(identity, "{\"a\":1}", "ACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste1, ste2), List.of());

        assertThat(report.comparisons()).hasSize(1);
        assertThat(report.comparisons().get(0).comparisonClass())
                .isEqualTo(HearingComparisonClass.UNPAIRED_AMBIGUOUS);
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("a SIT body cut short is SIT body unusable; nothing stands in for it (FR-001-021)")
    void cut_short_sit_body_is_unusable() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = new SitLoggedShare(identity, Optional.empty(), Optional.of("cut-short"));
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "ACTIVE");

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of());

        final HearingComparison only = report.comparisons().get(0);
        assertThat(only.comparisonClass()).isEqualTo(HearingComparisonClass.SIT_BODY_UNUSABLE);
        assertThat(only.unusableReason()).contains("cut-short");
        assertThat(report.verdict()).isEqualTo(ReplayReport.VERDICT_BLOCKS_CUTOVER);
    }

    @Test
    @DisplayName("an explanation matching no current difference or share is reported stale, never silently dropped")
    void stale_explanation_is_reported_never_dropped() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "ACTIVE");
        final ExplanationEntry stale = new ExplanationEntry(identity, "second-line-support",
                "no longer applies", List.of("/a"), Instant.parse("2026-08-25T09:00:00Z"));

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of(stale));

        assertThat(report.comparisons().get(0).comparisonClass()).isEqualTo(HearingComparisonClass.MATCH);
        assertThat(report.staleExplanations()).containsExactly(stale);
    }

    @Test
    @DisplayName("an explanation naming an identity no share carries is reported stale")
    void explanation_for_unknown_identity_is_stale() {
        final ShareIdentity identity = identity();
        final SitLoggedShare sit = sitShare(identity, "{\"a\":1}");
        final SteAttempt ste = steAttempt(identity, "{\"a\":1}", "ACTIVE");
        final ExplanationEntry unknown = new ExplanationEntry(
                new ShareIdentity(UUID.randomUUID(), DAY, Instant.parse("2026-08-19T18:00:00Z")),
                "second-line-support", "does not match any share", List.of(),
                Instant.parse("2026-08-25T09:00:00Z"));

        final ReplayReport report = compare(List.of(sit), List.of(ste), List.of(unknown));

        assertThat(report.staleExplanations()).containsExactly(unknown);
    }

    @Test
    @DisplayName("the report records the week, the export dates and every STE build seen")
    void report_records_week_export_dates_and_ste_builds() {
        final ShareIdentity first = identity();
        final ShareIdentity second = new ShareIdentity(UUID.randomUUID(), DAY,
                Instant.parse("2026-08-20T10:00:00Z"));
        final SitLoggedShare sitOne = sitShare(first, "{\"a\":1}");
        final SitLoggedShare sitTwo = sitShare(second, "{\"a\":1}");
        final SteAttempt steOne = new SteAttempt(first, node("{\"a\":1}"), "SENT", "cache-day",
                "ACTIVE", "1.2.0");
        final SteAttempt steTwo = new SteAttempt(second, node("{\"a\":1}"), "SENT", "cache-day",
                "ACTIVE", "1.3.0");

        final ReplayReport report = compare(List.of(sitOne, sitTwo), List.of(steOne, steTwo), List.of());

        assertThat(report.week()).isEqualTo(WEEK);
        assertThat(report.exportDates()).isEqualTo(EXPORT_DATES);
        assertThat(report.preRunChecks()).isEqualTo(PASSING_CHECKS);
        assertThat(report.steBuilds()).containsExactlyInAnyOrder("1.2.0", "1.3.0");
    }

    private static ReplayReport compare(final List<SitLoggedShare> sitShares,
            final List<SteAttempt> steAttempts, final List<ExplanationEntry> explanations) {
        return ReplayComparator.compare(WEEK, EXPORT_DATES, PASSING_CHECKS, sitShares, steAttempts,
                explanations);
    }

    private static ShareIdentity identity() {
        return new ShareIdentity(UUID.randomUUID(), DAY, Instant.parse("2026-08-19T16:42:07.512Z"));
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
