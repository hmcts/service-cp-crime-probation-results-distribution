package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;

/**
 * Pins FR-005-003, FR-005-010, FR-005-011, FR-005-016 and FR-005-033: every argument is parsed as
 * its type, a whole-call refusal before any downstream call, never the caller's value, never
 * truncated (Assumption 4).
 */
@DisplayName("the operations request mapper")
class OperationsRequestMapperTest {

    private final OperationsRequestMapper mapper = new OperationsRequestMapper();

    @Nested
    @DisplayName("the replay requestId")
    class ReplayRequestId {

        @Test
        @DisplayName("parses a well-formed UUID")
        void parses_a_well_formed_uuid() {
            final UUID id = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");

            assertThat(mapper.parseRequestId(id.toString())).isEqualTo(id);
        }

        @Test
        @DisplayName("refuses an unreadable requestId naming requestId, never the value sent")
        void refuses_an_unreadable_requestId() {
            assertThatThrownBy(() -> mapper.parseRequestId("not-a-uuid"))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.UNREADABLE_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("requestId");
                        assertThat(refusal.getMessage()).doesNotContain("not-a-uuid");
                    });
        }
    }

    @Nested
    @DisplayName("replay's body")
    class ReplayBody {

        @Test
        @DisplayName("accepts no body")
        void accepts_no_body() {
            mapper.validateReplayBody(null);
        }

        @Test
        @DisplayName("accepts the empty object")
        void accepts_the_empty_object() {
            mapper.validateReplayBody(Map.of());
        }

        @Test
        @DisplayName("refuses any field, operator and reason included")
        void refuses_any_field() {
            assertThatThrownBy(() -> mapper.validateReplayBody(Map.of("operator", "someone")))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.UNKNOWN_FIELD);
                        assertThat(refusal.argumentName()).contains("operator");
                    });
        }
    }

    @Nested
    @DisplayName("resend-leg")
    class ResendLeg {

        private ResendLegRequest validRequest() {
            return new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "HMPPS", "an-operator",
                    "a reason", false, false);
        }

        @Test
        @DisplayName("parses a fully valid request")
        void parses_a_fully_valid_request() {
            final ParsedResendLegCommand parsed = mapper.parseResendLeg(validRequest());

            assertThat(parsed.requestIds()).containsExactly(
                    UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
            assertThat(parsed.leg()).isEqualTo(Leg.HMPPS);
            assertThat(parsed.operator()).isEqualTo("an-operator");
            assertThat(parsed.reason()).isEqualTo("a reason");
            assertThat(parsed.allowTerminalSuccess()).isFalse();
            assertThat(parsed.allowChangedPayload()).isFalse();
        }

        @Test
        @DisplayName("passes the list as sent, duplicates and order included")
        void passes_the_list_as_sent() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6",
                            "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                            "4fa85f64-5717-4562-b3fc-2c963f66afa6"),
                    "HMPPS", "an-operator", "a reason", false, false);

            final ParsedResendLegCommand parsed = mapper.parseResendLeg(raw);

            assertThat(parsed.requestIds()).containsExactly(
                    UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
                    UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"),
                    UUID.fromString("4fa85f64-5717-4562-b3fc-2c963f66afa6"));
        }

        @Test
        @DisplayName("refuses an absent or empty requestIds list naming requestIds")
        void refuses_an_empty_requestIds_list() {
            final ResendLegRequest raw = new ResendLegRequest(List.of(), "HMPPS", "an-operator",
                    "a reason", false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.MISSING_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("requestIds");
                    });
        }

        @Test
        @DisplayName("refuses a requestIds entry that is not a UUID, naming requestIds and never the value")
        void refuses_an_unreadable_requestIds_entry() {
            final ResendLegRequest raw = new ResendLegRequest(List.of("not-a-uuid"), "HMPPS",
                    "an-operator", "a reason", false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.UNREADABLE_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("requestIds");
                        assertThat(refusal.getMessage()).doesNotContain("not-a-uuid");
                    });
        }

        @Test
        @DisplayName("refuses an absent leg naming leg")
        void refuses_an_absent_leg() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), null, "an-operator",
                    "a reason", false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.MISSING_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("leg");
                    });
        }

        @Test
        @DisplayName("refuses a leg other than HMPPS naming leg")
        void refuses_a_leg_other_than_hmpps() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "VEP", "an-operator",
                    "a reason", false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.UNREADABLE_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("leg");
                    });
        }

        @Test
        @DisplayName("refuses a blank operator naming operator")
        void refuses_a_blank_operator() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "HMPPS", "  ",
                    "a reason", false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.MISSING_ARGUMENT);
                        assertThat(refusal.argumentName()).contains("operator");
                    });
        }

        @Test
        @DisplayName("refuses a reason over 200 characters naming reason, refused not truncated")
        void refuses_a_reason_over_the_bound() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "HMPPS", "an-operator",
                    "x".repeat(201), false, false);

            assertThatThrownBy(() -> mapper.parseResendLeg(raw))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> {
                        final OperationsRefusalException refusal = (OperationsRefusalException) ex;
                        assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.ARGUMENT_TOO_LONG);
                        assertThat(refusal.argumentName()).contains("reason");
                        assertThat(refusal.getMessage()).doesNotContain("x".repeat(201));
                    });
        }

        @Test
        @DisplayName("an operator or reason of exactly 200 characters is accepted")
        void accepts_exactly_the_bound() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "HMPPS", "o".repeat(200),
                    "r".repeat(200), false, false);

            final ParsedResendLegCommand parsed = mapper.parseResendLeg(raw);

            assertThat(parsed.operator()).hasSize(200);
            assertThat(parsed.reason()).hasSize(200);
        }

        @Test
        @DisplayName("passes allowChangedPayload through unchanged")
        void passes_allow_changed_payload_through() {
            final ResendLegRequest raw = new ResendLegRequest(
                    List.of("3fa85f64-5717-4562-b3fc-2c963f66afa6"), "HMPPS", "an-operator",
                    "a reason", true, true);

            final ParsedResendLegCommand parsed = mapper.parseResendLeg(raw);

            assertThat(parsed.allowTerminalSuccess()).isTrue();
            assertThat(parsed.allowChangedPayload()).isTrue();
        }
    }
}
