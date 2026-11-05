package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

@DisplayName("the single-runner advisory lock")
class SingleRunnerLockIT extends SchemaTestSupport {

    @Test
    @DisplayName("a second attempt fails fast while the first holds it, and succeeds once released")
    void serialises_concurrent_attempts() {
        final SingleRunnerLock first = new SingleRunnerLock(datasource());
        final SingleRunnerLock second = new SingleRunnerLock(datasource());

        assertThat(first.tryAcquire()).isTrue();
        assertThat(second.tryAcquire()).isFalse();

        first.release(true);

        assertThat(second.tryAcquire()).isTrue();
        second.release(true);
    }

    @Test
    @DisplayName("stays held across other statements run on different connections until released")
    void stays_held_across_other_statements() {
        final SingleRunnerLock first = new SingleRunnerLock(datasource());
        assertThat(first.tryAcquire()).isTrue();

        // An unrelated statement on a different connection must not be blocked by the lock, and
        // must not itself release it.
        jdbc().sql("SELECT 1").query(Integer.class).single();

        final SingleRunnerLock second = new SingleRunnerLock(datasource());
        assertThat(second.tryAcquire()).isFalse();

        first.release(true);
        assertThat(second.tryAcquire()).isTrue();
        second.release(true);
    }

    @Test
    @DisplayName("a rollback release also frees the lock for the next attempt")
    void rollback_also_releases() {
        final SingleRunnerLock first = new SingleRunnerLock(datasource());
        assertThat(first.tryAcquire()).isTrue();

        first.release(false);

        final SingleRunnerLock second = new SingleRunnerLock(datasource());
        assertThat(second.tryAcquire()).isTrue();
        second.release(true);
    }
}
