package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

@DisplayName("database-backed age gauges")
class AgeGaugesTest {

    @Test
    @DisplayName("refreshes the non-terminal and parked ages together in seconds every 30 seconds")
    void refreshes_both_gauges_on_the_fixed_schedule() throws Exception {
        final JdbcClient jdbc = mock(JdbcClient.class);
        final JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
        final JdbcClient.MappedQuerySpec<Double> query = queryMock();
        when(jdbc.sql(anyString())).thenReturn(statement);
        when(statement.query(Double.class)).thenReturn(query);
        when(query.single()).thenReturn(125.5d, 5.25d);
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final AgeGaugeRefresher refresher = new AgeGaugeRefresher(jdbc, new MetricRegistry(meters));

        refresher.refresh();

        assertThat(meters.get("probation_oldest_non_terminal_age").gauge().value())
                .isEqualTo(125.5d);
        assertThat(meters.get("probation_oldest_parked_age").gauge().value()).isEqualTo(5.25d);
        verify(jdbc, org.mockito.Mockito.times(2)).sql(anyString());
        final Method method = AgeGaugeRefresher.class.getMethod("refresh");
        assertThat(method.getAnnotation(org.springframework.scheduling.annotation.Scheduled.class)
                .fixedDelay()).isEqualTo(30_000L);
    }

    @SuppressWarnings("unchecked")
    private static JdbcClient.MappedQuerySpec<Double> queryMock() {
        return mock(JdbcClient.MappedQuerySpec.class);
    }
}
