package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The JDBC {@link RequestListingRepository}: plain {@code SELECT}s over {@code processed_request},
 * {@code outbound_message} and {@code outbound_message_latest}. The select list names identifiers,
 * statuses and reasons, attempt numbers and origins, HTTP statuses and times only, so a listing
 * never reads the stored command, {@code audit_note}, an operator, a body or a {@code userId}
 * (FR-004-041).
 */
public final class JdbcRequestListingRepository implements RequestListingRepository {

    private static final String SELECT = "SELECT pr.request_id, pr.hearing_id, pr.hearing_day, "
            + "pr.status AS request_status, pr.failure_reason, pr.completion_reason, om.attempt, "
            + "om.attempt_origin, om.status AS leg_status, om.reason AS leg_reason, "
            + "om.http_status, om.updated_at, om.sent_at FROM processed_request pr ";
    private static final String LIMIT = "limit";

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the JDBC client
     */
    public JdbcRequestListingRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ListedAttempt> byHearing(final UUID hearingId, final LocalDate hearingDay,
                                         final int limit) {
        return jdbc.sql(SELECT + "JOIN outbound_message om ON om.source = pr.source "
                        + "AND om.request_id = pr.request_id AND om.leg = 'HMPPS' "
                        + "WHERE pr.hearing_id = :hearingId AND pr.hearing_day = :hearingDay "
                        + "ORDER BY pr.request_id, om.attempt LIMIT :limit")
                .param("hearingId", hearingId)
                .param("hearingDay", hearingDay)
                .param(LIMIT, limit)
                .query((rs, rowNum) -> map(rs))
                .list();
    }

    @Override
    public List<ListedAttempt> byLatestAttempt(final String status, final Optional<String> reason,
                                               final Optional<Instant> updatedAfter,
                                               final Optional<Instant> updatedBefore,
                                               final int limit) {
        return jdbc.sql(SELECT + "JOIN outbound_message_latest om ON om.source = pr.source "
                        + "AND om.request_id = pr.request_id AND om.leg = 'HMPPS' "
                        + "WHERE om.status = :status "
                        + "AND (CAST(:reason AS text) IS NULL OR om.reason = :reason) "
                        + "AND (CAST(:after AS timestamptz) IS NULL OR om.updated_at >= :after) "
                        + "AND (CAST(:before AS timestamptz) IS NULL OR om.updated_at < :before) "
                        + "ORDER BY om.updated_at, pr.request_id LIMIT :limit")
                .param("status", status)
                .param("reason", reason.orElse(null))
                .param("after", updatedAfter.map(JdbcRequestListingRepository::utc).orElse(null))
                .param("before", updatedBefore.map(JdbcRequestListingRepository::utc).orElse(null))
                .param(LIMIT, limit)
                .query((rs, rowNum) -> map(rs))
                .list();
    }

    @Override
    public Map<UUID, String> requestStatuses(final String source,
                                             final Collection<UUID> requestIds) {
        Map<UUID, String> statuses = Map.of();
        if (!requestIds.isEmpty()) {
            statuses = jdbc.sql("SELECT request_id, status FROM processed_request "
                            + "WHERE source = :source AND request_id IN (:requestIds)")
                    .param("source", source)
                    .param("requestIds", List.copyOf(requestIds))
                    .query((rs, rowNum) -> Map.entry(rs.getObject("request_id", UUID.class),
                            rs.getString("status")))
                    .list().stream()
                    .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        }
        return statuses;
    }

    private static ListedAttempt map(final ResultSet rs) throws SQLException {
        final Integer httpStatus = rs.getObject("http_status", Integer.class);
        final OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
        return new ListedAttempt(
                rs.getObject("request_id", UUID.class),
                rs.getObject("hearing_id", UUID.class),
                rs.getObject("hearing_day", LocalDate.class),
                rs.getString("request_status"),
                Optional.ofNullable(rs.getString("failure_reason")),
                Optional.ofNullable(rs.getString("completion_reason")),
                rs.getInt("attempt"),
                rs.getString("attempt_origin"),
                rs.getString("leg_status"),
                Optional.ofNullable(rs.getString("leg_reason")),
                httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(sentAt).map(OffsetDateTime::toInstant));
    }

    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
