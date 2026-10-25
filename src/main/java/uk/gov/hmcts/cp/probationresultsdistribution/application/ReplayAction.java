package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ReplayCandidate;

/**
 * The service's replay action (design §4.8 action 2; slice 004, FR-004-014–FR-004-017). It takes one
 * {@code requestId}, decides under the request's current state, and — for a {@code FAILED} request
 * with no {@code PENDING} resend row on its leg — re-sends the stored command byte for byte under a
 * fresh message identity. It writes nothing to the store itself: the admission statement that moves
 * the request {@code FAILED} → {@code RECEIVED} runs later, when the replay message is delivered
 * (the guard's DV-26 predicate, {@link IdempotencyGuard}).
 *
 * <p>Replay reuses resend-leg's own refusal codes for a missing or ineligible request
 * ({@code unknown-request}, {@code not-eligible}; the design names only {@code resend-in-progress}
 * for replay itself), rather than coining new ones.
 */
public final class ReplayAction {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayAction.class);
    private static final String SOURCE = "RESULTS";
    private static final String ORIGIN_REPLAY = "REPLAY";

    private final ProcessedRequestRepository repository;
    private final ReplayEnqueuer enqueuer;

    /**
     * Creates the action.
     *
     * @param repository the processed-log store
     * @param enqueuer   the replay enqueue port
     */
    public ReplayAction(final ProcessedRequestRepository repository, final ReplayEnqueuer enqueuer) {
        this.repository = repository;
        this.enqueuer = enqueuer;
    }

    /**
     * Replays one request.
     *
     * @param requestId the request to replay
     * @return the bounded outcome
     */
    public ReplayOutcome replay(final UUID requestId) {
        Optional<ReplayCandidate> found;
        boolean storeUnavailable = false;
        try {
            found = repository.findForReplay(SOURCE, requestId);
        } catch (final DataAccessException storeFailure) {
            LOG.error("replay store-unavailable requestId={}", requestId);
            found = Optional.empty();
            storeUnavailable = true;
        }
        final ReplayOutcome outcome;
        if (storeUnavailable) {
            outcome = new ReplayOutcome.StoreUnavailable();
        } else {
            outcome = found.map(candidate -> decide(requestId, candidate))
                    .orElseGet(() -> {
                        LOG.info("replay outcome=unknown-request requestId={}", requestId);
                        return new ReplayOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST);
                    });
        }
        return outcome;
    }

    private ReplayOutcome decide(final UUID requestId, final ReplayCandidate candidate) {
        return switch (candidate.status()) {
            case "COMPLETED" -> {
                LOG.info("replay outcome=no-op requestId={}", requestId);
                yield new ReplayOutcome.NoOp();
            }
            case "RECEIVED", "RETRYING" -> {
                LOG.info("replay outcome=not-eligible requestId={}", requestId);
                yield new ReplayOutcome.Refused(ResendRefusal.NOT_ELIGIBLE);
            }
            case "FAILED" -> candidate.pendingResendRowLatest()
                    ? refuseResendInProgress(requestId)
                    : enqueue(requestId, candidate);
            default -> throw new IllegalStateException("unknown request status: " + candidate.status());
        };
    }

    private ReplayOutcome refuseResendInProgress(final UUID requestId) {
        LOG.info("replay outcome=resend-in-progress requestId={}", requestId);
        return new ReplayOutcome.Refused(ResendRefusal.RESEND_IN_PROGRESS);
    }

    private ReplayOutcome enqueue(final UUID requestId, final ReplayCandidate candidate) {
        final String messageId = ORIGIN_REPLAY + ":" + UUID.randomUUID();
        ReplayOutcome outcome;
        try {
            enqueuer.enqueue(messageId, candidate.hearingId().toString(), candidate.command());
            LOG.info("replay outcome=enqueued requestId={} messageId={}", requestId, messageId);
            outcome = new ReplayOutcome.Enqueued(messageId);
        } catch (final ReplayEnqueuer.ReplayEnqueueException broker) {
            LOG.error("replay broker-unavailable requestId={}", requestId);
            outcome = new ReplayOutcome.BrokerUnavailable();
        }
        return outcome;
    }
}
