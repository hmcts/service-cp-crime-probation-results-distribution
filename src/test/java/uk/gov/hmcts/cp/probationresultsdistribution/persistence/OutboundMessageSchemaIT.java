package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("the outbound_message table (V1)")
class OutboundMessageSchemaIT extends SchemaTestSupport {

    @Test
    @DisplayName("accepts the valid outcome rows this slice writes")
    void accepts_valid_outcome_rows() {
        final String req = freshRequest();
        assertThatCode(() -> execute(leg(req, Map.of("status", "'PENDING'")))).doesNotThrowAnyException();

        final String sentReq = freshRequest();
        assertThatCode(() -> execute(leg(sentReq, sent()))).doesNotThrowAnyException();

        final String naReq = freshRequest();
        assertThatCode(() -> execute(leg(naReq, Map.of(
                "status", "'NOT_APPLICABLE'", "reason", "'sjp-or-box-hearing'",
                "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'"))))
                .doesNotThrowAnyException();

        final String parkReq = freshRequest();
        assertThatCode(() -> execute(leg(parkReq, Map.of(
                "status", "'PARKED'", "reason", "'payload-unavailable'"))))
                .doesNotThrowAnyException();

        final String transientReq = freshRequest();
        assertThatCode(() -> execute(leg(transientReq, Map.of(
                "status", "'PARKED'", "reason", "'gateway-transient'",
                "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'",
                "body_digest", "'deadbeef'", "service_version", "'v1'", "http_status", "503"))))
                .doesNotThrowAnyException();

        final String rejectedReq = freshRequest();
        assertThatCode(() -> execute(leg(rejectedReq, Map.of(
                "status", "'FAILED'", "reason", "'receiver-rejected'",
                "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'",
                "body_digest", "'deadbeef'", "service_version", "'v1'", "http_status", "400"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a SENT row with no body digest, no http status, or no sent_at")
    void refuses_sent_without_evidence() {
        rejects(sentOverriding(Map.of("body_digest", "NULL", "service_version", "NULL")));
        rejects(sentOverriding(Map.of("http_status", "NULL")));
        rejects(sentOverriding(Map.of("sent_at", "NULL")));
    }

    @Test
    @DisplayName("refuses a reason on PENDING and a null reason on FAILED")
    void refuses_bad_status_reason_pairs() {
        rejects(Map.of("status", "'PENDING'", "reason", "'gateway-transient'"));
        rejects(Map.of("status", "'FAILED'", "reason", "NULL",
                "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'"));
    }

    @Test
    @DisplayName("refuses values outside the youth_switch and payload_source sets")
    void refuses_values_outside_closed_sets() {
        rejects(Map.of("status", "'NOT_APPLICABLE'", "reason", "'sjp-or-box-hearing'",
                "payload_source", "'cache-day'", "youth_switch", "'MAYBE'"));
        rejects(Map.of("status", "'NOT_APPLICABLE'", "reason", "'sjp-or-box-hearing'",
                "payload_source", "'somewhere'", "youth_switch", "'ACTIVE'"));
    }

    @Test
    @DisplayName("ties attempt 1 to attempt_origin queue")
    void ties_attempt_one_to_queue() {
        rejects(Map.of("attempt", "1", "attempt_origin", "'resend'", "status", "'PENDING'"));
    }

    @Test
    @DisplayName("requires service_version exactly with body_digest, and payload_source with digest")
    void enforces_presence_and_pairing() {
        rejects(Map.of("status", "'FAILED'", "reason", "'receiver-rejected'",
                "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'",
                "body_digest", "'deadbeef'", "http_status", "400"));
        rejects(Map.of("status", "'FAILED'", "reason", "'receiver-rejected'",
                "youth_switch", "'ACTIVE'", "body_digest", "'deadbeef'",
                "service_version", "'v1'", "http_status", "400"));
    }

    @Test
    @DisplayName("keeps youth_switch null on a PENDING row")
    void youth_switch_null_on_pending() {
        rejects(Map.of("status", "'PENDING'", "payload_source", "'cache-day'",
                "youth_switch", "'ACTIVE'"));
    }

    @Test
    @DisplayName("refuses a note longer than 256 characters")
    void refuses_overlong_note() {
        rejects(Map.of("status", "'PENDING'", "note", "'" + "x".repeat(257) + "'"));
    }

    @Test
    @DisplayName("refuses a second row with the same (source, request_id, leg, attempt)")
    void refuses_duplicate_attempt() {
        final String req = freshRequest();
        execute(leg(req, Map.of("status", "'PENDING'")));
        assertThatThrownBy(() -> execute(leg(req, Map.of("status", "'PENDING'"))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("refuses a leg row with no parent request (FK)")
    void refuses_orphan_leg() {
        final String orphan = UUID.randomUUID().toString();
        assertThatThrownBy(() -> execute(
                "INSERT INTO outbound_message (id, source, request_id, leg, attempt, attempt_origin,"
                        + " status) VALUES (gen_random_uuid(), 'RESULTS', '" + orphan
                        + "', 'HMPPS', 1, 'queue', 'PENDING')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("requires the leg claim triple and resend_run_id exactly on a resend row")
    void enforces_resend_claim_presence() {
        final String req = freshRequest();
        execute(leg(req, Map.of("status", "'PENDING'")));
        assertThatThrownBy(() -> execute(leg(req, Map.of(
                "attempt", "2", "attempt_origin", "'resend'", "status", "'PENDING'"))))
                .isInstanceOf(DataAccessException.class);

        final String ok = freshRequest();
        execute(leg(ok, Map.of("status", "'PENDING'")));
        assertThatCode(() -> execute(leg(ok, Map.of(
                "attempt", "2", "attempt_origin", "'resend'", "status", "'PENDING'",
                "claim_owner", "'owner'", "claim_token", "gen_random_uuid()",
                "claim_expires_at", "now()", "resend_run_id", "gen_random_uuid()"))))
                .doesNotThrowAnyException();
    }

    private static Map<String, String> sent() {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("status", "'SENT'");
        row.put("http_status", "200");
        row.put("body_digest", "'deadbeef'");
        row.put("service_version", "'v1'");
        row.put("payload_source", "'cache-day'");
        row.put("youth_switch", "'ACTIVE'");
        row.put("sent_at", "now()");
        return row;
    }

    private static Map<String, String> sentOverriding(final Map<String, String> overrides) {
        final Map<String, String> row = new LinkedHashMap<>(sent());
        row.putAll(overrides);
        return row;
    }

    private void rejects(final Map<String, String> cols) {
        final String req = freshRequest();
        assertThatThrownBy(() -> execute(leg(req, cols)))
                .as("row %s should be rejected", cols)
                .isInstanceOf(DataAccessException.class);
    }

    private String freshRequest() {
        final String req = UUID.randomUUID().toString();
        insertReceivedRequest("RESULTS", req);
        return req;
    }

    private static String leg(final String requestId, final Map<String, String> cols) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("id", "gen_random_uuid()");
        row.put("source", "'RESULTS'");
        row.put("request_id", "'" + requestId + "'");
        row.put("leg", "'HMPPS'");
        row.put("attempt", "1");
        row.put("attempt_origin", "'queue'");
        row.putAll(cols);
        final String names = String.join(", ", row.keySet());
        final String values = String.join(", ", row.values());
        return "INSERT INTO outbound_message (" + names + ") VALUES (" + values + ")";
    }
}
