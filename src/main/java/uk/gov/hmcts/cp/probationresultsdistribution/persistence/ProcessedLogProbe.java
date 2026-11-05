package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Can the processed log be reached right now? (design §4.2 Intake; FR-002-009).
 *
 * <p>One question, answered by asking the database the cheapest thing it can answer. Processed-log
 * availability is a <strong>precondition</strong> rather than a step: a delivery that arrives without
 * a store is handed back before its body is read, and the consumer lifecycle controller asks the same
 * question on a schedule to learn when an outage is over.
 *
 * <p>The failure is caught and turned into an answer rather than propagated — which is what this class
 * is for, and is not a swallowed exception: every caller acts on the answer, loudly. The exception
 * itself is reported at DEBUG by type, because during an outage this runs every few seconds and a
 * driver message would quote a connection string the constitution keeps out of every log level.
 */
public final class ProcessedLogProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessedLogProbe.class);

    /**
     * The cheapest round trip there is. Asked of the pool rather than of a table on purpose: the
     * question is "is the store reachable", and a query naming {@code processed_request} would also
     * fail on an unmigrated schema, which is a different problem with a different answer.
     */
    private static final String PROBE = "SELECT 1";

    private final JdbcClient jdbcClient;

    /**
     * Creates the probe over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     */
    public ProcessedLogProbe(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Asks the processed log to answer a trivial query.
     *
     * @return whether the processed log answered
     */
    public boolean available() {
        boolean reachable;
        try {
            jdbcClient.sql(PROBE).query(Integer.class).single();
            reachable = true;
        } catch (final DataAccessException unreachable) {
            LOG.debug("the processed log did not answer the probe; cause={}",
                    unreachable.getClass().getName());
            reachable = false;
        }
        return reachable;
    }
}
