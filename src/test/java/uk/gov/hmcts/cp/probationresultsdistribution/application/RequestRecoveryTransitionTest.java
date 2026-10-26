package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * Recovery after a replay (US1 half of FR-004-012/FR-004-013). A terminal leg completes the request
 * {@code completion_reason = recovered} whenever {@code audit_note} carries the
 * {@code REPLAYED_AFTER_FAILURE} marker — including when the first replayed run parks ({@code RETRYING})
 * and a later redelivery settles it, because the marker, not which run settled it, decides. The
 * transition is fenced by the request claim: a completion by a claim that no longer matches changes
 * nothing. (The leg-write fencing of FR-004-013 is pinned in {@code RollbackFencingIT}.)
 */
@DisplayName("recovery after a replay")
class RequestRecoveryTransitionTest extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4L).plusSeconds(30L));

    @Test
    @DisplayName("completes recovered when the admitting replayed run settles terminally")
    void completes_recovered_on_the_admitting_run() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        final RunClaim claim = RunClaim.fresh();
        assertThat(repository.admitReplay(SOURCE, requestId, claim, ReplayRoute.SERVICE)).isTrue();

        final boolean completed = repository.markCompleted(SOURCE, requestId, claim,
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(completed).isTrue();
        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    @DisplayName("completes recovered when the first replayed run parks and a later redelivery settles")
    void completes_recovered_after_a_park_then_a_later_redelivery() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        final RunClaim first = RunClaim.fresh();
        assertThat(repository.admitReplay(SOURCE, requestId, first, ReplayRoute.SERVICE)).isTrue();

        // The first replayed run parks: RETRYING, claim released, replayed_at and audit_note kept.
        assertThat(repository.markRetrying(SOURCE, requestId, first)).isTrue();
        assertThat(requestRow(requestId).get("replayed_at")).isNotNull();

        // A later redelivery reclaims (the park released the claim) and settles terminally.
        final RunClaim second = RunClaim.fresh();
        assertThat(repository.reclaim(SOURCE, requestId, second)).isTrue();
        assertThat(repository.markCompleted(SOURCE, requestId, second,
                CompletionReason.BOTH_LEGS_TERMINAL)).isTrue();

        final Map<String, Object> row = requestRow(requestId);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("replayed_at")).isNull();
    }

    @Test
    @DisplayName("a completion by a claim that no longer matches changes nothing")
    void a_stale_claim_completion_changes_nothing() {
        final UUID requestId = UUID.randomUUID();
        seedFailedRequest(requestId);
        assertThat(repository.admitReplay(SOURCE, requestId, RunClaim.fresh(), ReplayRoute.SERVICE))
                .isTrue();

        final boolean completed = repository.markCompleted(SOURCE, requestId, RunClaim.fresh(),
                CompletionReason.BOTH_LEGS_TERMINAL);

        assertThat(completed).isFalse();
        assertThat(requestRow(requestId).get("status")).isEqualTo("RECEIVED");
    }

    private void seedFailedRequest(final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "failure_reason, exhausted_message_id) VALUES (:s, :r, gen_random_uuid(), "
                        + "DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', 'FAILED', 1, '{}', "
                        + "'leg-exhausted', :exhaustedId)")
                .param("s", SOURCE).param("r", requestId)
                .param("exhaustedId", "RESULTS:" + requestId)
                .update();
    }

    private Map<String, Object> requestRow(final UUID requestId) {
        return jdbc().sql("SELECT status, completion_reason, failure_reason, replayed_at "
                        + "FROM processed_request WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
    }
}
