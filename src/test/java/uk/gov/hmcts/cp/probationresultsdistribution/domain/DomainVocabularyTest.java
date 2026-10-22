package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("the closed domain vocabularies")
class DomainVocabularyTest {

    @Nested
    @DisplayName("each wire-valued enum")
    class WireValuedEnums {

        @Test
        @DisplayName("exposes exactly the design's wire values")
        void exposes_exactly_the_designs_wire_values() {
            assertThat(wireValues(Leg.class)).containsExactly("HMPPS");
            assertThat(wireValues(LegStatus.class))
                    .containsExactly("PENDING", "SENT", "NOT_APPLICABLE", "PARKED", "FAILED");
            assertThat(wireValues(LegReason.class)).containsExactlyInAnyOrder(
                    "sjp-or-box-hearing", "nothing-left-after-redaction", "receiver-rejected",
                    "rules-exception", "payload-unavailable", "gateway-transient", "payload-changed",
                    "rolled-back", "contract-invalid");
            assertThat(wireValues(AttemptOrigin.class))
                    .containsExactlyInAnyOrder("queue", "replay", "resend");
            assertThat(wireValues(PayloadSource.class))
                    .containsExactlyInAnyOrder("cache-day", "cache-nullday", "query");
            assertThat(wireValues(YouthSwitchUnreadableCause.class)).containsExactlyInAnyOrder(
                    "HTTP_FAILURE", "TIMEOUT", "EMPTY_PERMISSIONS", "UNPARSEABLE");
            assertThat(wireValues(CompletionReason.class))
                    .containsExactlyInAnyOrder("both-legs-terminal", "recovered");
            assertThat(wireValues(FailureReason.class))
                    .containsExactlyInAnyOrder("leg-exhausted", "rolled-back");
            assertThat(wireValues(DeadLetterReason.class)).containsExactlyInAnyOrder(
                    "validation", "collision", "exhausted", "leg-exhausted");
            assertThat(wireValues(YouthSwitchRecordedValue.class)).containsExactlyInAnyOrder(
                    "ACTIVE", "INACTIVE", "UNREADABLE_DEFAULTED_ACTIVE",
                    "UNREADABLE_DEFAULTED_INACTIVE");
            assertThat(wireValues(HttpStatusBucket.class)).containsExactlyInAnyOrder(
                    "200", "2xx-other", "400", "401", "408", "429", "4xx-other", "5xx", "other",
                    "none");
            assertThat(wireValues(BodyShape.class))
                    .containsExactlyInAnyOrder("application-only", "case");
            assertThat(wireValues(IntakeSuspensionCause.class))
                    .containsExactlyInAnyOrder("database", "operator");
            assertThat(wireValues(Non200SuccessStatus.class))
                    .containsExactlyInAnyOrder("201", "202", "204", "other");
            assertThat(wireValues(GatewayRecovery.class))
                    .containsExactlyInAnyOrder("429-in-run", "5xx-redelivery");
            assertThat(wireValues(ReplayRoute.class))
                    .containsExactlyInAnyOrder("service", "support", "copy");
            assertThat(wireValues(ResendRefusal.class)).containsExactlyInAnyOrder(
                    "unknown-request", "not-eligible", "resend-in-progress", "request-claimed");
            assertThat(wireValues(BodyMaintenanceStep.class))
                    .containsExactlyInAnyOrder("lock", "create", "drop");
            assertThat(wireValues(OperationsAction.class))
                    .containsExactlyInAnyOrder("replay", "resend-leg");
            assertThat(wireValues(OperationsFailure.class)).containsExactlyInAnyOrder(
                    "audit-unavailable", "audit-response-unpublished", "store-unavailable",
                    "broker-unavailable", "unexpected");
            assertThat(wireValues(NoteForm.class)).containsExactlyInAnyOrder(
                    "prev=<STATUS>/<reason>", "prev=PENDING/-", "rerun-by=<messageId>",
                    "override=allow-changed-payload", "override=force-resend", "baseline=none",
                    "contract=invalid");
        }

        @Test
        @DisplayName("round-trips every constant through fromValue")
        void round_trips_every_constant_through_fromValue() {
            roundTrip(Leg.class, Leg::fromValue);
            roundTrip(LegStatus.class, LegStatus::fromValue);
            roundTrip(LegReason.class, LegReason::fromValue);
            roundTrip(AttemptOrigin.class, AttemptOrigin::fromValue);
            roundTrip(PayloadSource.class, PayloadSource::fromValue);
            roundTrip(YouthSwitchUnreadableCause.class, YouthSwitchUnreadableCause::fromValue);
            roundTrip(CompletionReason.class, CompletionReason::fromValue);
            roundTrip(FailureReason.class, FailureReason::fromValue);
            roundTrip(DeadLetterReason.class, DeadLetterReason::fromValue);
            roundTrip(YouthSwitchRecordedValue.class, YouthSwitchRecordedValue::fromValue);
            roundTrip(HttpStatusBucket.class, HttpStatusBucket::fromValue);
            roundTrip(BodyShape.class, BodyShape::fromValue);
            roundTrip(IntakeSuspensionCause.class, IntakeSuspensionCause::fromValue);
            roundTrip(Non200SuccessStatus.class, Non200SuccessStatus::fromValue);
            roundTrip(GatewayRecovery.class, GatewayRecovery::fromValue);
            roundTrip(ReplayRoute.class, ReplayRoute::fromValue);
            roundTrip(ResendRefusal.class, ResendRefusal::fromValue);
            roundTrip(BodyMaintenanceStep.class, BodyMaintenanceStep::fromValue);
            roundTrip(OperationsAction.class, OperationsAction::fromValue);
            roundTrip(OperationsFailure.class, OperationsFailure::fromValue);
            roundTrip(NoteForm.class, NoteForm::fromValue);
        }

