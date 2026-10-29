package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jms.UncategorizedJmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyMaintenanceStateRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;

/**
 * Pins FR-005-025 to FR-005-027 against the real {@code cp-audit-filter-springboot} ({@code
 * cp.audit.enabled=true}, every one of its beans genuinely constructed — {@code AuditService}, the
 * payload generator, the OpenAPI-backed path-parameter lookup — with only its JMS transport stubbed
 * (the {@code auditJmsTemplate} bean overridden by name, so no real broker is needed): an admitted
 * call to the real {@link ReplayController} produces exactly two audit events, before and after,
 * naming the action and the outcome, with no request or response body; a request-event publish
 * failure refuses the call {@code 503 audit-unavailable} with {@link ReplayAction} never reached.
 *
 * <p>{@code authz.http.enabled} is off here, since {@link OperationsAuthorisationIT} already pins
 * the authorisation chain on its own; this test isolates the audit behaviour behind an admitted
 * call. {@code OperationsConfiguration.estateAuditFilter}/{@code estateAuditFilterRegistration}
 * pre-empt and disable {@code cp-audit-filter-springboot}'s own automatic HTTP audit filter — found,
 * by reading that library's bytecode, to audit every request unconditionally via the same
 * {@code AuditService.postMessageToArtemis} call this slice's {@link OperationsAuditPublisher} also
 * reaches, on the same topic — so a call produces this test's two events, not four.
 */
@MockitoBean(types = {BodyRetentionJob.class, DatabaseClock.class, MaintenanceMetrics.class,
    PartitionCatalogue.class, PartitionMaintenanceRepository.class,
    BodyMaintenanceStateRepository.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"cp.audit.enabled=true", "cp.audit.hosts[0]=localhost", "cp.audit.port=61616",
            "audit.http.enabled=true", "authz.http.enabled=false"})
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({ReplayControllerTest.ReplayActionBean.class, OperationsAuditIT.StubAuditTransport.class})
@DisplayName("the operations API's audit publishing, against the real cp-audit-filter-springboot")
class OperationsAuditIT {

    private static final String REPLAY_PATH =
            "/operations/requests/3fa85f64-5717-4562-b3fc-2c963f66afa6/replay";

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ReplayAction replayAction;
    @Autowired
    private Recorder recorder;

    @BeforeEach
    void resetFixtures() {
        reset(replayAction);
        recorder.clear();
    }

    @Test
    @DisplayName("an admitted call produces exactly two audit events, naming the action and outcome, no body")
    void admitted_call_produces_exactly_two_audit_events() {
        when(replayAction.replay(any())).thenReturn(new ReplayOutcome.NoOp());

        final ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + REPLAY_PATH, HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recorder.events()).hasSize(2);
        assertThat(recorder.events()).allSatisfy(event -> {
            assertThat(event).contains("\"action\"");
            assertThat(event).doesNotContain("operator").doesNotContain("reason");
        });
        assertThat(recorder.events().get(1)).contains("no-op");
    }

    @Test
    @DisplayName("a request-event publish failure refuses 503 audit-unavailable, nothing reached")
    void request_event_publish_failure_refuses_503() {
        recorder.failNextSend();

        final ResponseEntity<String> response = restTemplate.exchange(
                "http://localhost:" + port + REPLAY_PATH, HttpMethod.POST,
                new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("audit-unavailable");
        verify(replayAction, never()).replay(any(UUID.class));
        assertThat(recorder.events()).isEmpty();
    }

    /** Records every message the service's own publisher sends, standing in for a real broker. */
    static final class Recorder {

        private final List<String> recordedEvents = new ArrayList<>();
        private final AtomicBoolean failNext = new AtomicBoolean(false);

        synchronized void record(final String body) {
            recordedEvents.add(body);
        }

        void failNextSend() {
            failNext.set(true);
        }

        synchronized boolean consumeFailure() {
            return failNext.compareAndSet(true, false);
        }

        synchronized List<String> events() {
            return List.copyOf(recordedEvents);
        }

        synchronized void clear() {
            recordedEvents.clear();
            failNext.set(false);
        }
    }

    /**
     * Overrides {@code cp-audit-filter-springboot}'s own {@code auditJmsTemplate} bean by name
     * (its {@code @Bean} method is {@code @ConditionalOnMissingBean(name=["auditJmsTemplate"])}, so
     * the library's own copy — which would need a real {@code ActiveMQConnectionFactory} — is never
     * built), with a {@link JmsTemplate} that records every send in memory instead of reaching a
     * broker. {@code AuditService} and every other library bean the audit filter needs are still
     * genuinely constructed, since {@code cp.audit.enabled=true} is real.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubAuditTransport {

        /* default */ @Bean
        Recorder recorder() {
            return new Recorder();
        }

        /* default */ @Bean(name = "auditJmsTemplate")
        JmsTemplate auditJmsTemplate(final Recorder recorder) {
            return new RecordingJmsTemplate(recorder);
        }
    }

    /** A {@link JmsTemplate} that never touches a broker: it records, or fails, on demand. */
    private static final class RecordingJmsTemplate extends JmsTemplate {

        private final Recorder recorder;

        RecordingJmsTemplate(final Recorder recorder) {
            // JmsTemplate.afterPropertiesSet requires a non-null ConnectionFactory, but
            // convertAndSend is overridden below and never delegates to it, so a harmless mock
            // that is never invoked satisfies the property check without needing a real broker.
            super(mock(jakarta.jms.ConnectionFactory.class));
            this.recorder = recorder;
        }

        @Override
        public void convertAndSend(final String destinationName, final Object message) {
            if (recorder.consumeFailure()) {
                throw new UncategorizedJmsException("stubbed audit transport failure");
            }
            recorder.record((String) message);
        }
    }
}
