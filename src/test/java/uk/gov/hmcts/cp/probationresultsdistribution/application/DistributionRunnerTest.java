package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

@ExtendWith(MockitoExtension.class)
@DisplayName("the distribution runner")
class DistributionRunnerTest {

    private static final JsonNode PAYLOAD = JsonNodeFactory.instance.objectNode();
    private static final String BODY = "{\"hearing\":{}}";

    @Mock
    private HearingPayloadSource payloadSource;
    @Mock
    private YouthFilterSource youthFilterSource;
    @Mock
    private HmppsSender hmppsSender;
    @Mock
    private OutboundLog outboundLog;

    private DistributionRunner runner;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        runner = new DistributionRunner(payloadSource, youthFilterSource, hmppsSender, outboundLog,
                YouthSwitchRecordedValue.ACTIVE);
        command = new DistributionCommand(
                "RESULTS", UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
    }

    @Test
    @DisplayName("runs fetch, switch, send then record in order, threading the run origin")
    void runs_the_pipeline_in_order_threading_the_run_origin() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(PAYLOAD)).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE);

        final InOrder order = inOrder(payloadSource, youthFilterSource, hmppsSender, outboundLog);
        order.verify(payloadSource).fetch(command);
        order.verify(youthFilterSource).read(command);
        order.verify(hmppsSender).send(PAYLOAD);
        order.verify(outboundLog).record(any());

        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.runOrigin()).isEqualTo(AttemptOrigin.QUEUE);
        assertThat(recorded.status()).isEqualTo(LegStatus.SENT);
        assertThat(recorded.youthSwitch()).contains(YouthSwitchRecordedValue.ACTIVE);
        assertThat(recorded.payloadSource()).contains(PayloadSource.CACHE_DAY);
        assertThat(recorded.httpStatus()).hasValue(200);
        assertThat(recorded.bodyDigest()).isPresent();
        assertThat(recorded.body()).contains(BODY);
        assertThat(result.status()).isEqualTo(LegStatus.SENT);
        assertThat(result.runOrigin()).isEqualTo(AttemptOrigin.QUEUE);
        assertThat(result.recorded()).isTrue();
    }

    @Test
    @DisplayName("parks when no source yields a payload, without reading the switch or sending")
    void parks_when_no_payload() {
        when(payloadSource.fetch(command)).thenReturn(Optional.empty());
        when(outboundLog.record(any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE);

        verify(youthFilterSource, never()).read(any());
        verify(hmppsSender, never()).send(any());
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.PARKED);
        assertThat(recorded.reason()).contains(LegReason.PAYLOAD_UNAVAILABLE);
        assertThat(recorded.youthSwitch()).isEmpty();
        assertThat(recorded.payloadSource()).isEmpty();
        assertThat(recorded.runOrigin()).isEqualTo(AttemptOrigin.QUEUE);
        assertThat(result.status()).isEqualTo(LegStatus.PARKED);
    }

    @Test
    @DisplayName("maps a 4xx to FAILED(receiver-rejected) with the status")
    void maps_receiver_rejected() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(PAYLOAD))
                .thenReturn(new SentBody(new PublishOutcome.FailedNonTransient(400), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE);

        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(result.reason()).contains(LegReason.RECEIVER_REJECTED);
        assertThat(captureOutcome().httpStatus()).hasValue(400);
    }

    @Test
    @DisplayName("maps an outcome-unknown answer to PARKED(gateway-transient)")
    void maps_gateway_transient() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(PAYLOAD)).thenReturn(
                new SentBody(new PublishOutcome.TransientExhausted(OptionalInt.of(503)), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE);

        assertThat(result.status()).isEqualTo(LegStatus.PARKED);
        assertThat(result.reason()).contains(LegReason.GATEWAY_TRANSIENT);
        assertThat(captureOutcome().httpStatus()).hasValue(503);
    }

    @Test
    @DisplayName("records INACTIVE when the switch read is inactive")
    void records_inactive_switch() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Inactive());
        when(hmppsSender.send(PAYLOAD)).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE);

        assertThat(captureOutcome().youthSwitch()).contains(YouthSwitchRecordedValue.INACTIVE);
    }

    @Test
    @DisplayName("defaults an unreadable switch to the configured ACTIVE default")
    void defaults_unreadable_switch_to_active() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(
                new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.TIMEOUT));
        when(hmppsSender.send(PAYLOAD)).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE);

        assertThat(captureOutcome().youthSwitch())
                .contains(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE);
    }

    @Test
    @DisplayName("defaults an unreadable switch to INACTIVE when so configured")
    void defaults_unreadable_switch_to_inactive() {
        runner = new DistributionRunner(payloadSource, youthFilterSource, hmppsSender, outboundLog,
                YouthSwitchRecordedValue.INACTIVE);
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(
                new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE));
        when(hmppsSender.send(PAYLOAD)).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE);

        assertThat(captureOutcome().youthSwitch())
                .contains(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE);
    }

    @Test
    @DisplayName("reports the run as not recorded when the guarded write matches no row")
    void reports_stale_when_record_matches_no_row() {
        givenPayload();
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(PAYLOAD)).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any())).thenReturn(false);

        assertThat(runner.run(command, AttemptOrigin.QUEUE).recorded()).isFalse();
    }

    private void givenPayload() {
        when(payloadSource.fetch(command)).thenReturn(Optional.of(
                new FetchedPayload(PAYLOAD, PayloadSource.CACHE_DAY,
                        Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")))));
    }

    private LegOutcome captureOutcome() {
        final ArgumentCaptor<LegOutcome> captor = ArgumentCaptor.forClass(LegOutcome.class);
        verify(outboundLog).record(captor.capture());
        return captor.getValue();
    }
}
