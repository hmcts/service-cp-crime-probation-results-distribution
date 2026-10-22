package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.SharedTimes;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;

@DisplayName("the payload stage budget and defensive sharedTime parsing")
class PayloadStageBudgetTest {

    private static final Instant START = Instant.parse("2026-08-19T16:00:00Z");

    @Test
    @DisplayName("admits an attempt whose worst case fits the remaining budget")
    void admits_an_attempt_that_fits() {
        final StageBudget budget = StageBudget.startingAt(START, Duration.ofSeconds(60));

        // 10s into a 60s stage, a 35s worst case (wait + connect + read) ends at 45s <= 60s.
        assertThat(budget.attemptFits(START.plusSeconds(10), Duration.ofSeconds(35))).isTrue();
    }

    @Test
    @DisplayName("refuses an attempt whose worst case no longer fits, ending the stage")
    void refuses_an_attempt_that_no_longer_fits() {
        final StageBudget budget = StageBudget.startingAt(START, Duration.ofSeconds(60));

        // 40s into a 60s stage, a 35s worst case would end at 75s > 60s, so it is not started.
        assertThat(budget.attemptFits(START.plusSeconds(40), Duration.ofSeconds(35))).isFalse();
    }

    @Test
    @DisplayName("exposes the stage deadline the start plus the budget")
    void exposes_the_deadline() {
        final StageBudget budget = StageBudget.startingAt(START, Duration.ofSeconds(60));

        assertThat(budget.deadline()).isEqualTo(START.plusSeconds(60));
    }

    @Test
    @DisplayName("records a sharedTime with any offset and leaves an unparseable one empty")
    void parses_sharedtime_defensively() {
        assertThat(SharedTimes.parse("2026-08-19T17:42:07.512+01:00"))
                .contains(Instant.parse("2026-08-19T16:42:07.512Z"));
        assertThat(SharedTimes.parse("2026-08-19T16:42:07.512Z"))
                .contains(Instant.parse("2026-08-19T16:42:07.512Z"));
        assertThat(SharedTimes.parse("not-a-time")).isEmpty();
    }
}
