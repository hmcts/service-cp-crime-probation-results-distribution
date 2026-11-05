package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ReplayResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegAcceptedResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins FR-005-007 and FR-005-031: {@link uk.gov.hmcts.cp.probationresultsdistribution.application
 * .ReplayAction}'s five result cases render exactly as the contract names them, with the
 * {@code requestId} always the service's own canonical parse; and FR-005-011, FR-005-012,
 * FR-005-014 to FR-005-016 with research.md Decision 4: {@link uk.gov.hmcts.cp
 * .probationresultsdistribution.application.LegResender}'s run result renders as the accepted-run
 * answer carrying only {@code resendRunId}, or as the bounded refusal its whole-run code names.
 */
@DisplayName("the operations response mapper")
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

    @Nested
    @DisplayName("the resend-leg half")
    class ResendLeg {

        private static final UUID RUN_ID = UUID.fromString("9c4f2a10-1b2c-4d3e-8f90-0a1b2c3d4e5f");
        private static final int CAP = 100;

        private ResendRunResult accepted(final boolean storeFailed,
                                         final List<ResendRunResult.Entry> entries) {
            return new ResendRunResult(Optional.empty(), Optional.of(RUN_ID), false, false, entries,
                    storeFailed);
        }

        private OperationsRefusalException refusalFor(final WholeRunRefusal refusal) {
            final Throwable thrown = catchThrowable(() ->
                    mapper.toResendLegAcceptedResponse(ResendRunResult.refusedWhole(refusal), CAP));
            assertThat(thrown).isInstanceOf(OperationsRefusalException.class);
            return (OperationsRefusalException) thrown;
        }

        @Test
        @DisplayName("an accepted run renders only its resendRunId")
        void accepted_run_renders_its_run_id() {
            final ResendLegAcceptedResponse response = mapper.toResendLegAcceptedResponse(
                    accepted(false, List.of()), CAP);

            assertThat(response.resendRunId()).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("a run whose every id was refused is still accepted (per-ID refusals are the report's)")
        void every_id_refused_is_still_accepted() {
            final ResendLegAcceptedResponse response = mapper.toResendLegAcceptedResponse(
                    accepted(false, List.of(
                            new ResendRunResult.Entry(REQUEST_ID,
                                    new ResendEntryOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST)),
                            new ResendRunResult.Entry(UUID.randomUUID(), new ResendEntryOutcome.NotRun()))),
                    CAP);

            assertThat(response.resendRunId()).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("a run whose store failed mid-run is still accepted: its report stands")
        void store_failed_mid_run_is_still_accepted() {
            final ResendLegAcceptedResponse response = mapper.toResendLegAcceptedResponse(
                    accepted(true, List.of(new ResendRunResult.Entry(REQUEST_ID,
                            new ResendEntryOutcome.ClaimedIncomplete()))), CAP);

            assertThat(response.resendRunId()).isEqualTo(RUN_ID);
        }

        @Test
        @DisplayName("missing-argument renders missing-argument, quoting nothing")
        void missing_argument_renders() {
            final OperationsRefusalException refusal = refusalFor(WholeRunRefusal.MISSING_ARGUMENT);

            assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.MISSING_ARGUMENT);
            assertThat(refusal.capApplied()).isEmpty();
        }

        @Test
        @DisplayName("argument-too-long renders argument-too-long")
        void argument_too_long_renders() {
            assertThat(refusalFor(WholeRunRefusal.ARGUMENT_TOO_LONG).reason())
                    .isEqualTo(OperationsRefusalReason.ARGUMENT_TOO_LONG);
        }

        @Test
        @DisplayName("unreadable-argument renders unreadable-argument")
        void unreadable_argument_renders() {
            assertThat(refusalFor(WholeRunRefusal.UNREADABLE_ARGUMENT).reason())
                    .isEqualTo(OperationsRefusalReason.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("too-many-request-ids renders too-many-request-ids carrying the cap applied")
        void too_many_request_ids_carries_the_cap() {
            final OperationsRefusalException refusal = refusalFor(WholeRunRefusal.TOO_MANY_REQUEST_IDS);

            assertThat(refusal.reason()).isEqualTo(OperationsRefusalReason.TOO_MANY_REQUEST_IDS);
            assertThat(refusal.argumentName()).contains("requestIds");
            assertThat(refusal.capApplied()).contains(CAP);
        }

        @Test
        @DisplayName("intake-paused renders intake-paused")
        void intake_paused_renders() {
            assertThat(refusalFor(WholeRunRefusal.INTAKE_PAUSED).reason())
                    .isEqualTo(OperationsRefusalReason.INTAKE_PAUSED);
        }

        @Test
        @DisplayName("store-unavailable renders store-unavailable")
        void store_unavailable_renders() {
            assertThat(refusalFor(WholeRunRefusal.STORE_UNAVAILABLE).reason())
                    .isEqualTo(OperationsRefusalReason.STORE_UNAVAILABLE);
        }

        @ParameterizedTest
        @EnumSource(value = WholeRunRefusal.class, names = {"INTAKE_NOT_PAUSED", "BROKER_UNAVAILABLE"})
        @DisplayName("a whole-run code resend-leg never yields is a defect, never a bounded refusal")
        void codes_resend_never_yields_are_a_defect(final WholeRunRefusal refusal) {
            assertThatThrownBy(() -> mapper.toResendLegAcceptedResponse(
                    ResendRunResult.refusedWhole(refusal), CAP))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("an accepted run carrying no run id is a defect, never an answer without one")
        void accepted_run_without_a_run_id_is_a_defect() {
            assertThatThrownBy(() -> mapper.toResendLegAcceptedResponse(new ResendRunResult(
                    Optional.empty(), Optional.empty(), false, false, List.of(), false), CAP))
                    .isInstanceOf(IllegalStateException.class);
        }
    }
}
