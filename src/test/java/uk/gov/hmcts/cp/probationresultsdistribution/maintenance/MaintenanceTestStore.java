package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/** A disposable schema per test, keeping simulated calendars away from V1's shared baseline. */
abstract class MaintenanceTestStore extends SchemaTestSupport {

    // A near-zero TTL so this slice's other suites, which read a gauge immediately after changing the
    // state it reports, see every change without waiting out the five-minute production cache; the
    // caching behaviour itself (FR-003-022, FR-003-031, FR-003-032) has its own dedicated test.
    private static final java.time.Duration TEST_CACHE_TTL = java.time.Duration.ofMillis(1);

    private final String schema = "retention_" + UUID.randomUUID().toString().replace("-", "");
    protected final DriverManagerDataSource source;
    protected final JdbcClient sql;
    protected final PartitionMaintenanceRepository repository;
    protected final PartitionCatalogue catalogue;
    protected final BodyMaintenanceStateRepository stateRepository;

    protected MaintenanceTestStore() {
        execute("CREATE SCHEMA " + schema);
        final DriverManagerDataSource shared = (DriverManagerDataSource) datasource();
        source = new DriverManagerDataSource(shared.getUrl() + (shared.getUrl().contains("?") ? "&" : "?")
                + "currentSchema=" + schema,
                shared.getUsername(), shared.getPassword());
        Flyway.configure().dataSource(source).defaultSchema(schema).schemas(schema)
                .locations("classpath:db/migration").load().migrate();
        sql = JdbcClient.create(source);
        repository = new PartitionMaintenanceRepository(sql,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        catalogue = new PartitionCatalogue(sql,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
        stateRepository = new BodyMaintenanceStateRepository(sql,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    @AfterEach
    protected void removeSchema() {
        execute("DROP SCHEMA " + schema + " CASCADE");
    }

    protected final BodyRetentionJob job(final OffsetDateTime now, final Period retention,
                                         final MaintenanceMetrics metrics) {
        final DatabaseClock clock = org.mockito.Mockito.mock(DatabaseClock.class);
        org.mockito.Mockito.when(clock.nowUtc()).thenReturn(now);
        return new BodyRetentionJob(clock, catalogue, repository, retention,
                () -> new SingleRunnerLock(source), metrics, stateRepository);
    }

    protected final MaintenanceMetrics metrics(
            final io.micrometer.core.instrument.MeterRegistry registry, final OffsetDateTime now) {
        final DatabaseClock clock = org.mockito.Mockito.mock(DatabaseClock.class);
        org.mockito.Mockito.when(clock.nowUtc()).thenReturn(now);
        return new MaintenanceMetrics(registry, repository, catalogue, clock, stateRepository,
                TEST_CACHE_TTL);
    }

    protected final UUID insertBody(final OffsetDateTime at) {
        final UUID request = UUID.randomUUID();
        final UUID message = UUID.randomUUID();
        sql.sql("""
                INSERT INTO processed_request (source, request_id, hearing_id, hearing_day,
                    shared_time, event_type, request_fingerprint, status, command)
                VALUES ('RESULTS', :request, :request, DATE '2026-01-01', now(),
                    'Hearing_Resulted', 'fixture', 'RECEIVED', '{}')
                """).param("request", request).update();
        final String body = "{\"synthetic\":\"" + message + "\"}";
        sql.sql("""
                INSERT INTO outbound_message (id, source, request_id, leg, status, http_status,
                    body_digest, payload_source, service_version, youth_switch, sent_at)
                VALUES (:id, 'RESULTS', :request, 'HMPPS', 'SENT', 200,
                    encode(sha256(convert_to(:body, 'UTF8')), 'hex'), 'cache-day',
                    'retention-test', 'ACTIVE', now())
                """).param("id", message).param("request", request).param("body", body).update();
        sql.sql("INSERT INTO outbound_body (outbound_message_id, body, created_at) "
                + "VALUES (:id, :body, :at)").param("id", message)
                .param("body", body).param("at", at).update();
        return message;
    }

    protected final List<String> metadata() {
        return sql.sql("SELECT row_to_json(r)::text FROM processed_request r ORDER BY request_id")
                .query(String.class).list();
    }

    protected final List<String> attempts() {
        return sql.sql("SELECT row_to_json(m)::text FROM outbound_message m ORDER BY id")
                .query(String.class).list();
    }

    protected static String name(final OffsetDateTime month) {
        return String.format(java.util.Locale.ROOT, "outbound_body_%04d_%02d", month.getYear(),
                month.getMonthValue());
    }
}
