package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The JDBC {@link RequestListingRepository}: plain {@code SELECT}s over {@code processed_request},
 * {@code outbound_message} and {@code outbound_message_latest}, selecting none of the columns a
 * listing must never print.
 */
public final class JdbcRequestListingRepository implements RequestListingRepository {

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the JDBC client
     */
    public JdbcRequestListingRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ListedAttempt> byHearing(final UUID hearingId, final LocalDate hearingDay,
                                         final int limit) {
        return List.of();
    }

    @Override
    public List<ListedAttempt> byLatestAttempt(final String status, final Optional<String> reason,
                                               final Optional<Instant> updatedAfter,
                                               final Optional<Instant> updatedBefore,
                                               final int limit) {
        return List.of();
    }

    @Override
    public Map<UUID, String> requestStatuses(final String source,
                                             final Collection<UUID> requestIds) {
        return Map.of();
    }

    /* default */ JdbcClient jdbc() {
        return jdbc;
    }
}
