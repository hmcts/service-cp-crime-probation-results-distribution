package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

@DisplayName("the database clock")
class DatabaseClockIT extends SchemaTestSupport {

    @Test
    @DisplayName("reads a live UTC instant from the database, not the JVM's own clock")
    void reads_a_live_utc_instant() {
        final DatabaseClock clock = new DatabaseClock(jdbc());

        final OffsetDateTime now = clock.nowUtc();

        assertThat(now.getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(Duration.between(now.toInstant(), Instant.now()).abs())
                .isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("returns the same instant whether the JDBC session's own time zone is UTC or the UK's")
    void is_identical_under_a_non_utc_session_zone() {
        final DatabaseClock utcSessionClock = new DatabaseClock(jdbc());
        final DatabaseClock ukSessionClock = new DatabaseClock(JdbcClient.create(ukSessionDataSource()));

        final OffsetDateTime fromUtcSession = utcSessionClock.nowUtc();
        final OffsetDateTime fromUkSession = ukSessionClock.nowUtc();

        assertThat(Duration.between(fromUtcSession.toInstant(), fromUkSession.toInstant()).abs())
                .isLessThan(Duration.ofSeconds(10));
        assertThat(fromUkSession.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    private DriverManagerDataSource ukSessionDataSource() {
        final DriverManagerDataSource base = (DriverManagerDataSource) datasource();
        final String separator = base.getUrl().contains("?") ? "&" : "?";
        final DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(base.getUrl() + separator + "options=-c%20TimeZone%3DEurope/London");
        source.setUsername(base.getUsername());
        source.setPassword(base.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        return source;
    }
}
