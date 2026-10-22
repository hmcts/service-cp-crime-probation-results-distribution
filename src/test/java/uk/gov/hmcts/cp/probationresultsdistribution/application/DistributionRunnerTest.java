package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

@ExtendWith(MockitoExtension.class)
@DisplayName("the distribution runner")
class DistributionRunnerTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String CASE_PAYLOAD =
            "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":[{\"isYouth\":false,"
            + "\"offences\":[{\"laaApplnReference\":\"LAA1\",\"judicialResults\":[]}]}]}]}}";
    private static final String BODY = "{\"hearing\":{}}";
    private static final RunClaim CLAIM = RunClaim.fresh();

    @Mock
    private HearingPayloadSource payloadSource;
    @Mock
    private YouthFilterSource youthFilterSource;
    @Mock
    private HmppsSender hmppsSender;
    @Mock
    private OutboundLog outboundLog;

    private SimpleMeterRegistry meter;
    private DistributionRunner runner;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        meter = new SimpleMeterRegistry();
        runner = newRunner(YouthSwitchRecordedValue.ACTIVE);
        command = new DistributionCommand(
                "RESULTS", UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
    }

    private DistributionRunner newRunner(final YouthSwitchRecordedValue defaultWhenUnreadable) {
        return new DistributionRunner(payloadSource, youthFilterSource, hmppsSender, outboundLog,
                defaultWhenUnreadable, new MetricRegistry(meter));
    }

    @Test
    @DisplayName("fetches, reads the switch, redacts, sends then records in order")
    void runs_the_pipeline_in_order() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        final InOrder order = inOrder(payloadSource, youthFilterSource, hmppsSender, outboundLog);
        order.verify(payloadSource).fetch(command);
        order.verify(youthFilterSource).read(command);
        order.verify(hmppsSender).send(any());
        order.verify(outboundLog).record(any(), any());

        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.SENT);
        assertThat(recorded.youthSwitch()).contains(YouthSwitchRecordedValue.ACTIVE);
        assertThat(recorded.payloadSource()).contains(PayloadSource.CACHE_DAY);
        assertThat(recorded.httpStatus()).hasValue(200);
        assertThat(recorded.bodyDigest()).isPresent();
        assertThat(recorded.body()).contains(BODY);
        assertThat(result.status()).isEqualTo(LegStatus.SENT);
        assertThat(result.recorded()).isTrue();
        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("status", "SENT").tag("http", "200").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("sends slice 001's redaction output, not the raw payload")
    void sends_the_redacted_tree() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        final ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(hmppsSender).send(sent.capture());
        assertThat(sent.getValue().toString()).doesNotContain("laaApplnReference");
    }

    @Test
    @DisplayName("records NOT_APPLICABLE(sjp-or-box-hearing) and does not send when R1 empties")
    void not_applicable_when_r1_empties() {
        givenPayload("{\"hearing\":{\"isSJPHearing\":true,\"prosecutionCases\":[]}}");
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        verify(hmppsSender, never()).send(any());
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.NOT_APPLICABLE);
        assertThat(recorded.reason()).contains(LegReason.SJP_OR_BOX_HEARING);
        assertThat(recorded.youthSwitch()).contains(YouthSwitchRecordedValue.ACTIVE);
        assertThat(recorded.payloadSource()).contains(PayloadSource.CACHE_DAY);
        assertThat(recorded.bodyDigest()).isEmpty();
        assertThat(recorded.body()).isEmpty();
        assertThat(result.status()).isEqualTo(LegStatus.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("records NOT_APPLICABLE(nothing-left-after-redaction) when R11 empties")
    void not_applicable_when_r11_empties() {
        givenPayload("{\"hearing\":{\"prosecutionCases\":[]}}");
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        verify(hmppsSender, never()).send(any());
        assertThat(captureOutcome().reason()).contains(LegReason.NOTHING_LEFT_AFTER_REDACTION);
    }

    @Test
    @DisplayName("records FAILED(rules-exception) with no digest when a rule throws (P19)")
    void failed_rules_exception_when_a_rule_throws() {
        givenPayload("{\"hearing\":{\"prosecutionCases\":[{\"defendants\":[{}]}]}}");
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        verify(hmppsSender, never()).send(any());
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.FAILED);
        assertThat(recorded.reason()).contains(LegReason.RULES_EXCEPTION);
        assertThat(recorded.youthSwitch()).contains(YouthSwitchRecordedValue.ACTIVE);
        assertThat(recorded.bodyDigest()).isEmpty();
        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("status", "FAILED").tag("reason", "rules-exception").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("maps a 4xx to FAILED(receiver-rejected), storing the body and digest")
    void maps_receiver_rejected() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any()))
                .thenReturn(new SentBody(new PublishOutcome.FailedNonTransient(400), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(result.reason()).contains(LegReason.RECEIVER_REJECTED);
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.httpStatus()).hasValue(400);
        assertThat(recorded.bodyDigest()).isPresent();
        assertThat(recorded.body()).contains(BODY);
        assertThat(meter.find("probation_leg_outcomes_total").tag("http", "400").counter().count())
                .isEqualTo(1.0d);
    }

    @Test
    @DisplayName("maps an outcome-unknown answer to PARKED(gateway-transient) and counts it parked")
    void maps_gateway_transient() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(
                new SentBody(new PublishOutcome.TransientExhausted(OptionalInt.of(503)), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(result.status()).isEqualTo(LegStatus.PARKED);
        assertThat(result.reason()).contains(LegReason.GATEWAY_TRANSIENT);
        assertThat(captureOutcome().httpStatus()).hasValue(503);
        assertThat(meter.find("probation_parked_total").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("parks a never-sent connection failure without recording an HTTP status")
    void parks_connection_failure_without_http_status() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(
                new SentBody(new PublishOutcome.TransientExhausted(OptionalInt.empty()), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(result.status()).isEqualTo(LegStatus.PARKED);
        assertThat(result.reason()).contains(LegReason.GATEWAY_TRANSIENT);
        assertThat(captureOutcome().httpStatus()).isEmpty();
        assertThat(captureOutcome().bodyDigest()).isPresent();
        assertThat(meter.find("probation_leg_outcomes_total").tag("http", "none")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("fails a transient gateway outcome on the final permitted delivery")
    void fails_gateway_transient_on_final_delivery() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(
                new SentBody(new PublishOutcome.TransientExhausted(OptionalInt.of(503)), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, true);

        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(result.reason()).contains(LegReason.GATEWAY_TRANSIENT);
        assertThat(captureOutcome().httpStatus()).hasValue(503);
        assertThat(meter.find("probation_parked_total").counter()).isNull();
    }

    @Test
    @DisplayName("maps a final 302 to FAILED(gateway-transient) and the closed other HTTP bucket")
    void fails_redirect_status_on_final_delivery() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(
                new SentBody(new PublishOutcome.TransientExhausted(OptionalInt.of(302)), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, true);

        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(result.reason()).contains(LegReason.GATEWAY_TRANSIENT);
        assertThat(captureOutcome().httpStatus()).hasValue(302);
        assertThat(meter.find("probation_leg_outcomes_total").tag("http", "other")
                .counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("counts a non-200 2xx as a gateway non-200 success")
    void counts_non_200_success() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(202), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(captureOutcome().status()).isEqualTo(LegStatus.SENT);
        assertThat(meter.find("probation_gateway_non200_success_total")
                .tag("status", "202").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("labels a court-application-only body with shape=application-only")
    void counts_application_only_shape() {
        givenPayload("{\"hearing\":{\"courtApplications\":[{\"subject\":"
                + "{\"masterDefendant\":{\"isYouth\":false}}}]}}");
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(captureOutcome().status()).isEqualTo(LegStatus.SENT);
        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("shape", "application-only").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("labels a prosecution-case body with shape=case")
    void counts_case_shape() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("shape", "case").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("parks when no source yields a payload, without reading the switch or sending")
    void parks_when_no_payload() {
        when(payloadSource.fetch(command)).thenReturn(Optional.empty());
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        verify(youthFilterSource, never()).read(any());
        verify(hmppsSender, never()).send(any());
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.PARKED);
        assertThat(recorded.reason()).contains(LegReason.PAYLOAD_UNAVAILABLE);
        assertThat(recorded.youthSwitch()).isEmpty();
        assertThat(recorded.payloadSource()).isEmpty();
        assertThat(result.status()).isEqualTo(LegStatus.PARKED);
        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("status", "PARKED").tag("reason", "payload-unavailable").counter().count())
                .isEqualTo(1.0d);
        assertThat(meter.find("probation_parked_total")
                .tag("reason", "payload-unavailable").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("records INACTIVE when the switch read is inactive")
    void records_inactive_switch() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Inactive());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(captureOutcome().youthSwitch()).contains(YouthSwitchRecordedValue.INACTIVE);
    }

    @Test
    @DisplayName("defaults an unreadable switch to the configured ACTIVE default and counts it")
    void defaults_unreadable_switch_to_active() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(
                new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.TIMEOUT));
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(captureOutcome().youthSwitch())
                .contains(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE);
        assertThat(meter.find("probation_youth_switch_unreadable_total")
                .tag("cause", "TIMEOUT").tag("defaulted", "ACTIVE").counter().count())
                .isEqualTo(1.0d);
        assertThat(meter.find("probation_youth_switch_state")
                .tag("state", "UNREADABLE_DEFAULTED_ACTIVE").gauge().value()).isEqualTo(1.0d);
        assertThat(meter.find("probation_youth_switch_state")
                .tag("state", "INACTIVE").gauge().value()).isZero();
    }

    @Test
    @DisplayName("defaults an unreadable switch to INACTIVE when so configured")
    void defaults_unreadable_switch_to_inactive() {
        runner = newRunner(YouthSwitchRecordedValue.INACTIVE);
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(
                new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE));
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(true);

        runner.run(command, AttemptOrigin.QUEUE, CLAIM, false);

        assertThat(captureOutcome().youthSwitch())
                .contains(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE);
    }

    @Test
    @DisplayName("reports the run as not recorded when the guarded write matches no row")
    void reports_stale_when_record_matches_no_row() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(new PublishOutcome.Sent(200), BODY));
        when(outboundLog.record(any(), any())).thenReturn(false);

        assertThat(runner.run(command, AttemptOrigin.QUEUE, CLAIM, false).recorded()).isFalse();
        assertThat(meter.find("probation_leg_outcomes_total").counter()).isNull();
        assertThat(meter.find("probation_gateway_recovered_total").counter()).isNull();
    }

    @Test
    @DisplayName("does not count a gateway recovery when the fenced outcome write is rejected")
    void does_not_count_unrecorded_gateway_recovery() {
        givenPayload(CASE_PAYLOAD);
        when(youthFilterSource.read(command)).thenReturn(new YouthSwitchDecision.Active());
        when(hmppsSender.send(any())).thenReturn(new SentBody(
                new PublishOutcome.Sent(200, Optional.of(GatewayRecovery.IN_RUN_429)), BODY));
        when(outboundLog.record(any(), any())).thenReturn(false);

        assertThat(runner.run(command, AttemptOrigin.QUEUE, CLAIM, false).recorded()).isFalse();
        assertThat(meter.find("probation_gateway_recovered_total").counter()).isNull();
    }

    @Test
    @DisplayName("fails payload-unavailable on the final permitted delivery, without reading the switch")
    void fails_payload_unavailable_on_the_final_permitted_delivery() {
        when(payloadSource.fetch(command)).thenReturn(Optional.empty());
        when(outboundLog.record(any(), any())).thenReturn(true);

        final RunResult result = runner.run(command, AttemptOrigin.QUEUE, CLAIM, true);

        verify(youthFilterSource, never()).read(any());
        verify(hmppsSender, never()).send(any());
        final LegOutcome recorded = captureOutcome();
        assertThat(recorded.status()).isEqualTo(LegStatus.FAILED);
        assertThat(recorded.reason()).contains(LegReason.PAYLOAD_UNAVAILABLE);
        assertThat(recorded.youthSwitch()).isEmpty();
        assertThat(recorded.payloadSource()).isEmpty();
        assertThat(recorded.bodyDigest()).isEmpty();
        assertThat(result.status()).isEqualTo(LegStatus.FAILED);
        assertThat(result.reason()).contains(LegReason.PAYLOAD_UNAVAILABLE);
        assertThat(meter.find("probation_leg_outcomes_total")
                .tag("status", "FAILED").tag("reason", "payload-unavailable").counter().count())
                .isEqualTo(1.0d);
        assertThat(meter.find("probation_parked_total").counter()).isNull();
    }

    private void givenPayload(final String json) {
        when(payloadSource.fetch(command)).thenReturn(Optional.of(
                new FetchedPayload(MAPPER.readTree(json), PayloadSource.CACHE_DAY,
                        Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")))));
    }

    private LegOutcome captureOutcome() {
        final ArgumentCaptor<LegOutcome> captor = ArgumentCaptor.forClass(LegOutcome.class);
        verify(outboundLog).record(captor.capture(), any());
        return captor.getValue();
    }
}
