package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("telemetry privacy")
class TelemetryPrivacyTest {

    @Test
    @DisplayName("defines exactly the seven MDC fields and never userId")
    void mdc_fields_exclude_user_id() {
        assertThat(MdcFields.KEYS).containsExactlyInAnyOrder(
                "requestId", "hearingId", "hearingDay", "source", "sequenceNumber", "deliveryCount",
                "leg");
        assertThat(MdcFields.KEYS).doesNotContain("userId");
    }
}
