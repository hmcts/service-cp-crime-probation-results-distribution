package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The support-lookup query (design §8, FR-002-053, SC-002-008). It answers "was this hearing sent to
 * HMPPS, when, with what answer, and with exactly which bytes?" with one read by hearing id and
 * hearing day, joining {@code processed_request} with {@code outbound_message_latest} (a leg's truth,
 * never {@code processed_request.status}) and the stored {@code outbound_body}. It is a repository
 * method only — no HTTP endpoint — because it is a support capability, not an API.
 */
public final class SupportLookupRepository {

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the JDBC client
     */
    public SupportLookupRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Looks a hearing's HMPPS leg up by hearing id and hearing day.
     *
     * @param hearingId  the hearing id
     * @param hearingDay the hearing day
     * @return the request, its latest HMPPS attempt and any stored body; empty when none matches
     */
    public Optional<SupportLookupResult> lookup(final UUID hearingId, final LocalDate hearingDay) {
        return jdbc.sql("SELECT pr.source, pr.request_id, pr.status AS request_status, "
                        + "oml.status AS leg_status, oml.reason, oml.http_status, "
                        + "oml.youth_switch, oml.payload_source, oml.body_digest, "
                        + "oml.service_version, oml.sent_at, ob.body "
                        + "FROM processed_request pr "
                        + "JOIN outbound_message_latest oml ON oml.source = pr.source "
                        + "AND oml.request_id = pr.request_id "
                        + "LEFT JOIN outbound_body ob ON ob.outbound_message_id = oml.id "
                        + "WHERE pr.hearing_id = :hearingId AND pr.hearing_day = :hearingDay")
                .param("hearingId", hearingId)
                .param("hearingDay", hearingDay)
                .query((rs, rowNum) -> map(rs))
                .optional();
    }

    private static SupportLookupResult map(final ResultSet rs) throws SQLException {
        final Integer httpStatus = rs.getObject("http_status", Integer.class);
        final OffsetDateTime sentAt = rs.getObject("sent_at", OffsetDateTime.class);
        return new SupportLookupResult(
                rs.getString("source"),
                rs.getObject("request_id", UUID.class),
                rs.getString("request_status"),
                rs.getString("leg_status"),
                Optional.ofNullable(rs.getString("reason")),
                httpStatus == null ? OptionalInt.empty() : OptionalInt.of(httpStatus),
                Optional.ofNullable(rs.getString("youth_switch")),
                Optional.ofNullable(rs.getString("payload_source")),
                Optional.ofNullable(rs.getString("body_digest")),
                Optional.ofNullable(rs.getString("service_version")),
                Optional.ofNullable(sentAt).map(OffsetDateTime::toInstant),
                Optional.ofNullable(rs.getString("body")));
    }

    /**
     * One support-lookup row: the request, its latest HMPPS attempt and the exact stored body bytes.
     *
     * @param source         the command source
     * @param requestId      the request id
     * @param requestStatus  the request status
     * @param legStatus      the latest attempt's status
     * @param reason         the latest attempt's reason, when any
     * @param httpStatus     the gateway's last status, when any
     * @param youthSwitch    the recorded switch value, when read
     * @param payloadSource  where the payload came from, when read
     * @param bodyDigest     the SHA-256 of the sent bytes, when a body was sent
     * @param serviceVersion the build version, present with the digest
     * @param sentAt         when the leg was sent, on a {@code SENT} row
     * @param body           the exact UTF-8 bytes sent, when a body row survives
     */
    public record SupportLookupResult(
            String source,
            UUID requestId,
            String requestStatus,
            String legStatus,
            Optional<String> reason,
            OptionalInt httpStatus,
            Optional<String> youthSwitch,
            Optional<String> payloadSource,
            Optional<String> bodyDigest,
            Optional<String> serviceVersion,
            Optional<Instant> sentAt,
            Optional<String> body) {

        /** Rejects null components; absence is an empty optional, never null. */
        public SupportLookupResult {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(requestStatus, "requestStatus");
            Objects.requireNonNull(legStatus, "legStatus");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(httpStatus, "httpStatus");
            Objects.requireNonNull(youthSwitch, "youthSwitch");
            Objects.requireNonNull(payloadSource, "payloadSource");
            Objects.requireNonNull(bodyDigest, "bodyDigest");
            Objects.requireNonNull(serviceVersion, "serviceVersion");
            Objects.requireNonNull(sentAt, "sentAt");
            Objects.requireNonNull(body, "body");
        }
    }
}
