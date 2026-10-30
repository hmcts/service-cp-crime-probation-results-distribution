package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.activemq.ArtemisContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyMaintenanceStateRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;

/**
 * Pins the operations API's audit against the real wiring (FR-005-025 to FR-005-028; 005 T051,
 * T052):
 * Spring Boot's own JMS/Artemis auto-configuration left in place, the real {@code
 * cp-audit-filter-springboot} auto-configuration, and a real Artemis broker. {@link
 * OperationsAuditIT} replaces the audit transport with a stub, which is why neither the default
 * {@code JmsTemplate} Spring Boot registers when the audit transport is off nor the library's own
 * filter publishing beside this slice's publisher was ever seen there.
 *
 * <p>The enclosing class is the transport-off case (the local and compose default): Spring Boot's
 * own Artemis connection factory is pointed at a closed local port so that, were its default
 * {@code JmsTemplate} mistaken for the audit transport, the publish would fail and the call be
 * refused, whatever happens to be listening on the Artemis default port of the machine running the
 * suite. The nested classes switch the audit transport on, once against a real broker and once
 * against a closed port. Against the real broker a subscriber on the audit topic sees every
 * message published to it, so one call must put exactly this slice's two bounded events there and
 * nothing from the library's own filter, which would carry the request body.
 *
 * <p>No assertion message here quotes an audit event, a body, or the caller's operator or reason
 * text: counts and boolean checks only.
 */
