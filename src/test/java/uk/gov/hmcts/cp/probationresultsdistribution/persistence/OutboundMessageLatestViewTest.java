package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("the outbound_message_latest view (V1)")
class OutboundMessageLatestViewTest extends SchemaTestSupport {

    @Test
    @DisplayName("returns the highest attempt per (source, request_id, leg)")
    void returns_the_highest_attempt_per_leg() {
        final String requestId = UUID.randomUUID().toString();
        insertReceivedRequest("RESULTS", requestId);

        execute("INSERT INTO outbound_message "
                + "(id, source, request_id, leg, attempt, attempt_origin, status, http_status, "
                + " body_digest, service_version, payload_source, youth_switch, sent_at) VALUES ("
                + "gen_random_uuid(), 'RESULTS', '" + requestId + "', 'HMPPS', 1, 'queue', 'SENT', "
                + "200, 'deadbeef', 'v1', 'cache-day', 'ACTIVE', now())");
        execute("INSERT INTO outbound_message "
                + "(id, source, request_id, leg, attempt, attempt_origin, status, "
                + " claim_owner, claim_token, claim_expires_at, resend_run_id) VALUES ("
                + "gen_random_uuid(), 'RESULTS', '" + requestId + "', 'HMPPS', 2, 'resend', "
                + "'PENDING', 'owner', gen_random_uuid(), now(), gen_random_uuid())");

        final Integer latestAttempt = jdbc().sql(
                        "SELECT attempt FROM outbound_message_latest WHERE request_id = '"
                                + requestId + "' AND leg = 'HMPPS'")
                .query(Integer.class).single();
        final String latestStatus = jdbc().sql(
                        "SELECT status FROM outbound_message_latest WHERE request_id = '"
                                + requestId + "' AND leg = 'HMPPS'")
                .query(String.class).single();

        assertThat(latestAttempt).isEqualTo(2);
        assertThat(latestStatus).isEqualTo("PENDING");
    }
}
