package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.SupportSubmission;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.RequestRecord;

/**
 * The processed-log guard, the only duplicate protection (design §4.5, H14, FR-002-007). Every copy
 * of a message reaches it. It decides, for one delivery, what the listener must do: admit and run,
 * complete a duplicate, abandon a live claim or lost race, admit as a replay, defer over an
 * in-progress resend, or dead-letter a collision or an exhausted replay identity.
 *
 * <p>A {@code FAILED} request's delivery is judged by the DV-26 predicate (design A2-62, A3-30;
 * slice 004 FR-004-001, replacing the interim FR-002-008): admitted as a replay unless its
 * {@code messageId} repeats the request's {@code exhausted_message_id} on other than the first
 * delivery of that id, in which case it is dead-lettered {@code exhausted}; and, before that replay
 * statement runs, deferred instead whenever the leg's latest row is a {@code PENDING} resend row,
 * live or expired (FR-004-003).
 */
public final class IdempotencyGuard {

    private static final String RECEIVED = "RECEIVED";
    private static final String RETRYING = "RETRYING";
    private static final String COMPLETED = "COMPLETED";
    private static final String FAILED = "FAILED";

    private final ProcessedRequestRepository repository;
    private final RequestFingerprint fingerprint;

    /**
     * Creates the guard.
     *
     * @param repository  the processed-log store
     * @param fingerprint the request fingerprint function
     */
    public IdempotencyGuard(final ProcessedRequestRepository repository,
                            final RequestFingerprint fingerprint) {
        this.repository = repository;
        this.fingerprint = fingerprint;
    }

    /**
     * Decides the outcome for one delivery of a valid command.
     *
     * @param command       the parsed command
     * @param commandBody   the admitted body exactly as received
     * @param messageId     the delivery's {@code messageId}, judged against
     *                      {@code exhausted_message_id} for a {@code FAILED} request (FR-004-001)
     * @param deliveryCount the broker delivery count (0 on a first delivery, matching this
     *                      listener's convention)
     * @param route         the replay route this delivery would be admitted under, derived from its
     *                      {@code origin} application property; read only when the delivery is
     *                      admitted as a replay (FR-004-005)
     * @return the guard's decision
     */
    public GuardDecision admit(final DistributionCommand command, final String commandBody,
                               final String messageId, final long deliveryCount,
                               final ReplayRoute route) {
        return admit(command, commandBody, messageId, deliveryCount, route, Optional.empty());
    }

    /**
     * Decides the outcome for one delivery marked {@code origin = SUPPORT}, carrying the
     * submit-hearing markers to audit when it is admitted (slice 004, FR-004-057).
     *
     * @param command       the parsed command
     * @param commandBody   the admitted body exactly as received
     * @param messageId     the delivery's {@code messageId}
     * @param deliveryCount the broker delivery count
     * @param route         the replay route ({@code support})
     * @param support       the submit-hearing markers
     * @return the guard's decision
     */
    public GuardDecision admit(final DistributionCommand command, final String commandBody,
                               final String messageId, final long deliveryCount,
                               final ReplayRoute route, final SupportSubmission support) {
        return admit(command, commandBody, messageId, deliveryCount, route, Optional.of(support));
    }

    private GuardDecision admit(final DistributionCommand command, final String commandBody,
                                final String messageId, final long deliveryCount,
                                final ReplayRoute route, final Optional<SupportSubmission> support) {
        final String fp = fingerprint.compute(command);
        final Optional<RequestRecord> existing =
                repository.find(command.source(), command.requestId());
        final GuardDecision decision;
        if (existing.isEmpty()) {
            decision = admitFresh(command, commandBody, fp, support);
        } else {
            final RequestRecord row = existing.get();
            if (row.fingerprint().equals(fp)) {
                decision = decideForKnownKey(command, row, messageId, deliveryCount, route, support);
            } else {
                decision = new GuardDecision.DeadLetter(DeadLetterReason.COLLISION);
            }
        }
        return decision;
    }

