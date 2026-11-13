package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Duration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The JDBC {@link IntakeControlRepository} over the single-row {@code intake_control} table.
 */
public final class JdbcIntakeControlRepository implements IntakeControlRepository {

    private final JdbcClient jdbc;

    /**
     * Creates the repository.
     *
     * @param jdbc the JDBC client
     */
    public JdbcIntakeControlRepository(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean pausedByOperator() {
        return Boolean.TRUE.equals(jdbc.sql("SELECT paused_by_operator FROM intake_control WHERE id = 1")
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean pausedForAtLeast(final Duration window) {
        // paused_at + make_interval(secs => :windowSeconds) <= now() is evaluated by Postgres
        // against its own clock on both sides, so the caller's clock never enters the comparison
        // (review of PR #23 round 4). windowSeconds is bound as a fractional number of seconds, not
        // truncated to whole seconds, since the packaged defaults carry sub-second remainders and a
        // test environment shortens the window to tens of milliseconds. "AND paused_at IS NOT NULL"
        // is defensive: paused_by_operator is never true with a NULL paused_at through pause()'s own
        // statement, but a CHECK-less row must still not confirm on a null comparison, which SQL
        // would otherwise answer UNKNOWN.
        return Boolean.TRUE.equals(jdbc.sql("SELECT paused_by_operator AND paused_at IS NOT NULL "
                        + "AND paused_at + make_interval(secs => :windowSeconds) <= now() "
                        + "FROM intake_control WHERE id = 1")
                .param("windowSeconds", window.toMillis() / 1000.0)
                .query(Boolean.class)
                .single());
    }

    @Override
    public boolean pause() {
        final int rows = jdbc.sql("UPDATE intake_control SET paused_by_operator = true, "
                        + "paused_at = now(), updated_at = now() "
                        + "WHERE id = 1 AND paused_by_operator = false")
                .update();
        return rows > 0;
    }

    @Override
    public boolean resume() {
        final int rows = jdbc.sql("UPDATE intake_control SET paused_by_operator = false, "
                        + "paused_at = NULL, updated_at = now() "
                        + "WHERE id = 1 AND paused_by_operator = true")
                .update();
        return rows > 0;
    }
}
