package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

/**
 * Exercises {@link BodyRetentionJob} against a real PostgreSQL 15 Testcontainer, through the whole
 * build-up of its user stories (AS-12). This file grows a {@code @Nested} class per story.
 */
@DisplayName("the body-retention job, against a real store")
class BodyRetentionJobIT extends SchemaTestSupport {

    private final PartitionCatalogue catalogue = new PartitionCatalogue(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
            new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    private final MaintenanceMetrics metrics = new MaintenanceMetrics(meterRegistry, repository);

    @Nested
    @DisplayName("User Story 1 - the coming months always have somewhere to put a body")
    class CreateAhead {

        @Test
        @DisplayName("creates the missing newest month with V1's exact name and bounds, owned by the service role")
        void creates_the_missing_newest_month() {
            final OffsetDateTime currentMonth = currentMonthStart();
            final String nextMonthName = partitionName(currentMonth.plusMonths(2));
            // V1's migration already created the current month and the next two; drop the
            // newest one to simulate it missing, as spec.md's independent test for User Story 1
            // prescribes.
            execute("DROP TABLE IF EXISTS \"" + nextMonthName + "\"");

            final BodyRetentionJob job = newJob();
            final MaintenanceRunOutcome outcome = job.run();

            assertThat(outcome.partitionsCreated()).contains(nextMonthName);
            final List<PartitionBound> partitions = catalogue.partitions();
            assertThat(partitions).anySatisfy(p -> {
                assertThat(p.partitionName()).isEqualTo(nextMonthName);
                assertThat(p.from()).isEqualTo(currentMonth.plusMonths(2));
                assertThat(p.to()).isEqualTo(currentMonth.plusMonths(3));
            });
            final String owner = jdbc()
                    .sql("SELECT pg_get_userbyid(relowner) FROM pg_class WHERE relname = :name")
                    .param("name", nextMonthName)
                    .query(String.class)
                    .single();
            final String currentRole = jdbc().sql("SELECT current_user").query(String.class).single();
            assertThat(owner).isEqualTo(currentRole);
        }

        @Test
        @DisplayName("creates nothing, and counts nothing, when all three months already exist")
        void creates_nothing_when_all_present() {
            // Self-contained: does not assume a sibling test already left the three months present
            // (the shared container's partition set is mutated by every test in this class).
            final BodyRetentionJob job = newJob();
            job.run();

            final MaintenanceRunOutcome outcome = job.run();

            assertThat(outcome.partitionsCreated()).isEmpty();
            assertThat(outcome.failures()).isEmpty();
        }

        @Test
        @DisplayName("refuses a creation that would overlap a same-ranged partition under another name")
        void refuses_an_overlapping_creation() {
            final OffsetDateTime currentMonth = currentMonthStart();
            final OffsetDateTime thirdMonthFrom = currentMonth.plusMonths(2);
            final OffsetDateTime thirdMonthTo = currentMonth.plusMonths(3);
            final String thirdMonthName = partitionName(thirdMonthFrom);
            final String overlapName = thirdMonthName + "_under_another_name";
            execute("DROP TABLE IF EXISTS \"" + thirdMonthName + "\"");
            repository.createPartition(overlapName, thirdMonthFrom, thirdMonthTo);

            try {
                final BodyRetentionJob job = newJob();
                final MaintenanceRunOutcome outcome = job.run();

                assertThat(outcome.partitionsCreated()).doesNotContain(thirdMonthName);
                assertThat(outcome.failures()).anySatisfy(f ->
                        assertThat(f.detail()).isEqualTo(thirdMonthName));
            } finally {
                // Restores the baseline for sibling tests: remove the stray overlap and recreate the
                // correctly named partition it blocked.
                execute("DROP TABLE IF EXISTS \"" + overlapName + "\"");
                repository.createPartition(thirdMonthName, thirdMonthFrom, thirdMonthTo);
            }
        }
    }

    @Nested
    @DisplayName("User Story 2 - bodies past the retention are removed, and nothing else is")
    class Removal {

        @Test
        @DisplayName("removes an expired month's partition and leaves its attempt row unchanged")
        void removes_an_expired_month_leaves_the_attempt_row() {
            final OffsetDateTime from = OffsetDateTime.of(2020, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2020, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final String partitionName = "outbound_body_2020_02";
            repository.createPartition(partitionName, from, to);
            final String messageId = insertBody(from.plusDays(1));

            final MaintenanceRunOutcome outcome = newJob().run();

            assertThat(outcome.partitionsDropped()).contains(partitionName);
            assertThat(catalogue.partitions())
                    .noneMatch(p -> partitionName.equals(p.partitionName()));
            final String digest = jdbc()
                    .sql("SELECT body_digest FROM outbound_message WHERE id = CAST(:id AS uuid)")
                    .param("id", messageId)
                    .query(String.class)
                    .single();
            assertThat(digest).isNotBlank();
        }

        @Test
        @DisplayName("removes a partition on its bounds even when its name suggests a later month")
        void removes_by_bounds_never_by_name() {
            final OffsetDateTime from = OffsetDateTime.of(2020, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2020, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            // Misleadingly named for 2020_04 but its real bounds cover March 2020.
            repository.createPartition("outbound_body_2020_04", from, to);

            final MaintenanceRunOutcome outcome = newJob().run();

            assertThat(outcome.partitionsDropped()).contains("outbound_body_2020_04");
        }

        @Test
        @DisplayName("deletes stranded DEFAULT rows older than the cut-off and leaves newer ones")
        void deletes_default_rows_older_than_the_cutoff() {
            // Neither date has a partition of its own, so both route to DEFAULT: "old" is well
            // before the (roughly six-month-back) cut-off and must be deleted; "recent" is a month
            // far enough ahead that no partition has been created for it yet (unlike "now", which
            // would route to its own already-created current-month partition, never DEFAULT) and
            // must be kept.
            final OffsetDateTime old = OffsetDateTime.of(2019, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime recent = OffsetDateTime.now(ZoneOffset.UTC).plusYears(5);
            final String oldMessageId = insertBody(old);
            final String recentMessageId = insertBody(recent);

            newJob().run();

            final List<String> remaining = jdbc()
                    .sql("SELECT outbound_message_id::text FROM outbound_body_default")
                    .query(String.class)
                    .list();
            assertThat(remaining).doesNotContain(oldMessageId).contains(recentMessageId);
        }
    }

    @Nested
    @DisplayName("User Story 3 - one runner at a time, and maintenance never stalls delivery")
    class SingleRunner {

        @Test
        @DisplayName("exactly one of two simultaneous runs performs the work, repeatedly")
        void exactly_one_of_two_simultaneous_runs_wins() throws InterruptedException {
            for (int rep = 0; rep < 20; rep++) {
                runOneConcurrentRepetition();
            }
        }

        private void runOneConcurrentRepetition() throws InterruptedException {
            final java.util.concurrent.CyclicBarrier barrier =
                    new java.util.concurrent.CyclicBarrier(2);
            final java.util.concurrent.atomic.AtomicReference<MaintenanceRunOutcome> first =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final java.util.concurrent.atomic.AtomicReference<MaintenanceRunOutcome> second =
                    new java.util.concurrent.atomic.AtomicReference<>();
            final Thread t1 = new Thread(() -> runAfterBarrier(barrier, first));
            final Thread t2 = new Thread(() -> runAfterBarrier(barrier, second));
            t1.start();
            t2.start();
            t1.join();
            t2.join();

            final boolean[] acquired = {first.get().lockAcquired(), second.get().lockAcquired()};
            assertThat(acquired).containsExactlyInAnyOrder(true, false);
            assertThat(first.get().failures()).isEmpty();
            assertThat(second.get().failures()).isEmpty();
        }

        private void runAfterBarrier(final java.util.concurrent.CyclicBarrier barrier,
                                     final java.util.concurrent.atomic.AtomicReference<MaintenanceRunOutcome> result) {
            try {
                barrier.await();
            } catch (final InterruptedException | java.util.concurrent.BrokenBarrierException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            result.set(newJob().run());
        }

        @Test
        @org.junit.jupiter.api.Timeout(30)
        @DisplayName("a creation gives up after 5s when another session holds a conflicting lock, and is logged/counted under lock-timeout")
        void a_creation_times_out_on_a_conflicting_lock() throws Exception {
            final OffsetDateTime currentMonth = currentMonthStart();
            final String nextMonthName = partitionName(currentMonth.plusMonths(2));
            execute("DROP TABLE IF EXISTS \"" + nextMonthName + "\"");

            try (java.sql.Connection blocker = datasource().getConnection()) {
                blocker.setAutoCommit(false);
                try (java.sql.Statement statement = blocker.createStatement()) {
                    // ACCESS SHARE — "an investigation query left open in a transaction on
                    // outbound_body" (spec.md) — conflicts with CREATE's need for ACCESS EXCLUSIVE,
                    // but is compatible with another ACCESS SHARE, so the catalogue read the run
                    // starts with is not itself blocked and the run reaches the CREATE step.
                    statement.execute("LOCK TABLE outbound_body IN ACCESS SHARE MODE");

                    final long start = System.nanoTime();
                    final MaintenanceRunOutcome outcome = newJob().run();
                    final long elapsedSeconds = java.time.Duration.ofNanos(
                            System.nanoTime() - start).toSeconds();

                    // At least one 5s lock_timeout was genuinely waited out (not instant), but other
                    // statements this run also attempts (any pending removal, the DEFAULT delete) may
                    // independently contend with the same ACCESS SHARE blocker and each wait up to
                    // their own 5s too, so only a lower bound is asserted here, within the test's own
                    // @Timeout(30) ceiling.
                    assertThat(elapsedSeconds).isGreaterThanOrEqualTo(3L);
                    assertThat(outcome.failures()).anySatisfy(f -> {
                        assertThat(f.step()).isEqualTo(
                                uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep.CREATE);
                        assertThat(f.cause()).isEqualTo(
                                uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause.LOCK_TIMEOUT);
                        assertThat(f.detail()).isEqualTo(nextMonthName);
                    });
                } finally {
                    blocker.rollback();
                }
            }

            // The next run, once the conflicting lock is gone, creates the month that timed out.
            final MaintenanceRunOutcome retry = newJob().run();
            assertThat(retry.partitionsCreated()).contains(nextMonthName);
        }

        @Test
        @DisplayName("a store the job cannot reach is a lock-step failure, touching no partition statement")
        void an_unreachable_store_is_a_lock_step_failure() {
            final javax.sql.DataSource unreachable = brokenDataSource();
            final BodyRetentionJob job = new BodyRetentionJob(new DatabaseClock(jdbc()), catalogue,
                    repository, Period.ofMonths(6), () -> new SingleRunnerLock(unreachable), metrics);

            final MaintenanceRunOutcome outcome = job.run();

            assertThat(outcome.lockAcquired()).isFalse();
            assertThat(outcome.failures()).hasSize(1);
            assertThat(outcome.failures().get(0).step()).isEqualTo(
                    uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep.LOCK);
        }

        @Test
        @org.junit.jupiter.api.Timeout(30)
        @DisplayName("a concurrent write to the current month's partition completes quickly during a run with removals")
        void a_concurrent_write_is_not_blocked_for_the_whole_run() throws Exception {
            // Give the run at least one removal to perform, so the run's duration is non-trivial.
            final OffsetDateTime oldFrom = OffsetDateTime.of(2018, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime oldTo = OffsetDateTime.of(2018, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            repository.createPartition("outbound_body_2018_06", oldFrom, oldTo);
            final String messageId = insertBody(oldFrom.plusDays(1));

            try (java.util.concurrent.ExecutorService executor =
                    java.util.concurrent.Executors.newSingleThreadExecutor()) {
                final java.util.concurrent.Future<MaintenanceRunOutcome> runFuture =
                        executor.submit(() -> newJob().run());
                final long start = System.nanoTime();
                // A plain write to the current month's own partition must not queue behind the
                // whole run — only (at most) behind one removal's own brief ACCESS EXCLUSIVE hold.
                insertBody(OffsetDateTime.now(ZoneOffset.UTC));
                final long elapsedSeconds = java.time.Duration.ofNanos(
                        System.nanoTime() - start).toSeconds();

                assertThat(elapsedSeconds).isLessThan(5L);
                final MaintenanceRunOutcome outcome = runFuture.get(30, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(outcome.partitionsDropped()).contains("outbound_body_2018_06");
            }
            assertThat(messageId).isNotBlank();
        }

        private javax.sql.DataSource brokenDataSource() {
            final org.springframework.jdbc.datasource.DriverManagerDataSource source =
                    new org.springframework.jdbc.datasource.DriverManagerDataSource();
            source.setUrl("jdbc:postgresql://localhost:1/nonexistent");
            source.setUsername("none");
            source.setPassword("none");
            source.setDriverClassName("org.postgresql.Driver");
            return source;
        }
    }

    @Test
    void standalone_month_table_should_not_be_counted_as_created() {
        final OffsetDateTime month = currentMonthStart().plusMonths(2);
        final String name = partitionName(month);
        execute("DROP TABLE IF EXISTS " + name);
        execute("CREATE TABLE " + name + " (marker integer)");
        try {
            final MaintenanceRunOutcome outcome = newJob().run();
            assertThat(outcome.partitionsCreated()).doesNotContain(name);
            assertThat(outcome.failures()).isEmpty();
        } finally {
            execute("DROP TABLE " + name);
            repository.createPartition(name, month, month.plusMonths(1));
        }
    }

    private String insertBody(final OffsetDateTime createdAt) {
        final String source = "RESULTS";
        final String requestId = java.util.UUID.randomUUID().toString();
        execute("INSERT INTO processed_request "
                + "(source, request_id, hearing_id, hearing_day, shared_time, event_type, "
                + " request_fingerprint, status, command) VALUES ("
                + "'" + source + "', '" + requestId + "', gen_random_uuid(), DATE '2026-08-19', "
                + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', 'fp', 'RECEIVED', "
                + "'{}')");
        final String messageId = java.util.UUID.randomUUID().toString();
        final String body = "{\"hearing\":{\"id\":\"" + messageId + "\"}}";
        final String digest = sha256Hex(body);
        execute("INSERT INTO outbound_message (id, source, request_id, leg, status, http_status, "
                + "body_digest, payload_source, service_version, youth_switch, sent_at) VALUES ("
                + "'" + messageId + "', '" + source + "', '" + requestId + "', 'HMPPS', 'SENT', 200, "
                + "'" + digest + "', 'cache-day', 'test-version', 'ACTIVE', now())");
        execute("INSERT INTO outbound_body (outbound_message_id, body, created_at) VALUES ("
                + "'" + messageId + "', '" + body.replace("'", "''") + "', "
                + "TIMESTAMPTZ '" + createdAt + "')");
        return messageId;
    }

    private static String sha256Hex(final String value) {
        try {
            final byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private BodyRetentionJob newJob() {
        return new BodyRetentionJob(new DatabaseClock(jdbc()), catalogue, repository,
                Period.ofMonths(6), () -> new SingleRunnerLock(datasource()), metrics);
    }

    private static OffsetDateTime currentMonthStart() {
        final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        return now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    private static String partitionName(final OffsetDateTime month) {
        return String.format(java.util.Locale.ROOT, "outbound_body_%04d_%02d", month.getYear(),
                month.getMonthValue());
    }
}
