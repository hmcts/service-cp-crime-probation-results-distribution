package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * Holds {@link DistributionCommandParser} to the closed inbound contract (design Appendix B.1,
 * FR-002-002, FR-002-003). The vendored {@code distribution-command.schema.json} is the build's
 * documented authority; the parser validates explicitly in code, and this test proves the parser
 * enforces the same closed contract: a valid command accepted, every invalid shape rejected with a
 * bounded kind that never quotes the offending field.
 */
@DisplayName("the inbound distribution command contract")
class DistributionCommandSchemaTest {

    private static final String REQUEST_ID = "11111111-1111-1111-1111-111111111111";
    private static final String HEARING_ID = "22222222-2222-2222-2222-222222222222";
    private static final String USER_ID = "33333333-3333-3333-3333-333333333333";

    private DistributionCommandParser parser;

    @BeforeEach
    void setUp() {
        parser = new DistributionCommandParser();
    }

    private String valid(final boolean withUserId) {
        final String user = withUserId ? ",\n  \"userId\": \"" + USER_ID + "\"" : "";
        return "{\n"
                + "  \"source\": \"RESULTS\",\n"
                + "  \"requestId\": \"" + REQUEST_ID + "\",\n"
                + "  \"hearingId\": \"" + HEARING_ID + "\",\n"
                + "  \"hearingDay\": \"2026-08-19\",\n"
                + "  \"sharedTime\": \"2026-08-19T16:42:07.512Z\",\n"
                + "  \"eventType\": \"Hearing_Resulted\"" + user + "\n"
                + "}";
    }

    @Nested
    @DisplayName("a valid command")
    class ValidCommand {

        @Test
        @DisplayName("is accepted with its userId, and every field is parsed")
        void accepts_a_full_command() {
            final DistributionCommand command = parser.parse(valid(true));

            assertThat(command.source()).isEqualTo("RESULTS");
            assertThat(command.requestId()).isEqualTo(UUID.fromString(REQUEST_ID));
            assertThat(command.hearingId()).isEqualTo(UUID.fromString(HEARING_ID));
            assertThat(command.hearingDay()).isEqualTo(LocalDate.parse("2026-08-19"));
            assertThat(command.sharedTime()).isEqualTo(Instant.parse("2026-08-19T16:42:07.512Z"));
            assertThat(command.eventType()).isEqualTo("Hearing_Resulted");
            assertThat(command.userId()).contains(UUID.fromString(USER_ID));
        }

        @Test
        @DisplayName("is accepted without a userId, which is optional")
        void accepts_a_command_without_user_id() {
            final DistributionCommand command = parser.parse(valid(false));

            assertThat(command.userId()).isEqualTo(Optional.empty());
        }

        @Test
        @DisplayName("accepts a sharedTime carrying an offset other than Z")
        void accepts_a_shared_time_with_offset() {
            final String body = valid(false)
                    .replace("2026-08-19T16:42:07.512Z", "2026-08-19T17:42:07.512+01:00");

            final DistributionCommand command = parser.parse(body);

            assertThat(command.sharedTime()).isEqualTo(Instant.parse("2026-08-19T16:42:07.512Z"));
        }
    }

    @Nested
    @DisplayName("an invalid command")
    class InvalidCommand {

        @Test
        @DisplayName("with an unknown field is rejected (the schema is closed)")
        void rejects_an_unknown_field() {
            final String body = valid(true).replace("}", ",\n  \"extra\": \"x\"\n}");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.UNKNOWN_FIELD);
        }

        @Test
        @DisplayName("with a missing required field is rejected")
        void rejects_a_missing_required_field() {
            final String body = valid(true)
                    .replaceFirst("  \"hearingId\": \"" + HEARING_ID + "\",\n", "");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.MISSING_REQUIRED_FIELD);
        }

        @Test
        @DisplayName("with a field of the wrong type is rejected")
        void rejects_a_wrong_type() {
            final String body = valid(true)
                    .replace("\"hearingDay\": \"2026-08-19\"", "\"hearingDay\": 20260819");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.WRONG_TYPE);
        }

        @Test
        @DisplayName("with an unparseable sharedTime is rejected")
        void rejects_an_unparseable_shared_time() {
            final String body = valid(true)
                    .replace("2026-08-19T16:42:07.512Z", "not-a-time");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.WRONG_TYPE);
        }

        @Test
        @DisplayName("with an eventType other than Hearing_Resulted is rejected")
        void rejects_a_bad_event_type() {
            final String body = valid(true)
                    .replace("\"Hearing_Resulted\"", "\"Hearing_Amended\"");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.DISALLOWED_VALUE);
        }

        @Test
        @DisplayName("with a source other than RESULTS is rejected")
        void rejects_a_bad_source() {
            final String body = valid(true).replace("\"RESULTS\"", "\"COURTS\"");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.DISALLOWED_VALUE);
        }

        @Test
        @DisplayName("with an explicit null userId is rejected")
        void rejects_an_explicit_null_user_id() {
            final String body = valid(false).replace("\n}", ",\n  \"userId\": null\n}");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.NULL_USER_ID);
        }

        @Test
        @DisplayName("that is not JSON is rejected")
        void rejects_a_non_json_body() {
            assertThatThrownBy(() -> parser.parse("not json at all"))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.NOT_JSON);
        }

        @Test
        @DisplayName("that is a JSON array, not an object, is rejected")
        void rejects_a_non_object_body() {
            assertThatThrownBy(() -> parser.parse("[]"))
                    .isInstanceOf(CommandValidationException.class)
                    .extracting(e -> ((CommandValidationException) e).kind())
                    .isEqualTo(CommandValidationException.Kind.NOT_OBJECT);
        }

        @Test
        @DisplayName("never carries the offending field name in the exception")
        void never_quotes_the_field_name() {
            final String body = valid(true).replace("}", ",\n  \"secretFieldName\": \"x\"\n}");

            assertThatThrownBy(() -> parser.parse(body))
                    .isInstanceOf(CommandValidationException.class)
                    .hasMessageNotContaining("secretFieldName");
        }
    }
}
