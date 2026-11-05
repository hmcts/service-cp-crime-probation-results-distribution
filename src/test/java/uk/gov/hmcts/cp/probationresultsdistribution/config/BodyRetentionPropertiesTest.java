package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;

class BodyRetentionPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(BodyRetentionConfiguration.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withBean(JdbcClient.class, () -> mock(JdbcClient.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("distribution.body-retention=P6M",
                    "distribution.body-maintenance.cron=0 15 2 * * *",
                    "distribution.consumer.enabled=false");

    @ParameterizedTest
    @ValueSource(strings = {"P1M", "P6M", "P1Y", "P1Y6M"})
    void valid_period_should_start_with_job_even_when_intake_paused(final String value) {
        runner.withPropertyValues("distribution.body-retention=" + value).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(BodyRetentionJob.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"P0M", "P30D", "P6M1D", "-P6M", "not-a-period", "P1Y-1M"})
    void invalid_period_should_refuse_startup_naming_property(final String value) {
        runner.withPropertyValues("distribution.body-retention=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("distribution.body-retention");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0 15 2 * * *", "0 0 12 * * MON-FRI", "0 0 0 30 2 *"})
    void valid_six_field_cron_should_start(final String value) {
        runner.withPropertyValues("distribution.body-maintenance.cron=" + value).run(context ->
                assertThat(context).hasNotFailed().hasSingleBean(BodyRetentionJob.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"15 2 * * *", "malformed", "-", "@daily"})
    void invalid_cron_should_refuse_startup_naming_property(final String value) {
        runner.withPropertyValues("distribution.body-maintenance.cron=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("distribution.body-maintenance.cron");
        });
    }

    @Test
    void schedule_should_be_registered_in_utc_across_clock_changes() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(BodyRetentionJob.class);
            final Scheduled schedule = java.util.Arrays.stream(BodyRetentionJob.class.getMethods())
                    .map(method -> method.getAnnotation(Scheduled.class))
                    .filter(java.util.Objects::nonNull).findFirst().orElseThrow();
            assertThat(schedule.cron()).isEqualTo("${distribution.body-maintenance.cron}");
            assertThat(schedule.zone()).isEqualTo("UTC");
            final CronExpression cron = CronExpression.parse("0 15 2 * * *");
            for (final String day : java.util.List.of("2026-03-28T03:00:00Z", "2026-10-24T03:00:00Z")) {
                final ZonedDateTime next = cron.next(ZonedDateTime.parse(day).withZoneSameInstant(
                        ZoneId.of(schedule.zone())));
                assertThat(next.getHour()).isEqualTo(2);
                assertThat(next.getMinute()).isEqualTo(15);
            }
            assertThat(context.getBean(org.springframework.scheduling.annotation
                    .ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks()).hasSize(1);
        });
    }

    @Test
    void configuration_should_keep_two_properties_without_environment_bindings() throws IOException {
        final String yaml = Files.readString(Path.of("src/main/resources/application.yaml"));
        assertThat(yaml).contains("body-retention: P6M", "cron: \"0 15 2 * * *\"")
                .doesNotContain("${BODY_RETENTION", "${BODY_MAINTENANCE_CRON", "lock-timeout:",
                        "lock-key:", "create-ahead-window:");
    }
}
