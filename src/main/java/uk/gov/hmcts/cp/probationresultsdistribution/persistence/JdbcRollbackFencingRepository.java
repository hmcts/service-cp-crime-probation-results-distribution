package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The JDBC {@link RollbackFencingRepository}, implementing the close-in-flight statement exactly
 * as FR-004-049a states: one transaction per {@code PENDING}/{@code PARKED} leg row, closing it
 * {@code FAILED(rolled-back)} and mapping its request according to the request's own status, with
 * nothing else touched. A late runner's write after a row is closed — the leg-outcome UPDATE
 * guarded by the request claim (slice 002), or a resend completion guarded by its own leg claim
 * (slice 004 Phase 6) — matches no row once this has run, because the claim it checks has already
 * been cleared or rotated here.
 */
public final class JdbcRollbackFencingRepository implements RollbackFencingRepository {

    private static final String PENDING = "PENDING";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the repository.
     *
     * @param jdbc                the JDBC client
     * @param transactionTemplate the transaction template binding each row's leg and request update
     */
    public JdbcRollbackFencingRepository(final JdbcClient jdbc,
                                         final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public CloseInFlightResult closeInFlight() {
        final List<InFlightRow> rows = findInFlightRows();
        int pendingClosed = 0;
        int parkedClosed = 0;
        for (final InFlightRow row : rows) {
            final boolean closed = Boolean.TRUE.equals(
                    transactionTemplate.execute(status -> closeOneRow(row)));
            if (closed && PENDING.equals(row.status())) {
                pendingClosed++;
            } else if (closed) {
                parkedClosed++;
            }
        }
        return new CloseInFlightResult(pendingClosed, parkedClosed);
    }

    private List<InFlightRow> findInFlightRows() {
        return jdbc.sql("SELECT id, source, request_id, status, reason, note, attempt_origin "
                        + "FROM outbound_message WHERE leg = 'HMPPS' AND status IN ('PENDING', 'PARKED') "
                        + "ORDER BY id")
                .query((rs, rowNum) -> new InFlightRow(
                        (UUID) rs.getObject("id"), rs.getString("source"),
                        (UUID) rs.getObject("request_id"), rs.getString("status"),
                        rs.getString("reason"), rs.getString("note"), rs.getString("attempt_origin")))
                .list();
    }

    private boolean closeOneRow(final InFlightRow row) {
        final String note = withPrevReplaced(row.note(), newPrevForm(row));
        final boolean resendRow = "resend".equals(row.attemptOrigin());
        final String legUpdate = "UPDATE outbound_message SET status = 'FAILED', "
                + "reason = 'rolled-back', note = :note, updated_at = now()"
                + (resendRow ? ", claim_token = gen_random_uuid() " : " ")
                + "WHERE id = :id AND status IN ('PENDING', 'PARKED')";
        final int legRows = jdbc.sql(legUpdate)
                .param("note", note)
                .param("id", row.id())
                .update();
        final boolean closed = legRows > 0;
        if (closed) {
            closeRequestIfReceivedOrRetrying(row);
        }
        return closed;
    }

    /**
     * Maps the row's request: {@code RECEIVED}/{@code RETRYING} becomes
     * {@code FAILED(rolled-back)} with its claim cleared; a {@code FAILED} request keeps its own
     * reason and exhausted id untouched; a {@code COMPLETED} request is untouched (FR-004-049a).
     * Both of the latter are achieved simply by the {@code WHERE} clause matching nothing.
     *
     * @param row the leg row just closed
     */
    private void closeRequestIfReceivedOrRetrying(final InFlightRow row) {
        jdbc.sql("UPDATE processed_request SET status = 'FAILED', "
                        + "failure_reason = 'rolled-back', exhausted_message_id = NULL, "
                        + "claim_owner = NULL, claim_token = NULL, claim_expires_at = NULL, "
                        + "updated_at = now() "
                        + "WHERE source = :source AND request_id = :requestId "
                        + "AND status IN ('RECEIVED', 'RETRYING')")
                .param("source", row.source())
                .param("requestId", row.requestId())
                .update();
    }

    private static String newPrevForm(final InFlightRow row) {
        final String reasonPart = row.reason() == null ? "-" : row.reason();
        return "prev=" + row.status() + "/" + reasonPart;
    }

    /**
     * Replaces any earlier {@code prev=} form in {@code existingNote} with {@code newPrev},
     * keeping every other note form the row already carried (FR-004-049a).
     *
     * @param existingNote the row's note before the close, possibly {@code null}
     * @param newPrev      the {@code prev=<STATUS>/<reason>} form to install
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

    private record InFlightRow(UUID id, String source, UUID requestId, String status,
                               String reason, String note, String attemptOrigin) {
    }
}
