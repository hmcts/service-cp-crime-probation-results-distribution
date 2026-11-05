/**
 * Read-only evidence over the existing store (design §9.2, §9.5, §10.4; slice 006,
 * FR-006-023, FR-006-030, FR-006-031, FR-006-047). Every class here answers one question — is a
 * go/no-go row met, what did a day's reconciliation show, is the decommissioning evidence in place
 * — by reading {@code outbound_message_latest}, {@code processed_request} or
 * {@code scheduled_job_status} (002's V1, 003's V2). None of them writes: no {@code UPDATE}, no
 * {@code INSERT}, no claim, no settlement. That invariant is this package's whole reason to exist
 * separately from {@code persistence}, which owns every write path 002–004 already built.
 *
 * <p>Every result is a typed, identifier-only record — counts, statuses, reasons, request and
 * hearing identifiers — never a payload value or a stored body (FR-006-030; constitution
 * Principle VII).
 *
 * <p><strong>Dependency direction.</strong> This package depends on {@code domain} (the closed
 * {@code LegStatus}/{@code LegReason}/{@code CompletionReason}/{@code FailureReason} vocabularies
 * these queries read against) and on the existing {@code JdbcClient} pattern 002–004 already use;
 * it imports no {@code application} port, no adapter and no driving adapter. Nothing in
 * {@code application}, {@code pipeline}, {@code persistence}, {@code cli} or {@code api} depends on
 * it: these queries are read by the owning team and operations, directly or through the
 * documentation this slice adds, never by the running pipeline itself.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.evidence;
