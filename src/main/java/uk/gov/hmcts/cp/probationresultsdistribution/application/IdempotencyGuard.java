package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.RequestRecord;

/**
 * The processed-log guard, the only duplicate protection (design §4.5, H14, FR-002-007). Every copy
 * of a message reaches it. It decides, for one delivery, what the listener must do: admit and run,
 * complete a duplicate, abandon a live claim or lost race, or dead-letter a collision or a failed
 * request. In this slice a {@code FAILED} request is dead-lettered {@code exhausted} at once
 * (interim FR-002-008; slice 004 replaces this with replay admission).
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
     * @param command     the parsed command
     * @param commandBody the admitted body exactly as received
     * @return the guard's decision
     */
    public GuardDecision admit(final DistributionCommand command, final String commandBody) {
        final String fp = fingerprint.compute(command);
        final Optional<RequestRecord> existing =
                repository.find(command.source(), command.requestId());
        final GuardDecision decision;
        if (existing.isEmpty()) {
            decision = admitFresh(command, commandBody, fp);
        } else {
            final RequestRecord row = existing.get();
            if (row.fingerprint().equals(fp)) {
                decision = decideForKnownKey(command, row);
            } else {
                decision = new GuardDecision.DeadLetter(DeadLetterReason.COLLISION);
            }
        }
        return decision;
    }

    private GuardDecision decideForKnownKey(final DistributionCommand command,
                                            final RequestRecord row) {
        return switch (row.status()) {
            case COMPLETED -> new GuardDecision.DuplicateComplete();
            case FAILED -> new GuardDecision.DeadLetter(DeadLetterReason.EXHAUSTED);
            case RECEIVED, RETRYING -> reclaimOrAbandon(command, row);
            default -> throw new IllegalStateException(
                    "unknown request status: " + row.status());
        };
    }

    private GuardDecision admitFresh(final DistributionCommand command, final String commandBody,
                                     final String fingerprintValue) {
        final RunClaim claim = RunClaim.fresh();
        return repository.insertClaim(command, commandBody, fingerprintValue, claim)
                ? new GuardDecision.Admitted(claim) : new GuardDecision.Abandon();
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
            decision = reclaimed ? new GuardDecision.Admitted(claim, crashRerun, gatewayParked)
                    : new GuardDecision.Abandon();
        }
        return decision;
    }
}
