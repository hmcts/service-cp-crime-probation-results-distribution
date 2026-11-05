package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;

/**
 * Pins submit-hearing (slice 004, US8; FR-004-054–FR-004-058; {@code contracts/cli-runners.md} §5,
 * {@code contracts/broker-properties.md}): the cache only — the dated {@code EXT_} key, then the
 * undated one, never the results query API —, the producer's {@code requestId} from the raw
 * strings, one normal command with no {@code userId} under {@code RESULTS:{requestId}} marked
 * {@code origin = SUPPORT} with operator, run id and reason as broker application properties, its
 * input checked whole before the cache is read without echoing it, and no dependency on the
 * intake state.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("submit-hearing")
class SubmitHearingActionTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String HEARING_ID = "0aa5bf35-1e51-45e5-9e42-64bd57e15c11";
    private static final String DAY = "2026-08-19";
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";
    private static final UUID REQUEST_ID = UUID.fromString("e74ed270-0b62-36d9-bb10-12bd6baa5bea");
    private static final UUID RUN_ID = UUID.fromString("7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77");
    private static final String DATED_KEY = "EXT_" + HEARING_ID + "_" + DAY + "_result_";
    private static final String UNDATED_KEY = "EXT_" + HEARING_ID + "_result_";

    @Mock
    private StatefulRedisConnection<String, String> connection;
    @Mock
    private RedisCommands<String, String> redis;
    @Mock
    private CommandEnqueuer enqueuer;

    private SubmitHearingAction action;

    @BeforeEach
    void setUp() {
        action = new SubmitHearingAction(new CachedHearingPayloadAdapter(connection, "EXT_"),
                enqueuer, () -> RUN_ID);
    }

    private void cache() {
        when(connection.sync()).thenReturn(redis);
    }

    @Nested
    @DisplayName("reads the cache only")
    class Cache {

        @Test
        @DisplayName("uses the dated key's payload, and enqueues one normal command marked support")
        void submit_datedKey_should_enqueue_one_command() throws Exception {
            cache();
            when(redis.get(DATED_KEY)).thenReturn(payload(SHARED_TIME));

            final SubmitHearingOutcome outcome = action.submit(request("local-dev",
                    "share never admitted"));

            assertThat(outcome).isEqualTo(new SubmitHearingOutcome.Enqueued(REQUEST_ID, RUN_ID));
            final OutgoingCommand sent = sent();
            assertThat(sent.messageId()).isEqualTo("RESULTS:" + REQUEST_ID);
            assertThat(sent.correlationId()).isEqualTo(HEARING_ID);
            assertThat(sent.applicationProperties()).isEqualTo(Map.of("origin", "SUPPORT",
                    "operator", "local-dev", "runId", RUN_ID.toString(),
                    "reason", "share never admitted"));
            final JsonNode body = MAPPER.readTree(sent.body());
            assertThat(body.propertyNames()).containsExactlyInAnyOrder("source", "requestId",
                    "hearingId", "hearingDay", "sharedTime", "eventType");
            assertThat(body.get("source").stringValue()).isEqualTo("RESULTS");
            assertThat(body.get("requestId").stringValue()).isEqualTo(REQUEST_ID.toString());
            assertThat(body.get("hearingId").stringValue()).isEqualTo(HEARING_ID);
            assertThat(body.get("hearingDay").stringValue()).isEqualTo(DAY);
            assertThat(body.get("sharedTime").stringValue()).isEqualTo(SHARED_TIME);
            assertThat(body.get("eventType").stringValue()).isEqualTo("Hearing_Resulted");
            final DistributionCommand parsed = new DistributionCommandParser().parse(sent.body());
            assertThat(parsed.requestId()).isEqualTo(REQUEST_ID);
            assertThat(parsed.userId()).isEmpty();
            verify(redis, never()).get(UNDATED_KEY);
        }

        @Test
        @DisplayName("falls to the undated key, after the dated one")
        void submit_undatedKeyOnly_should_use_it() {
            cache();
            when(redis.get(anyString())).thenReturn(null);
            when(redis.get(UNDATED_KEY)).thenReturn(payload(SHARED_TIME));

            final SubmitHearingOutcome outcome = action.submit(request("op", "why"));

            assertThat(outcome).isEqualTo(new SubmitHearingOutcome.Enqueued(REQUEST_ID, RUN_ID));
            final InOrder order = inOrder(redis);
            order.verify(redis).get(DATED_KEY);
            order.verify(redis).get(UNDATED_KEY);
        }

        @Test
        @DisplayName("takes sharedTime as the payload's raw string for the requestId and the body")
        void submit_offsetRendering_should_keep_the_raw_string() throws Exception {
            final String rendering = "2026-08-19T17:42:07.512+01:00";
            cache();
            when(redis.get(DATED_KEY)).thenReturn(payload(rendering));

            final SubmitHearingOutcome outcome = action.submit(request("op", "why"));

            final UUID expected = RequestIdRecipe.requestId(HEARING_ID, DAY, rendering);
            assertThat(outcome).isEqualTo(new SubmitHearingOutcome.Enqueued(expected, RUN_ID));
            assertThat(MAPPER.readTree(sent().body()).get("sharedTime").stringValue())
                    .isEqualTo(rendering);
        }

        @Test
        @DisplayName("refuses payload-unavailable, enqueuing nothing, when neither key holds one")
        void submit_noPayload_should_refuse() {
            cache();
            when(redis.get(anyString())).thenReturn(null);

            assertThat(action.submit(request("op", "why")))
                    .isEqualTo(new SubmitHearingOutcome.PayloadUnavailable());
            verifyNoInteractions(enqueuer);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
            "no sharedTime|{\"hearing\":{}}",
            "sharedTime not a string|{\"sharedTime\":1}",
            "sharedTime not an instant|{\"sharedTime\":\"yesterday\"}"
        })
        void submit_payloadWithoutReadableSharedTime_should_refuse(final String name,
                                                                   final String cached) {
            cache();
            when(redis.get(DATED_KEY)).thenReturn(cached);

            assertThat(action.submit(request("op", "why")))
                    .isEqualTo(new SubmitHearingOutcome.PayloadUnavailable());
            verifyNoInteractions(enqueuer);
        }
    }

    @Nested
    @DisplayName("checks its input whole, before the cache is read, never echoing it")
    class Input {

        @ParameterizedTest(name = "{0}")
        @CsvSource(nullValues = "null", value = {
            "no hearing id,null,2026-08-19,op,why,missing-argument",
            "no day,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,null,op,why,missing-argument",
            "no operator,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-08-19,null,why,missing-argument",
            "blank reason,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-08-19,op,' ',missing-argument",
            "hearing id not a UUID,SECRET-id,2026-08-19,op,why,unreadable-argument",
            "day not YYYY-MM-DD,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,19-08-SECRET,op,why,"
                    + "unreadable-argument",
            "day not a date,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-02-30,op,why,"
                    + "unreadable-argument"
        })
        void submit_badInput_should_refuse_whole(final String name, final String hearingId,
                                                 final String day, final String operator,
                                                 final String reason, final String refusal) {
            final SubmitHearingRequest request = new SubmitHearingRequest(
                    Optional.ofNullable(hearingId), Optional.ofNullable(day),
                    Optional.ofNullable(operator), Optional.ofNullable(reason));

            final SubmitHearingOutcome outcome = action.submit(request);

            assertThat(outcome).isEqualTo(
                    new SubmitHearingOutcome.Refused(WholeRunRefusal.fromValue(refusal)));
            assertThat(SubmitHearingAction.check(request))
                    .contains(WholeRunRefusal.fromValue(refusal));
            assertThat(outcome.toString()).doesNotContain("SECRET");
            verifyNoInteractions(connection, enqueuer);
        }

        @Test
        @DisplayName("an operator or reason over 200 characters is argument-too-long")
        void submit_tooLong_should_refuse() {
            assertThat(action.submit(request("o".repeat(201), "why"))).isEqualTo(
                    new SubmitHearingOutcome.Refused(WholeRunRefusal.ARGUMENT_TOO_LONG));
            assertThat(action.submit(request("op", "r".repeat(201)))).isEqualTo(
                    new SubmitHearingOutcome.Refused(WholeRunRefusal.ARGUMENT_TOO_LONG));
            verifyNoInteractions(connection, enqueuer);
        }

        @Test
        @DisplayName("a readable request passes the check")
        void check_readable_should_pass() {
            assertThat(SubmitHearingAction.check(request("o".repeat(200), "why"))).isEmpty();
        }
    }

    @Test
    @DisplayName("sanitises the operator and reason to one line each before they are sent")
    void submit_multiLineText_should_be_sanitised() {
        cache();
        when(redis.get(DATED_KEY)).thenReturn(payload(SHARED_TIME));

        action.submit(request("local\ndev", " share\r\nnever\tadmitted "));

        assertThat(sent().applicationProperties()).containsEntry("operator", "local dev")
                .containsEntry("reason", "share never admitted");
    }

    @Test
    @DisplayName("answers broker-unavailable when the broker cannot take the command")
    void submit_brokerDown_should_answer_broker_unavailable() {
        cache();
        when(redis.get(DATED_KEY)).thenReturn(payload(SHARED_TIME));
        doThrow(new CommandEnqueuer.CommandEnqueueException(new IllegalStateException("down")))
                .when(enqueuer).enqueue(any());

        assertThat(action.submit(request("op", "why")))
                .isEqualTo(new SubmitHearingOutcome.BrokerUnavailable());
    }

    @Test
    @DisplayName("takes a fresh run id on each invocation")
    void submit_twice_should_take_a_run_id_each_time() {
        cache();
        when(redis.get(DATED_KEY)).thenReturn(payload(SHARED_TIME));
        final SubmitHearingAction fresh = new SubmitHearingAction(
                new CachedHearingPayloadAdapter(connection, "EXT_"), enqueuer, UUID::randomUUID);

        final SubmitHearingOutcome first = fresh.submit(request("op", "why"));
        final SubmitHearingOutcome second = fresh.submit(request("op", "why"));

        assertThat(((SubmitHearingOutcome.Enqueued) first).runId())
                .isNotEqualTo(((SubmitHearingOutcome.Enqueued) second).runId());
        assertThat(((SubmitHearingOutcome.Enqueued) first).requestId())
                .isEqualTo(((SubmitHearingOutcome.Enqueued) second).requestId());
    }

    @Test
    @DisplayName("is available whatever the intake state: it reads no pause and writes no store")
    void action_should_not_depend_on_the_intake_state() {
        final Constructor<?>[] constructors = SubmitHearingAction.class.getConstructors();

        assertThat(constructors).hasSize(1);
        assertThat(Arrays.stream(constructors[0].getParameterTypes()).map(Class::getSimpleName))
                .containsExactly("HearingPayloadSource", "CommandEnqueuer", "Supplier");
    }

    private OutgoingCommand sent() {
        final ArgumentCaptor<OutgoingCommand> captor = ArgumentCaptor.forClass(OutgoingCommand.class);
        verify(enqueuer).enqueue(captor.capture());
        return captor.getValue();
    }

    private static SubmitHearingRequest request(final String operator, final String reason) {
        return new SubmitHearingRequest(Optional.of(HEARING_ID), Optional.of(DAY),
                Optional.of(operator), Optional.of(reason));
    }

    private static String payload(final String sharedTime) {
        return "{\"sharedTime\":\"" + sharedTime + "\",\"hearing\":{\"id\":\"" + HEARING_ID + "\"}}";
    }
}
