package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;

/**
 * Pins FR-005-029 to FR-005-034: every closed {@code reason} maps to the right status family, a
 * {@code 400} about one argument names the argument and never its value, no exception
 * message/stack trace/far-end text reaches the body, and every {@code 5xx} is counted
 * (FR-005-043).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the operations problem-detail handler")
class OperationsProblemDetailHandlerTest {

    @Mock
    private MetricRegistry metrics;
    @Mock
    private HttpServletRequest request;

    private OperationsProblemDetailHandler handler;

    @BeforeEach
    void setUp() {
        handler = new OperationsProblemDetailHandler(metrics);
        lenient().when(request.getRequestURI())
                .thenReturn("/operations/requests/3fa85f64-5717-4562-b3fc-2c963f66afa6/replay");
    }

    static Stream<OperationsRefusalReason> everyReason() {
        return Stream.of(OperationsRefusalReason.values());
    }

    @ParameterizedTest
    @MethodSource("everyReason")
    @DisplayName("maps every closed reason to its status family and never counts a non-5xx")
    void maps_every_reason_to_its_status_family(final OperationsRefusalReason reason) {
        final ProblemDetail problem = handler.handleRefusal(OperationsRefusalException.forReason(reason),
                request);

        assertThat(HttpStatus.valueOf(problem.getStatus())).isEqualTo(reason.httpStatus());
        assertThat(problem.getProperties()).containsEntry("reason", reason.wireValue());
        if (!reason.httpStatus().is5xxServerError()) {
            verifyNoInteractions(metrics);
        }
    }

    @Nested
    @DisplayName("a 400 about one argument")
    class ArgumentRefusals {

        @Test
        @DisplayName("names the argument and never a value")
        void names_the_argument_never_the_value() {
            final ProblemDetail problem = handler.handleRefusal(OperationsRefusalException.ofArgument(
                    OperationsRefusalReason.MISSING_ARGUMENT, "operator"), request);

            assertThat(problem.getProperties()).containsEntry("argumentName", "operator");
            assertThat(problem.getDetail()).isNull();
        }

        @Test
        @DisplayName("too-many-request-ids carries the cap applied")
        void too_many_request_ids_carries_the_cap() {
            final ProblemDetail problem = handler.handleRefusal(
                    OperationsRefusalException.tooManyRequestIds(100), request);

            assertThat(HttpStatus.valueOf(problem.getStatus())).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(problem.getProperties()).containsEntry("reason", "too-many-request-ids")
                    .containsEntry("cap", 100);
        }
    }

    @Nested
    @DisplayName("no exception message, stack trace or far-end words reach the body")
    class NoEcho {

        @Test
        @DisplayName("an unreadable body maps to unreadable-body, never the parser's own message")
        void unreadable_body_never_echoes_the_parser_message() {
            final HttpMessageNotReadableException cause = new HttpMessageNotReadableException(
                    "Unexpected character ('x' (code 120)): a secret-looking parser message",
                    (org.springframework.http.HttpInputMessage) null);

            final ProblemDetail problem = handler.handleUnreadableBody(cause, request);

            assertThat(problem.getProperties()).containsEntry("reason", "unreadable-body");
            assertThat(problem.getDetail()).isNull();
            assertThat(problem.getProperties().values()).noneMatch(
                    value -> value.toString().contains("secret-looking parser message"));
        }

        @Test
        @DisplayName("an unexpected failure maps to unexpected, never the exception's message")
        void unexpected_failure_never_echoes_its_message() {
            final RuntimeException thrown = new IllegalStateException("do not leak this");

            final ProblemDetail problem = handler.handleUnexpected(thrown, request);

            assertThat(HttpStatus.valueOf(problem.getStatus())).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(problem.getProperties()).containsEntry("reason", "unexpected");
            assertThat(problem.getDetail()).isNull();
            verify(metrics).operationsFailure(any(OperationsAction.class), eq(OperationsFailure.UNEXPECTED));
        }

        @Test
        @DisplayName("a content type other than JSON maps to 415 unsupported-content-type")
        void unsupported_content_type_maps_to_415() {
            final HttpMediaTypeNotSupportedException cause = new HttpMediaTypeNotSupportedException(
                    "text/plain is not supported");

            final ProblemDetail problem = handler.handleUnsupportedMediaType(cause, request);

            assertThat(HttpStatus.valueOf(problem.getStatus()))
                    .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
            assertThat(problem.getProperties()).containsEntry("reason", "unsupported-content-type");
        }

        @Test
        @DisplayName("an unrecognised JSON field maps to unknown-field, naming it")
        void unrecognised_field_maps_to_unknown_field() {
            final com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException unrecognised =
                    com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.from(
                            org.mockito.Mockito.mock(com.fasterxml.jackson.core.JsonParser.class),
                            Object.class, "notAField", List.of());
            final HttpMessageConversionException wrapped =
                    new HttpMessageNotReadableException("JSON parse error",
                            unrecognised, (org.springframework.http.HttpInputMessage) null);

            final ProblemDetail problem = handler.handleUnreadableBody(
                    (HttpMessageNotReadableException) wrapped, request);

            assertThat(problem.getProperties()).containsEntry("reason", "unknown-field");
        }
    }

    @Nested
    @DisplayName("an unmapped path or method (FR-005-034)")
    class UnmappedRoute {

        @Test
        @DisplayName("application.yaml suppresses the stack trace and the path on the default error body")
        void application_yaml_suppresses_stacktrace_and_path() throws IOException {
            final List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
                    new FileSystemResource("src/main/resources/application.yaml"));
            final Binder binder = new Binder(
                    org.springframework.boot.context.properties.source.ConfigurationPropertySources
                            .from(sources));

            assertThat(binder.bind("server.error.include-stacktrace", Bindable.of(String.class))
                    .orElse("")).isEqualToIgnoringCase("never");
            assertThat(binder.bind("server.error.include-path", Bindable.of(String.class))
                    .orElse("")).isEqualToIgnoringCase("never");
        }
    }
}
