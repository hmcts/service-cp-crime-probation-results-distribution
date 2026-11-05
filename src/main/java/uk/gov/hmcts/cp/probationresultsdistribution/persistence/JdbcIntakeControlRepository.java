package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

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
