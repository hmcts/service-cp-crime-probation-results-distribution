package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("the outbound_body table (V1)")
class OutboundBodySchemaIT extends SchemaTestSupport {

    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy'_'MM");

    @Test
    @DisplayName("creates the current month, the next two and a DEFAULT partition")
    void creates_the_expected_partitions() {
        final List<String> partitions = jdbc().sql(
                        "SELECT inhrelid::regclass::text FROM pg_inherits "
                                + "WHERE inhparent = 'outbound_body'::regclass")
                .query(String.class).list();

        final YearMonth now = YearMonth.now(ZoneOffset.UTC);
        assertThat(partitions).contains(
                "outbound_body_default",
                "outbound_body_" + now.format(MONTH),
                "outbound_body_" + now.plusMonths(1).format(MONTH),
                "outbound_body_" + now.plusMonths(2).format(MONTH));
    }

    @Test
    @DisplayName("accepts a body whose SHA-256 matches its attempt's digest")
    void accepts_a_matching_body() {
        final String messageId = sentLegWithDigestOf("the exact bytes");
        assertThatCode(() -> execute(
                "INSERT INTO outbound_body (outbound_message_id, body) VALUES ('"
                        + messageId + "', 'the exact bytes')"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects a body whose SHA-256 differs from its attempt's digest")
    void rejects_a_mismatched_body() {
        final String messageId = sentLegWithDigestOf("the exact bytes");
        assertThatThrownBy(() -> execute(
                "INSERT INTO outbound_body (outbound_message_id, body) VALUES ('"
                        + messageId + "', 'different bytes')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("leaves a body with no parent attempt to the foreign key")
    void rejects_an_orphan_body() {
        assertThatThrownBy(() -> execute(
                "INSERT INTO outbound_body (outbound_message_id, body) VALUES ('"
                        + UUID.randomUUID() + "', 'anything')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("restricts deleting an attempt that still has a stored body")
    void restricts_deleting_a_parent_with_a_body() {
        final String messageId = sentLegWithDigestOf("kept bytes");
        execute("INSERT INTO outbound_body (outbound_message_id, body) VALUES ('"
                + messageId + "', 'kept bytes')");
        assertThatThrownBy(() -> execute(
                "DELETE FROM outbound_message WHERE id = '" + messageId + "'"))
                .isInstanceOf(DataAccessException.class);
    }

    private String sentLegWithDigestOf(final String body) {
        final String requestId = UUID.randomUUID().toString();
        insertReceivedRequest("RESULTS", requestId);
        final UUID messageId = UUID.randomUUID();
        execute("INSERT INTO outbound_message "
                + "(id, source, request_id, leg, attempt, attempt_origin, status, http_status, "
                + " body_digest, service_version, payload_source, youth_switch, sent_at) VALUES ("
                + "'" + messageId + "', 'RESULTS', '" + requestId + "', 'HMPPS', 1, 'queue', 'SENT', "
                + "200, encode(sha256(convert_to('" + body + "', 'UTF8')), 'hex'), 'v1', "
                + "'cache-day', 'ACTIVE', now())");
        return messageId.toString();
    }
}
