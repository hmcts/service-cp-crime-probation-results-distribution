package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Period;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The two retention settings; neither has a named environment-variable mapping (FR-003-027).
 *
 * @param bodyRetention whole months to retain, with years interpreted as twelve months
 * @param bodyMaintenance the UTC trigger settings
 */
@ConfigurationProperties(prefix = "distribution")
public record BodyRetentionProperties(@DefaultValue("P6M") Period bodyRetention,
                                     @DefaultValue BodyMaintenance bodyMaintenance) {

    /**
     * The six-field UTC schedule.
     *
     * @param cron the daily schedule, validated before scheduling is registered
     */
    public record BodyMaintenance(@DefaultValue("0 15 2 * * *") String cron) {
    }
}
