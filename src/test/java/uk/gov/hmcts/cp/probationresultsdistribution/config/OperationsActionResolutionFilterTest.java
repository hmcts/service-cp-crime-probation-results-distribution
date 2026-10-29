package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Pins FR-005-022 against research.md Decision 2's confirmed finding: {@code
 * cp-auth-rules-filter}'s own {@code RequestActionResolver} prefers a caller-supplied vendor media
 * type or the {@code CPP-ACTION} header over any path/method default, so this filter must neutralise
 * all three ahead of it for the two operations routes.
 */
@DisplayName("the operations action resolution filter")
class OperationsActionResolutionFilterTest {

    private final OperationsActionResolutionFilter filter = new OperationsActionResolutionFilter();

    @ParameterizedTest
    @CsvSource({
        "/operations/requests/3fa85f64-5717-4562-b3fc-2c963f66afa6/replay,"
                + "probation-results-distribution-operations.replay",
        "/operations/resend-leg,probation-results-distribution-operations.resend-leg"
    })
    @DisplayName("fixes CPP-ACTION regardless of what the caller sent, for both operations routes")
    void fixes_the_action_header_for_both_routes(final String path, final String expectedAction)
            throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.addHeader("CPP-ACTION", "something-the-caller-chose");
        final FilterChain chain = (servletRequest, servletResponse) -> {
            final HttpServletRequest wrapped = (HttpServletRequest) servletRequest;
            assertThat(wrapped.getHeader("CPP-ACTION")).isEqualTo(expectedAction);
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @ParameterizedTest
    @CsvSource({
        "/operations/requests/3fa85f64-5717-4562-b3fc-2c963f66afa6/replay",
        "/operations/resend-leg"
    })
    @DisplayName("strips a vnd. vendor token from Content-Type and Accept on both routes")
    void strips_vendor_tokens_from_content_type_and_accept(final String path)
            throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/vnd.resend-leg+json");
        request.addHeader("Accept", "application/vnd.replay+json, application/json");
        final FilterChain chain = (servletRequest, servletResponse) -> {
            final HttpServletRequest wrapped = (HttpServletRequest) servletRequest;
            assertThat(wrapped.getContentType()).doesNotContain("vnd.");
            assertThat(wrapped.getHeader("Accept")).doesNotContain("vnd.");
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("leaves a request outside /operations/** unwrapped")
    void leaves_other_requests_unwrapped() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        request.addHeader("CPP-ACTION", "caller-chosen");
        final FilterChain chain = (servletRequest, servletResponse) -> {
            final HttpServletRequest unwrapped = (HttpServletRequest) servletRequest;
            assertThat(unwrapped.getHeader("CPP-ACTION")).isEqualTo("caller-chosen");
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }

    @Test
    @DisplayName("resolves no action for an unmapped method on an operations path")
    void resolves_no_action_for_an_unmapped_method() throws ServletException, IOException {
        final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/operations/resend-leg");
        request.addHeader("CPP-ACTION", "caller-chosen");
        final FilterChain chain = (servletRequest, servletResponse) -> {
            final HttpServletRequest unwrapped = (HttpServletRequest) servletRequest;
            assertThat(unwrapped.getHeader("CPP-ACTION")).isEqualTo("caller-chosen");
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);
    }
}
