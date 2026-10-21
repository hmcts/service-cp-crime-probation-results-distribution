package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * The JDBC {@link ProcessedRequestRepository}. The insert is the idempotency claim: it writes the
 * {@code processed_request} ({@code RECEIVED}) and the attempt-1 {@code PENDING}
 * {@code outbound_message} row in one transaction, and a unique violation is handled as a concurrent
 * or duplicate delivery, not an error. It issues no {@code DELETE} against either table and writes no
 * {@code audit_note} (FR-002-045, FR-002-056). The database clock computes every claim expiry.
 */
public final class JdbcProcessedRequestRepository implements ProcessedRequestRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";

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
        return jdbc.sql("SELECT status, request_fingerprint, "
                        + "(claim_expires_at IS NOT NULL AND claim_expires_at > now()) AS claim_live "
                        + "FROM processed_request WHERE source = :source AND request_id = :requestId")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new RequestRecord(
                        rs.getString("status"),
                        rs.getString("request_fingerprint"),
                        rs.getBoolean("claim_live")))
                .optional();
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
                .param("owner", claim.owner().toString())
                .param("token", claim.token())
                .param("lease", (double) leaseSeconds)
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
                        + "WHERE source = :source AND request_id = :requestId "
                        + "AND status IN ('RECEIVED', 'RETRYING') "
                        + "AND (claim_expires_at IS NULL OR claim_expires_at <= now())")
                .param("owner", claim.owner().toString())
                .param("token", claim.token())
                .param("lease", (double) leaseSeconds)
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .update();
        return rows > 0;
    }
}
