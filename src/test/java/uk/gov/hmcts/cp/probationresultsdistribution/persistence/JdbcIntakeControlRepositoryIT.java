package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Pins {@code pausedSince()} (review of PR #8/#23, M3 follow-up): the timestamp
 * {@link ConfirmedIntakePauseGate} derives a standalone command's confirmation window from.
 */
@Testcontainers
@DisplayName("the intake_control repository's paused_at read")
class JdbcIntakeControlRepositoryIT extends SchemaTestSupport {

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
    @DisplayName("is empty while intake is not paused")
    void empty_when_not_paused() {
        assertThat(repository.pausedSince()).isEmpty();
    }

    @Test
    @DisplayName("holds the moment the pause row records, once paused")
    void present_once_paused() {
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = now() "
                + "WHERE id = 1");

        final Optional<Instant> pausedSince = repository.pausedSince();

        assertThat(pausedSince).isPresent();
        assertThat(pausedSince.get()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("is empty again once resumed")
    void empty_again_after_resuming() {
        execute("UPDATE intake_control SET paused_by_operator = true, paused_at = now() "
                + "WHERE id = 1");
        execute("UPDATE intake_control SET paused_by_operator = false, paused_at = NULL "
                + "WHERE id = 1");

        assertThat(repository.pausedSince()).isEmpty();
    }
}
