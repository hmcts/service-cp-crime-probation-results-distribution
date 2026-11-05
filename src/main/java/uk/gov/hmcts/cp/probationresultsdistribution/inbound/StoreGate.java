package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

/**
 * The two things a delivery needs to know about the processed log before its body is examined
 * (design §4.2 Intake; FR-002-009). Store availability is a <strong>precondition</strong>, not a
 * step: a delivery arriving without a store is handed straight back, unexamined, and intake stops
 * until the store returns, so the broker's delivery budget is not spent on an outage of ours.
 *
 * <p>Both halves live here because both belong to the same moment — the callback that discovers the
 * outage is the callback that hands its delivery back and asks for intake to stop. Asking is all the
 * callback may do: stopping a processor from inside its own message callback deadlocks the shutdown,
 * so the request is enqueued and carried out on another thread.
 */
public interface StoreGate {

    /**
     * Reports whether the processed log can be reached right now.
     *
     * @return whether the processed log can be reached right now
     */
    boolean storeAvailable();

    /**
     * Asks for intake to stop. Returns immediately, having decided nothing and stopped nothing.
     */
    void suspendIntake();
}
