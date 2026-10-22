package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import java.time.Duration;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * The payload-fetch chain (design §10, FR-002-015, FR-002-013): the runner's single payload-fetch
 * stage, composing the three sources in order — the dated cache key, the undated cache key (both
 * behind {@link CachedHearingPayloadAdapter}), then the results query API. The first source that
 * yields a payload ends the fetch; when every source misses the runner parks the leg
 * {@code payload-unavailable}.
 *
 * <p>The payload stage budget is measured from the stage's start: it opens as the chain's
 * {@code fetch} begins, so the cache reads and the query retries share the one 60 s window, and the
 * query's attempt-fits rule is checked against the time the cache reads leave.
 */
public final class ChainedHearingPayloadSource implements HearingPayloadSource {

    private final CachedHearingPayloadAdapter cache;
    private final ResultsQueryExternalHearingClient query;
    private final Duration payloadBudget;

    /**
     * Creates the chain over the cache adapter, the query client and the payload stage budget.
     *
     * @param cache         the two-key cache adapter (dated then undated)
     * @param query         the results-query fallback
     * @param payloadBudget the payload stage budget (default 60 s)
     */
    public ChainedHearingPayloadSource(final CachedHearingPayloadAdapter cache,
                                       final ResultsQueryExternalHearingClient query,
                                       final Duration payloadBudget) {
        this.cache = cache;
        this.query = query;
        this.payloadBudget = payloadBudget;
    }

    @Override
    public Optional<FetchedPayload> fetch(final DistributionCommand command) {
        final StageBudget budget = StageBudget.startingNow(payloadBudget);
        Optional<FetchedPayload> result = cache.fetch(command);
        if (result.isEmpty()) {
            result = query.fetch(command, budget);
        }
        return result;
    }
}
