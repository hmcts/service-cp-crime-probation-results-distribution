package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.filter.audit.AuditFilter;
import uk.gov.hmcts.cp.filter.audit.config.ArtemisAuditAutoConfiguration;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsAuditPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;

/**
 * Pins how the operations API reads the audit transport's switch, {@code cp.audit.enabled}, when
 * it is left unset (gate round 1 of 005 T051/T052). {@code cp-audit-filter-springboot}'s
 * auto-configuration is on when the switch is unset ({@code matchIfMissing = true}), and so is this
 * service's pre-empting {@code estateAuditFilter}; the publisher must read the unset switch the same
 * way, so that a context where the library's transport exists never serves calls unaudited while
 * keeping the library's own filter out of the chain. A context with no audit auto-configuration at
 * all (the {@code ApplicationContextRunner} wiring suites) has no {@code auditJmsTemplate} and is
 * served unaudited. {@code application.yaml} always sets the switch, so a deployed pod never meets
 * the unset case.
 */
@DisplayName("the operations API's audit with cp.audit.enabled left unset")
class OperationsAuditSwitchUnsetTest {

    private static final String AUDIT_TOPIC = "jms.topic.auditing.event";

    private final JmsTemplate auditTransport = mock(JmsTemplate.class);

    private ApplicationContextRunner withLibraryTransport() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ArtemisAuditAutoConfiguration.class))
                .withUserConfiguration(OperationsConfiguration.class)
                .withBean(MetricRegistry.class, () -> mock(MetricRegistry.class))
                .withBean("auditJmsTemplate", JmsTemplate.class, () -> auditTransport)
                .withPropertyValues("audit.http.enabled=true",
                        "audit.http.openapi-rest-spec=probation-results-distribution-openapi.yaml",
                        "cp.audit.hosts[0]=localhost", "cp.audit.port=61616");
    }

    @Test
    @DisplayName("library transport present: the call's request event is published through it")
    void publish_switchUnsetLibraryTransportPresent_should_publishThroughTheLibrarysTemplate() {
        withLibraryTransport().run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(OperationsAuditPublisher.class)
                    .publishRequestEvent(OperationsAction.REPLAY, Map.of("requestId", "r"));

            verify(auditTransport).convertAndSend(eq(AUDIT_TOPIC), anyString());
        });
    }

    @Test
    @DisplayName("library transport present: the library's own filter is pre-empted and never registered")
    void filter_switchUnsetLibraryTransportPresent_should_keepTheLibrarysFilterOutOfTheChain() {
        withLibraryTransport().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(AuditFilter.class)).containsOnlyKeys("estateAuditFilter");
            assertThat(context.getBean("estateAuditFilterRegistration", FilterRegistrationBean.class)
                    .isEnabled()).isFalse();
        });
    }

    @Test
    @DisplayName("switched off explicitly: nothing is published even though a template exists")
    void publish_switchOffLibraryTemplatePresent_should_publishNothing() {
        withLibraryTransport().withPropertyValues("cp.audit.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            context.getBean(OperationsAuditPublisher.class)
                    .publishRequestEvent(OperationsAction.REPLAY, Map.of("requestId", "r"));

            verify(auditTransport, never()).convertAndSend(anyString(), anyString());
        });
    }

    @Test
    @DisplayName("no audit auto-configuration and no template: the context starts and calls are unaudited")
    void publish_switchUnsetNoAuditAutoConfiguration_should_beANoOp() {
        new ApplicationContextRunner()
                .withUserConfiguration(OperationsConfiguration.class)
                .withBean(MetricRegistry.class, () -> mock(MetricRegistry.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final OperationsAuditPublisher publisher = context.getBean(OperationsAuditPublisher.class);

                    assertThatCode(() -> publisher.publishRequestEvent(OperationsAction.REPLAY,
                            Map.of("requestId", "r"))).doesNotThrowAnyException();
                });
    }

    @Test
    @DisplayName("switched on explicitly with no audit template: the context refuses to start")
    void context_switchOnNoAuditTemplate_should_fail() {
        new ApplicationContextRunner()
                .withUserConfiguration(OperationsConfiguration.class)
                .withBean(MetricRegistry.class, () -> mock(MetricRegistry.class))
                .withPropertyValues("cp.audit.enabled=true")
                .run(context -> assertThat(context).hasFailed());
    }
}