@MockitoBean(types = {BodyRetentionJob.class, DatabaseClock.class, MaintenanceMetrics.class,
    PartitionCatalogue.class, PartitionMaintenanceRepository.class,
    BodyMaintenanceStateRepository.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cp.audit.enabled=false", "audit.http.enabled=true", "authz.http.enabled=false"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({ReplayControllerTest.ReplayActionBean.class, ResendLegControllerTest.LegResenderBean.class})
@DisplayName("the operations API's audit, against Spring Boot's JMS auto-configuration and a real Artemis")
class OperationsAuditTransportIT {

    /* default */ static final String AUDIT_TOPIC = "jms.topic.auditing.event";
    /* default */ static final String REQUEST_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    /* default */ static final String REPLAY_PATH = "/operations/requests/" + REQUEST_ID + "/replay";
    /* default */ static final String RESEND_PATH = "/operations/resend-leg";
    /* default */ static final String CANARY_OPERATOR = "op-canary-Vb7Qm2";
    /* default */ static final String CANARY_REASON = "rsn-canary-Kz4Wp9";
    /* default */ static final String RESEND_BODY = """
            {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s",
             "allowTerminalSuccess": true}
            """.formatted(REQUEST_ID, CANARY_OPERATOR, CANARY_REASON);
    /** The only top-level fields this slice's own audit events carry (FR-005-026). */
    /* default */ static final Set<String> BOUNDED_FIELDS = Set.of("action", "outcome", "requestId",
            "allowTerminalSuccess", "allowChangedPayload", "resendRunId", "ids");
    /* default */ static final int CLOSED_PORT = unusedPort();
    /* default */ static final ArtemisContainer ARTEMIS =
            new ArtemisContainer(DockerImageName.parse("apache/activemq-artemis:2.37.0"));
    private static final long FIRST_EVENT_WAIT_SECONDS = 10;
    private static final long QUIET_WAIT_SECONDS = 3;
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ReplayAction replayAction;
    @Autowired
    private LegResender legResender;

    @DynamicPropertySource
    static void springBootArtemisAtClosedPort(final DynamicPropertyRegistry registry) {
        registry.add("spring.artemis.broker-url", () -> "tcp://localhost:" + CLOSED_PORT);
    }

    @BeforeEach
    void resetFixtures() {
        stubActions(replayAction, legResender);
    }

    @Test
    @DisplayName("transport off: replay runs and answers as normal, nothing refused 503")
    void replay_transportOff_should_runAndAnswerAsNormal() {
        final ResponseEntity<String> response = replay(restTemplate, port);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(replayAction).replay(UUID.fromString(REQUEST_ID));
    }

    @Test
    @DisplayName("transport off: resend-leg is accepted as normal, nothing refused 503")
    void resendLeg_transportOff_should_beAcceptedAsNormal() {
        final ResponseEntity<String> response = resendLeg(restTemplate, port);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        verify(legResender).accept(any());
    }

    /** Transport on, broker reachable: the call answers as normal and this slice's two events are published. */
    @Nested
    @DisplayName("transport on, broker reachable")
    class TransportOnBrokerReachable {

        @LocalServerPort
        private int port;
        @Autowired
        private TestRestTemplate restTemplate;
        @Autowired
        private ReplayAction replayAction;
        @Autowired
        private LegResender legResender;

        private final TopicRecorder topic = new TopicRecorder();

        @DynamicPropertySource
        static void auditTransportAtRealBroker(final DynamicPropertyRegistry registry) {
            ARTEMIS.start();
            registry.add("cp.audit.enabled", () -> "true");
            registry.add("cp.audit.hosts[0]", ARTEMIS::getHost);
            registry.add("cp.audit.port", () -> ARTEMIS.getMappedPort(ArtemisPorts.CORE));
            registry.add("cp.audit.user", ARTEMIS::getUser);
            registry.add("cp.audit.password", ARTEMIS::getPassword);
        }

        @BeforeEach
        void subscribe() throws JMSException {
            stubActions(replayAction, legResender);
            topic.open();
        }

        @AfterEach
        void unsubscribe() throws JMSException {
            topic.close();
        }

        @Test
        @DisplayName("replay answers as normal and this slice's request and response events reach the topic")
        void replay_transportOnBrokerReachable_should_publishTheSlicesTwoEvents() throws InterruptedException {
            final ResponseEntity<String> response = replay(restTemplate, port);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            final List<String> events = topic.drain();
            assertThat(events.stream().filter(OperationsAuditTransportIT::isSliceEvent).count())
                    .as("this slice's own audit events on the topic").isEqualTo(2);
        }

        @Test
        @DisplayName("one replay call puts exactly two events on the topic, both bounded (FR-005-025, FR-005-026)")
        void replay_transportOn_should_putExactlyTwoBoundedEventsOnTheTopic() throws InterruptedException {
            final ResponseEntity<String> response = replay(restTemplate, port);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            final List<String> events = topic.drain();
            assertSoftly(softly -> {
                softly.assertThat(events.size()).as("audit events on the topic for one replay call")
                        .isEqualTo(2);
                softly.assertThat(events.stream().allMatch(OperationsAuditTransportIT::isSliceEvent))
                        .as("every event carries this slice's bounded fields only").isTrue();
            });
        }

        @Test
        @DisplayName("one resend-leg call puts exactly two events on the topic, with no body, operator or reason")
        void resendLeg_transportOn_should_putExactlyTwoBoundedEventsWithNoCallerText() throws InterruptedException {
            final ResponseEntity<String> response = resendLeg(restTemplate, port);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            final List<String> events = topic.drain();
            assertSoftly(softly -> {
                softly.assertThat(events.size()).as("audit events on the topic for one resend-leg call")
                        .isEqualTo(2);
                softly.assertThat(events.stream().allMatch(OperationsAuditTransportIT::isSliceEvent))
                        .as("every event carries this slice's bounded fields only").isTrue();
                softly.assertThat(events.stream().anyMatch(event -> event.contains(CANARY_OPERATOR)))
                        .as("an event carries the caller's operator text").isFalse();
                softly.assertThat(events.stream().anyMatch(event -> event.contains(CANARY_REASON)))
                        .as("an event carries the caller's reason text").isFalse();
            });
        }
    }

    /** Transport on, broker unreachable: refused before anything is read or changed (FR-005-027). */
    @Nested
    @DisplayName("transport on, broker unreachable")
    class TransportOnBrokerUnreachable {

        @LocalServerPort
        private int port;
        @Autowired
        private TestRestTemplate restTemplate;
        @Autowired
        private ReplayAction replayAction;
        @Autowired
        private LegResender legResender;

        @DynamicPropertySource
        static void auditTransportAtClosedPort(final DynamicPropertyRegistry registry) {
            registry.add("cp.audit.enabled", () -> "true");
            registry.add("cp.audit.hosts[0]", () -> "localhost");
            registry.add("cp.audit.port", () -> CLOSED_PORT);
        }

        @BeforeEach
        void resetFixtures() {
            stubActions(replayAction, legResender);
        }

        @Test
        @DisplayName("replay is refused 503 audit-unavailable and ReplayAction is never reached")
        void replay_transportOnBrokerUnreachable_should_refuse503BeforeAnythingRuns() {
            final ResponseEntity<String> response = replay(restTemplate, port);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).contains("audit-unavailable");
            verify(replayAction, never()).replay(any(UUID.class));
        }

        @Test
        @DisplayName("resend-leg is refused 503 audit-unavailable and LegResender is never reached")
        void resendLeg_transportOnBrokerUnreachable_should_refuse503BeforeAnythingRuns() {
            final ResponseEntity<String> response = resendLeg(restTemplate, port);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getBody()).contains("audit-unavailable");
            verify(legResender, never()).accept(any());
        }
    }

    /* default */ static void stubActions(final ReplayAction replayAction, final LegResender legResender) {
        reset(replayAction, legResender);
        when(replayAction.replay(any())).thenReturn(new ReplayOutcome.NoOp());
        when(legResender.accept(any())).thenReturn(new ResendAcceptance.Accepted(UUID.randomUUID(),
                List.of(UUID.fromString(REQUEST_ID)), CANARY_OPERATOR, false, Optional.of(CANARY_REASON)));
    }

    /* default */ static ResponseEntity<String> replay(final TestRestTemplate restTemplate, final int port) {
        return restTemplate.exchange("http://localhost:" + port + REPLAY_PATH, HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);
    }

    /* default */ static ResponseEntity<String> resendLeg(final TestRestTemplate restTemplate, final int port) {
        final HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("http://localhost:" + port + RESEND_PATH, HttpMethod.POST,
                new HttpEntity<>(RESEND_BODY, headers), String.class);
    }

    /**
     * Whether a message on the topic is one of this slice's own bounded events: a JSON object whose
     * top-level fields are all drawn from {@link #BOUNDED_FIELDS} and which names the action.
     */
    /* default */ static boolean isSliceEvent(final String message) {
        final JsonNode event;
        try {
            event = JSON.readTree(message);
        } catch (final JsonProcessingException notJson) {
            return false;
        }
        if (!event.isObject() || !event.has("action")) {
            return false;
        }
        final List<String> names = new ArrayList<>();
        event.fieldNames().forEachRemaining(names::add);
        return BOUNDED_FIELDS.containsAll(names);
    }

    private static int unusedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (final IOException noPort) {
            throw new UncheckedIOException(noPort);
        }
    }

    /** The broker port Artemis serves its core protocol on inside the container. */
    /* default */ static final class ArtemisPorts {

        /* default */ static final int CORE = 61_616;

        private ArtemisPorts() {
        }
    }

    /** A non-durable subscriber on the audit topic, recording every text message published to it. */
    /* default */ static final class TopicRecorder {

        private final BlockingQueue<String> received = new LinkedBlockingQueue<>();
        private ActiveMQConnectionFactory factory;
        private Connection connection;
        private Session session;
        private MessageConsumer consumer;

        /* default */ void open() throws JMSException {
            factory = new ActiveMQConnectionFactory(ARTEMIS.getBrokerUrl(), ARTEMIS.getUser(),
                    ARTEMIS.getPassword());
            connection = factory.createConnection();
            session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            consumer = session.createConsumer(session.createTopic(AUDIT_TOPIC));
            consumer.setMessageListener(message -> {
                try {
                    received.add(((TextMessage) message).getText());
                } catch (final JMSException unreadable) {
                    throw new IllegalStateException("audit message unreadable", unreadable);
                }
            });
            connection.start();
        }

        /* default */ void close() throws JMSException {
            if (consumer != null) {
                consumer.close();
            }
            if (session != null) {
                session.close();
            }
            if (connection != null) {
                connection.close();
            }
            if (factory != null) {
                factory.close();
            }
        }

        /**
         * Every message published to the topic since {@link #open}: waits for the first, then until
         * the topic has been quiet for {@link #QUIET_WAIT_SECONDS}.
         */
        /* default */ List<String> drain() throws InterruptedException {
            final List<String> messages = new ArrayList<>();
            String next = received.poll(FIRST_EVENT_WAIT_SECONDS, TimeUnit.SECONDS);
            while (next != null) {
                messages.add(next);
                next = received.poll(QUIET_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            return messages;
        }
    }
}
