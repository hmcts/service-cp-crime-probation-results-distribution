/**
 * The hearing payload source: the Redis {@code EXT_} cache, then the results query API (design
 * §4.4 {@code adapter.payload.CachedHearingPayloadAdapter},
 * {@code ResultsQueryExternalHearingClient}).
 *
 * <p>Read in order: the dated key {@code EXT_{hearingId}_{hearingDay}_result_}, the undated key,
 * then {@code hearingDetails/{hearingId}?hearingDate={hearingDay}} on the query API. A value that
 * will not parse, and a 2xx answer that is empty or {@code {}}, are misses; a miss everywhere is
 * transient and parks the leg as {@code payload-unavailable}. Redis TLS verifies the peer. The
 * source used is recorded as {@code cache-day}, {@code cache-nullday} or {@code query}.
 *
 * <p><strong>Dependency direction.</strong> Implements the {@code HearingPayloadSource} port
 * declared in {@code application}, and depends on that port, on {@code domain} and on the shared
 * HTTP retry support only. It never depends on another adapter package, on {@code persistence},
 * or on the driving adapters {@code inbound} and {@code api}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;
