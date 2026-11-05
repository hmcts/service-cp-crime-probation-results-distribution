package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.SupportSubmission;

/**
 * Pins the submit-hearing admission audit (slice 004, US8; FR-004-057): a delivery marked
 * {@code origin = SUPPORT} appends one timestamped line with its operator, run id and reason to
 * {@code audit_note} in the admission's own statement or transaction — on a first delivery and on a
 * replay, after the replay's own lines — and changes nothing else about the admission.
 */
@DisplayName("the submit-hearing admission audit")
class SupportSubmissionAuditIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";
    private static final SupportSubmission SUPPORT = new SupportSubmission("local-dev",
            "7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77", "share never admitted");
    private static final String LINE = " SUPPORT_SUBMISSION operator=local-dev "
            + "runId=7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77 reason=share never admitted";

    private final ProcessedRequestRepository repository = new JdbcProcessedRequestRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            Duration.ofMinutes(4).plusSeconds(30));

    @Test
    @DisplayName("a first delivery's claim is inserted with the admission line on audit_note")
    void insertClaim_withSupport_should_append_the_line() {
        final DistributionCommand command = command();

        final boolean inserted = repository.insertClaim(command, "{}", "fp", RunClaim.fresh(),
                SUPPORT);

        assertThat(inserted).isTrue();
        final Map<String, Object> row = row(command.requestId());
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat((String) row.get("audit_note")).matches("\\d{4}-\\d{2}-\\d{2} .+" + LINE);
        assertThat(attemptOrigin(command.requestId())).isEqualTo("queue");
    }

    @Test
    @DisplayName("a first delivery with no markers writes no audit line")
    void insertClaim_withoutSupport_should_leave_audit_note_empty() {
        final DistributionCommand command = command();

        repository.insertClaim(command, "{}", "fp", RunClaim.fresh());

        assertThat(row(command.requestId()).get("audit_note")).isNull();
    }

    @Test
    @DisplayName("a lost insert race writes nothing and answers false")
    void insertClaim_withSupport_duplicate_should_answer_false() {
        final DistributionCommand command = command();
        repository.insertClaim(command, "{}", "fp", RunClaim.fresh());

        assertThat(repository.insertClaim(command, "{}", "fp", RunClaim.fresh(), SUPPORT))
                .isFalse();
        assertThat(row(command.requestId()).get("audit_note")).isNull();
    }

    @Test
    @DisplayName("a replay of a FAILED request appends the admission line after its route line")
    void admitReplay_withSupport_should_append_the_line_last() {
        final UUID requestId = seedFailed();

        final boolean admitted = repository.admitReplay(SOURCE, requestId, RunClaim.fresh(),
                ReplayRoute.SUPPORT, SUPPORT);

        assertThat(admitted).isTrue();
        final Map<String, Object> row = row(requestId);
        assertThat(row.get("status")).isEqualTo("RECEIVED");
        assertThat(row.get("replayed_at")).isNotNull();
        final String[] lines = ((String) row.get("audit_note")).split("\n");
        assertThat(lines).hasSize(3);
        assertThat(lines[0]).endsWith(" REPLAYED_AFTER_FAILURE prior=leg-exhausted");
        assertThat(lines[1]).endsWith(" route=support");
        assertThat(lines[2]).endsWith(LINE);
    }

    @Test
    @DisplayName("a replay that finds the request no longer FAILED writes nothing")
    void admitReplay_withSupport_notFailed_should_write_nothing() {
        final DistributionCommand command = command();
        repository.insertClaim(command, "{}", "fp", RunClaim.fresh());

        assertThat(repository.admitReplay(SOURCE, command.requestId(), RunClaim.fresh(),
                ReplayRoute.SUPPORT, SUPPORT)).isFalse();
        assertThat(row(command.requestId()).get("audit_note")).isNull();
    }

    private static DistributionCommand command() {
        return new DistributionCommand(SOURCE, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private UUID seedFailed() {
        final UUID requestId = UUID.randomUUID();
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, "
                        + "failure_reason, exhausted_message_id, command) VALUES ('RESULTS', :id, "
                        + ":hearingId, '2026-08-19', now(), 'Hearing_Resulted', 'fp', 'FAILED', 5, "
                        + "'leg-exhausted', :exhausted, '{}')")
                .param("id", requestId)
                .param("hearingId", UUID.randomUUID())
                .param("exhausted", "RESULTS:" + requestId)
                .update();
        return requestId;
    }

    private Map<String, Object> row(final UUID requestId) {
        return jdbc().sql("SELECT * FROM processed_request WHERE request_id = :id")
                .param("id", requestId).query().singleRow();
    }

    private String attemptOrigin(final UUID requestId) {
        return jdbc().sql("SELECT attempt_origin FROM outbound_message WHERE request_id = :id")
                .param("id", requestId).query(String.class).single();
    }
}
