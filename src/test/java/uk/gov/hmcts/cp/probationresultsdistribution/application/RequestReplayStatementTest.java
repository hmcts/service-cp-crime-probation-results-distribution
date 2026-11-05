package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * The replay admission statement (FR-004-001, FR-004-004): one statement whose condition is the
 * DV-26 predicate moves a {@code FAILED} request to {@code RECEIVED}, clears {@code failure_reason}
 * and {@code exhausted_message_id}, sets {@code replayed_at}, appends — never replaces — the
 * {@code REPLAYED_AFTER_FAILURE prior=<reason>} line and the route line to {@code audit_note}, and
 * takes a fresh request claim. A statement that does not match a {@code FAILED} row changes nothing.
 */
@DisplayName("the replay admission statement")
class RequestReplayStatementTest extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4L).plusSeconds(30L));

    @Test
    @DisplayName("moves FAILED to RECEIVED, clears the failure columns, sets replayed_at, "
            + "appends the two audit lines and takes a fresh claim")
    void admits_a_failed_request_as_a_replay() {
        final UUID requestId = UUID.randomUUID();
        final String exhaustedId = "RESULTS:" + requestId;
        seedFailedRequest(requestId, "leg-exhausted", exhaustedId,
                "2026-10-26T09:00:00Z PRIOR_LINE");
        final RunClaim claim = RunClaim.fresh();

        final boolean admitted = repository.admitReplay(SOURCE, requestId, claim, ReplayRoute.SERVICE);

        assertThat(admitted).isTrue();
        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("exhausted_message_id")).isNull();
        assertThat(row.get("replayed_at")).isNotNull();
        assertThat(row.get("audit_note")).asString()
                .contains("PRIOR_LINE")
                .contains("REPLAYED_AFTER_FAILURE prior=leg-exhausted")
                .contains("route=service");
        assertThat(row.get("claim_owner")).isEqualTo(claim.owner().toString());
        assertThat(row.get("claim_token")).isEqualTo(claim.token());
        assertThat(row.get("claim_expires_at")).isNotNull();
    }

    @Test
    @DisplayName("records the route as an audit label only, with no behaviour of its own")
    void records_the_copy_route() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId, "leg-exhausted", "RESULTS:" + requestId, null);

        assertThat(repository.admitReplay(SOURCE, requestId, RunClaim.fresh(), ReplayRoute.COPY))
                .isTrue();
        assertThat(requestRow(requestId).get("audit_note")).asString().contains("route=copy");
    }

    @Test
    @DisplayName("records the support route in audit_note, admitting a FAILED request")
    void records_the_support_route() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId, "leg-exhausted", "RESULTS:" + requestId, null);

        assertThat(repository.admitReplay(SOURCE, requestId, RunClaim.fresh(), ReplayRoute.SUPPORT))
                .isTrue();
        assertThat(requestRow(requestId).get("audit_note")).asString().contains("route=support");
    }

    @Test
    @DisplayName("matches no row and changes nothing when the request is not FAILED")
    void does_not_touch_a_non_failed_request() {
        final UUID requestId = UUID.randomUUID();
        seedCompletedRequest(requestId);

        final boolean admitted = repository.admitReplay(SOURCE, requestId, RunClaim.fresh(),
                ReplayRoute.COPY);

        assertThat(admitted).isFalse();
        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("replayed_at")).isNull();
        assertThat(row.get("claim_owner")).isNull();
    }

    private void seedFailedRequest(final UUID requestId, final String failureReason,
                                   final String exhaustedMessageId, final String auditNote) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "failure_reason, exhausted_message_id, audit_note) VALUES (:s, :r, "
                        + "gen_random_uuid(), DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', "
                        + "'FAILED', 1, '{}', :reason, :exhaustedId, :note)")
                .param("s", SOURCE)
                .param("r", requestId)
                .param("reason", failureReason)
                .param("exhaustedId", exhaustedMessageId)
                .param("note", auditNote)
                .update();
    }

    private void seedCompletedRequest(final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "completion_reason) VALUES (:s, :r, gen_random_uuid(), DATE '2026-08-19', "
                        + "now(), 'Hearing_Resulted', 'fp', 'COMPLETED', 1, '{}', 'both-legs-terminal')")
                .param("s", SOURCE)
                .param("r", requestId)
                .update();
    }

    private Map<String, Object> requestRow(final UUID requestId) {
        return jdbc().sql("SELECT status, failure_reason, exhausted_message_id, replayed_at, "
                        + "audit_note, claim_owner, claim_token, claim_expires_at "
                        + "FROM processed_request WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
    }
}
