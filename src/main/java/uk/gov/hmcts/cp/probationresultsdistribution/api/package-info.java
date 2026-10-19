/**
 * The operations API, {@code /operations/**} (design §4.8; recommended, open as O-1).
 *
 * <p>The actions an operator needs and nothing more: list requests and their leg rows, replay a
 * {@code FAILED} request, resend one leg, pause and resume intake, submit a hearing that reached
 * neither path, and purge the queue while intake is paused. There is no business REST API. Every
 * request passes the estate's authorisation filter ({@code cp-auth-rules-filter}, default deny)
 * and audit filter ({@code cp-audit-filter-springboot}); a refusal is an API response with a
 * bounded code, never a store row. Until the API is approved the same actions run as
 * command-line runners in the image.
 *
 * <p><strong>Dependency direction.</strong> A driving adapter, as {@code inbound} is: it calls into
 * {@code application} and speaks {@code domain}, and never reaches an outbound adapter or the
 * store directly. Nothing in {@code application}, {@code pipeline} or {@code domain} depends on it.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.api;
