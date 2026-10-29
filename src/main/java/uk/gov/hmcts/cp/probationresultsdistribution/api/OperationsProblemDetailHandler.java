package uk.gov.hmcts.cp.probationresultsdistribution.api;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;

/**
 * Maps every operations-API refusal and failure to an RFC 9457 {@link ProblemDetail} (FR-005-029 to
 * FR-005-034). Holds no business logic of its own: it only renders what {@link
 * OperationsRefusalException} or a framework-level parsing failure already decided. Every
 * {@code 5xx} is counted on {@code probation_operations_failures_total} (FR-005-043); the
 * {@code action} label is a best-effort guess from the request, since the advice runs outside the
 * controller that would otherwise supply it precisely.
 */
@RestControllerAdvice
public class OperationsProblemDetailHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsProblemDetailHandler.class);
    private static final String REASON = "reason";
    private static final String ARGUMENT_NAME = "argumentName";
    private static final String CAP = "cap";

    private final MetricRegistry metrics;

    /**
     * Creates the advice.
     *
     * @param metrics the metric registry, for counting every {@code 5xx}
     */
    public OperationsProblemDetailHandler(final MetricRegistry metrics) {
        this.metrics = metrics;
    }

    /**
     * Renders one bounded refusal (FR-005-029 to FR-005-032).
     *
     * @param ex      the refusal
     * @param request the request, to derive the {@code action} metric label only
     * @return the problem detail
     */
    @ExceptionHandler(OperationsRefusalException.class)
    public ProblemDetail handleRefusal(final OperationsRefusalException ex,
                                       final HttpServletRequest request) {
        final OperationsRefusalReason reason = ex.reason();
        final ProblemDetail problem = ProblemDetail.forStatus(reason.httpStatus());
        problem.setInstance(instanceFor(request));
        problem.setProperty(REASON, reason.wireValue());
        ex.argumentName().ifPresent(name -> problem.setProperty(ARGUMENT_NAME, name));
        ex.capApplied().ifPresent(cap -> problem.setProperty(CAP, cap));
        if (reason.httpStatus().is5xxServerError()) {
            metrics.operationsFailure(actionFrom(request), OperationsFailure.fromValue(reason.wireValue()));
        }
        return problem;
    }

    /**
     * Renders a body that will not parse (FR-005-033): {@code unknown-field} when the cause is an
     * unrecognised JSON property, {@code unreadable-body} for anything else. Never echoes the
     * parser's own message.
     *
     * @param ex      the parsing failure
     * @param request the request, to derive the no-echo {@code instance} only
     * @return the problem detail
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(final HttpMessageNotReadableException ex,
                                              final HttpServletRequest request) {
        final ProblemDetail problem;
        if (ex.getCause() instanceof UnrecognizedPropertyException unrecognised) {
            problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            problem.setProperty(REASON, OperationsRefusalReason.UNKNOWN_FIELD.wireValue());
            OperationsRefusalException.unknownField(unrecognised.getPropertyName()).argumentName()
                    .ifPresent(name -> problem.setProperty(ARGUMENT_NAME, name));
        } else {
            problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
            problem.setProperty(REASON, OperationsRefusalReason.UNREADABLE_BODY.wireValue());
        }
        problem.setInstance(instanceFor(request));
        return problem;
    }

    /**
     * Renders a content type other than JSON (FR-005-033).
     *
     * @param ex      the media-type failure
     * @param request the request, to derive the no-echo {@code instance} only
     * @return the problem detail
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleUnsupportedMediaType(final HttpMediaTypeNotSupportedException ex,
                                                     final HttpServletRequest request) {
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        problem.setProperty(REASON, OperationsRefusalReason.UNSUPPORTED_CONTENT_TYPE.wireValue());
        problem.setInstance(instanceFor(request));
        return problem;
    }

    /**
     * Renders every other failure as {@code 500 unexpected}: a defect, never the exception's own
     * message or stack trace (FR-005-031).
     *
     * @param ex      the unclassified failure
     * @param request the request, to derive the {@code action} metric label only
     * @return the problem detail
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(final Exception ex, final HttpServletRequest request) {
        LOG.error("operations call failed unexpectedly", ex);
        final ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setProperty(REASON, OperationsRefusalReason.UNEXPECTED.wireValue());
        problem.setInstance(instanceFor(request));
        metrics.operationsFailure(actionFrom(request), OperationsFailure.UNEXPECTED);
        return problem;
    }

    /**
     * Derives the metric's {@code action} label from the request path — this slice serves exactly
     * two routes (FR-005-001), so a path ending {@code /replay} is {@code replay} and anything else
     * under {@code /operations/**} is {@code resend-leg}.
     */
    private static OperationsAction actionFrom(final HttpServletRequest request) {
        final String path = request.getRequestURI();
        return path != null && path.endsWith("/replay") ? OperationsAction.REPLAY
                : OperationsAction.RESEND_LEG;
    }

    /**
     * A fixed, safe {@code instance} naming only the action, never the real request path: Spring
     * auto-populates a blank {@code instance} from the request URI for any {@code @ExceptionHandler}
     * returning {@link ProblemDetail}, and for replay that path embeds the caller's own value
     * verbatim (FR-005-031) — this pre-empts it.
     */
    private static URI instanceFor(final HttpServletRequest request) {
        return URI.create("/operations/" + actionFrom(request).wireValue());
    }
}
