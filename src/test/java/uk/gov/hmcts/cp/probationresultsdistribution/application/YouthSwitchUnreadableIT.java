package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

/**
 * US4 Acceptance Scenarios 8–9: every unreadable switch cause defaults and the hearing is sent.
 */
@DisplayName("an unreadable youth switch defaults and the hearing still reaches HMPPS")
class YouthSwitchUnreadableIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String PAYLOAD = "{\"policeCases\":[{\"id\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":true,\"offences\":[]},{\"isYouth\":false,\"offences\":[]}]}]}}";

    private final Logger runnerLogger =
            (Logger) LoggerFactory.getLogger(DistributionRunner.class);
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void captureRunnerLogs() {
        appender = new ListAppender<>();
        appender.start();
        runnerLogger.addAppender(appender);
    }

    @AfterEach
    void stopCapturingRunnerLogs() {
        runnerLogger.detachAppender(appender);
        appender.stop();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> unreadableCases() {
        final Stream<Scenario> causes = Stream.of(
                new Scenario(500, "{}", 0, YouthSwitchUnreadableCause.HTTP_FAILURE),
                new Scenario(200, "{\"permissions\":[{\"active\":true}]}", 600,
                        YouthSwitchUnreadableCause.TIMEOUT),
                new Scenario(200, "{\"permissions\":[]}", 0,
                        YouthSwitchUnreadableCause.EMPTY_PERMISSIONS),
                new Scenario(200, "not-json", 0, YouthSwitchUnreadableCause.UNPARSEABLE));
        return causes.flatMap(scenario -> Stream.of(YouthSwitchRecordedValue.ACTIVE,
                        YouthSwitchRecordedValue.INACTIVE)
                .map(defaultValue -> org.junit.jupiter.params.provider.Arguments.of(scenario,
                        defaultValue)));
    }

    @ParameterizedTest(name = "{0}, default {1}")
    @MethodSource("unreadableCases")
    @DisplayName("applies the selected default, records the cause and sends the hearing")
    void defaults_unreadable_switch_and_sends(final Scenario scenario,
                                              final YouthSwitchRecordedValue configuredDefault) {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitch(scenario.status(), scenario.body(), scenario.delayMillis());
        stubGateway(200);
        startProcessorWithYouthDefault(configuredDefault);
        final double unreadableCountBefore = unreadableCounterCount(
                scenario.cause(), configuredDefault);

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, HEARING_DAY);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.orElseThrow();
        final YouthSwitchRecordedValue recorded = configuredDefault == YouthSwitchRecordedValue.ACTIVE
                ? YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE
                : YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE;
        assertThat(row.get("leg_status")).isEqualTo("SENT");
        assertThat(row.get("youth_switch")).isEqualTo(recorded.wireValue());
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(gatewayPostCount()).isEqualTo(1);
        assertThat(gatewayPostBody()).isNotBlank();
        assertThat(unreadableCounterCount(scenario.cause(), configuredDefault))
                .isGreaterThan(unreadableCountBefore);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("WARN");
            assertThat(event.getFormattedMessage()).contains("UNREADABLE")
                    .contains(scenario.cause().wireValue())
                    .contains("defaulted=" + configuredDefault.wireValue());
        });
    }

    @Test
    @DisplayName("preserves gateway exhaustion when an unreadable switch defaults")
    void unreadable_switch_does_not_mask_gateway_failure() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitch(200, "{\"permissions\":[]}", 0);
        stubGateway(503);
        startProcessorWithYouthDefault(YouthSwitchRecordedValue.ACTIVE);
        final double unreadableCountBefore = unreadableCounterCount(
                YouthSwitchUnreadableCause.EMPTY_PERMISSIONS, YouthSwitchRecordedValue.ACTIVE);

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        awaitRequestStatus(requestId, "FAILED");
        final Optional<Map<String, Object>> settled = awaitLegStatus(hearingId, HEARING_DAY, "FAILED");
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.orElseThrow();
        assertThat(row.get("leg_status")).isEqualTo("FAILED");
        assertThat(row.get("reason")).isEqualTo("gateway-transient");
        assertThat(row.get("youth_switch")).isEqualTo("UNREADABLE_DEFAULTED_ACTIVE");
        assertThat(row.get("request_status")).isEqualTo("FAILED");
        assertThat(gatewayPostCount()).isEqualTo(MAX_DELIVERY_COUNT);
        assertThat(unreadableCounterCount(YouthSwitchUnreadableCause.EMPTY_PERMISSIONS,
                YouthSwitchRecordedValue.ACTIVE)).isGreaterThan(unreadableCountBefore);
    }

    private double unreadableCounterCount(final YouthSwitchUnreadableCause cause,
                                          final YouthSwitchRecordedValue defaulted) {
        final Counter counter = meterRegistry().find("probation_youth_switch_unreadable_total")
                .tag("cause", cause.wireValue())
                .tag("defaulted", defaulted.wireValue()).counter();
        return counter == null ? 0.0d : counter.count();
    }

    private record Scenario(int status, String body, int delayMillis,
                            YouthSwitchUnreadableCause cause) {
    }
}
