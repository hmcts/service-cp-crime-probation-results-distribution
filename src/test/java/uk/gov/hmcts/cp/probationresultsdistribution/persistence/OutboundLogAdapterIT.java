package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

@DisplayName("the outbound-log adapter")
class OutboundLogAdapterIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";
    private static final String BODY = "{\"hearing\":{\"id\":\"x\"}}";

    private final OutboundMessageRepository repository = new OutboundMessageRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            "test-version");
    private final OutboundLogAdapter adapter = new OutboundLogAdapter(repository);

    @Test
    @DisplayName("writes the SENT outcome under the claim and stores the exact bytes")
    void writes_the_sent_outcome_and_body() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, hearingId, claim);
        final LegOutcome outcome = sentOutcome(requestId, hearingId);

        final boolean recorded = adapter.record(outcome, claim);

        assertThat(recorded).isTrue();
        final java.util.Map<String, Object> row = jdbc().sql(
                        "SELECT oml.status, oml.http_status, oml.body_digest, "
                        + "oml.service_version, ob.body FROM outbound_message_latest oml "
                        + "LEFT JOIN outbound_body ob ON ob.outbound_message_id = oml.id "
                        + "WHERE oml.source = :s AND oml.request_id = :r")
                .param("s", SOURCE).param("r", requestId).query().singleRow();
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("service_version")).isEqualTo("test-version");
        assertThat(row.get("body")).isEqualTo(BODY);
        assertThat(sha256(BODY)).isEqualTo(row.get("body_digest"));
    }

    @Test
    @DisplayName("writes a NOT_APPLICABLE outcome with no body")
    void writes_a_not_applicable_outcome() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedPendingAttempt(requestId, hearingId, claim);
        final LegOutcome outcome = new LegOutcome(
                new DistributionCommand(SOURCE, requestId, hearingId, LocalDate.parse("2026-08-19"),
                        Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted",
                        Optional.empty()),
                AttemptOrigin.QUEUE, LegStatus.NOT_APPLICABLE,
                Optional.of(uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason
                        .SJP_OR_BOX_HEARING),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")), Optional.empty(),
                OptionalInt.empty(), Optional.empty());

        assertThat(adapter.record(outcome, claim)).isTrue();
        final String status = jdbc().sql("SELECT status FROM outbound_message_latest "
                        + "WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query(String.class).single();
        assertThat(status).isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("returns false and writes nothing when the claim no longer matches (stale runner)")
    void returns_false_for_a_stale_claim() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        seedPendingAttempt(requestId, hearingId, RunClaim.fresh());

        final boolean recorded = adapter.record(sentOutcome(requestId, hearingId), RunClaim.fresh());

        assertThat(recorded).isFalse();
        final String status = jdbc().sql("SELECT status FROM outbound_message_latest "
                        + "WHERE source = :s AND request_id = :r")
                .param("s", SOURCE).param("r", requestId).query(String.class).single();
        assertThat(status).isEqualTo("PENDING");
    }

    private void seedPendingAttempt(final UUID requestId, final UUID hearingId,
                                    final RunClaim claim) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:s, :r, :h, "
                        + "DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', 'RECEIVED', 1, '{}', "
                        + ":owner, :token, now() + interval '5 minutes')")
                .param("s", SOURCE).param("r", requestId).param("h", hearingId)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts) VALUES (:id, :s, :r, 'HMPPS', 1, "
                        + "'queue', 'PENDING', 0)")
                .param("id", UUID.randomUUID()).param("s", SOURCE).param("r", requestId)
                .update();
    }

    private LegOutcome sentOutcome(final UUID requestId, final UUID hearingId) {
        final DistributionCommand command = new DistributionCommand(SOURCE, requestId, hearingId,
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")), Optional.of(sha256(BODY)),
                OptionalInt.of(200), Optional.of(BODY));
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
