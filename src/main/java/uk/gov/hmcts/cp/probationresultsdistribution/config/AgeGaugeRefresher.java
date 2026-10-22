package uk.gov.hmcts.cp.probationresultsdistribution.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;

/** Refreshes database-derived age gauges every 30 seconds. */
public final class AgeGaugeRefresher {

    private static final Logger LOG = LoggerFactory.getLogger(AgeGaugeRefresher.class);
    private static final String NON_TERMINAL_AGE_SQL = "SELECT COALESCE(EXTRACT(EPOCH FROM "
            + "(now() - MIN(created_at))), 0)::double precision "
            + "FROM processed_request WHERE status IN ('RECEIVED', 'RETRYING')";
    private static final String PARKED_AGE_SQL = "SELECT COALESCE(EXTRACT(EPOCH FROM "
            + "(now() - MIN(updated_at))), 0)::double precision FROM outbound_message_latest "
            + "WHERE status = 'PARKED'";

    private final JdbcClient jdbc;
    private final MetricRegistry metrics;

    /** Creates the refresher over the processed-log database and metric registry. */
    public AgeGaugeRefresher(final JdbcClient jdbc, final MetricRegistry metrics) {
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    /** Refreshes both gauges together; a database outage preserves the last observed values. */
    @Scheduled(fixedDelay = 30_000L)
    public void refresh() {
        try {
            final double nonTerminalSeconds = jdbc.sql(NON_TERMINAL_AGE_SQL)
                    .query(Double.class).single();
            final double parkedSeconds = jdbc.sql(PARKED_AGE_SQL)
                    .query(Double.class).single();
            metrics.recordOldestAges(nonTerminalSeconds, parkedSeconds);
        } catch (final DataAccessException unavailable) {
            LOG.warn("could not refresh processed-log age gauges; retaining last values; type={}",
                    unavailable.getClass().getSimpleName());
        }
    }
}
