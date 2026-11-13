package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins {@code pausedForAtLeast()} (review of PR #8/#23, M3 and round-4 follow-ups): the SQL
 * {@link ConfirmedIntakePauseGate} derives a standalone command's confirmation from, evaluated
 * entirely against the database's own clock so the comparison never depends on the caller's.
 */
@Testcontainers
@DisplayName("the intake_control repository's confirmed-pause read")
class JdbcIntakeControlRepositoryIT extends SchemaTestSupport {

    private static final Duration WINDOW = Duration.ofMillis(300);

    private final JdbcIntakeControlRepository repository = new JdbcIntakeControlRepository(jdbc());

    @AfterEach
    void tearDown() {
        // SchemaTestSupport's Postgres container (and its intake_control row) is shared by every
        // schema IT in this JVM; leaving the row paused would fail SchemaVocabularyIT's "seeds
        // exactly one row, unpaused" assertion for an entirely different test class.
        execute("UPDATE intake_control SET paused_by_operator = false, paused_at = NULL "
                + "WHERE id = 1");
    }

    @Test
    @DisplayName("is false while intake is not paused at all")
    void false_when_not_paused() {
        assertThat(repository.pausedForAtLeast(WINDOW)).isFalse();
    }

    @Test
    @DisplayName("is false before the window has elapsed, even though the row already says paused")
    void false_before_the_window_elapses() throws InterruptedException {
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = now() "
                + "WHERE id = 1");

        assertThat(repository.pausedForAtLeast(WINDOW)).isFalse();
    }

    @Test
    @DisplayName("is true once the window has genuinely elapsed, to millisecond precision")
    void true_once_the_window_has_elapsed() throws InterruptedException {
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = now() "
                + "WHERE id = 1");

        Thread.sleep(WINDOW.plusMillis(100).toMillis());

        assertThat(repository.pausedForAtLeast(WINDOW)).isTrue();
    }

    @Test
    @DisplayName("is false again once resumed, however long ago it was paused")
    void false_again_after_resuming() {
        execute("UPDATE intake_control SET paused_by_operator = true, "
                + "paused_at = now() - interval '1 hour' WHERE id = 1");
        execute("UPDATE intake_control SET paused_by_operator = false, paused_at = NULL "
                + "WHERE id = 1");

        assertThat(repository.pausedForAtLeast(WINDOW)).isFalse();
    }

    @Test
    @DisplayName("is false when the row says paused but carries no paused_at, rather than confirming "
            + "on a null comparison")
    void false_when_paused_with_no_timestamp() {
        // Not a state pause()/resume() can produce; pinned defensively since the SQL comparison
        // would otherwise evaluate UNKNOWN, not FALSE, on a NULL paused_at.
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = NULL "
                + "WHERE id = 1");

        assertThat(repository.pausedForAtLeast(WINDOW)).isFalse();
    }
}
