package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.Objects;

/**
 * One of FR-006-008's five pre-run checks (a)-(e), recorded whether it passed or failed — a
 * failure stops the replay before any comparison runs.
 *
 * @param checkId the design's own letter (a)-(e)
 * @param passed  whether the check passed
 * @param detail  what was read, for the report — identifiers and booleans only, never a payload
 */
public record PreRunCheckResult(String checkId, boolean passed, String detail) {

    /** Rejects null components. */
    public PreRunCheckResult {
        Objects.requireNonNull(checkId, "checkId");
        Objects.requireNonNull(detail, "detail");
    }
}
