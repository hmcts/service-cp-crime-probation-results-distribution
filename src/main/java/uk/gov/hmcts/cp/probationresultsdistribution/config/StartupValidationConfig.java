package uk.gov.hmcts.cp.probationresultsdistribution.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Binds {@link DistributionProperties} and runs the {@link StartupValidator} at startup, refusing to
 * start when the configuration is invalid (FR-002-066, FR-002-067). Required secrets are enforced
 * only outside the {@code local} and {@code test} profiles, where they arrive from the platform's
 * secret store. The resolved youth-switch default is logged at INFO (FR-002-026).
 */
@Configuration
@EnableConfigurationProperties(DistributionProperties.class)
public class StartupValidationConfig {

    private static final Logger LOG = LoggerFactory.getLogger(StartupValidationConfig.class);

    /* default */ @Bean
    ApplicationRunner distributionConfigValidator(final DistributionProperties properties,
                                                  final Environment environment) {
        return args -> {
            final StartupValidator validator = new StartupValidator();
            final boolean requireSecrets =
                    !environment.acceptsProfiles(Profiles.of("local", "test"));
            validator.validate(properties, requireSecrets);
            validator.validateNoForbiddenProperties(environment);
            LOG.info("distribution.youth-switch.default-when-unreadable resolved to {}",
                    properties.youthSwitch().defaultWhenUnreadable());
            warnIfAuditFilterOnWithTransportOff(environment);
        };
    }

    /**
     * Warns once, at startup, when the operations API's HTTP audit filter is switched on but its
     * JMS transport is off (FR-005-028): every call the filter admits is then served unaudited.
     * This is not a refusal — start-up MUST NOT refuse any combination of these two switches — and
     * it is not FR-005-027's refusal either, which is for a publish attempted and failed, not a
     * transport deliberately absent.
     *
     * @param environment the application environment
     */
    private static void warnIfAuditFilterOnWithTransportOff(final Environment environment) {
        final boolean auditFilterOn = environment.getProperty("audit.http.enabled", Boolean.class,
                Boolean.FALSE);
        final boolean auditTransportOn = environment.getProperty("cp.audit.enabled", Boolean.class,
                Boolean.FALSE);
        if (auditFilterOn && !auditTransportOn) {
            LOG.warn("the operations API's HTTP audit filter is on (audit.http.enabled={}) but its "
                            + "JMS transport is off (cp.audit.enabled={}): admitted calls will be "
                            + "served unaudited until an environment switches the transport on too",
                    auditFilterOn, auditTransportOn);
        }
    }
}
