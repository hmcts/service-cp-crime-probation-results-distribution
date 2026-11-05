package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutboundLog;
import uk.gov.hmcts.cp.probationresultsdistribution.application.YouthFilterSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

@DisplayName("one structured log line per leg outcome")
class LegOutcomeLogLineTest {

    private static final String PAYLOAD = "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[{\"judicialResults\":[]}]}]}]}}";
    private final Logger logger = (Logger) LoggerFactory.getLogger(DistributionRunner.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void clearMdcAndAppender() {
        MdcFields.KEYS.forEach(MDC::remove);
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("logs bounded outcome fields and the full delivery MDC without payload or user data")
    void logs_one_private_safe_line_for_each_outcome_class() throws Exception {
        appender.start();
        logger.addAppender(appender);
        run(PAYLOAD, new PublishOutcome.Sent(200), false);
        assertOutcomeLog(Level.INFO, "status=SENT", "httpStatus=200", true);

        appender.list.clear();
        run("{\"hearing\":{\"isSJPHearing\":true,\"prosecutionCases\":[]}}",
                null, false);
        assertOutcomeLog(Level.INFO, "status=NOT_APPLICABLE", "reason=sjp-or-box-hearing", false);

        appender.list.clear();
        run(null, null, false);
        assertOutcomeLog(Level.ERROR, "status=PARKED", "reason=payload-unavailable", false);

        appender.list.clear();
        run(PAYLOAD, new PublishOutcome.FailedNonTransient(400), false);
        assertOutcomeLog(Level.ERROR, "status=FAILED", "reason=receiver-rejected", true);

        appender.list.clear();
        run(PAYLOAD, new PublishOutcome.Sent(202), true);
        assertOutcomeLog(Level.WARN, "status=SENT", "youthSwitchCause=TIMEOUT", true);
    }

    private void run(final String source, final PublishOutcome outcome,
                     final boolean unreadableSwitch) throws Exception {
        final HearingPayloadSource payloads = mock(HearingPayloadSource.class);
        final YouthFilterSource youth = mock(YouthFilterSource.class);
        final HmppsSender sender = mock(HmppsSender.class);
        final OutboundLog outbound = mock(OutboundLog.class);
        if (source == null) {
            when(payloads.fetch(any())).thenReturn(Optional.empty());
        } else {
            when(payloads.fetch(any())).thenReturn(Optional.of(new HearingPayloadSource.FetchedPayload(
                    JsonMapper.builder().build().readTree(source), PayloadSource.CACHE_DAY,
                    Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")))));
            when(youth.read(any())).thenReturn(unreadableSwitch
                    ? new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.TIMEOUT)
                    : new YouthSwitchDecision.Active());
            if (outcome != null) {
                when(sender.send(any())).thenReturn(new HmppsSender.SentBody(outcome, "{\"sent\":true}"));
            }
        }
        when(outbound.record(any(), any())).thenReturn(true);
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId, hearingId,
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
        putMdc(requestId, hearingId);
        new DistributionRunner(payloads, youth, sender, outbound,
                YouthSwitchRecordedValue.ACTIVE, new MetricRegistry(new SimpleMeterRegistry()))
                .run(command, AttemptOrigin.QUEUE, RunClaim.fresh(), false);
    }

    private void assertOutcomeLog(final Level level, final String status, final String field,
                                 final boolean hasDigest) {
        final List<ILoggingEvent> matching = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("HMPPS leg outcome")).toList();
        assertThat(matching).hasSize(1);
        final ILoggingEvent event = matching.getFirst();
        assertThat(event.getLevel()).isEqualTo(level);
        assertThat(event.getFormattedMessage()).contains(status, field, "runOrigin=QUEUE", "youthSwitch=");
        if (hasDigest) {
            assertThat(event.getFormattedMessage()).contains("bodyDigest=");
        }
        final Map<String, String> mdc = event.getMDCPropertyMap();
        assertThat(mdc.keySet()).containsExactlyInAnyOrderElementsOf(MdcFields.KEYS);
        assertThat(mdc).containsKeys("requestId", "hearingId", "hearingDay", "source",
                "sequenceNumber", "deliveryCount", "leg").doesNotContainKey("userId");
        assertThat(event.getFormattedMessage()).doesNotContain("userId", "prosecutionCases", "sent=true");
    }

    private static void putMdc(final UUID requestId, final UUID hearingId) {
        MDC.put(MdcFields.REQUEST_ID, requestId.toString());
        MDC.put(MdcFields.HEARING_ID, hearingId.toString());
        MDC.put(MdcFields.HEARING_DAY, "2026-08-19");
        MDC.put(MdcFields.SOURCE, "RESULTS");
        MDC.put(MdcFields.SEQUENCE_NUMBER, "42");
        MDC.put(MdcFields.DELIVERY_COUNT, "1");
        MDC.put(MdcFields.LEG, "HMPPS");
    }
}
