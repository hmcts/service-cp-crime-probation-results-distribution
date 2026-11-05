package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * "Now" per FR-003-005: read from the database server's own clock, pinned to UTC, never the JVM's or
 * the session's own clock. Used for the current month, the next two and the cut-off alike, so a run's
 * outcome does not depend on the pod's own time zone or the JDBC session's.
 */
public final class DatabaseClock {

    private final JdbcClient jdbc;

    /**
     * Creates the clock over a {@link JdbcClient}.
     *
     * @param jdbc the JDBC client to read the database server's clock through
     */
    public DatabaseClock(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The database server's current instant, pinned to UTC regardless of the session's own zone.
     *
     * @return the current instant as a UTC {@link OffsetDateTime}
     */
    public OffsetDateTime nowUtc() {
        return jdbc.sql("SELECT (now() AT TIME ZONE 'UTC')")
                .query(OffsetDateTime.class)
                .single();
    }
}
