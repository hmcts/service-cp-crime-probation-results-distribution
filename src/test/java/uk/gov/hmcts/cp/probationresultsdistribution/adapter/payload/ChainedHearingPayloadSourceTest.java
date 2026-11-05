package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

@ExtendWith(MockitoExtension.class)
@DisplayName("the payload-fetch chain")
class ChainedHearingPayloadSourceTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Mock
    private CachedHearingPayloadAdapter cache;
    @Mock
    private ResultsQueryExternalHearingClient query;

    private ChainedHearingPayloadSource chain;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        chain = new ChainedHearingPayloadSource(cache, query, Duration.ofSeconds(60));
        command = new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private FetchedPayload payload(final PayloadSource source) {
        return new FetchedPayload(MAPPER.readTree("{\"hearing\":{}}"), source, Optional.empty());
    }

    @Test
    @DisplayName("returns the cache payload and never asks the query when the cache hits")
    void returns_the_cache_payload_without_querying() {
        when(cache.fetch(command)).thenReturn(Optional.of(payload(PayloadSource.CACHE_DAY)));

        final Optional<FetchedPayload> fetched = chain.fetch(command);

        assertThat(fetched).map(FetchedPayload::source).contains(PayloadSource.CACHE_DAY);
        verify(query, never()).fetch(any(), any());
    }

    @Test
    @DisplayName("falls through to the query when the cache misses")
    void falls_through_to_the_query_on_a_cache_miss() {
        when(cache.fetch(command)).thenReturn(Optional.empty());
        when(query.fetch(eq(command), any(StageBudget.class)))
                .thenReturn(Optional.of(payload(PayloadSource.QUERY)));

        final Optional<FetchedPayload> fetched = chain.fetch(command);

        assertThat(fetched).map(FetchedPayload::source).contains(PayloadSource.QUERY);
    }

    @Test
    @DisplayName("misses when every source misses")
    void misses_when_every_source_misses() {
        when(cache.fetch(command)).thenReturn(Optional.empty());
        when(query.fetch(eq(command), any(StageBudget.class))).thenReturn(Optional.empty());

        assertThat(chain.fetch(command)).isEmpty();
    }
}
