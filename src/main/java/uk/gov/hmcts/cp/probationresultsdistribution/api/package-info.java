/**
 * The operations API, {@code /operations/**} (design §4.8; decided for replay and resend-leg, H9).
 *
 * <p>The actions an operator needs and nothing more: list requests and their leg rows, replay a
 * {@code FAILED} request, resend one leg for an explicit list of {@code requestId}s (with the
 * forced-resend override for a leg already sent), pause and resume intake, submit a hearing that
 * reached neither path, and purge the queue while intake is paused; a listing of dead-letter
 * entries whose request is now {@code COMPLETED} is recommended. There is no business REST API.
 * Every request passes the estate's authorisation filter ({@code cp-auth-rules-filter}, default
 * deny) and audit filter ({@code cp-audit-filter-springboot}); a refusal is an API response with a
 * bounded code, never a store row.
 *
 * <p>Replay and resend-leg are decided for this API, behind the five deployment gates of design
 * §4.8, and the command-line runners in the image stay as their fallback. The HTTP exposure of
 * the other actions is still open (O-1); until it is decided they run as command-line runners.
 *
 * <p><strong>Dependency direction.</strong> A driving adapter, as {@code inbound} is: it calls into
 * {@code application} and speaks {@code domain}, and never reaches an outbound adapter or the
 * store directly. Nothing in {@code application}, {@code pipeline} or {@code domain} depends on it.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.api;
