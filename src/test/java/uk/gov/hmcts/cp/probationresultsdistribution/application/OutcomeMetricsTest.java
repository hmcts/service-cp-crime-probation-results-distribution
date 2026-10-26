package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

/**
 * Replay outcome telemetry (US1 half of FR-004-011, FR-004-040, FR-004-005). A replayed run labels
 * {@code probation_leg_outcomes_total} with {@code origin = replay} and {@code forced = false} and
 * writes one structured log line carrying {@code runOrigin=REPLAY} with the bounded outcome fields;
 * {@code probation_replays_total} counts one admitted replay per {@code route}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("replay outcome telemetry")
class OutcomeMetricsTest {

    private static final String PAYLOAD = "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[{\"judicialResults\":[]}]}]}]}}";
    private static final String SENT_BODY = "{\"sent\":true}";

    @Mock
    private HearingPayloadSource payloadSource;
    @Mock
    private YouthFilterSource youthFilterSource;
    @Mock
    private HmppsSender hmppsSender;
    @Mock
    private OutboundLog outboundLog;

    private final Logger runnerLogger = (Logger) LoggerFactory.getLogger(DistributionRunner.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private SimpleMeterRegistry meter;
    private DistributionRunner runner;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        meter = new SimpleMeterRegistry();
        runner = new DistributionRunner(payloadSource, youthFilterSource, hmppsSender, outboundLog,
                YouthSwitchRecordedValue.ACTIVE, new MetricRegistry(meter));
        command = new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
        appender.start();
        runnerLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        runnerLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("a replay outcome counts leg_outcomes_total with origin=replay and forced=false")
    void replay_outcome_labels_leg_outcomes_with_origin_replay() throws Exception {
        stubReplaySend(200);

        runner.run(command, AttemptOrigin.REPLAY, RunClaim.fresh(), false, "RESULTS:replay-1");

        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("origin", "replay").tag("forced", "false").tag("status", "SENT")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("a replay outcome log line carries runOrigin=REPLAY and the bounded outcome fields")
    void replay_outcome_log_line_carries_run_origin_replay() throws Exception {
        stubReplaySend(200);

        runner.run(command, AttemptOrigin.REPLAY, RunClaim.fresh(), false, "RESULTS:replay-1");

        final ILoggingEvent event = appender.list.stream()
                .filter(e -> e.getFormattedMessage().contains("HMPPS leg outcome"))
                .findFirst().orElseThrow();
        assertThat(event.getFormattedMessage())
                .contains("status=SENT", "httpStatus=200", "runOrigin=REPLAY", "bodyDigest=")
                .doesNotContain("sent=true");
    }

    @Test
    @DisplayName("probation_replays_total counts one admitted replay per route")
    void replays_total_counts_each_route() {
        final MetricRegistry metrics = new MetricRegistry(meter);

        metrics.replays(ReplayRoute.SERVICE);
        metrics.replays(ReplayRoute.SUPPORT);
        metrics.replays(ReplayRoute.COPY);

        assertThat(meter.find("probation_replays_total").tag("route", "service").counter().count())
                .isEqualTo(1.0d);
        assertThat(meter.find("probation_replays_total").tag("route", "support").counter().count())
                .isEqualTo(1.0d);
        assertThat(meter.find("probation_replays_total").tag("route", "copy").counter().count())
                .isEqualTo(1.0d);
    }

    private void stubReplaySend(final int httpStatus) throws Exception {
        when(payloadSource.fetch(any())).thenReturn(Optional.of(new FetchedPayload(
                JsonMapper.builder().build().readTree(PAYLOAD), PayloadSource.CACHE_DAY,
                Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")))));
        when(youthFilterSource.read(any())).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any()))
                .thenReturn(new SentBody(new PublishOutcome.Sent(httpStatus), SENT_BODY));
        when(outboundLog.recordReplay(any(), any(), any())).thenReturn(true);
    }
}
