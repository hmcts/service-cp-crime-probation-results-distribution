package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.util.Optional;
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
import uk.gov.hmcts.cp.probationresultsdistribution.domain.FailureReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
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
@SuppressWarnings("PMD.OnlyOneReturn")
public final class ProbationMessageListener {

    private static final Logger LOG = LoggerFactory.getLogger(ProbationMessageListener.class);
    private static final String ORIGIN_PROPERTY = "origin";
    private static final String DEFAULT_MESSAGE_ID = "unit-test";

    private final DistributionCommandParser parser;
    private final IdempotencyGuard guard;
    private final DistributionRunner runner;
    private final ProcessedRequestRepository processedRequests;
    private final MetricRegistry metrics;
    private final StoreGate storeGate;
    private final int maxDeliveryCount;

    /**
     * Creates the listener over its collaborators.
     *
     * @param parser            the closed-contract command parser
     * @param guard             the processed-log idempotency guard
     * @param runner            the distribution use case
     * @param processedRequests the request store for the per-request state transition
     * @param metrics           the metric registry
     * @param storeGate         the processed-log precondition every delivery passes through before its
     *                          body is read
     * @param maxDeliveryCount  the queue's own delivery budget, mirrored in configuration, used to
     *                          recognise the final permitted delivery
     */
    public ProbationMessageListener(final DistributionCommandParser parser,
                                    final IdempotencyGuard guard,
                                    final DistributionRunner runner,
                                    final ProcessedRequestRepository processedRequests,
                                    final MetricRegistry metrics,
                                    final StoreGate storeGate,
                                    final int maxDeliveryCount) {
        this.parser = parser;
        this.guard = guard;
        this.runner = runner;
        this.processedRequests = processedRequests;
        this.metrics = metrics;
        this.storeGate = storeGate;
        this.maxDeliveryCount = maxDeliveryCount;
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
        final long deliveryCount = message.getDeliveryCount();
        setDeliveryMdc(message.getSequenceNumber(), deliveryCount);
        Settlement settlement;
        // storeAvailable defaults to false: if the precondition check itself throws (below, inside the
        // try), the outage is unknown rather than disproven, so the finally block still suspends intake.
        boolean storeAvailable = false;
        try {
            // The store precondition is checked before the body is read (FR-002-009): a delivery
            // arriving while the processed log is unreachable is handed back unexamined, and intake is
            // asked to stop. Checked inside this try so a failure here is also settled, never escaping
            // processMessage unsettled (FR-002-007).
            storeAvailable = storeGate.storeAvailable();
            settlement = storeAvailable
                    ? decide(message.getBody().toString(), deliveryCount, message.getSequenceNumber(),
                            message.getMessageId(), routeOf(message))
                    : storeUnavailable(deliveryCount);
        } catch (final RuntimeException unexpected) {
            // Not swallowed: an unexpected failure is logged by type, counted and the delivery handed
            // back for redelivery — or dead-lettered exhausted on its final permitted delivery, so the
            // service's own reason is counted rather than the broker's (FR-002-007).
            metrics.processingFailure();
            LOG.error("unexpected failure processing a delivery; handing it back; type={}",
                    unexpected.getClass().getName());
            settlement = handBack(deliveryCount);
        }
        try {
            apply(context, settlement);
        } catch (final RuntimeException settlementFailure) {
            // Not swallowed in the defect sense: applying the chosen settlement is the one broker call the
            // listener cannot itself recover from — if complete()/abandon()/deadLetter() throws (the lock
            // lost, the connection dropped) any further broker call would fail the same way. It is logged
            // by type and counted; the lock is left to expire so the broker redelivers. The state that
            // justified the settlement is already persisted, so the redelivery is handled idempotently.
            // The exception must never escape processMessage and leave the processor to its default
            // handling (FR-002-007): the listener owns every delivery's outcome.
            metrics.settlementFailure();
            LOG.error("failed to apply the settlement for a delivery; the lock will expire and the "
                    + "broker will redeliver; type={}", settlementFailure.getClass().getName());
        } finally {
            // Suspend only after the settlement is durable: asking the lifecycle to stop before the
            // delivery is settled races the processor's own drain and can leave the settlement unapplied.
            if (!storeAvailable) {
                storeGate.suspendIntake();
            }
            MdcFields.KEYS.forEach(MDC::remove);
        }
    }

