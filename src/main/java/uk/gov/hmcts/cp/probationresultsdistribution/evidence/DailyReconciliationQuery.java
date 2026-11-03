package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The daily reconciliation's per-day counts (slice 006, FR-006-023 to FR-006-026, §9.5 row 2).
 * Reads only {@code outbound_message_latest} — never {@code processed_request.status} — so a
 * request recovered by a resend counts once, and a completed request whose resend failed counts
 * as failed. Does not call results' own viewstore (research.md Decision 5): the UTC-day
 * results-side count is supplied externally and compared by the owning team.
 */
public final class DailyReconciliationQuery {

    private static final Set<String> RESEND_FROM_DLQ_REASONS = Set.of("exhausted");

    private final JdbcClient jdbc;

    /**
     * Creates the query.
     *
     * @param jdbc the JDBC client over the service's own store
     */
    public DailyReconciliationQuery(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The reconciliation counts for one UTC hearing day.
     *
     * @param hearingDay the day
     * @return the day's counts
     */
    public DailyCounts countsFor(final LocalDate hearingDay) {
        final int requestsReceived = jdbc.sql(
                        "SELECT count(*) FROM processed_request WHERE hearing_day = :day")
                .param("day", hearingDay)
                .query(Integer.class).single();

        int sent = 0;
        int parked = 0;
        int pending = 0;
        final Map<String, Integer> notApplicableByReason = new HashMap<>();
        final Map<String, Integer> failedByReason = new HashMap<>();

        final List<Object[]> rows = jdbc.sql(
                        "SELECT m.status, m.reason, count(*) AS n "
                        + "FROM processed_request pr "
                        + "JOIN outbound_message_latest m "
                        + "  ON m.source = pr.source AND m.request_id = pr.request_id "
                        + "  AND m.leg = 'HMPPS' "
                        + "WHERE pr.hearing_day = :day "
                        + "GROUP BY m.status, m.reason")
                .param("day", hearingDay)
                .query((rs, rowNum) -> new Object[] {rs.getString("status"), rs.getString("reason"),
                        rs.getInt("n")})
                .list();

        for (final Object[] row : rows) {
            final String status = (String) row[0];
            final String reason = (String) row[1];
            final int count = (Integer) row[2];
            switch (status) {
                case "SENT" -> sent += count;
                case "PARKED" -> parked += count;
                case "PENDING" -> pending += count;
                case "NOT_APPLICABLE" -> notApplicableByReason.merge(reason, count, Integer::sum);
                case "FAILED" -> failedByReason.merge(reason, count, Integer::sum);
                default -> throw new IllegalStateException("unknown leg status: " + status);
            }
        }

        return new DailyCounts(hearingDay, requestsReceived, sent, notApplicableByReason,
                failedByReason, parked, pending);
    }

    /**
     * §9.2 row 21 / FR-006-026: the repair route for a dead-letter entry with no
     * {@code processed_request} row — a message the service did not admit.
     *
     * @param deadLetterReason the broker dead-letter reason
     * @return {@code "resend-from-dlq"} for {@code exhausted}, {@code "submit-hearing"} otherwise
     */
    public static String repairRouteForOrphanedDeadLetter(final String deadLetterReason) {
        return RESEND_FROM_DLQ_REASONS.contains(deadLetterReason)
                ? "resend-from-dlq" : "submit-hearing";
    }

    /**
     * The soak's legacy-silence check (FR-006-027): reads the identical telemetry-line
     * definition alert 11 uses ({@link LegacyBothPathsCheck}), one definition never two that can
     * drift (User Story 6's own Independent Test).
     *
     * @param telemetryResourceEstablished whether this environment's legacy telemetry resource
     *                                     exists at all
     * @param lines                        the legacy app's own telemetry lines
     * @param cutoverInstant               the recorded cutover instant
     * @return the verdict
     */
    public static LegacyBothPathsVerdict legacySilenceCheck(
            final boolean telemetryResourceEstablished, final List<LegacyTelemetryLine> lines,
            final Instant cutoverInstant) {
        return LegacyBothPathsCheck.evaluate(telemetryResourceEstablished, lines, cutoverInstant);
    }
}
