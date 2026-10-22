package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;

/**
 * The inbound Service Bus adapter (design §4.2, §4.5). It is the one place that turns a delivery into
 * exactly one settlement: it parses the body against the closed contract, runs it through the guard
 * and the runner, persists the state that justifies the settlement, and then performs exactly one
 * {@code complete()} / {@code abandon()} / {@code deadLetter()}. It holds no business logic of its
 * own and never calls a downstream adapter directly.
 *
 * <p>This is the US1 seam (T030-T033 land the end-to-end tests red against it); the parse, guard,
 * run and settle wiring is US1's T042. Until then, a delivery is completed with no outcome written,
 * so the end-to-end tests fail for the right reason: no {@code outbound_message} row is ever
 * recorded.
 */
public final class ProbationMessageListener {

    /**
     * Handles one peek-lock delivery.
     *
     * @param context the delivery, settled exactly once on every path
     */
    public void processMessage(final ServiceBusReceivedMessageContext context) {
        context.complete();
    }
}