        @Test
        @DisplayName("refuses an unknown value on every enum")
        void refuses_an_unknown_value_on_every_enum() {
            assertThatThrownBy(() -> Leg.fromValue("VEP"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LegStatus.fromValue("GONE"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LegReason.fromValue("youth-switch-unreadable"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> HttpStatusBucket.fromValue("3xx"))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> DeadLetterReason.fromValue("poison"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        private static <E extends Enum<E> & WireValued> List<String> wireValues(
                final Class<E> type) {
            return Arrays.stream(type.getEnumConstants()).map(WireValued::wireValue).toList();
        }

        private static <E extends Enum<E> & WireValued> void roundTrip(
                final Class<E> type, final Function<String, E> parser) {
            for (final E constant : type.getEnumConstants()) {
                assertThat(parser.apply(constant.wireValue())).isSameAs(constant);
            }
        }
    }

    @Nested
    @DisplayName("the note forms")
    class NoteForms {

        @Test
        @DisplayName("mark baseline=none and contract=invalid reserved, and the rest in use")
        void mark_reserved_forms() {
            assertThat(NoteForm.BASELINE_NONE.reserved()).isTrue();
            assertThat(NoteForm.CONTRACT_INVALID.reserved()).isTrue();
            assertThat(NoteForm.PREV_STATUS.reserved()).isFalse();
            assertThat(NoteForm.PREV_PENDING.reserved()).isFalse();
            assertThat(NoteForm.RERUN_BY.reserved()).isFalse();
            assertThat(NoteForm.OVERRIDE_ALLOW_CHANGED_PAYLOAD.reserved()).isFalse();
            assertThat(NoteForm.OVERRIDE_FORCE_RESEND.reserved()).isFalse();
        }
    }

    @Nested
    @DisplayName("the youth-switch recorded value")
    class YouthSwitchRecorded {

        @Test
        @DisplayName("runs the youth rules only for the active and unreadable-defaulted-active forms")
        void removes_youth_defendants_only_when_active() {
            assertThat(YouthSwitchRecordedValue.ACTIVE.removesYouthDefendants()).isTrue();
            assertThat(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE.removesYouthDefendants())
                    .isTrue();
            assertThat(YouthSwitchRecordedValue.INACTIVE.removesYouthDefendants()).isFalse();
            assertThat(YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE
                    .removesYouthDefendants()).isFalse();
        }
    }

    @Nested
    @DisplayName("the youth-switch decision")
    class YouthSwitchDecisions {

        @Test
        @DisplayName("carries a cause only on an unreadable reading, and refuses a null cause")
        void carries_a_cause_only_on_unreadable() {
            final YouthSwitchDecision active = new YouthSwitchDecision.Active();
            final YouthSwitchDecision inactive = new YouthSwitchDecision.Inactive();
            final YouthSwitchDecision.Unreadable unreadable =
                    new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.TIMEOUT);

            assertThat(active).isInstanceOf(YouthSwitchDecision.Active.class);
            assertThat(inactive).isInstanceOf(YouthSwitchDecision.Inactive.class);
            assertThat(unreadable.cause()).isEqualTo(YouthSwitchUnreadableCause.TIMEOUT);
            assertThatThrownBy(() -> new YouthSwitchDecision.Unreadable(null))
                    .isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    @DisplayName("the publish outcome")
    class PublishOutcomes {

        @Test
        @DisplayName("carries the gateway status, or none for a connection failure")
        void carries_the_gateway_status() {
            assertThat(new PublishOutcome.Sent(200).httpStatus()).isEqualTo(200);
            assertThat(new PublishOutcome.FailedNonTransient(400).httpStatus()).isEqualTo(400);
            assertThat(new PublishOutcome.TransientExhausted(OptionalInt.of(503)).httpStatus())
                    .hasValue(503);
            assertThat(new PublishOutcome.TransientExhausted(OptionalInt.empty()).httpStatus())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the distribution command")
    class DistributionCommands {

        @Test
        @DisplayName("holds the identity fields and refuses a null required field")
        void holds_identity_and_refuses_null() {
            final DistributionCommand command = new DistributionCommand(
                    "RESULTS",
                    UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34"),
                    UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa"),
                    LocalDate.parse("2026-08-19"),
                    Instant.parse("2026-08-19T16:42:07.512Z"),
                    "Hearing_Resulted",
                    Optional.empty());

            assertThat(command.source()).isEqualTo("RESULTS");
            assertThat(command.userId()).isEmpty();
            assertThatThrownBy(() -> new DistributionCommand(
                    "RESULTS", null,
                    command.hearingId(), command.hearingDay(), command.sharedTime(),
                    command.eventType(), Optional.empty()))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
