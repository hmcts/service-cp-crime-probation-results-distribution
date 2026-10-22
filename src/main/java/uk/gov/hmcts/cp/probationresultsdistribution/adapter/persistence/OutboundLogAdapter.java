package uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence;

import uk.gov.hmcts.cp.probationresultsdistribution.application.OutboundLog;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;

/**
 * The outbound-log adapter (design §4.4, §4.6). It implements the application's {@link OutboundLog}
 * port against the persistence {@code OutboundMessageRepository}, so the application core records a
 * leg outcome without importing a JDBC type and {@code persistence} never imports from
 * {@code application}. The one-UPDATE outcome write and the {@code outbound_body} insert happen in the
 * repository's single transaction, fenced by the request claim (H18-H20).
 *
 * <p>This slice (US1) writes the attempt-1 row, so the guarded write expects the {@code PENDING}
 * status the admission insert left. The in-place re-run of a {@code PARKED} or reclaimed
 * {@code PENDING} attempt-1 row (a different expected status) is US3/US5's.
 */
public final class OutboundLogAdapter implements OutboundLog {

    private final OutboundMessageRepository repository;

    /**
     * Creates the adapter over the outbound-message repository.
     *
     * @param repository the attempt-1 outcome writer
     */
    public OutboundLogAdapter(final OutboundMessageRepository repository) {
        this.repository = repository;
    }

    /**
     * Writes the attempt-1 outcome and, when a body was sent, its exact bytes, in the repository's
     * single transaction, fenced by the request claim against the admission's {@code PENDING} row.
     *
     * @param outcome the derived leg outcome
     * @param claim   the request claim fencing the write
     * @return {@code true} when the guarded write took effect; {@code false} when it matched no row
     */
    @Override
    public boolean record(final LegOutcome outcome, final RunClaim claim) {
        return repository.writeOutcome(outcome, claim, LegStatus.PENDING) == 1;
    }
}
