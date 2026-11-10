package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.PairedShare;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.ShareIdentity;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SitLoggedShare;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.HearingPairing.SteAttempt;

/**
 * Classes one week's union of SIT logged shares and STE HMPPS attempts into a {@link
 * ReplayReport} (slice 006, FR-006-002 to FR-006-007, FR-006-011). Pure: no I/O, no clock, no
 * randomness — testable with synthetic fixtures alone, over 001's existing {@link JsonParity} and
 * {@link RegisteredFieldDeviations}.
 */
public final class ReplayComparator {

    /** The deviation this slice derives DV-25 under (FR-006-004; corpus case 13's own derivation). */
    public static final String DV_25 = "DV-25";

    private static final String DEFAULTED_ACTIVE = "UNREADABLE_DEFAULTED_ACTIVE";
    private static final String DEFAULTED_INACTIVE = "UNREADABLE_DEFAULTED_INACTIVE";
    private static final String STATUS_FAILED = "FAILED";
    private static final String STATUS_PARKED = "PARKED";
    private static final String STATUS_NOT_APPLICABLE = "NOT_APPLICABLE";

    private ReplayComparator() {
    }

    /**
     * Compares a week's shares and classes every one of them.
     *
     * @param week          the chosen SIT week (FR-006-009)
     * @param exportDates   when the SIT bodies/payloads were exported
     * @param preRunChecks  FR-006-008's checks, recorded regardless of outcome
     * @param sitShares     the week's SIT logged shares
     * @param steAttempts   STE's latest HMPPS attempts for the same week
     * @param explanations  reasons an operator has recorded against a share or its paths
     * @return the replay report
     */
    public static ReplayReport compare(
            final String week,
            final List<LocalDate> exportDates,
            final List<PreRunCheckResult> preRunChecks,
            final List<SitLoggedShare> sitShares,
            final List<SteAttempt> steAttempts,
            final List<ExplanationEntry> explanations) {

        final List<PairedShare> paired = HearingPairing.pair(sitShares, steAttempts);
        final Map<ShareIdentity, List<ExplanationEntry>> explanationsByIdentity = explanations.stream()
                .collect(Collectors.groupingBy(ExplanationEntry::identity, LinkedHashMap::new,
                        Collectors.toList()));

        final List<HearingComparison> comparisons = new ArrayList<>();
        final Map<ShareIdentity, HearingComparison> comparisonsByIdentity = new LinkedHashMap<>();
        for (final PairedShare share : paired) {
            final HearingComparison comparison = classify(share,
                    explanationsByIdentity.getOrDefault(share.identity(), List.of()));
            comparisons.add(comparison);
            comparisonsByIdentity.put(share.identity(), comparison);
        }

        final List<ExplanationEntry> staleExplanations =
                findStale(explanations, comparisonsByIdentity);

        final String verdict = comparisons.stream().anyMatch(HearingComparison::blocksCutover)
                ? ReplayReport.VERDICT_BLOCKS_CUTOVER
                : ReplayReport.VERDICT_NO_UNEXPLAINED_DIFFERENCES;

        final List<String> steBuilds = steAttempts.stream()
                .map(SteAttempt::serviceVersion)
                .distinct()
                .toList();

        return new ReplayReport(week, exportDates, preRunChecks, steBuilds, comparisons,
                staleExplanations, verdict);
    }

