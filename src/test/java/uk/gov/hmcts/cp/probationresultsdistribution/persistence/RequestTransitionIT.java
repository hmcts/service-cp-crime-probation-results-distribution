package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

@DisplayName("the per-request state transition")
class RequestTransitionIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4).plusSeconds(30));

    @Test
    @DisplayName("moves a claimed request to COMPLETED(both-legs-terminal) and releases the claim")
    void completes_a_claimed_request() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReceived(requestId, claim);

        final boolean moved = repository.markCompleted(SOURCE, requestId, claim,
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(moved).isTrue();
        final var row = status(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(row.get("claim_owner")).isNull();
    }

    @Test
    @DisplayName("refuses to complete when the claim no longer matches")
    void refuses_complete_for_a_stale_claim() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());

        final boolean moved = repository.markCompleted(SOURCE, requestId, RunClaim.fresh(),
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(moved).isFalse();
        assertThat(status(requestId).get("status")).isEqualTo("RECEIVED");
    }

    @Test
    @DisplayName("moves a claimed request to RETRYING and releases the claim")
    void retries_a_claimed_request() {
        final UUID requestId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReceived(requestId, claim);

        final boolean moved = repository.markRetrying(SOURCE, requestId, claim);

        assertThat(moved).isTrue();
        final var row = status(requestId);
        assertThat(row.get("status")).isEqualTo("RETRYING");
        assertThat(row.get("claim_owner")).isNull();
    }

    @Test
    @DisplayName("refuses to retry when the claim no longer matches")
    void refuses_retry_for_a_stale_claim() {
        final UUID requestId = UUID.randomUUID();
        seedReceived(requestId, RunClaim.fresh());

        assertThat(repository.markRetrying(SOURCE, requestId, RunClaim.fresh())).isFalse();
    }

    private void seedReceived(final UUID requestId, final RunClaim claim) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:s, :r, "
                        + "gen_random_uuid(), DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', "
                        + "'RECEIVED', 1, '{}', :owner, :token, now() + interval '5 minutes')")
                .param("s", SOURCE).param("r", requestId)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
    }

    private java.util.Map<String, Object> status(final UUID requestId) {
        return jdbc().sql("SELECT status, completion_reason, claim_owner FROM processed_request "
                        + "WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
    }
}
