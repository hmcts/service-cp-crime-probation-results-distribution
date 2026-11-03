package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * A disposable schema per test, mirroring 003's {@code MaintenanceTestStore}, with seeding helpers
 * for the go/no-go evidence rows this slice's queries read (slice 006, US2/US5/US7's own
 * Independent Tests).
 */
abstract class EvidenceTestStore extends SchemaTestSupport {

    private final String schema = "evidence_" + UUID.randomUUID().toString().replace("-", "");
    protected final JdbcClient sql;

    protected EvidenceTestStore() {
        execute("CREATE SCHEMA " + schema);
        final DriverManagerDataSource shared = (DriverManagerDataSource) datasource();
        final DriverManagerDataSource source = new DriverManagerDataSource(
                shared.getUrl() + (shared.getUrl().contains("?") ? "&" : "?")
                        + "currentSchema=" + schema,
                shared.getUsername(), shared.getPassword());
        Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();
        sql = JdbcClient.create(source);
    }

    @AfterEach
    protected void removeSchema() {
        execute("DROP SCHEMA " + schema + " CASCADE");
    }

    /**
     * Admits a {@code RECEIVED} request from the given source, with a distinct hearing.
     *
     * @param source    the command source (e.g. {@code "RESULTS"})
     * @param requestId the request id
     * @return the hearing id generated for this request
     */
    protected final UUID insertAdmittedRequest(final String source, final UUID requestId) {
        final UUID hearingId = UUID.randomUUID();
        sql.sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, command) "
                        + "VALUES (:source, :requestId, :hearingId, DATE '2026-08-19', "
                        + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', "
                        + "'RECEIVED', '{}')")
                .param("source", source).param("requestId", requestId)
                .param("hearingId", hearingId).update();
        return hearingId;
    }

    /**
     * Inserts a {@code SENT} attempt-1 HMPPS leg for the given request.
     *
     * @param source      the command source
     * @param requestId   the request id
     * @param httpStatus  the gateway's status code
     * @param youthSwitch the recorded youth switch value
     */
    protected final void insertSentLeg(final String source, final UUID requestId,
            final int httpStatus, final String youthSwitch) {
        sql.sql("INSERT INTO outbound_message (id, source, request_id, leg, status, http_status, "
                        + "body_digest, payload_source, service_version, youth_switch, sent_at) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', 'SENT', :httpStatus, "
                        + "'deadbeef', 'cache-day', 'evidence-test', :youthSwitch, now())")
                .param("id", UUID.randomUUID()).param("source", source)
                .param("requestId", requestId).param("httpStatus", httpStatus)
                .param("youthSwitch", youthSwitch).update();
        sql.sql("UPDATE processed_request SET status = 'COMPLETED', completion_reason = "
                        + "'both-legs-terminal' WHERE source = :source AND request_id = :requestId")
                .param("source", source).param("requestId", requestId).update();
    }

    /**
     * Inserts a {@code PARKED} or {@code PENDING} attempt-1 HMPPS leg in flight.
     *
     * @param source    the command source
     * @param requestId the request id
     * @param status    {@code "PARKED"} or {@code "PENDING"}
     */
    protected final void insertInFlightLeg(final String source, final UUID requestId,
            final String status) {
        final boolean parked = "PARKED".equals(status);
        // A PARKED row's presence CHECKs treat 'gateway-transient' as "the write already
        // happened" (the park-after-write case, H11/H15), so the digest columns are required
        // even though the row is not yet terminal.
        sql.sql("INSERT INTO outbound_message (id, source, request_id, leg, status, reason, "
                        + "body_digest, payload_source, service_version, youth_switch) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', :status, :reason, "
                        + ":bodyDigest, :payloadSource, :serviceVersion, :youthSwitch)")
                .param("id", UUID.randomUUID()).param("source", source)
                .param("requestId", requestId).param("status", status)
                .param("reason", parked ? "gateway-transient" : null)
                .param("bodyDigest", parked ? "deadbeef" : null)
                .param("payloadSource", parked ? "cache-day" : null)
                .param("serviceVersion", parked ? "evidence-test" : null)
                .param("youthSwitch", parked ? "ACTIVE" : null)
                .update();
        sql.sql("UPDATE processed_request SET status = 'RETRYING' "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("source", source).param("requestId", requestId).update();
    }

    /**
     * Dead-letters the request's attempt-1 leg {@code FAILED(leg-exhausted)}, as the final
     * delivery does.
     *
     * @param source           the command source
     * @param requestId        the request id
     * @param exhaustedMessageId the broker message id the delivery was exhausted under
     */
    protected final void insertLegExhausted(final String source, final UUID requestId,
            final String exhaustedMessageId) {
        sql.sql("INSERT INTO outbound_message (id, source, request_id, leg, status, reason, "
                        + "body_digest, payload_source, service_version, youth_switch) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', 'FAILED', 'gateway-transient', "
                        + "'deadbeef', 'cache-day', 'evidence-test', 'ACTIVE')")
                .param("id", UUID.randomUUID()).param("source", source)
                .param("requestId", requestId).update();
        sql.sql("UPDATE processed_request SET status = 'FAILED', failure_reason = 'leg-exhausted', "
                        + "exhausted_message_id = :exhaustedMessageId "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("exhaustedMessageId", exhaustedMessageId)
                .param("source", source).param("requestId", requestId).update();
    }

    /**
     * Recovers a dead-lettered request: a fresh attempt {@code SENT}, the request
     * {@code COMPLETED(recovered)} with {@code replayed_at} set, as the {@code copy} route's
     * admission does (DV-26, H9).
     *
     * @param source    the command source
     * @param requestId the request id
     */
    protected final void recoverByReplay(final String source, final UUID requestId) {
        sql.sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, http_status, body_digest, payload_source, "
                        + "service_version, youth_switch, sent_at) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', 2, 'replay', 'SENT', 200, "
                        + "'deadbeef', 'cache-day', 'evidence-test', 'ACTIVE', now())")
                .param("id", UUID.randomUUID()).param("source", source)
                .param("requestId", requestId).update();
        sql.sql("UPDATE processed_request SET status = 'COMPLETED', "
                        + "completion_reason = 'recovered', failure_reason = NULL, "
                        + "replayed_at = :replayedAt WHERE source = :source AND request_id = :requestId")
                .param("replayedAt", OffsetDateTime.now())
                .param("source", source).param("requestId", requestId).update();
    }

    protected static LocalDate day() {
        return LocalDate.parse("2026-08-19");
    }
}