    // Guard-and-return by class, like HmppsRedaction's own ported rules: the clearest shape for a
    // dispatch over mutually exclusive conditions.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static HearingComparison classify(final PairedShare share,
            final List<ExplanationEntry> entries) {
        final ShareIdentity id = share.identity();
        final Optional<ExplanationEntry> wholeExplanation =
                entries.stream().filter(ExplanationEntry::coversWhole).findFirst();

        if (share.isAmbiguous()) {
            return unpaired(id, HearingComparisonClass.UNPAIRED_AMBIGUOUS, wholeExplanation,
                    share.ste().stream().findFirst());
        }
        if (share.isSitOnly()) {
            return unpaired(id, HearingComparisonClass.UNPAIRED_SIT_ONLY, wholeExplanation,
                    Optional.empty());
        }
        if (share.isSteOnly()) {
            return unpaired(id, HearingComparisonClass.UNPAIRED_STE_ONLY, wholeExplanation,
                    share.ste().stream().findFirst());
        }

        final SitLoggedShare sit = share.sit().get(0);
        final SteAttempt ste = share.ste().get(0);

        // A FAILED or PARKED attempt never reached HMPPS, even though redaction ran and a body was
        // digested and stored before the send failed — that stored body can still byte-match SIT's
        // logged body by coincidence. Treat it as though STE recorded nothing (Edge Cases: "STE
        // parked or failed a hearing that SIT sent"), so it stays unpaired and blocking rather than
        // being waved through as a match.
        if (isUndelivered(ste.status())) {
            return unpaired(id, HearingComparisonClass.UNPAIRED_SIT_ONLY, wholeExplanation,
                    Optional.of(ste));
        }

        // A genuine NOT_APPLICABLE attempt (sjp-or-box-hearing, nothing-left-after-redaction) never
        // serialises a body (H20; outbound_body has no row for it), so ste.body() is null here by
        // construction — never an empty object a real diff could run against. It must never reach
        // JsonParity.differencePaths, which assumes two real trees and NPEs on a null side (M14, PR
        // #23 review). A share with no SIT body either is a genuine match: both sides legitimately
        // sent nothing. One with a SIT body is the same "nothing reached HMPPS" divergence the
        // FAILED/PARKED undelivered branch above already reports.
        if (STATUS_NOT_APPLICABLE.equals(ste.status()) && ste.body() == null) {
            if (sit.loggedBody().isEmpty() && sit.unusableReason().isEmpty()) {
                return new HearingComparison(id.hearingId(), id.hearingDay(), id.sharedTime(),
                        HearingComparisonClass.MATCH, List.of(), Optional.empty(), Optional.empty(),
                        Optional.of(ste.status()), Optional.of(ste.payloadSource()),
                        Optional.of(ste.youthSwitch()), Optional.of(ste.serviceVersion()),
                        Optional.empty());
            }
            return unpaired(id, HearingComparisonClass.UNPAIRED_SIT_ONLY, wholeExplanation,
                    Optional.of(ste));
        }

        if (sit.unusableReason().isPresent()) {
            return new HearingComparison(id.hearingId(), id.hearingDay(), id.sharedTime(),
                    HearingComparisonClass.SIT_BODY_UNUSABLE, List.of(), wholeExplanation,
                    Optional.empty(), Optional.of(ste.status()), Optional.of(ste.payloadSource()),
                    Optional.of(ste.youthSwitch()), Optional.of(ste.serviceVersion()),
                    sit.unusableReason());
        }

        final List<String> diffPaths =
                JsonParity.differencePaths(sit.loggedBody().orElseThrow(), ste.body());
        final boolean defaulted = isDefaulted(ste.youthSwitch());

        if (diffPaths.isEmpty()) {
            final HearingComparisonClass comparisonClass = defaulted
                    ? HearingComparisonClass.REGISTERED_DEVIATION
                    : HearingComparisonClass.MATCH;
            final Optional<String> dvId = defaulted ? Optional.of(DV_25) : Optional.empty();
            return new HearingComparison(id.hearingId(), id.hearingDay(), id.sharedTime(),
                    comparisonClass, List.of(), Optional.empty(), dvId, Optional.of(ste.status()),
                    Optional.of(ste.payloadSource()), Optional.of(ste.youthSwitch()),
                    Optional.of(ste.serviceVersion()), Optional.empty());
        }

        final Optional<ExplanationEntry> covering = entries.stream()
                .filter(entry -> !entry.coversWhole())
                .filter(entry -> entry.coveredPaths().containsAll(diffPaths))
                .findFirst();
        final HearingComparisonClass comparisonClass = covering.isPresent()
                ? HearingComparisonClass.EXPLAINED_DIFFERENCE
                : HearingComparisonClass.UNEXPLAINED_DIFFERENCE;

        return new HearingComparison(id.hearingId(), id.hearingDay(), id.sharedTime(),
                comparisonClass, diffPaths, covering, Optional.empty(), Optional.of(ste.status()),
                Optional.of(ste.payloadSource()), Optional.of(ste.youthSwitch()),
                Optional.of(ste.serviceVersion()), Optional.empty());
    }

    private static HearingComparison unpaired(final ShareIdentity id,
            final HearingComparisonClass comparisonClass,
            final Optional<ExplanationEntry> explanation,
            final Optional<SteAttempt> steAttempt) {
        return new HearingComparison(id.hearingId(), id.hearingDay(), id.sharedTime(),
                comparisonClass, List.of(), explanation, Optional.empty(),
                steAttempt.map(SteAttempt::status), steAttempt.map(SteAttempt::payloadSource),
                steAttempt.map(SteAttempt::youthSwitch), steAttempt.map(SteAttempt::serviceVersion),
                Optional.empty());
    }

    private static boolean isDefaulted(final String youthSwitch) {
        return DEFAULTED_ACTIVE.equals(youthSwitch) || DEFAULTED_INACTIVE.equals(youthSwitch);
    }

    private static boolean isUndelivered(final String steStatus) {
        return STATUS_FAILED.equals(steStatus) || STATUS_PARKED.equals(steStatus);
    }

    /**
     * An entry is stale when it names no current share, or when the share it names has nothing
     * left for it to explain: a match or registered deviation (no difference), or a difference/
     * unpaired/unusable share none of whose current coverage the entry's own paths or whole-cover
     * reach (FR-006-006).
     */
    private static List<ExplanationEntry> findStale(final List<ExplanationEntry> explanations,
            final Map<ShareIdentity, HearingComparison> comparisonsByIdentity) {
        final List<ExplanationEntry> stale = new ArrayList<>();
        for (final ExplanationEntry entry : explanations) {
            final HearingComparison comparison = comparisonsByIdentity.get(entry.identity());
            if (comparison == null || isStaleAgainst(entry, comparison)) {
                stale.add(entry);
            }
        }
        return stale;
    }

    @SuppressWarnings("PMD.OnlyOneReturn")
    private static boolean isStaleAgainst(final ExplanationEntry entry,
            final HearingComparison comparison) {
        final boolean wholeCoverable = switch (comparison.comparisonClass()) {
            case UNPAIRED_SIT_ONLY, UNPAIRED_STE_ONLY, UNPAIRED_AMBIGUOUS, SIT_BODY_UNUSABLE -> true;
            default -> false;
        };
        if (entry.coversWhole()) {
            return !wholeCoverable;
        }
        if (wholeCoverable) {
            return true;
        }
        return entry.coveredPaths().stream().noneMatch(comparison.differingPaths()::contains);
    }
}
