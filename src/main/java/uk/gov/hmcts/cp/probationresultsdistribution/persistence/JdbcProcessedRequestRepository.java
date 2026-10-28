package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.FailureReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.NoteForm;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

/**
 * The JDBC {@link ProcessedRequestRepository}. The insert is the idempotency claim: it writes the
 * {@code processed_request} ({@code RECEIVED}) and the attempt-1 {@code PENDING}
 * {@code outbound_message} row in one transaction, and a unique violation is handled as a concurrent
 * or duplicate delivery, not an error. It issues no {@code DELETE} against either table; replay admission appends to
 * {@code audit_note} (FR-004-004, FR-004-005). The database clock computes every claim expiry.
 */
public final class JdbcProcessedRequestRepository implements ProcessedRequestRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String OWNER = "owner";
    private static final String TOKEN = "token";
    private static final String LEASE = "lease";
    private static final String REASON = "reason";
    private static final String REQUEST_WHERE = "WHERE source = :source AND request_id = :requestId ";
    private static final String ACTIVE_REQUEST = "AND status IN ('RECEIVED', 'RETRYING') ";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final long leaseSeconds;

    /**
     * Creates the repository.
     *
     * @param jdbc                the JDBC client
     * @param transactionTemplate the transaction template for the two-row admission insert
     * @param claimLease          how long an acquired claim stays live
     */
    public JdbcProcessedRequestRepository(final JdbcClient jdbc,
                                          final TransactionTemplate transactionTemplate,
                                          final Duration claimLease) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.leaseSeconds = claimLease.toSeconds();
    }

    @Override
    public Optional<RequestRecord> find(final String source, final UUID requestId) {
        return jdbc.sql("SELECT pr.status, pr.request_fingerprint, pr.exhausted_message_id, "
                        + "(pr.claim_expires_at IS NOT NULL AND pr.claim_expires_at > now()) AS claim_live, "
                        + "om.status AS attempt_one_status, om.reason AS attempt_one_reason, "
                        + "om.http_status AS attempt_one_http_status, "
                        + "(oml.attempt_origin = 'resend' AND oml.status = 'PENDING') "
                        + "AS pending_resend_row_latest "
                        + "FROM processed_request pr LEFT JOIN outbound_message om "
                        + "ON om.source = pr.source AND om.request_id = pr.request_id "
                        + "AND om.leg = 'HMPPS' AND om.attempt = 1 "
                        + "LEFT JOIN outbound_message_latest oml "
                        + "ON oml.source = pr.source AND oml.request_id = pr.request_id "
                        + "AND oml.leg = 'HMPPS' "
                        + "WHERE pr.source = :source AND pr.request_id = :requestId")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> {
                    final Integer httpStatus = (Integer) rs.getObject("attempt_one_http_status");
                    final OptionalInt optionalHttpStatus = httpStatus == null ? OptionalInt.empty()
                            : OptionalInt.of(httpStatus);
                    return new RequestRecord(rs.getString("status"),
                            rs.getString("request_fingerprint"), rs.getBoolean("claim_live"),
                            Optional.ofNullable(rs.getString("attempt_one_status")),
                            Optional.ofNullable(rs.getString("attempt_one_reason")), optionalHttpStatus,
                            Optional.ofNullable(rs.getString("exhausted_message_id")),
                            rs.getBoolean("pending_resend_row_latest"));
                })
                .optional();
    }

    @Override
    public boolean admitReplay(final String source, final UUID requestId, final RunClaim claim,
                               final ReplayRoute route) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status ->
                lockedAdmitReplay(source, requestId, claim, route)));
    }

    private boolean lockedAdmitReplay(final String source, final UUID requestId,
                                      final RunClaim claim, final ReplayRoute route) {
        // Lock the request row first, as resend-leg's claim does, so this statement runs either
        // before a resend's claim or after it has committed — and, as a fresh statement, then sees
        // its PENDING resend row. A replay is never admitted over one (FR-004-003, FR-004-035).
        jdbc.sql("SELECT status FROM processed_request " + REQUEST_WHERE + "FOR UPDATE")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query(String.class)
                .optional();
        final int rows = jdbc.sql("UPDATE processed_request SET status = 'RECEIVED', "
                        + "exhausted_message_id = NULL, replayed_at = now(), "
                        + "claim_owner = :owner, claim_token = :token, "
                        + "claim_expires_at = now() + make_interval(secs => :lease), "
                        + "attempts = attempts + 1, updated_at = now(), "
                        + "audit_note = coalesce(audit_note || E'\\n', '') || now()::text "
                        + "|| ' REPLAYED_AFTER_FAILURE prior=' || failure_reason "
                        + "|| E'\\n' || now()::text || ' route=' || :route, "
                        + "failure_reason = NULL "
                        + REQUEST_WHERE + "AND status = 'FAILED' "
                        + "AND NOT EXISTS (SELECT 1 FROM outbound_message_latest oml "
                        + "WHERE oml.source = processed_request.source "
                        + "AND oml.request_id = processed_request.request_id AND oml.leg = 'HMPPS' "
                        + "AND oml.attempt_origin = 'resend' AND oml.status = 'PENDING')")
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(LEASE, (double) leaseSeconds)
                .param("route", route.wireValue())
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }

    @Override
    public boolean hasTerminalSuccess(final String source, final UUID requestId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS' "
                        + "AND status IN ('SENT', 'NOT_APPLICABLE'))")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public Optional<ReplayCandidate> findForReplay(final String source, final UUID requestId) {
        return jdbc.sql("SELECT pr.status, pr.hearing_id, pr.command, "
                        + "(oml.attempt_origin = 'resend' AND oml.status = 'PENDING') "
                        + "AS pending_resend_row_latest "
                        + "FROM processed_request pr LEFT JOIN outbound_message_latest oml "
                        + "ON oml.source = pr.source AND oml.request_id = pr.request_id "
                        + "AND oml.leg = 'HMPPS' "
                        + "WHERE pr.source = :source AND pr.request_id = :requestId")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new ReplayCandidate(rs.getString("status"),
                        (UUID) rs.getObject("hearing_id"), rs.getString("command"),
                        rs.getBoolean("pending_resend_row_latest")))
                .optional();
    }

    @Override
    public ResendClaim claimResend(final String source, final UUID requestId, final UUID resendRunId,
                                   final String operator, final RunClaim legClaim,
                                   final Optional<String> forcedResendReason) {
        return transactionTemplate.execute(status -> checkAndClaim(source, requestId, resendRunId,
                operator, legClaim, forcedResendReason));
    }

    // Each refusal exits as soon as its check decides, in the fixed FR-004-022 order.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private ResendClaim checkAndClaim(final String source, final UUID requestId,
                                      final UUID resendRunId, final String operator,
                                      final RunClaim legClaim,
                                      final Optional<String> forcedResendReason) {
        // The request row is locked first (FOR UPDATE), so a concurrent resend of the same request
        // waits here and then, in its next statement, sees this run's committed attempt row.
        final Optional<ResendRequestRow> request = jdbc.sql("SELECT hearing_id, hearing_day, "
                        + "shared_time, event_type, "
                        + "(claim_expires_at IS NOT NULL AND claim_expires_at > now()) AS claim_live "
                        + "FROM processed_request " + REQUEST_WHERE + "FOR UPDATE")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new ResendRequestRow((UUID) rs.getObject("hearing_id"),
                        rs.getObject("hearing_day", LocalDate.class),
                        rs.getObject("shared_time", OffsetDateTime.class).toInstant(),
                        rs.getString("event_type"), rs.getBoolean("claim_live")))
                .optional();
        if (request.isEmpty()) {
            return new ResendClaim.Refused(ResendRefusal.UNKNOWN_REQUEST);
        }
        if (request.get().claimLive()) {
            return new ResendClaim.Refused(ResendRefusal.REQUEST_CLAIMED);
        }
        final Optional<LatestLeg> latest = jdbc.sql("SELECT attempt, attempt_origin, status, reason "
                        + "FROM outbound_message " + REQUEST_WHERE + "AND leg = 'HMPPS' "
                        + "ORDER BY attempt DESC LIMIT 1 FOR UPDATE")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new LatestLeg(rs.getInt("attempt"),
                        rs.getString("attempt_origin"), rs.getString("status"),
                        rs.getString(REASON)))
                .optional();
        if (latest.isPresent() && latest.get().pendingResend()) {
            return new ResendClaim.Refused(ResendRefusal.RESEND_IN_PROGRESS);
        }
        // The override applies per ID: it is used only over a terminal-success latest row.
        final boolean forced = forcedResendReason.isPresent() && latest.isPresent()
                && latest.get().terminalSuccess();
        if (latest.isEmpty() || !latest.get().resendEligible() && !forced) {
            return new ResendClaim.Refused(ResendRefusal.NOT_ELIGIBLE);
        }
        final UUID attemptId = UUID.randomUUID();
        final int attempt = latest.get().attempt() + 1;
        jdbc.sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts, claim_owner, claim_token, "
                        + "claim_expires_at, resend_run_id, operator, override, note) VALUES (:id, "
                        + ":source, :requestId, 'HMPPS', :attempt, 'resend', 'PENDING', 0, :owner, "
                        + ":token, now() + make_interval(secs => :lease), :resendRunId, :operator, "
                        + ":override, :note)")
                .param("id", attemptId)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .param("attempt", attempt)
                .param(OWNER, legClaim.owner().toString())
                .param(TOKEN, legClaim.token())
                .param(LEASE, (double) leaseSeconds)
                .param("resendRunId", resendRunId)
                .param("operator", operator)
                .param("override", forced)
                .param("note", forced ? NoteForm.OVERRIDE_FORCE_RESEND.wireValue() : null)
                .update();
        if (forced) {
            appendForcedResendLine(source, requestId, attempt, operator, forcedResendReason.get());
        }
        final ResendRequestRow row = request.get();
        return new ResendClaim.Claimed(
                new ResendAttempt(attemptId, attempt, legClaim, resendRunId, forced),
                new DistributionCommand(source, requestId, row.hearingId(), row.hearingDay(),
                        row.sharedTime(), row.eventType(), Optional.empty()));
    }

    private void appendForcedResendLine(final String source, final UUID requestId, final int attempt,
                                        final String operator, final String reason) {
        // The forced resend is audited on the request's append-only log in the claim's own
        // transaction, so before any POST: one timestamped line with the operator and the reason,
        // already sanitised to one line of at most 200 characters (FR-004-037). The request's
        // status and reasons are not touched (FR-004-038).
        jdbc.sql("UPDATE processed_request SET audit_note = coalesce(audit_note || E'\\n', '') "
                        + "|| now()::text || ' FORCED_RESEND attempt=' || :attempt "
                        + "|| ' operator=' || :operator || ' reason=' || :reason "
                        + REQUEST_WHERE)
                .param("attempt", attempt)
                .param("operator", operator)
                .param(REASON, reason)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
    }

    /** The request columns a resend rebuilds its command from, and whether a live claim holds it. */
    private record ResendRequestRow(UUID hearingId, LocalDate hearingDay, Instant sharedTime,
                                    String eventType, boolean claimLive) {
    }

    /**
     * The leg's latest attempt, as the resend eligibility table reads it (FR-004-023, FR-004-024):
     * a {@code PENDING} resend row, live or expired, is in progress and never reclaimed; a
     * {@code FAILED} row is eligible except an attempt-1 {@code FAILED(rolled-back)} row, which a
     * replay re-runs instead; a {@code SENT} or {@code NOT_APPLICABLE} row is eligible only under the
     * forced-resend override (FR-004-036); every other latest row is not eligible.
     */
    private record LatestLeg(int attempt, String attemptOrigin, String status, String reason) {

        /* default */ boolean pendingResend() {
            return "resend".equals(attemptOrigin) && "PENDING".equals(status);
        }

        /* default */ boolean resendEligible() {
            return "FAILED".equals(status) && !(attempt == 1 && "rolled-back".equals(reason));
        }

        /* default */ boolean terminalSuccess() {
            return "SENT".equals(status) || "NOT_APPLICABLE".equals(status);
        }
    }

    @Override
    public boolean insertClaim(final DistributionCommand command, final String commandBody,
                               final String fingerprint, final RunClaim claim) {
        boolean inserted;
        try {
            transactionTemplate.executeWithoutResult(status -> insertRows(command, commandBody,
                    fingerprint, claim));
            inserted = true;
        } catch (final DuplicateKeyException duplicate) {
            // Expected: a concurrent or duplicate first delivery lost the race. Handled as a
            // no-admission, never swallowed silently.
            inserted = false;
        }
        return inserted;
    }

    private void insertRows(final DistributionCommand command, final String commandBody,
                            final String fingerprint, final RunClaim claim) {
        jdbc.sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:source, :requestId, "
                        + ":hearingId, :hearingDay, :sharedTime, :eventType, :fingerprint, "
                        + "'RECEIVED', 1, :command, :owner, :token, "
                        + "now() + make_interval(secs => :lease))")
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .param("hearingId", command.hearingId())
                .param("hearingDay", command.hearingDay())
                .param("sharedTime", OffsetDateTime.ofInstant(command.sharedTime(), ZoneOffset.UTC))
                .param("eventType", command.eventType())
                .param("fingerprint", fingerprint)
                .param("command", commandBody)
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(LEASE, (double) leaseSeconds)
                .update();
        jdbc.sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts) VALUES (:id, :source, :requestId, "
                        + "'HMPPS', 1, 'queue', 'PENDING', 0)")
                .param("id", UUID.randomUUID())
                .param(SOURCE, command.source())
                .param(REQUEST_ID, command.requestId())
                .update();
    }

    @Override
    public boolean reclaim(final String source, final UUID requestId, final RunClaim claim) {
        final int rows = jdbc.sql("UPDATE processed_request SET claim_owner = :owner, "
                        + "claim_token = :token, "
                        + "claim_expires_at = now() + make_interval(secs => :lease), "
                        + "attempts = attempts + 1, updated_at = now() "
                        + REQUEST_WHERE + ACTIVE_REQUEST
                        + "AND (claim_expires_at IS NULL OR claim_expires_at <= now())")
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(LEASE, (double) leaseSeconds)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }

    @Override
    public boolean markCompleted(final String source, final UUID requestId, final RunClaim claim,
                                 final CompletionReason reason) {
        // completion_reason is "recovered" whenever audit_note holds the replay marker anywhere
        // (FR-004-012), even when the caller's own reason is both-legs-terminal: a replayed run's
        // first delivery may park and a later redelivery settle it, so the marker — not which run
        // settled it — decides. replayed_at is cleared here regardless, since completion is terminal.
        final int rows = jdbc.sql("UPDATE processed_request SET status = 'COMPLETED', "
                        + "completion_reason = CASE "
                        + "WHEN audit_note LIKE '%REPLAYED_AFTER_FAILURE%' THEN 'recovered' "
                        + "ELSE :reason END, "
                        + "failure_reason = NULL, replayed_at = NULL, claim_owner = NULL, "
                        + "claim_token = NULL, claim_expires_at = NULL, updated_at = now() "
                        + REQUEST_WHERE + ACTIVE_REQUEST
                        + "AND claim_owner = :owner AND claim_token = :token")
                .param(REASON, reason.wireValue())
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }

    @Override
    public boolean markRetrying(final String source, final UUID requestId, final RunClaim claim) {
        final int rows = jdbc.sql("UPDATE processed_request SET status = 'RETRYING', "
                        + "failure_reason = NULL, claim_owner = NULL, claim_token = NULL, "
                        + "claim_expires_at = NULL, updated_at = now() "
                        + REQUEST_WHERE + ACTIVE_REQUEST
                        + "AND claim_owner = :owner AND claim_token = :token")
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }

    @Override
    public boolean markFailed(final String source, final UUID requestId, final RunClaim claim,
                              final FailureReason reason, final String exhaustedMessageId) {
        final int rows = jdbc.sql("UPDATE processed_request SET status = 'FAILED', "
                        + "failure_reason = :reason, exhausted_message_id = :exhaustedMessageId, "
                        + "completion_reason = NULL, replayed_at = NULL, claim_owner = NULL, "
                        + "claim_token = NULL, claim_expires_at = NULL, updated_at = now() "
                        + REQUEST_WHERE + ACTIVE_REQUEST
                        + "AND claim_owner = :owner AND claim_token = :token")
                .param(REASON, reason.wireValue())
                .param("exhaustedMessageId", exhaustedMessageId)
                .param(OWNER, claim.owner().toString())
                .param(TOKEN, claim.token())
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }
}
