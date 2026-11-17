package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.NoteForm;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * The JDBC {@link CloseExpiredResendRepository}, implementing the close-expired-resend statement
 * exactly as FR-004-059 states: one transaction, the request row locked before the leg's latest
 * row (the same order {@link JdbcRollbackFencingRepository} and {@code claimResend} both take, PR
 * #23 review's P2 follow-up), refusing {@code unknown-request}, {@code not-eligible} or
 * {@code resend-in-progress} in that order, or else closing the row {@code FAILED(rolled-back)}
 * with a fresh {@code claim_token} and appending the operator and reason to
 * {@code processed_request.audit_note}. A late runner's own completion write for the closed row,
 * guarded by its now-stale {@code claim_token}, matches no row once this has run — the same
 * fencing guarantee {@link JdbcRollbackFencingRepository} relies on.
 */
public final class JdbcCloseExpiredResendRepository implements CloseExpiredResendRepository {

    private static final String PENDING = "PENDING";
    private static final String RESEND = "resend";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the repository.
     *
     * @param jdbc                the JDBC client
     * @param transactionTemplate the transaction template binding the request-row lock, the
     *                            leg-row close and the audit-note append in one transaction
     */
    public JdbcCloseExpiredResendRepository(final JdbcClient jdbc,
                                            final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public CloseExpiredResendResult close(final UUID requestId, final String operator,
                                          final String reason) {
        return transactionTemplate.execute(status -> closeLocked(requestId, operator, reason));
    }

    // Each refusal exits as soon as its check decides, in the fixed FR-004-059 order.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private CloseExpiredResendResult closeLocked(final UUID requestId, final String operator,
                                                  final String reason) {
        // The request row is locked first: left the other way round, a late queue or replay write
        // overrunning the drain window and this close racing the same request could each hold the
        // row the other needs, and PostgreSQL's deadlock detector rolls one of them back.
        final Optional<String> source = jdbc.sql(
                        "SELECT source FROM processed_request WHERE request_id = :requestId FOR UPDATE")
                .param("requestId", requestId)
                .query(String.class)
                .optional();
        if (source.isEmpty()) {
            return new CloseExpiredResendResult.Refused(ResendRefusal.UNKNOWN_REQUEST);
        }
        final Optional<LatestLeg> latest = jdbc.sql("SELECT id, status, attempt_origin, note, "
                        + "(claim_expires_at IS NOT NULL AND claim_expires_at > now()) AS claim_live "
                        + "FROM outbound_message WHERE source = :source AND request_id = :requestId "
                        + "AND leg = 'HMPPS' ORDER BY attempt DESC LIMIT 1 FOR UPDATE")
                .param("source", source.get())
                .param("requestId", requestId)
                .query((rs, rowNum) -> new LatestLeg((UUID) rs.getObject("id"),
                        rs.getString("status"), rs.getString("attempt_origin"),
                        rs.getString("note"), rs.getBoolean("claim_live")))
                .optional();
        if (latest.isEmpty() || !latest.get().pendingResend()) {
            return new CloseExpiredResendResult.Refused(ResendRefusal.NOT_ELIGIBLE);
        }
        if (latest.get().claimLive()) {
            return new CloseExpiredResendResult.Refused(ResendRefusal.RESEND_IN_PROGRESS);
        }
        closeRow(latest.get());
        appendAuditNote(source.get(), requestId, operator, reason);
        return new CloseExpiredResendResult.Closed();
    }

    private void closeRow(final LatestLeg row) {
        final String note = withPrevReplaced(row.note(), NoteForm.PREV_PENDING.wireValue());
        jdbc.sql("UPDATE outbound_message SET status = 'FAILED', reason = 'rolled-back', "
                        + "note = :note, claim_token = gen_random_uuid(), updated_at = now() "
                        + "WHERE id = :id")
                .param("note", note)
                .param("id", row.id())
                .update();
    }

    private void appendAuditNote(final String source, final UUID requestId, final String operator,
                                 final String reason) {
        // One timestamped line with the operator and the reason, each already sanitised to one
        // line of at most 200 characters (FR-004-059). processed_request.status is left untouched.
        jdbc.sql("UPDATE processed_request SET audit_note = coalesce(audit_note || E'\\n', '') "
                        + "|| now()::text || ' CLOSE_EXPIRED_RESEND operator=' || :operator "
                        + "|| ' reason=' || :reason "
                        + "WHERE source = :source AND request_id = :requestId")
                .param("operator", operator)
                .param("reason", reason)
                .param("source", source)
                .param("requestId", requestId)
                .update();
    }

    /**
     * Replaces any earlier {@code prev=} form in {@code existingNote} with {@code newPrev}, keeping
     * every other note form the row already carried — the same merge
     * {@link JdbcRollbackFencingRepository} applies.
     *
     * @param existingNote the row's note before the close, possibly {@code null}
     * @param newPrev      the {@code prev=PENDING/-} form to install
     * @return the merged note
     */
    private static String withPrevReplaced(final String existingNote, final String newPrev) {
        final String merged;
        if (existingNote == null || existingNote.isBlank()) {
            merged = newPrev;
        } else {
            final StringBuilder builder = new StringBuilder(newPrev);
            for (final String part : existingNote.split(";")) {
                if (!part.startsWith("prev=")) {
                    builder.append(';').append(part);
                }
            }
            merged = builder.toString();
        }
        return merged;
    }

    /**
     * The leg's latest attempt, as the close-expired-resend eligibility check reads it: a
     * {@code PENDING} resend row is in progress (eligible to close only once its lease has
     * expired); any other latest row is not eligible.
     */
    private record LatestLeg(UUID id, String status, String attemptOrigin, String note,
                             boolean claimLive) {

        /* default */ boolean pendingResend() {
            return RESEND.equals(attemptOrigin) && PENDING.equals(status);
        }
    }
}
