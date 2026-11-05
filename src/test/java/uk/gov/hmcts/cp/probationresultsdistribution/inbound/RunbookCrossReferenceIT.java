package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * The dead-letter runbook made true (slice 004, US4; FR-004-046): each rule
 * {@code doc/runbooks/dead-letter-recovery.md} states for the four dead-letter reasons is asserted
 * here against real broker deliveries, over {@link DlqResubmissionReplayIT}'s fixtures
 * ({@link DeadLetterTestSupport}). Resend {@code leg-exhausted} and {@code exhausted}: a copy of a
 * {@code FAILED} request replays, a copy of a {@code COMPLETED} request is a no-op, a message never
 * admitted is a first delivery. Never resend {@code validation} or {@code collision}: each comes back
 * with the same reason and changes nothing. After a service replay completes, the original entry is
 * still on the dead-letter queue until an operator removes it.
 */
@DisplayName("the dead-letter runbook's rules")
class RunbookCrossReferenceIT extends DeadLetterTestSupport {

    private static final String LEG_EXHAUSTED = "leg-exhausted";
    private static final String EXHAUSTED = "exhausted";
    private static final String STATUS = "status";

    @Nested
    @DisplayName("resend leg-exhausted and exhausted entries")
    class Resend {

        @Test
        @DisplayName("a leg-exhausted entry resubmitted under its own messageId is replayed")
        void leg_exhausted_entry_is_replayed() {
            final DistributionCommand command = failedRequest();
            seedLeg(command, "FAILED", "gateway-transient");
            prepareGateway(command, 200);
            deadLetterDirectly(commandBody(command), originalId(command), NO_ORIGIN,
                    Optional.of(LEG_EXHAUSTED));

            resubmitFromDeadLetter(originalId(command));
            assertThat(processDeliveries(1)).as("one admission chance, at delivery count 1").isZero();

            assertRecovered(command, "copy");
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertThat(peekDeadLetterNow(originalId(command))).as("nothing dead-lettered again")
                    .isEmpty();
        }

        @Test
        @DisplayName("an exhausted entry whose request is already COMPLETED is a no-op")
        void exhausted_entry_of_a_completed_request_is_a_no_op() {
            final DistributionCommand command = failedRequest();
            seedLeg(command, "SENT", null);
            jdbc().sql("UPDATE processed_request SET status = 'COMPLETED', failure_reason = NULL, "
                            + "exhausted_message_id = NULL, completion_reason = 'both-legs-terminal' "
                            + "WHERE request_id = :id")
                    .param("id", command.requestId()).update();
            final Map<String, Object> before = requestRow(command);
            final Map<String, Object> legBefore = legRow(command);
            prepareGateway(command, 200);
            deadLetterDirectly(commandBody(command), originalId(command), NO_ORIGIN,
                    Optional.of(EXHAUSTED));

            resubmitFromDeadLetter(originalId(command));
            processDeliveries(1);

            assertThat(requestRow(command)).isEqualTo(before);
            assertThat(legRow(command)).isEqualTo(legBefore);
            assertThat(gatewayPostCount()).isZero();
            assertThat(peekDeadLetterNow(originalId(command))).as("the copy is completed, not parked")
                    .isEmpty();
        }

        @Test
        @DisplayName("an exhausted entry with no request row is processed as a first delivery")
        void exhausted_entry_without_a_request_row_is_a_first_delivery() {
            final DistributionCommand command = newCommand();
            prepareGateway(command, 200);
            deadLetterDirectly(commandBody(command), originalId(command), NO_ORIGIN,
                    Optional.of(EXHAUSTED));

            resubmitFromDeadLetter(originalId(command));
            processDeliveries(1);

            final Map<String, Object> request = requestRow(command);
            assertThat(request.get(STATUS)).isEqualTo("COMPLETED");
            assertThat(request.get("completion_reason")).isEqualTo("both-legs-terminal");
            assertThat(request.get("audit_note")).isNull();
            assertThat(legRow(command).get(STATUS)).isEqualTo("SENT");
            assertThat(legRow(command).get("attempt_origin")).isEqualTo("queue");
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertThat(replayCount("copy")).isZero();
        }
    }

    @Nested
    @DisplayName("never resend validation or collision entries")
    class NeverResend {

        @Test
        @DisplayName("a validation entry resubmitted is dead-lettered validation again, no row written")
        void validation_entry_comes_back_validation_with_no_row() {
            final DistributionCommand command = newCommand();
            final String invalid = commandBody(command).replace("Hearing_Resulted", "invalid");
            sendBody(invalid, originalId(command), NO_ORIGIN);
            processDeliveries(1);
            assertThat(reasonOf(awaitDeadLetter(originalId(command)))).isEqualTo("validation");

            resubmitFromDeadLetter(originalId(command));
            processDeliveries(1);

            assertThat(reasonOf(awaitDeadLetter(originalId(command)))).isEqualTo("validation");
            assertThat(processedRequestCount(command.requestId())).isZero();
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("a collision entry resubmitted is dead-lettered collision again, the row untouched")
        void collision_entry_comes_back_collision_with_the_row_untouched() {
            final DistributionCommand command = newCommand();
            seedCollisionRow(command.source(), command.requestId());
            final Map<String, Object> before = requestRow(command);
            prepareGateway(command, 200);
            send(command, originalId(command), NO_ORIGIN);
            processDeliveries(1);
            assertThat(reasonOf(awaitDeadLetter(originalId(command)))).isEqualTo("collision");

            resubmitFromDeadLetter(originalId(command));
            processDeliveries(1);

            assertThat(reasonOf(awaitDeadLetter(originalId(command)))).isEqualTo("collision");
            assertThat(requestRow(command)).isEqualTo(before);
            assertThat(gatewayPostCount()).isZero();
        }
    }

    @Nested
    @DisplayName("remove the original entry after completion")
    class RemoveAfterCompletion {

        @Test
        @DisplayName("after a service replay completes, the original entry is still dead-lettered")
        void original_entry_survives_a_completed_service_replay() {
            final DistributionCommand command = failedRequest();
            seedLeg(command, "FAILED", "gateway-transient");
            deadLetterDirectly(commandBody(command), originalId(command), NO_ORIGIN,
                    Optional.of(LEG_EXHAUSTED));
            prepareGateway(command, 200);

            send(command, UUID.randomUUID().toString(), "REPLAY");
            processDeliveries(1);

            assertRecovered(command, "service");
            assertThat(reasonOf(peekDeadLetterNow(originalId(command))))
                    .as("the service never removes a dead-letter entry; the operator does")
                    .isEqualTo(LEG_EXHAUSTED);
        }
    }

    private static String reasonOf(final Optional<ServiceBusReceivedMessage> deadLettered) {
        assertThat(deadLettered).as("the entry is on the dead-letter queue").isPresent();
        return deadLettered.get().getDeadLetterReason();
    }
}
