package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * Pins FR-005-035: {@code probation-results-distribution-openapi.yaml} describes exactly the two
 * operations under a version raised from the placeholder's {@code 0.0.0}, and every closed value set
 * it carries equals the service's own vocabulary, value for value, in both directions.
 *
 * <p>Both controllers now exist ({@link ReplayController}, {@link ResendLegController}), but the
 * served-operations half of story 6 ("everything the document describes is served") stays {@code
 * [BLOCKED-ON-004]} for resend-leg: {@code application.LegResender} is not yet a bean of the
 * serving context, so the endpoint is not registered there, and its answer is not yet the
 * asynchronous one FR-005-015 names (tasks.md T029, T046). This test covers the document-content
 * half, which needs no controller: the document's own closed enums compared against the code's,
 * both ways.
 */
@DisplayName("the operations API's OpenAPI document")
class OperationsContractTest {

    private static final String REPLAY_OPERATION_ID = "probation-results-distribution-operations.replay";
    private static final String RESEND_LEG_OPERATION_ID =
            "probation-results-distribution-operations.resend-leg";

    private static final List<String> CLOSED_REFUSAL_REASONS = Arrays.stream(OperationsRefusalReason.values())
            .map(OperationsRefusalReason::wireValue).toList();

    @SuppressWarnings("unchecked")
    private static Map<String, Object> document() throws IOException {
        try (InputStream in = Files.newInputStream(
                Path.of("src/main/resources/probation-results-distribution-openapi.yaml"))) {
            return new Yaml().load(in);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> at(final Map<String, Object> root, final String... path) {
        Map<String, Object> current = root;
        for (final String segment : path) {
            assertThat(current).as("path up to '%s'", segment).containsKey(segment);
            current = (Map<String, Object>) current.get(segment);
        }
        return current;
    }

    @SuppressWarnings("unchecked")
    private static List<String> enumAt(final Map<String, Object> schema) {
        return (List<String>) schema.get("enum");
    }

    @Test
    @DisplayName("the version is raised above the placeholder 0.0.0")
    void version_raised_above_placeholder() throws IOException {
        final Map<String, Object> info = at(document(), "info");

        assertThat(info.get("version")).isNotEqualTo("0.0.0");
    }

    @Test
    @DisplayName("describes exactly the two operations, by their fixed action-string operationId")
    void describes_exactly_the_two_operations() throws IOException {
        final Map<String, Object> paths = at(document(), "paths");

        assertThat(paths).hasSize(2);
        final Map<String, Object> replayPath = at(paths, "/operations/requests/{requestId}/replay");
        final Map<String, Object> replayPost = at(replayPath, "post");
        assertThat(replayPost.get("operationId")).isEqualTo(REPLAY_OPERATION_ID);

        final Map<String, Object> resendLegPath = at(paths, "/operations/resend-leg");
        final Map<String, Object> resendLegPost = at(resendLegPath, "post");
        assertThat(resendLegPost.get("operationId")).isEqualTo(RESEND_LEG_OPERATION_ID);
    }

    @Test
    @DisplayName("the ProblemDetail reason enum equals OperationsRefusalReason, value for value")
    void problem_detail_reason_enum_equals_the_service_vocabulary() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> problemDetail = at(schemas, "ProblemDetail");
        final Map<String, Object> properties = at(problemDetail, "properties");
        final Map<String, Object> reason = at(properties, "reason");

        assertThat(enumAt(reason)).containsExactlyInAnyOrderElementsOf(CLOSED_REFUSAL_REASONS);
    }

    @Test
    @DisplayName("the leg enum equals domain.Leg, value for value")
    void leg_enum_equals_domain_leg() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> leg = at(schemas, "Leg");

        assertThat(enumAt(leg)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(Leg.values()).map(Leg::wireValue).toList());
    }

    @Test
    @DisplayName("the LegStatus enum equals domain.LegStatus, value for value")
    void leg_status_enum_equals_domain_leg_status() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> legStatus = at(schemas, "LegStatus");

        assertThat(enumAt(legStatus)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(LegStatus.values()).map(LegStatus::wireValue).toList());
    }

    @Test
    @DisplayName("the LegReason enum equals domain.LegReason, value for value")
    void leg_reason_enum_equals_domain_leg_reason() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> legReason = at(schemas, "LegReason");

        assertThat(enumAt(legReason)).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(LegReason.values()).map(LegReason::wireValue).toList());
    }

    @Test
    @DisplayName("the per-ID refusal code enum is the four domain ResendRefusal codes plus not-run "
            + "and claimed-incomplete")
    void resend_refusal_code_enum_is_domain_codes_plus_not_run() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> refusalCode = at(schemas, "ResendRefusalCode");
        final List<String> expected = Arrays.stream(ResendRefusal.values())
                .map(ResendRefusal::wireValue).collect(Collectors.toCollection(java.util.ArrayList::new));
        expected.add("not-run");
        expected.add("claimed-incomplete");

        assertThat(enumAt(refusalCode)).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("replay's outcome enum is exactly enqueued and no-op")
    void replay_outcome_enum_is_enqueued_and_no_op() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> replayResponse = at(schemas, "ReplayResponse");
        final Map<String, Object> properties = at(replayResponse, "properties");
        final Map<String, Object> outcome = at(properties, "outcome");

        assertThat(enumAt(outcome)).containsExactlyInAnyOrder("enqueued", "no-op");
    }

    @Test
    @DisplayName("resend-leg's request body names exactly the fields the mapper reads")
    void resend_leg_request_body_is_closed() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> resendLegRequest = at(schemas, "ResendLegRequest");

        assertThat(resendLegRequest.get("additionalProperties")).isEqualTo(Boolean.FALSE);
        final Map<String, Object> properties = at(resendLegRequest, "properties");
        assertThat(properties.keySet()).containsExactlyInAnyOrder("requestIds", "leg", "operator",
                "reason", "allowTerminalSuccess", "allowChangedPayload");
    }

    @Test
    @DisplayName("resend-leg's accepted response carries only resendRunId")
    void resend_leg_accepted_response_carries_only_the_run_id() throws IOException {
        final Map<String, Object> schemas = at(document(), "components", "schemas");
        final Map<String, Object> accepted = at(schemas, "ResendLegAcceptedResponse");
        final Map<String, Object> properties = at(accepted, "properties");

        assertThat(properties.keySet()).containsExactly("resendRunId");
    }
}
