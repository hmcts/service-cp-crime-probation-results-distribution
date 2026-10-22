package uk.gov.hmcts.cp.probationresultsdistribution.application;

import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;

/**
 * The outbound-log port (design §4.4, §4.6). The core records a leg attempt's outcome, and its exact
 * sent bytes, only through this port; the JDBC adapter writes the {@code outbound_message} row and,
 * when a body was sent, the {@code outbound_body} text in the same transaction, guarded by the
 * request claim. The core never touches JDBC.
 */
public interface OutboundLog {

    /**
     * Records a leg attempt's outcome, and its body when one was sent, under the request claim.
     *
     * @param outcome the derived leg outcome
     * @return {@code true} when the row was written; {@code false} when the guarded write matched no
     *     row, which is a stale-runner rejection the caller must not settle as success
     */
    boolean record(LegOutcome outcome);
}
