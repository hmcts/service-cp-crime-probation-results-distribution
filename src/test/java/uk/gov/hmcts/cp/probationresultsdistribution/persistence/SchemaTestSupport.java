package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Shared Testcontainers base for the V1 schema suites. One PostgreSQL 15 container is started once
 * for the JVM and reused across every schema IT, with Flyway run from {@code classpath:db/migration}
 * so each suite sees exactly the migration under test. Ryuk removes the container at the end.
 */
class SchemaTestSupport {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"));
    private static volatile boolean migrated;

    static {
        POSTGRES.start();
    }

    private final DataSource ds = buildDataSource();

    SchemaTestSupport() {
        migrateOnce();
    }

    private static DataSource buildDataSource() {
        final DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        return source;
    }

    private void migrateOnce() {
        if (migrated) {
            return;
        }
        synchronized (SchemaTestSupport.class) {
            if (!migrated) {
                Flyway.configure()
                        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                                POSTGRES.getPassword())
                        .locations("classpath:db/migration")
                        .load()
                        .migrate();
                migrated = true;
            }
        }
    }

    final DataSource datasource() {
        return ds;
    }

    final JdbcClient jdbc() {
        return JdbcClient.create(ds);
    }

    /**
     * Runs a statement, failing the test (by propagating) if the database rejects it.
     *
     * @param sql the statement to run
     */
    final void execute(final String sql) {
        jdbc().sql(sql).update();
    }

    /**
     * Inserts a minimal valid {@code RECEIVED} parent request so a leg row has a parent to reference.
     *
     * @param source    the command source
     * @param requestId the request id
     */
    final void insertReceivedRequest(final String source, final String requestId) {
        execute("INSERT INTO processed_request "
                + "(source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                + " request_fingerprint, status, command) VALUES ("
                + "'" + source + "', '" + requestId + "', gen_random_uuid(), DATE '2026-08-19', "
                + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', 'RECEIVED', "
                + "'{}')");
    }
}
