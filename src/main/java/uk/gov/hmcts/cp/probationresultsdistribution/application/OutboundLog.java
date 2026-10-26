package uk.gov.hmcts.cp.probationresultsdistribution.application;

import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

/**
 * The outbound-log port (design §4.4, §4.6). The core records a leg attempt's outcome, and its exact
 * sent bytes, only through this port; the JDBC adapter writes the {@code outbound_message} row and,
 * when a body was sent, the {@code outbound_body} text in the same transaction, guarded by the
 * request claim. The core never touches JDBC.
 */
public interface OutboundLog {

    /**
     * Records a leg attempt's outcome, and its body when one was sent, under the request claim. The
     * claim is the fencing token the guarded write matches: a stale runner whose lease was taken over
     * no longer matches it, so its write updates no row (design §4.5, A2-67).
     *
     * @param outcome the derived leg outcome
     * @param claim   the request claim fencing the write
     * @return {@code true} when the row was written; {@code false} when the guarded write matched no
     *     row, which is a stale-runner rejection the caller must not settle as success
     */
    boolean record(LegOutcome outcome, RunClaim claim);

    /**
     * Re-runs the attempt-1 row in place for a replayed run, allowing a {@code FAILED} row as well as
     * {@code PENDING}/{@code PARKED} (design A2-39, A2-45; slice 004 FR-004-008), and rewriting
     * {@code note} to {@code prev=<STATUS>/<reason>;rerun-by=<messageId>}. A redelivery of the same
     * replay message that meets a {@code FAILED} row already naming that message in {@code rerun-by}
     * is skipped — matched by no row — so a {@code FAILED} row is re-run at most once per replay
     * message (FR-004-010); a {@code PARKED} row has no such guard and is re-run on every delivery.
     *
     * <p>When the leg's latest row is instead {@code FAILED} at attempt ≥ 2 — a resend row or an
     * earlier replay row — it is never re-run in place: a new {@code replay} attempt row
     * ({@code max(attempt) + 1}) is appended and run, carrying {@code rerun-by=<messageId>} so a
     * redelivery of the same replay message is likewise skipped (FR-004-009).
     *
     * @param outcome          the derived leg outcome
     * @param claim            the request claim fencing the write
     * @param rerunByMessageId the replay message's {@code messageId}
     * @return {@code true} when the row was written; {@code false} when the guarded write matched no
     *     row (a stale runner, or a {@code FAILED} row already re-run by this message)
     */
    boolean recordReplay(LegOutcome outcome, RunClaim claim, String rerunByMessageId);
}
