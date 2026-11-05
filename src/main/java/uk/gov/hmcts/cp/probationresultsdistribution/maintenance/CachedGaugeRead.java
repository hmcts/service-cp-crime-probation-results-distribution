package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * A database-backed gauge value, read whenever the metrics are collected, on every pod, lock or not,
 * at most {@code ttl} old (FR-003-022, FR-003-031, FR-003-032): a successful read is cached for
 * {@code ttl}; a failed read reports {@code -1} and is never cached, so the next collection retries
 * the database at once (A3-118).
 */
final class CachedGaugeRead {

    private static final long FAILED_READ = -1L;

    private final LongSupplier databaseRead;
    private final Duration ttl;
    private final AtomicReference<CacheEntry> cache = new AtomicReference<>();

    /**
     * Creates the cached read over a database-read supplier.
     *
     * @param databaseRead the live database read; MUST throw rather than return a sentinel on failure
     * @param ttl          how long a successful read is reused before the database is read again
     */
    /* default */ CachedGaugeRead(final LongSupplier databaseRead, final Duration ttl) {
        this.databaseRead = databaseRead;
        this.ttl = ttl;
    }

    // A Micrometer value function must never throw: a lock-timeout or a lost connection on this
    // live read would otherwise fail the whole scrape, not just this one series.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    /* default */ long value() {
        final CacheEntry cached = cache.get();
        final Instant now = Instant.now();
        if (cached != null && Duration.between(cached.readAt(), now).compareTo(ttl) < 0) {
            return cached.value();
        }
        try {
            final long fresh = databaseRead.getAsLong();
            cache.set(new CacheEntry(fresh, now));
            return fresh;
        } catch (final RuntimeException e) {
            return FAILED_READ;
        }
    }

    private record CacheEntry(long value, Instant readAt) {
    }
}