    /**
     * The processed log is unreachable, so this delivery is handed back unexamined — or dead-lettered
     * {@code exhausted} on its final permitted delivery, so the broker's budget is not burned on an
     * outage of ours (FR-002-009, FR-002-007). Nothing is examined, so nothing is judged.
     *
     * @param deliveryCount the broker delivery count
     * @return the settlement to apply
     */
    private Settlement storeUnavailable(final long deliveryCount) {
        final Settlement settlement = handBack(deliveryCount);
        if (settlement instanceof DeadLetter) {
            LOG.error("the processed log could not be reached, so the delivery was not examined; this "
                    + "was its final permitted delivery, so it is dead-lettered and intake is asked to "
                    + "stop; reason={}", DeadLetterReason.EXHAUSTED.wireValue());
        } else {
            LOG.error("the processed log could not be reached, so the delivery was not examined; it is "
                    + "returned for redelivery and intake is asked to stop");
        }
        return settlement;
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
    /* default */ Settlement decide(final String body, final long deliveryCount) {
        return decide(body, deliveryCount, 0L);
    }

    /* default */ Settlement decide(final String body, final long deliveryCount,
                              final long sequenceNumber) {
        // This overload's messageId/route stand in for the real broker values the tests that call it
        // do not exercise: the guard is mocked in every such test, so neither value drives behaviour.
        return decide(body, deliveryCount, sequenceNumber, DEFAULT_MESSAGE_ID, ReplayRoute.COPY);
    }

    private Settlement decide(final String body, final long deliveryCount,
                              final long sequenceNumber, final String messageId,
                              final ReplayRoute route) {
        final DistributionCommand command;
        try {
            command = parser.parse(body);
        } catch (final CommandValidationException invalid) {
            metrics.deadLettered(DeadLetterReason.VALIDATION);
            LOG.error("dead-lettering a contract-invalid delivery reason={} kind={}",
                    DeadLetterReason.VALIDATION.wireValue(), invalid.kind());
            return new DeadLetter(DeadLetterReason.VALIDATION);
        }
        setMdc(command, deliveryCount, sequenceNumber);
        final GuardDecision decision = guard.admit(command, body, messageId, deliveryCount, route);
        return switch (decision) {
            case GuardDecision.DuplicateComplete ignored -> new Complete();
            case GuardDecision.Abandon ignored -> handBack(deliveryCount);
            case GuardDecision.ReplayDeferred ignored -> {
                metrics.replayDeferred();
                LOG.warn("replay deferred: a PENDING resend row is in progress on the leg; "
                        + "abandoning requestId={}", command.requestId());
                yield handBack(deliveryCount);
            }
            case GuardDecision.DeadLetter deadLetter -> {
                metrics.deadLettered(deadLetter.reason());
                yield new DeadLetter(deadLetter.reason());
            }
            case GuardDecision.Admitted admitted -> {
                if (admitted.crashRerun()) {
                    metrics.crashRerun(Leg.HMPPS);
                }
                admitted.replayMessageId().ifPresent(replayId -> {
                    metrics.replays(route);
                    LOG.info("replay admitted requestId={} route={} messageId={}",
                            command.requestId(), route.wireValue(), replayId);
                });
                yield runAndSettle(command, admitted.claim(), deliveryCount, admitted.gatewayParked(),
                        admitted.replayMessageId());
            }
        };
    }

    /**
     * Hands a delivery back for redelivery, or dead-letters it {@code exhausted} when this is its final
     * permitted delivery (FR-002-007). A live claim, a lost race, a stale runner, an unexpected failure
     * and a database outage all share this rule: abandon until the broker's budget is spent, then record
     * the service's own {@code exhausted} reason rather than leaving the broker to park it under its own.
     *
     * @param deliveryCount the broker delivery count for this delivery
     * @return the settlement to apply
     */
    private Settlement handBack(final long deliveryCount) {
        final Settlement settlement;
        if (isFinalPermittedDelivery(deliveryCount)) {
            metrics.deadLettered(DeadLetterReason.EXHAUSTED);
            settlement = new DeadLetter(DeadLetterReason.EXHAUSTED);
        } else {
            settlement = new Abandon();
        }
        return settlement;
    }

    /**
     * Whether this is the final delivery the broker will permit (FR-002-048). Azure increments the
     * delivery count on each abandon or lock expiry, so the first delivery carries {@code 0} and the
     * {@code maxDeliveryCount}-th — the last the broker permits before it dead-letters under its own
     * reason — carries {@code maxDeliveryCount - 1}; the comparison is {@code >=} so a message that
     * somehow arrives past the budget is still recognised as final.
     *
     * @param deliveryCount the broker delivery count
     * @return whether this is the final permitted delivery
     */
    private boolean isFinalPermittedDelivery(final long deliveryCount) {
        return deliveryCount >= (long) maxDeliveryCount - 1;
    }

    private Settlement runAndSettle(final DistributionCommand command, final RunClaim claim,
                                    final long deliveryCount, final boolean gatewayParked,
                                    final Optional<String> replayMessageId) {
        final boolean finalDelivery = isFinalPermittedDelivery(deliveryCount);
        final RunResult result = replayMessageId.isPresent()
                ? runner.run(command, AttemptOrigin.REPLAY, claim, finalDelivery, replayMessageId.get())
                : runner.run(command, AttemptOrigin.QUEUE, claim, finalDelivery);
        if (!result.recorded()) {
            return staleRunnerRejection(deliveryCount);
        }
        if (gatewayParked && result.status() == LegStatus.SENT) {
            metrics.gatewayRecovered(GatewayRecovery.FIVE_XX_REDELIVERY);
        }
        return switch (result.status()) {
            case SENT, NOT_APPLICABLE -> completeTerminal(command, claim, deliveryCount);
            case FAILED -> settleFailed(command, claim, deliveryCount, result.reason());
            case PARKED -> {
                final boolean recorded =
                        processedRequests.markRetrying(command.source(), command.requestId(), claim);
                yield recorded ? new Abandon() : staleRunnerRejection(deliveryCount);
            }
            case PENDING -> throw new IllegalStateException("a run never ends PENDING");
        };
    }

    /**
     * Settles a terminal leg ({@code SENT}, {@code NOT_APPLICABLE}, or a {@code FAILED} whose reason
     * is terminal) by completing the request {@code both-legs-terminal} and completing the message.
     *
     * @param command       the admitted command
     * @param claim         the request claim fencing the transition
     * @param deliveryCount the broker delivery count, for a stale-runner hand-back
     * @return the settlement to apply
     */
    private Settlement completeTerminal(final DistributionCommand command, final RunClaim claim,
                                        final long deliveryCount) {
        final boolean recorded = processedRequests.markCompleted(command.source(),
                command.requestId(), claim, CompletionReason.BOTH_LEGS_TERMINAL);
        if (!recorded) {
            return staleRunnerRejection(deliveryCount);
        }
        metrics.processed("COMPLETED");
        return new Complete();
    }

    /**
     * Settles a {@code FAILED} leg. A terminal reason ({@code receiver-rejected},
     * {@code rules-exception}) completes the request {@code both-legs-terminal}. An exhaustion reason
     * ({@code payload-unavailable}, {@code gateway-transient}) is only ever produced on the final
     * permitted delivery, so the request is failed {@code leg-exhausted} and the message is
     * dead-lettered {@code leg-exhausted} (FR-002-047).
     *
     * @param command       the admitted command
     * @param claim         the request claim fencing the transition
     * @param deliveryCount the broker delivery count, for a stale-runner hand-back
     * @param reason        the leg's failure reason
     * @return the settlement to apply
     */
    private Settlement settleFailed(final DistributionCommand command, final RunClaim claim,
                                    final long deliveryCount, final Optional<LegReason> reason) {
        if (!isExhaustionReason(reason)) {
            return completeTerminal(command, claim, deliveryCount);
        }
        final boolean recorded = processedRequests.markFailed(command.source(), command.requestId(),
                claim, FailureReason.LEG_EXHAUSTED, "RESULTS:" + command.requestId());
        if (!recorded) {
            return staleRunnerRejection(deliveryCount);
        }
        metrics.deadLettered(DeadLetterReason.LEG_EXHAUSTED);
        metrics.processed("FAILED");
        return new DeadLetter(DeadLetterReason.LEG_EXHAUSTED);
    }

    private static boolean isExhaustionReason(final Optional<LegReason> reason) {
        return reason.filter(r -> r == LegReason.PAYLOAD_UNAVAILABLE
                || r == LegReason.GATEWAY_TRANSIENT).isPresent();
    }

    /**
     * Counts and logs a stale-runner rejection and hands the delivery back for redelivery, dead-lettering
     * it {@code exhausted} on the final permitted delivery (FR-002-007, A2-67). A guarded write that
     * matched no row means a newer runner holds the claim; this run must not settle on a transition it did
     * not take, so it never completes the message.
     *
     * @param deliveryCount the broker delivery count for this delivery
     * @return the hand-back settlement (abandon, or dead-letter exhausted on the final delivery)
     */
    private Settlement staleRunnerRejection(final long deliveryCount) {
        metrics.staleRunnerRejection();
        LOG.warn("stale-runner rejection: guarded write matched no row; handing the delivery back");
        return handBack(deliveryCount);
    }

    /**
     * Derives the replay route from the delivery's {@code origin} application property (design row
     * A3-34, FR-004-005): an audit label only, never read by any eligibility, settlement or sending
     * rule. Any value other than {@code REPLAY} or {@code SUPPORT} — absent included — is
     * {@code COPY}; the value itself is never recorded or logged (spec Assumption 17).
     *
     * @param message the delivery
     * @return the replay route this delivery would be admitted under, if admitted as a replay
     */
    private static ReplayRoute routeOf(final ServiceBusReceivedMessage message) {
        final Object origin = message.getApplicationProperties().get(ORIGIN_PROPERTY);
        final String originValue = origin instanceof String value ? value : null;
        return switch (originValue) {
            case "REPLAY" -> ReplayRoute.SERVICE;
            case "SUPPORT" -> ReplayRoute.SUPPORT;
            case null, default -> ReplayRoute.COPY;
        };
    }

    private static void setDeliveryMdc(final long sequenceNumber, final long deliveryCount) {
        MDC.put(MdcFields.SEQUENCE_NUMBER, Long.toString(sequenceNumber));
        MDC.put(MdcFields.DELIVERY_COUNT, Long.toString(deliveryCount));
        MDC.put(MdcFields.LEG, "HMPPS");
    }

    private static void setMdc(final DistributionCommand command, final long deliveryCount,
                               final long sequenceNumber) {
        MDC.put(MdcFields.REQUEST_ID, command.requestId().toString());
        MDC.put(MdcFields.HEARING_ID, command.hearingId().toString());
        MDC.put(MdcFields.HEARING_DAY, command.hearingDay().toString());
        MDC.put(MdcFields.SOURCE, command.source());
        setDeliveryMdc(sequenceNumber, deliveryCount);
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
