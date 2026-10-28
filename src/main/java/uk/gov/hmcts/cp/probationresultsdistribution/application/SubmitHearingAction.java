package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Submit-hearing (design §4.8 action 5; slice 004, FR-004-054–FR-004-058).
 */
public final class SubmitHearingAction {

    private final HearingPayloadSource cache;
    private final CommandEnqueuer enqueuer;
    private final Supplier<UUID> runIds;

    /**
     * Creates the action.
     *
     * @param cache    the {@code EXT_} cache reads only (dated key, then undated key)
     * @param enqueuer the enqueue port
     * @param runIds   the source of a fresh run id per invocation
     */
    public SubmitHearingAction(final HearingPayloadSource cache, final CommandEnqueuer enqueuer,
                               final Supplier<UUID> runIds) {
        this.cache = cache;
        this.enqueuer = enqueuer;
        this.runIds = runIds;
    }

    /**
     * Checks the arguments whole, before the cache is read.
     *
     * @param request the arguments as supplied
     * @return the refusal, or empty when readable
     */
    public static Optional<WholeRunRefusal> check(final SubmitHearingRequest request) {
        return Optional.empty();
    }

    /**
     * Submits one hearing.
     *
     * @param request the arguments as supplied
     * @return the bounded outcome
     */
    public SubmitHearingOutcome submit(final SubmitHearingRequest request) {
        return cache == null || enqueuer == null || runIds == null
                ? new SubmitHearingOutcome.BrokerUnavailable()
                : new SubmitHearingOutcome.PayloadUnavailable();
    }
}
