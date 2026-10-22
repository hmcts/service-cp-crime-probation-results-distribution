package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.util.BinaryData;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessageContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.search.Search;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.GuardDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener.Abandon;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener.Complete;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener.DeadLetter;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener.Settlement;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("the probation message listener")
class ProbationMessageListenerTest {

    private static final String BODY = "{\"any\":\"body\"}";

    @Mock
    private DistributionCommandParser parser;
    @Mock
    private IdempotencyGuard guard;
    @Mock
    private DistributionRunner runner;
    @Mock
    private ProcessedRequestRepository processedRequests;

    private SimpleMeterRegistry meter;
    private ProbationMessageListener listener;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        meter = new SimpleMeterRegistry();
        listener = new ProbationMessageListener(parser, guard, runner, processedRequests,
                new MetricRegistry(meter));
        command = new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    @Test
    @DisplayName("dead-letters a contract-invalid body as validation, with no admission")
    void dead_letters_invalid_body() {
        when(parser.parse(BODY)).thenThrow(
                new CommandValidationException(CommandValidationException.Kind.UNKNOWN_FIELD));

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(DeadLetter.class);
        assertThat(((DeadLetter) settlement).reason()).isEqualTo(DeadLetterReason.VALIDATION);
        verify(guard, never()).admit(any(), any());
        assertThat(count("probation_deadlettered_total", "reason", "validation")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("completes a duplicate of a COMPLETED request with no run")
    void completes_a_duplicate() {
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.DuplicateComplete());

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Complete.class);
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("abandons a live-claim delivery")
    void abandons_a_live_claim() {
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Abandon());

        assertThat(listener.decide(BODY, 1L)).isInstanceOf(Abandon.class);
        verify(runner, never()).run(any(), any(), any());
    }

    @Test
    @DisplayName("dead-letters a collision and counts it")
    void dead_letters_a_collision() {
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY))
                .thenReturn(new GuardDecision.DeadLetter(DeadLetterReason.COLLISION));

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(((DeadLetter) settlement).reason()).isEqualTo(DeadLetterReason.COLLISION);
        assertThat(count("probation_deadlettered_total", "reason", "collision")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("completes the request both-legs-terminal on a terminal leg")
    void completes_on_a_terminal_leg() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim))
                .thenReturn(new RunResult(AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(), true));

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Complete.class);
        verify(processedRequests).markCompleted(command.source(), command.requestId(), claim,
                CompletionReason.BOTH_LEGS_TERMINAL);
        assertThat(count("probation_processed_total", "status", "COMPLETED")).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("moves the request to RETRYING and abandons on a parked leg")
    void retries_on_a_parked_leg() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim)).thenReturn(new RunResult(
                AttemptOrigin.QUEUE, LegStatus.PARKED, Optional.of(LegReason.GATEWAY_TRANSIENT), true));

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Abandon.class);
        verify(processedRequests).markRetrying(command.source(), command.requestId(), claim);
        verify(processedRequests, never()).markCompleted(any(), any(), any(), any());
    }

    @Test
    @DisplayName("abandons and counts a stale-runner rejection when the guarded write matched no row")
    void abandons_on_a_stale_runner() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim)).thenReturn(
                new RunResult(AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(), false));

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Abandon.class);
        verify(processedRequests, never()).markCompleted(any(), any(), any(), any());
        assertThat(count("probation_stale_runner_rejections_total", null, null)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("abandons and counts a stale-runner rejection when the request transition matches no row")
    void abandons_when_the_request_transition_is_stale() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim))
                .thenReturn(new RunResult(AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(), true));
        when(processedRequests.markCompleted(command.source(), command.requestId(), claim,
                CompletionReason.BOTH_LEGS_TERMINAL)).thenReturn(false);

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Abandon.class);
        assertThat(count("probation_stale_runner_rejections_total", null, null)).isEqualTo(1.0d);
        assertThat(count("probation_processed_total", "status", "COMPLETED")).isEqualTo(0.0d);
    }

    @Test
    @DisplayName("abandons and counts a stale-runner rejection when the retry transition matches no row")
    void abandons_when_the_retry_transition_is_stale() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim)).thenReturn(new RunResult(
                AttemptOrigin.QUEUE, LegStatus.PARKED, Optional.of(LegReason.GATEWAY_TRANSIENT), true));
        when(processedRequests.markRetrying(command.source(), command.requestId(), claim))
                .thenReturn(false);

        final Settlement settlement = listener.decide(BODY, 1L);

        assertThat(settlement).isInstanceOf(Abandon.class);
        assertThat(count("probation_stale_runner_rejections_total", null, null)).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("processMessage completes the delivery exactly once on a terminal leg")
    void process_message_completes_once() {
        final RunClaim claim = RunClaim.fresh();
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenReturn(new GuardDecision.Admitted(claim));
        when(runner.run(command, AttemptOrigin.QUEUE, claim))
                .thenReturn(new RunResult(AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(), true));
        final ServiceBusReceivedMessageContext context = contextFor(BODY);

        listener.processMessage(context);

        verify(context).complete();
        verify(context, never()).abandon();
        verify(context, never()).deadLetter(any());
    }

    @Test
    @DisplayName("processMessage dead-letters a contract-invalid delivery exactly once")
    void process_message_dead_letters_invalid() {
        when(parser.parse(BODY)).thenThrow(
                new CommandValidationException(CommandValidationException.Kind.UNKNOWN_FIELD));
        final ServiceBusReceivedMessageContext context = contextFor(BODY);

        listener.processMessage(context);

        verify(context).deadLetter(any());
        verify(context, never()).complete();
        verify(context, never()).abandon();
    }

    @Test
    @DisplayName("processMessage abandons and counts when processing throws unexpectedly")
    void process_message_abandons_on_unexpected() {
        when(parser.parse(BODY)).thenReturn(command);
        when(guard.admit(command, BODY)).thenThrow(new IllegalStateException("boom"));
        final ServiceBusReceivedMessageContext context = contextFor(BODY);

        listener.processMessage(context);

        verify(context).abandon();
        verify(context, never()).complete();
        assertThat(count("probation_processing_failures_total", null, null)).isEqualTo(1.0d);
    }

    private ServiceBusReceivedMessageContext contextFor(final String body) {
        final ServiceBusReceivedMessageContext context =
                org.mockito.Mockito.mock(ServiceBusReceivedMessageContext.class);
        final ServiceBusReceivedMessage message =
                org.mockito.Mockito.mock(ServiceBusReceivedMessage.class);
        when(context.getMessage()).thenReturn(message);
        when(message.getBody()).thenReturn(BinaryData.fromString(body));
        when(message.getDeliveryCount()).thenReturn(1L);
        return context;
    }

    private double count(final String name, final String tagKey, final String tagValue) {
        final Search search = meter.find(name);
        final Counter counter =
                tagKey == null ? search.counter() : search.tag(tagKey, tagValue).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
