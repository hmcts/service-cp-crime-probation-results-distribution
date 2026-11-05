package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@DisplayName("the processed_request table (V1)")
class ProcessedRequestSchemaIT extends SchemaTestSupport {

    @Test
    @DisplayName("has the IR block columns plus the command and replayed_at ALTERs")
    void has_the_ir_block_columns_plus_the_alters() {
        final List<String> columns = jdbc().sql(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_name = 'processed_request'")
                .query(String.class).list();

        assertThat(columns).contains(
                "source", "request_id", "hearing_id", "hearing_day", "shared_time", "event_type",
                "request_fingerprint", "status", "attempts", "completion_reason", "failure_reason",
                "exhausted_message_id", "audit_note", "claim_owner", "claim_token",
                "claim_expires_at", "created_at", "updated_at", "command", "replayed_at");
    }

    @Test
    @DisplayName("carries the replaced exhausted-id and the completion-/failure-reason CHECKs")
    void carries_the_named_checks() {
        final List<String> checks = jdbc().sql(
                        "SELECT conname FROM pg_constraint "
                                + "WHERE conrelid = 'processed_request'::regclass AND contype = 'c'")
                .query(String.class).list();

        assertThat(checks).contains(
                "processed_request_exhausted_id_chk",
                "processed_request_completion_reason_chk",
                "processed_request_failure_reason_chk");
    }

    @Test
    @DisplayName("indexes (hearing_id, hearing_day) for support lookups")
    void indexes_hearing_id_and_day() {
        final List<String> indexes = jdbc().sql(
                        "SELECT indexname FROM pg_indexes WHERE tablename = 'processed_request'")
                .query(String.class).list();

        assertThat(indexes).contains("processed_request_hearing_idx");
    }

    @Test
    @DisplayName("requires an exhausted_message_id on a FAILED(leg-exhausted) row")
    void requires_exhausted_message_id_on_leg_exhausted() {
        final UUID requestId = UUID.randomUUID();
        assertThatThrownBy(() -> execute(
                "INSERT INTO processed_request "
                        + "(source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                        + " request_fingerprint, status, failure_reason, command) VALUES ("
                        + "'RESULTS', '" + requestId + "', gen_random_uuid(), DATE '2026-08-19', "
                        + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', "
                        + "'FAILED', 'leg-exhausted', '{}')"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("refuses a completion reason outside the set and off a COMPLETED row")
    void refuses_a_bad_completion_reason() {
        final UUID good = UUID.randomUUID();
        insertReceivedRequest("RESULTS", good.toString());
        assertThatThrownBy(() -> execute(
                "UPDATE processed_request SET status = 'COMPLETED', completion_reason = 'nope' "
                        + "WHERE request_id = '" + good + "'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("refuses a failure reason on a non-FAILED row")
    void refuses_a_failure_reason_off_failed() {
        final UUID good = UUID.randomUUID();
        insertReceivedRequest("RESULTS", good.toString());
        assertThatThrownBy(() -> execute(
                "UPDATE processed_request SET failure_reason = 'leg-exhausted' "
                        + "WHERE request_id = '" + good + "'"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("refuses a partial claim triple")
    void refuses_a_partial_claim_triple() {
        final UUID good = UUID.randomUUID();
        insertReceivedRequest("RESULTS", good.toString());
        assertThatThrownBy(() -> execute(
                "UPDATE processed_request SET claim_owner = 'owner' "
                        + "WHERE request_id = '" + good + "'"))
                .isInstanceOf(DataAccessException.class);
    }
}