    private GuardDecision decideForKnownKey(final DistributionCommand command,
                                            final RequestRecord row, final String messageId,
                                            final long deliveryCount, final ReplayRoute route,
                                            final Optional<SupportSubmission> support) {
        return switch (row.status()) {
            case COMPLETED -> new GuardDecision.DuplicateComplete();
            case FAILED -> decideReplayAdmission(command, row, messageId, deliveryCount, route,
                    support);
            case RECEIVED, RETRYING -> reclaimOrAbandon(command, row);
            default -> throw new IllegalStateException(
                    "unknown request status: " + row.status());
        };
    }

    /**
     * The DV-26 predicate (FR-004-001, FR-004-002, FR-004-003): the exhausted-identity test runs
     * first, so a copy under the exhausted {@code messageId} has exactly one admission chance; only
     * then is the leg checked for a {@code PENDING} resend row, live or expired, which defers the
     * delivery rather than admitting or dead-lettering it.
     *
     * @param command       the parsed command
     * @param row           the request's current state
     * @param messageId     the delivery's {@code messageId}
     * @param deliveryCount the broker delivery count
     * @param route         the replay route to record if admitted
     * @param support       the submit-hearing markers to audit, for a {@code SUPPORT} delivery
     * @return the guard's decision: dead-letter, defer, admit as a replay, or abandon a lost race
     */
    private GuardDecision decideReplayAdmission(final DistributionCommand command,
                                                final RequestRecord row, final String messageId,
                                                final long deliveryCount, final ReplayRoute route,
                                                final Optional<SupportSubmission> support) {
        final boolean sameExhaustedId = row.exhaustedMessageId().filter(messageId::equals).isPresent();
        // This listener's convention is a zero-based delivery count (the first delivery carries 0),
        // so the design's "delivery count 1" (a one-based count) is this count's 0.
        final boolean firstDelivery = deliveryCount == 0;
        final GuardDecision decision;
        if (sameExhaustedId && !firstDelivery) {
            decision = new GuardDecision.DeadLetter(DeadLetterReason.EXHAUSTED);
        } else if (row.pendingResendRowLatest()) {
            decision = new GuardDecision.ReplayDeferred();
        } else {
            final RunClaim claim = RunClaim.fresh();
            final boolean admitted = support.isPresent()
                    ? repository.admitReplay(command.source(), command.requestId(), claim, route,
                            support.get())
                    : repository.admitReplay(command.source(), command.requestId(), claim, route);
            decision = admitted ? new GuardDecision.Admitted(claim, false, false,
                    Optional.of(messageId), repository.hasTerminalSuccess(command.source(), command.requestId()))
                    : new GuardDecision.Abandon();
        }
        return decision;
    }

    private GuardDecision admitFresh(final DistributionCommand command, final String commandBody,
                                     final String fingerprintValue,
                                     final Optional<SupportSubmission> support) {
        final RunClaim claim = RunClaim.fresh();
        final boolean inserted = support.isPresent()
                ? repository.insertClaim(command, commandBody, fingerprintValue, claim, support.get())
                : repository.insertClaim(command, commandBody, fingerprintValue, claim);
        return inserted ? new GuardDecision.Admitted(claim) : new GuardDecision.Abandon();
    }

    private GuardDecision reclaimOrAbandon(final DistributionCommand command,
                                           final RequestRecord row) {
        final GuardDecision decision;
        if (row.claimLive()) {
            decision = new GuardDecision.Abandon();
        } else {
            final RunClaim claim = RunClaim.fresh();
            final boolean reclaimed = repository.reclaim(command.source(), command.requestId(), claim);
            final boolean crashRerun = reclaimed && row.attemptOneStatus().filter(
                    "PENDING"::equals).isPresent();
            final boolean gatewayParked = reclaimed && row.attemptOneStatus().filter(
                    "PARKED"::equals).isPresent() && row.attemptOneReason().filter(
                    "gateway-transient"::equals).isPresent() && row.attemptOneHttpStatus()
                    .stream().anyMatch(status -> status >= 500 && status < 600);
            decision = reclaimed ? new GuardDecision.Admitted(claim, crashRerun, gatewayParked,
                    Optional.empty(), repository.hasTerminalSuccess(command.source(), command.requestId()))
                    : new GuardDecision.Abandon();
        }
        return decision;
    }
}
