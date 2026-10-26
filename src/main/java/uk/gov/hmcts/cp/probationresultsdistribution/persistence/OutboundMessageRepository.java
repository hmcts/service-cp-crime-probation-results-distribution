package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

/**
 * The attempt-1 outcome writer (design §4.6, FR-002-040 to FR-002-042). It writes the leg outcome in
 * one UPDATE guarded by the attempt's expected status and the request's claim owner and token, and,
 * when a body was sent, replaces the attempt's {@code outbound_body} row in the same transaction. A
 * guarded UPDATE that matches no row is a stale-runner rejection: it returns zero. It issues no
 * {@code DELETE} against {@code processed_request} or {@code outbound_message} and writes no
 * {@code audit_note}; the {@code outbound_body} replacement is the only delete it makes.
 */
public final class OutboundMessageRepository {

    private static final String STATUS = "status";
    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final String serviceVersion;

    /**
     * Creates the repository.
     *
     * @param jdbc                the JDBC client
     * @param transactionTemplate the transaction template binding the UPDATE and body write
     * @param serviceVersion      the build version written with every {@code body_digest} (H19)
     */
    public OutboundMessageRepository(final JdbcClient jdbc,
                                     final TransactionTemplate transactionTemplate,
                                     final String serviceVersion) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.serviceVersion = serviceVersion;
    }

    /**
     * Writes the attempt-1 outcome under the request claim, replacing the body when one was sent. The
     * guarded UPDATE matches the attempt-1 row in either re-runnable state — {@code PENDING} (a first
     * run or a reclaimed crash re-run) or {@code PARKED} (a redelivery after a park) — so a re-run
     * overwrites the row in place without a fresh {@code PENDING} (FR-002-041). On a re-run (the row's
     * {@code attempts} is already above zero) {@code note} is rewritten whole as
     * {@code prev=<STATUS>/<reason>}, so a note never grows across re-runs; a first write leaves it
     * NULL. A terminal attempt-1 row never reaches here: its request is {@code COMPLETED} or
     * {@code FAILED}, so the guard settles the delivery before a run begins.
     *
     * @param outcome the derived leg outcome
     * @param claim   the request claim fencing the write
     * @return the number of rows updated: 1 when written, 0 when fenced out (a stale runner)
     */
    public int writeOutcome(final LegOutcome outcome, final RunClaim claim) {
        return transactionTemplate.execute(status -> applyOutcome(outcome, claim));
    }

    private int applyOutcome(final LegOutcome outcome, final RunClaim claim) {
        final String bodyDigest = outcome.bodyDigest().orElse(null);
        // service_version is written only with a body_digest (H19): precomputed here, as NULL, so the
        // bound parameters always carry a determinable type — a bare NULL inside a CASE leaves the
        // driver unable to infer its type.
        final String versionToWrite = bodyDigest == null ? null : serviceVersion;
        final boolean sent = outcome.status() == LegStatus.SENT;
        final Optional<UUID> updatedId = jdbc.sql(
                        "UPDATE outbound_message SET status = :status, reason = :reason, "
                        + "youth_switch = :youthSwitch, http_status = :httpStatus, "
                        + "payload_source = :payloadSource, payload_shared_time = :sharedTime, "
                        + "body_digest = :bodyDigest, service_version = :serviceVersion, "
                        + "sent_at = CASE WHEN :sent THEN now() ELSE NULL END, "
                        + "note = CASE WHEN attempts > 0 OR EXISTS ("
                        + "SELECT 1 FROM processed_request rerun_pr "
                        + "WHERE rerun_pr.source = outbound_message.source "
                        + "AND rerun_pr.request_id = outbound_message.request_id "
                        + "AND rerun_pr.attempts > 1) "
                        + "THEN 'prev=' || status || '/' || coalesce(reason, '-') ELSE note END, "
                        + "attempts = attempts + 1, updated_at = now() "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS' "
                        + "AND attempt = 1 AND status IN ('PENDING', 'PARKED') "
                        + "AND EXISTS (SELECT 1 FROM processed_request pr "
                        + "WHERE pr.source = outbound_message.source "
                        + "AND pr.request_id = outbound_message.request_id "
                        + "AND pr.claim_owner = :owner AND pr.claim_token = :token) "
                        + "RETURNING id")
                .param(STATUS, outcome.status().wireValue())
                .param("reason", outcome.reason().map(LegReason::wireValue).orElse(null))
                .param("youthSwitch",
                        outcome.youthSwitch().map(YouthSwitchRecordedValue::wireValue).orElse(null))
                .param("httpStatus",
                        outcome.httpStatus().isPresent() ? outcome.httpStatus().getAsInt() : null)
                .param("payloadSource",
                        outcome.payloadSource().map(PayloadSource::wireValue).orElse(null))
                .param("sharedTime", outcome.payloadSharedTime()
                        .map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                        .orElse(null))
                .param("bodyDigest", bodyDigest)
                .param("serviceVersion", versionToWrite)
                .param("sent", sent)
                .param(SOURCE, outcome.command().source())
                .param(REQUEST_ID, outcome.command().requestId())
                .param("owner", claim.owner().toString())
                .param("token", claim.token())
                .query(UUID.class)
                .optional();

        final int rows;
        if (updatedId.isEmpty()) {
            rows = 0;
        } else {
            replaceBody(updatedId.get(), outcome.body().orElse(null));
            rows = 1;
        }
        return rows;
    }

    /**
     * Re-runs the leg's latest attempt in place for a replayed run (slice 004, FR-004-008): attempt 1
     * in any re-runnable state ({@code FAILED}, {@code PARKED} or {@code PENDING}), or a replay-created
     * row at attempt ≥ 2 that is {@code PARKED}/{@code PENDING}. A {@code FAILED} row at attempt ≥ 2 is
     * instead appended over (FR-004-009). The note is always rewritten to
     * {@code prev=<STATUS>/<reason>;rerun-by=<messageId>} — a replay re-run always follows an earlier
     * write, so there is no "first write" case to preserve, unlike {@link #writeOutcome}. A
     * {@code FAILED} row already naming this exact {@code messageId} in its {@code note} is excluded
     * from the match, so it is re-run at most once per replay message (FR-004-010); a {@code PARKED}
     * row carries no such exclusion.
     *
     * @param outcome          the derived leg outcome
     * @param claim            the request claim fencing the write
     * @param rerunByMessageId the replay message's {@code messageId}
     * @return the number of rows updated: 1 when written, 0 when fenced out or already re-run by this
     *     message
     */
    public int writeReplayOutcome(final LegOutcome outcome, final RunClaim claim,
                                  final String rerunByMessageId) {
        return transactionTemplate.execute(status ->
                applyReplayOutcome(outcome, claim, rerunByMessageId));
    }

    private int applyReplayOutcome(final LegOutcome outcome, final RunClaim claim,
                                   final String rerunByMessageId) {
        // An empty leg history has nothing to re-run or append over, so it fences to 0 rows. A FAILED
        // row at attempt >= 2 (a resend row or an earlier replay row) is never re-run in place: a new
        // replay row is appended over it (FR-004-009). Everything else the replay column re-runs —
        // attempt 1 in any re-runnable state, or a replay-created row that is PARKED/PENDING at
        // attempt >= 2 — is re-run in place on that row's own attempt (FR-004-008).
        return latestLegRow(outcome.command().source(), outcome.command().requestId())
                .map(row -> row.attempt() >= 2 && "FAILED".equals(row.status())
                        ? appendReplayRowOrSkip(outcome, claim, rerunByMessageId, row)
                        : inPlaceReplayOutcome(outcome, claim, rerunByMessageId, row.attempt()))
                .orElse(0);
    }

    /**
     * Appends a new {@code replay} attempt row ({@code max(attempt) + 1}, {@code attempt_origin =
     * replay}, no leg claim) over a leg whose latest row is {@code FAILED} at attempt ≥ 2 — a resend
     * row or an earlier replay row — never re-running that row in place (FR-004-009). The write is
     * guarded by the request claim and carries {@code note = rerun-by=<messageId>}, so a redelivery of
     * the same replay message whose appended row already names it is skipped, exactly as an in-place
     * {@code FAILED} row is (FR-004-010).
     */
    private int appendReplayRowOrSkip(final LegOutcome outcome, final RunClaim claim,
                                      final String rerunByMessageId, final LatestLegRow latest) {
        final boolean alreadyRerunByThisMessage = latest.note() != null
                && latest.note().contains("rerun-by=" + rerunByMessageId);
        return alreadyRerunByThisMessage ? 0 : insertReplayRow(outcome, claim, rerunByMessageId);
    }

    private int insertReplayRow(final LegOutcome outcome, final RunClaim claim,
                                final String rerunByMessageId) {
        final String bodyDigest = outcome.bodyDigest().orElse(null);
        final String versionToWrite = bodyDigest == null ? null : serviceVersion;
        final boolean sent = outcome.status() == LegStatus.SENT;
        final Optional<UUID> insertedId = jdbc.sql(
                        "INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, youth_switch, http_status, payload_source, "
                        + "payload_shared_time, body_digest, service_version, sent_at, attempts, note, "
                        + "updated_at) SELECT gen_random_uuid(), :source, :requestId, 'HMPPS', "
                        + "(SELECT max(attempt) + 1 FROM outbound_message WHERE source = :source "
                        + "AND request_id = :requestId AND leg = 'HMPPS'), 'replay', :status, :reason, "
                        + ":youthSwitch, :httpStatus, :payloadSource, :sharedTime, :bodyDigest, "
                        + ":serviceVersion, CASE WHEN :sent THEN now() ELSE NULL END, 1, "
                        + "'rerun-by=' || :messageId, now() "
                        + "WHERE EXISTS (SELECT 1 FROM processed_request pr "
                        + "WHERE pr.source = :source AND pr.request_id = :requestId "
                        + "AND pr.claim_owner = :owner AND pr.claim_token = :token) RETURNING id")
                .param(STATUS, outcome.status().wireValue())
                .param("reason", outcome.reason().map(LegReason::wireValue).orElse(null))
                .param("youthSwitch",
                        outcome.youthSwitch().map(YouthSwitchRecordedValue::wireValue).orElse(null))
                .param("httpStatus",
                        outcome.httpStatus().isPresent() ? outcome.httpStatus().getAsInt() : null)
                .param("payloadSource",
                        outcome.payloadSource().map(PayloadSource::wireValue).orElse(null))
                .param("sharedTime", outcome.payloadSharedTime()
                        .map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                        .orElse(null))
                .param("bodyDigest", bodyDigest)
                .param("serviceVersion", versionToWrite)
                .param("sent", sent)
                .param("messageId", rerunByMessageId)
                .param(SOURCE, outcome.command().source())
                .param(REQUEST_ID, outcome.command().requestId())
                .param("owner", claim.owner().toString())
                .param("token", claim.token())
                .query(UUID.class)
                .optional();

        final int rows;
        if (insertedId.isEmpty()) {
            rows = 0;
        } else {
            replaceBody(insertedId.get(), outcome.body().orElse(null));
            rows = 1;
        }
        return rows;
    }

    private Optional<LatestLegRow> latestLegRow(final String source, final UUID requestId) {
        return jdbc.sql("SELECT attempt, status, note FROM outbound_message "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS' "
                        + "ORDER BY attempt DESC LIMIT 1")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new LatestLegRow(rs.getInt("attempt"), rs.getString(STATUS),
                        rs.getString("note")))
                .optional();
    }

    private int inPlaceReplayOutcome(final LegOutcome outcome, final RunClaim claim,
                                     final String rerunByMessageId, final int targetAttempt) {
        final String bodyDigest = outcome.bodyDigest().orElse(null);
        final String versionToWrite = bodyDigest == null ? null : serviceVersion;
        final boolean sent = outcome.status() == LegStatus.SENT;
        final Optional<UUID> updatedId = jdbc.sql(
                        "UPDATE outbound_message SET status = :status, reason = :reason, "
                        + "youth_switch = :youthSwitch, http_status = :httpStatus, "
                        + "payload_source = :payloadSource, payload_shared_time = :sharedTime, "
                        + "body_digest = :bodyDigest, service_version = :serviceVersion, "
                        + "sent_at = CASE WHEN :sent THEN now() ELSE NULL END, "
                        + "note = 'prev=' || status || '/' || coalesce(reason, '-') "
                        + "|| ';rerun-by=' || :messageId, "
                        + "attempts = attempts + 1, updated_at = now() "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS' "
                        + "AND attempt = :targetAttempt AND status IN ('PENDING', 'PARKED', 'FAILED') "
                        + "AND (status <> 'FAILED' OR note IS NULL "
                        + "OR position(('rerun-by=' || :messageId) in note) = 0) "
                        + "AND EXISTS (SELECT 1 FROM processed_request pr "
                        + "WHERE pr.source = outbound_message.source "
                        + "AND pr.request_id = outbound_message.request_id "
                        + "AND pr.claim_owner = :owner AND pr.claim_token = :token) "
                        + "RETURNING id")
                .param(STATUS, outcome.status().wireValue())
                .param("reason", outcome.reason().map(LegReason::wireValue).orElse(null))
                .param("youthSwitch",
                        outcome.youthSwitch().map(YouthSwitchRecordedValue::wireValue).orElse(null))
                .param("httpStatus",
                        outcome.httpStatus().isPresent() ? outcome.httpStatus().getAsInt() : null)
                .param("payloadSource",
                        outcome.payloadSource().map(PayloadSource::wireValue).orElse(null))
                .param("sharedTime", outcome.payloadSharedTime()
                        .map(instant -> OffsetDateTime.ofInstant(instant, ZoneOffset.UTC))
                        .orElse(null))
                .param("bodyDigest", bodyDigest)
                .param("serviceVersion", versionToWrite)
                .param("sent", sent)
                .param("messageId", rerunByMessageId)
                .param("targetAttempt", targetAttempt)
                .param(SOURCE, outcome.command().source())
                .param(REQUEST_ID, outcome.command().requestId())
                .param("owner", claim.owner().toString())
                .param("token", claim.token())
                .query(UUID.class)
                .optional();

        final int rows;
        if (updatedId.isEmpty()) {
            rows = 0;
        } else {
            replaceBody(updatedId.get(), outcome.body().orElse(null));
            rows = 1;
        }
        return rows;
    }

    /**
     * The leg's latest attempt, as the replay write path reads it to decide between an in-place
     * re-run (attempt 1, or a {@code PARKED}/{@code PENDING} replay-created row) and appending a new
     * row over a {@code FAILED} row at attempt ≥ 2 (FR-004-008, FR-004-009).
     *
     * @param attempt the highest attempt number
     * @param status  that row's status
     * @param note    that row's note, used for the per-message rerun-by skip (FR-004-010)
     */
    private record LatestLegRow(int attempt, String status, String note) {
    }

    private void replaceBody(final UUID outboundMessageId, final String body) {
        jdbc.sql("DELETE FROM outbound_body WHERE outbound_message_id = :id")
                .param("id", outboundMessageId)
                .update();
        if (body != null) {
            jdbc.sql("INSERT INTO outbound_body (outbound_message_id, body) VALUES (:id, :body)")
                    .param("id", outboundMessageId)
                    .param("body", body)
                    .update();
        }
    }
}
