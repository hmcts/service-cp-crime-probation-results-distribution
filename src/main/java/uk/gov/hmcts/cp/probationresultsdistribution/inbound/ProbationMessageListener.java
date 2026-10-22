package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.GuardDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MdcFields;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * The inbound Service Bus adapter (design §4.2, §4.5). It is the one place that turns a delivery into
 * exactly one settlement: it parses the body against the closed contract, admits it through the guard,
 * runs the admitted command, persists the state that justifies the settlement, and then performs
 * exactly one {@code complete()} / {@code abandon()} / {@code deadLetter()}. It holds no business logic
 * of its own and never calls a downstream adapter directly; the Azure {@code ServiceBusReceivedMessage}
 * type never leaves this class.
 *
 * <p>This slice (US1) settles the queue delivery: a terminal leg ({@code SENT}, {@code NOT_APPLICABLE}
 * or a terminal {@code FAILED}) completes the request {@code COMPLETED(both-legs-terminal)} and
 * completes the message; a {@code PARKED} leg moves the request to {@code RETRYING} and abandons the
 * delivery for redelivery. The final-permitted-delivery dead-letter (US3/US5), the database-outage
 * hand-back and the unexpected-exception final-delivery rule (US2) refine this; here an unexpected
 * failure abandons the delivery, never silently dropping it.
 */
public final class ProbationMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(ProbationMessageListener.class);

    private final DistributionCommandParser parser;
    private final IdempotencyGuard guard;
    private final DistributionRunner runner;
    private final ProcessedRequestRepository processedRequests;
    private final MetricRegistry metrics;

    /**
     * Creates the listener over its collaborators.
     *
     * @param parser            the closed-contract command parser
     * @param guard             the processed-log idempotency guard
     * @param runner            the distribution use case
     * @param processedRequests the request store for the per-request state transition
     * @param metrics           the metric registry
     */
    public ProbationMessageListener(final DistributionCommandParser parser,
                                    final IdempotencyGuard guard,
                                    final DistributionRunner runner,
                                    final ProcessedRequestRepository processedRequests,
                                    final MetricRegistry metrics) {
        this.parser = parser;
        this.guard = guard;
        this.runner = runner;
        this.processedRequests = processedRequests;
        this.metrics = metrics;
    }

    /**
     * Handles one peek-lock delivery, performing exactly one settlement after the state that justifies
     * it is written.
     *
     * @param context the delivery
     */
    // The listener must settle every delivery exactly once: any unexpected runtime failure is mapped
    // to an abandon here rather than left to the processor's default handling, which would leave the
    // delivery unsettled. Catching the generic type is deliberate and never swallows — it logs,
    // counts and abandons.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    public void processMessage(final ServiceBusReceivedMessageContext context) {
        final ServiceBusReceivedMessage message = context.getMessage();
        final String body = message.getBody().toString();
        try {
            final Settlement settlement = decide(body, message.getDeliveryCount());
            apply(context, settlement);
        } catch (final RuntimeException unexpected) {
            // Not swallowed: an unexpected failure is logged, counted and the delivery handed back for
            // redelivery. The final-permitted-delivery dead-letter is US2's (T052).
            metrics.processingFailure();
            LOG.error("unexpected failure processing a delivery; abandoning", unexpected);
            context.abandon();
        } finally {
            MdcFields.KEYS.forEach(MDC::remove);
        }
    }

    /**
     * Decides the settlement for one delivery. Visible for unit testing the parse/guard/run/settle
     * decision without the Azure plumbing.
     *
     * @param body          the raw message body
     * @param deliveryCount the broker delivery count
     * @return the settlement to apply
     */
    // The early return on a contract-invalid body and the switch's single return are the two exits a
    // parse-then-decide naturally has; funnelling them through one variable would not read more clearly.
    @SuppressWarnings("PMD.OnlyOneReturn")
    /* default */ Settlement decide(final String body, final long deliveryCount) {
        final DistributionCommand command;
        try {
            command = parser.parse(body);
        } catch (final CommandValidationException invalid) {
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            LOG.error("dead-lettering a contract-invalid delivery reason={} kind={}",
                    DeadLetterReason.VALIDATION.wireValue(), invalid.kind());
            return new DeadLetter(DeadLetterReason.VALIDATION);
        }
        setMdc(command, deliveryCount);
        final GuardDecision decision = guard.admit(command, body);
        return switch (decision) {
            case GuardDecision.DuplicateComplete ignored -> new Complete();
            case GuardDecision.Abandon ignored -> new Abandon();
            case GuardDecision.DeadLetter deadLetter -> {
                metrics.deadLettered(deadLetter.reason());
                yield new DeadLetter(deadLetter.reason());
            }
            case GuardDecision.Admitted admitted -> runAndSettle(command, admitted.claim());
        };
    }

    @SuppressWarnings("PMD.OnlyOneReturn")
    private Settlement runAndSettle(final DistributionCommand command, final RunClaim claim) {
        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, claim);
        if (!result.recorded()) {
            return staleRunnerRejection();
        }
        return switch (result.status()) {
            case SENT, NOT_APPLICABLE, FAILED -> {
                final boolean recorded = processedRequests.markCompleted(command.source(),
                        command.requestId(), claim, CompletionReason.BOTH_LEGS_TERMINAL);
                if (!recorded) {
                    yield staleRunnerRejection();
                }
                metrics.processed("COMPLETED");
                yield new Complete();
            }
            case PARKED -> {
                final boolean recorded =
                        processedRequests.markRetrying(command.source(), command.requestId(), claim);
                yield recorded ? new Abandon() : staleRunnerRejection();
            }
            case PENDING -> throw new IllegalStateException("a run never ends PENDING");
        };
    }

    /**
     * Counts and logs a stale-runner rejection and hands the delivery back for redelivery. A guarded
     * write that matched no row means a newer runner holds the claim; this run must not settle on a
     * transition it did not take (design row A2-67), so it abandons rather than completing.
     *
     * @return the abandon settlement
     */
    private Settlement staleRunnerRejection() {
        metrics.staleRunnerRejection();
        LOG.warn("stale-runner rejection: guarded write matched no row; abandoning");
        return new Abandon();
    }

    private static void setMdc(final DistributionCommand command, final long deliveryCount) {
        MDC.put(MdcFields.REQUEST_ID, command.requestId().toString());
        MDC.put(MdcFields.HEARING_ID, command.hearingId().toString());
        MDC.put(MdcFields.HEARING_DAY, command.hearingDay().toString());
        MDC.put(MdcFields.SOURCE, command.source());
        MDC.put(MdcFields.DELIVERY_COUNT, Long.toString(deliveryCount));
    }

    private static void apply(final ServiceBusReceivedMessageContext context,
                              final Settlement settlement) {
        switch (settlement) {
            case Complete ignored -> context.complete();
            case Abandon ignored -> context.abandon();
            case DeadLetter deadLetter -> context.deadLetter(
                    new DeadLetterOptions().setDeadLetterReason(deadLetter.reason().wireValue()));
        }
    }

    /** The settlement a delivery resolves to; the listener applies exactly one. */
    public sealed interface Settlement permits Complete, Abandon, DeadLetter {
    }

    /** Complete the delivery. */
    public record Complete() implements Settlement {
    }

    /** Abandon the delivery for redelivery. */
    public record Abandon() implements Settlement {
    }

    /**
     * Dead-letter the delivery with a bounded reason.
     *
     * @param reason the dead-letter reason
     */
    public record DeadLetter(DeadLetterReason reason) implements Settlement {
    }
}
