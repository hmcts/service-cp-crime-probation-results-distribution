package uk.gov.hmcts.cp.probationresultsdistribution.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Fixes the action {@code cp-auth-rules-filter} authorises the two operations-API routes against,
 * closing the bypass research.md Decision 2 confirms by decompiling that library:
 * {@code RequestActionResolver.resolve} prefers — in order — a caller-supplied {@code vnd.} vendor
 * suffix on {@code Content-Type}, then on {@code Accept}, then the configured action header
 * ({@code CPP-ACTION}) verbatim, and only last a {@code "{method} {path}"} fallback. FR-005-022
 * requires the service, never the caller, to decide the action, so this filter runs ahead of
 * {@code cp-auth-rules-filter} and neutralises all three vectors for exactly the two routes this
 * slice owns, wrapping the request so every filter after it sees this slice's own fixed action
 * string on {@code CPP-ACTION} and no vendor token on either header.
 *
 * <p>A request outside the two operations routes is passed through unwrapped.
 */
public class OperationsActionResolutionFilter extends OncePerRequestFilter {

    private static final String REPLAY_ACTION = "probation-results-distribution-operations.replay";
    private static final String RESEND_LEG_ACTION =
            "probation-results-distribution-operations.resend-leg";
    private static final String RESEND_LEG_PATH = "/operations/resend-leg";
    private static final Pattern REPLAY_PATH_PATTERN =
            Pattern.compile("^/operations/requests/[^/]+/replay$");
    private static final String POST_METHOD = "POST";
    private static final String ACTION_HEADER = "CPP-ACTION";
    private static final String ACCEPT_HEADER = "Accept";
    private static final String PLAIN_JSON = "application/json";
    private static final UrlPathHelper PATH_HELPER = new UrlPathHelper();

    @Override
    protected void doFilterInternal(final HttpServletRequest request, final HttpServletResponse response,
                                    final FilterChain filterChain) throws ServletException, IOException {
        final String action = resolveAction(request);
        if (action == null) {
            filterChain.doFilter(request, response);
        } else {
            filterChain.doFilter(new FixedActionRequest(request, action), response);
        }
    }

    private static String resolveAction(final HttpServletRequest request) {
        final String action;
        if (POST_METHOD.equalsIgnoreCase(request.getMethod())) {
            final String path = PATH_HELPER.getPathWithinApplication(request);
            if (RESEND_LEG_PATH.equals(path)) {
                action = RESEND_LEG_ACTION;
            } else if (REPLAY_PATH_PATTERN.matcher(path).matches()) {
                action = REPLAY_ACTION;
            } else {
                action = null;
            }
        } else {
            action = null;
        }
        return action;
    }

    /** Wraps one request, fixing the action header and stripping a vendor media type. */
    private static final class FixedActionRequest extends HttpServletRequestWrapper {

        private final String action;

        /* default */ FixedActionRequest(final HttpServletRequest request, final String action) {
            super(request);
            this.action = action;
        }

        @Override
        public String getHeader(final String name) {
            final String value;
            if (ACTION_HEADER.equalsIgnoreCase(name)) {
                value = action;
            } else if (ACCEPT_HEADER.equalsIgnoreCase(name)) {
                value = stripVendorToken(super.getHeader(name));
            } else {
                value = super.getHeader(name);
            }
            return value;
        }

        @Override
        public String getContentType() {
            return stripVendorToken(super.getContentType());
        }

        private static String stripVendorToken(final String raw) {
            final String stripped;
            if (raw != null && raw.toLowerCase(Locale.ROOT).contains("vnd.")) {
                stripped = PLAIN_JSON;
            } else {
                stripped = raw;
            }
            return stripped;
        }
    }
}
