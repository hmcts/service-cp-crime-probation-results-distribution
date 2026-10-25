package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ReplayResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * Pins FR-005-007 and FR-005-031: {@link uk.gov.hmcts.cp.probationresultsdistribution.application
 * .ReplayAction}'s five result cases render exactly as the contract names them, with the
 * {@code requestId} always the service's own canonical parse.
 */
@DisplayName("the operations response mapper (replay half)")
class OperationsResponseMapperTest {

    private static final UUID REQUEST_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

    private final OperationsResponseMapper mapper = new OperationsResponseMapper();

    @Test
    @DisplayName("Enqueued renders 200 with the outcome enqueued and the fresh message identity")
    void enqueued_renders_enqueued() {
        final ReplayResponse response = mapper.toReplayResponse(REQUEST_ID,
                new ReplayOutcome.Enqueued("RESULTS:abc-123"));

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.outcome()).isEqualTo("enqueued");
        assertThat(response.messageId()).isEqualTo("RESULTS:abc-123");
    }

    @Test
    @DisplayName("NoOp renders 200 with the outcome no-op and no message identity")
    void no_op_renders_no_op() {
        final ReplayResponse response = mapper.toReplayResponse(REQUEST_ID, new ReplayOutcome.NoOp());

        assertThat(response.requestId()).isEqualTo(REQUEST_ID);
        assertThat(response.outcome()).isEqualTo("no-op");
        assertThat(response.messageId()).isNull();
    }

    @Test
    @DisplayName("Refused(RESEND_IN_PROGRESS) refuses resend-in-progress")
    void refused_resend_in_progress() {
        assertThatThrownBy(() -> mapper.toReplayResponse(REQUEST_ID,
                new ReplayOutcome.Refused(ResendRefusal.RESEND_IN_PROGRESS)))
                .isInstanceOf(OperationsRefusalException.class)
                .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                        .isEqualTo(OperationsRefusalReason.RESEND_IN_PROGRESS));
    }

    @Test
    @DisplayName("Refused(UNKNOWN_REQUEST) refuses unknown-request")
    void refused_unknown_request() {
        assertThatThrownBy(() -> mapper.toReplayResponse(REQUEST_ID,
                new ReplayOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST)))
                .isInstanceOf(OperationsRefusalException.class)
                .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                        .isEqualTo(OperationsRefusalReason.UNKNOWN_REQUEST));
    }

    @Test
    @DisplayName("Refused(NOT_ELIGIBLE) refuses not-eligible")
    void refused_not_eligible() {
        assertThatThrownBy(() -> mapper.toReplayResponse(REQUEST_ID,
                new ReplayOutcome.Refused(ResendRefusal.NOT_ELIGIBLE)))
                .isInstanceOf(OperationsRefusalException.class)
                .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                        .isEqualTo(OperationsRefusalReason.NOT_ELIGIBLE));
    }

    @Test
    @DisplayName("StoreUnavailable refuses store-unavailable")
    void store_unavailable_refuses() {
        assertThatThrownBy(() -> mapper.toReplayResponse(REQUEST_ID, new ReplayOutcome.StoreUnavailable()))
                .isInstanceOf(OperationsRefusalException.class)
                .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                        .isEqualTo(OperationsRefusalReason.STORE_UNAVAILABLE));
    }

    @Test
    @DisplayName("BrokerUnavailable refuses broker-unavailable")
    void broker_unavailable_refuses() {
        assertThatThrownBy(() -> mapper.toReplayResponse(REQUEST_ID, new ReplayOutcome.BrokerUnavailable()))
                .isInstanceOf(OperationsRefusalException.class)
                .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                        .isEqualTo(OperationsRefusalReason.BROKER_UNAVAILABLE));
    }
}
