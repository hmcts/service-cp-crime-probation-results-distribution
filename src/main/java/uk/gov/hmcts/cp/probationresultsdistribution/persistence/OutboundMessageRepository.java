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
                        + "note = CASE WHEN attempts > 0 "
                        + "THEN 'prev=' || status || '/' || coalesce(reason, '-') ELSE note END, "
                        + "attempts = attempts + 1, updated_at = now() "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS' "
                        + "AND attempt = 1 AND status IN ('PENDING', 'PARKED') "
                        + "AND EXISTS (SELECT 1 FROM processed_request pr "
                        + "WHERE pr.source = outbound_message.source "
                        + "AND pr.request_id = outbound_message.request_id "
                        + "AND pr.claim_owner = :owner AND pr.claim_token = :token) "
                        + "RETURNING id")
                .param("status", outcome.status().wireValue())
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
                .param("source", outcome.command().source())
                .param("requestId", outcome.command().requestId())
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
