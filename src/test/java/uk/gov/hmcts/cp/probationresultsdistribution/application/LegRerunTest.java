package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * The replayed-run leg write paths (FR-004-008, FR-004-009, FR-004-010). An attempt-1
 * {@code FAILED}/{@code PARKED} row is re-run in place; a {@code FAILED} row at attempt ≥ 2 — a
 * resend row or an earlier replay row — is never re-run in place but gets a new appended
 * {@code replay} row; and a redelivery of a replay message whose latest {@code FAILED} row already
 * names it in {@code rerun-by} is skipped, while a {@code PARKED} row is re-run on every delivery.
 */
@DisplayName("the replayed-run leg write paths")
class LegRerunTest extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";
    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final Instant SHARED = Instant.parse("2026-08-19T16:42:07.512Z");
    private static final String SENT_BODY = "{\"hearing\":{\"id\":\"x\"}}";
    private static final String PRIOR_DIGEST = sha256("prior-failed-body");

    private final OutboundMessageRepository repository = new OutboundMessageRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())),
            "test-version");
    private final OutboundLogAdapter adapter = new OutboundLogAdapter(repository);

    @Test
    @DisplayName("re-runs an attempt-1 FAILED row in place, one row, with the prev/rerun-by note "
            + "and the replaced body (FR-004-008)")
    void reruns_attempt_one_failed_in_place() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReplayableRequest(requestId, claim);
        insertFailedAttempt(requestId, 1, "queue", "gateway-transient", null, null);
        final String messageId = "RESULTS:" + requestId;

        final boolean recorded = adapter.recordReplay(sentOutcome(requestId, hearingId), claim,
                messageId);

        assertThat(recorded).isTrue();
        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).hasSize(1);
        final Map<String, Object> row = rows.get(0);
        assertThat(row.get("attempt")).isEqualTo(1);
        assertThat(row.get("attempt_origin")).isEqualTo("queue");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("note")).isEqualTo("prev=FAILED/gateway-transient;rerun-by=" + messageId);
        assertThat(storedBody(requestId, 1)).isEqualTo(SENT_BODY);
    }

    @Test
    @DisplayName("appends a new replay row over a FAILED attempt-2 resend row, never re-runs it in "
            + "place, and leaves the earlier rows unchanged (FR-004-009)")
    void appends_a_replay_row_over_a_failed_resend_row() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        seedReplayableRequest(requestId, claim);
        insertFailedAttempt(requestId, 1, "queue", "gateway-transient", null, null);
        insertFailedAttempt(requestId, 2, "resend", "gateway-transient", null, RunClaim.fresh());
        final String messageId = "RESULTS:" + requestId;

        final boolean recorded = adapter.recordReplay(sentOutcome(requestId, hearingId), claim,
                messageId);

        assertThat(recorded).isTrue();
        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).hasSize(3);
        final Map<String, Object> appended = rows.get(2);
        assertThat(appended.get("attempt")).isEqualTo(3);
        assertThat(appended.get("attempt_origin")).isEqualTo("replay");
        assertThat(appended.get("status")).isEqualTo("SENT");
        assertThat(appended.get("note")).asString().contains("rerun-by=" + messageId);
        assertThat(appended.get("claim_owner")).isNull();
        assertThat(appended.get("resend_run_id")).isNull();
        assertThat(storedBody(requestId, 3)).isEqualTo(SENT_BODY);
        assertThat(rows.get(0).get("status")).as("attempt 1 is untouched").isEqualTo("FAILED");
        assertThat(rows.get(1).get("status")).as("attempt 2 is untouched").isEqualTo("FAILED");
        assertThat(rows.get(1).get("attempts")).isEqualTo(1);
    }

    @Test
    @DisplayName("skips a FAILED row already naming this replay message in rerun-by (FR-004-010)")
    void skips_a_failed_row_already_rerun_by_this_message() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        final String messageId = "RESULTS:" + requestId;
        seedReplayableRequest(requestId, claim);
        insertFailedAttempt(requestId, 1, "queue", "gateway-transient",
                "prev=FAILED/receiver-rejected;rerun-by=" + messageId, null);

        final boolean recorded = adapter.recordReplay(sentOutcome(requestId, hearingId), claim,
                messageId);

        assertThat(recorded).isFalse();
        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("skips an appended FAILED replay row already naming this replay message, appending "
            + "no further row on redelivery (FR-004-010)")
    void skips_an_appended_row_already_rerun_by_this_message() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        final String messageId = "RESULTS:" + requestId;
        seedReplayableRequest(requestId, claim);
        insertFailedAttempt(requestId, 1, "queue", "gateway-transient", null, null);
        insertFailedAttempt(requestId, 2, "resend", "gateway-transient", null, RunClaim.fresh());

        // The replay appends attempt 3, which itself FAILS on this delivery.
        assertThat(adapter.recordReplay(failedOutcome(requestId, hearingId), claim, messageId))
                .as("the appended FAILED replay row is created").isTrue();
        // The same replay message is redelivered: the appended FAILED row already names it.
        assertThat(adapter.recordReplay(failedOutcome(requestId, hearingId), claim, messageId))
                .as("the redelivery is skipped, not appended again").isFalse();

        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).as("no fourth attempt is appended").hasSize(3);
        final Map<String, Object> appended = rows.get(2);
        assertThat(appended.get("attempt")).isEqualTo(3);
        assertThat(appended.get("status")).isEqualTo("FAILED");
        assertThat(appended.get("attempts")).as("attempt 3 is not re-run").isEqualTo(1);
        assertThat(appended.get("note")).isEqualTo("rerun-by=" + messageId);
    }

    @Test
    @DisplayName("re-runs an appended PARKED replay row in place on redelivery, leaving the earlier "
            + "attempts untouched (FR-004-008)")
    void reruns_an_appended_parked_replay_row_in_place_on_redelivery() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        final String messageId = "RESULTS:" + requestId;
        seedReplayableRequest(requestId, claim);
        insertFailedAttempt(requestId, 1, "queue", "gateway-transient", null, null);
        insertFailedAttempt(requestId, 2, "resend", "gateway-transient", null, RunClaim.fresh());

        // The replay appends attempt 3, which itself parks on this delivery.
        assertThat(adapter.recordReplay(parkedOutcome(requestId, hearingId), claim, messageId))
                .as("the appended replay row is created").isTrue();
        // The same replay message is redelivered: attempt 3 must be re-run in place, not attempt 1.
        assertThat(adapter.recordReplay(sentOutcome(requestId, hearingId), claim, messageId))
                .as("the redelivery re-runs the appended PARKED row in place").isTrue();

        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).as("no fourth attempt is appended").hasSize(3);
        assertThat(rows.get(0).get("status")).as("attempt 1 is untouched").isEqualTo("FAILED");
        assertThat(rows.get(0).get("attempts")).as("attempt 1 is untouched").isEqualTo(1);
        assertThat(rows.get(1).get("status")).as("attempt 2 is untouched").isEqualTo("FAILED");
        assertThat(rows.get(1).get("attempts")).as("attempt 2 is untouched").isEqualTo(1);
        final Map<String, Object> appended = rows.get(2);
        assertThat(appended.get("attempt")).isEqualTo(3);
        assertThat(appended.get("attempt_origin")).isEqualTo("replay");
        assertThat(appended.get("status")).as("attempt 3 re-run to SENT in place").isEqualTo("SENT");
        assertThat(appended.get("attempts")).as("attempt 3's attempts incremented").isEqualTo(2);
        assertThat(appended.get("note")).asString()
                .contains("prev=PARKED/gateway-transient").contains("rerun-by=" + messageId);
        assertThat(storedBody(requestId, 3)).isEqualTo(SENT_BODY);
    }

    @Test
    @DisplayName("re-runs a PARKED attempt-1 row on every delivery, whatever its note (FR-004-010)")
    void reruns_a_parked_row_on_every_delivery() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final RunClaim claim = RunClaim.fresh();
        final String messageId = "RESULTS:" + requestId;
        seedReplayableRequest(requestId, claim);
        insertParkedAttemptOne(requestId);

        assertThat(adapter.recordReplay(parkedOutcome(requestId, hearingId), claim, messageId))
                .as("first redelivery re-runs the parked row").isTrue();
        assertThat(adapter.recordReplay(parkedOutcome(requestId, hearingId), claim, messageId))
                .as("a second redelivery re-runs it again, not skipped").isTrue();
        final List<Map<String, Object>> rows = legRows(requestId);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("PARKED");
    }

    private void seedReplayableRequest(final UUID requestId, final RunClaim claim) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "replayed_at, claim_owner, claim_token, claim_expires_at) VALUES (:s, :r, "
                        + "gen_random_uuid(), DATE '2026-08-19', now(), 'Hearing_Resulted', 'fp', "
                        + "'RECEIVED', 1, '{}', now(), :owner, :token, now() + interval '5 minutes')")
                .param("s", SOURCE).param("r", requestId)
                .param("owner", claim.owner().toString()).param("token", claim.token())
                .update();
    }

    private void insertFailedAttempt(final UUID requestId, final int attempt, final String origin,
                                     final String reason, final String noteValue,
                                     final RunClaim legClaim) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "payload_source, payload_shared_time, service_version, youth_switch, note, "
                        + "claim_owner, claim_token, claim_expires_at, resend_run_id) VALUES (:id, "
                        + ":s, :r, 'HMPPS', :attempt, :origin, 'FAILED', :reason, 1, 503, :digest, "
                        + "'cache-day', :shared, 'test-version', 'ACTIVE', :note, :owner, :token, "
                        + ":expires, :runId)")
                .param("id", UUID.randomUUID()).param("s", SOURCE).param("r", requestId)
                .param("attempt", attempt).param("origin", origin).param("reason", reason)
                .param("digest", PRIOR_DIGEST)
                .param("shared", java.time.OffsetDateTime.ofInstant(SHARED, java.time.ZoneOffset.UTC))
                .param("note", noteValue)
                .param("owner", legClaim == null ? null : legClaim.owner().toString())
                .param("token", legClaim == null ? null : legClaim.token())
                .param("expires", legClaim == null ? null
                        : java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC).plusMinutes(5L))
                .param("runId", "resend".equals(origin) ? UUID.randomUUID() : null)
                .update();
    }

    private void insertParkedAttemptOne(final UUID requestId) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "payload_source, payload_shared_time, service_version, youth_switch) VALUES "
                        + "(:id, :s, :r, 'HMPPS', 1, 'queue', 'PARKED', 'gateway-transient', 1, 503, "
                        + ":digest, 'cache-day', :shared, 'test-version', 'ACTIVE')")
                .param("id", UUID.randomUUID()).param("s", SOURCE).param("r", requestId)
                .param("digest", PRIOR_DIGEST)
                .param("shared", java.time.OffsetDateTime.ofInstant(SHARED, java.time.ZoneOffset.UTC))
                .update();
    }

    private LegOutcome sentOutcome(final UUID requestId, final UUID hearingId) {
        return new LegOutcome(command(requestId, hearingId), AttemptOrigin.REPLAY, LegStatus.SENT,
                Optional.empty(), Optional.of(YouthSwitchRecordedValue.ACTIVE),
                Optional.of(PayloadSource.CACHE_DAY), Optional.of(SHARED),
                Optional.of(sha256(SENT_BODY)), OptionalInt.of(200), Optional.of(SENT_BODY));
    }

    private LegOutcome failedOutcome(final UUID requestId, final UUID hearingId) {
        return new LegOutcome(command(requestId, hearingId), AttemptOrigin.REPLAY, LegStatus.FAILED,
                Optional.of(LegReason.RECEIVER_REJECTED),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(SHARED), Optional.of(sha256(SENT_BODY)), OptionalInt.of(400),
                Optional.of(SENT_BODY));
    }

    private LegOutcome parkedOutcome(final UUID requestId, final UUID hearingId) {
        return new LegOutcome(command(requestId, hearingId), AttemptOrigin.REPLAY, LegStatus.PARKED,
                Optional.of(LegReason.GATEWAY_TRANSIENT),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(SHARED), Optional.of(sha256(SENT_BODY)), OptionalInt.of(503),
                Optional.of(SENT_BODY));
    }

    private DistributionCommand command(final UUID requestId, final UUID hearingId) {
        return new DistributionCommand(SOURCE, requestId, hearingId, DAY, SHARED, "Hearing_Resulted",
                Optional.empty());
    }

    private List<Map<String, Object>> legRows(final UUID requestId) {
        return jdbc().sql("SELECT attempt, attempt_origin, status, reason, attempts, note, "
                        + "claim_owner, resend_run_id FROM outbound_message "
                        + "WHERE source = :s AND request_id = :r AND leg = 'HMPPS' ORDER BY attempt")
                .param("s", SOURCE).param("r", requestId).query().listOfRows();
    }

    private String storedBody(final UUID requestId, final int attempt) {
        return jdbc().sql("SELECT ob.body FROM outbound_body ob JOIN outbound_message om "
                        + "ON om.id = ob.outbound_message_id WHERE om.source = :s "
                        + "AND om.request_id = :r AND om.attempt = :attempt")
                .param("s", SOURCE).param("r", requestId).param("attempt", attempt)
                .query(String.class).single();
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
