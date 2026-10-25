/**
 * The command-line runners: a driving adapter, symmetric with {@code inbound} and {@code api}
 * (design §4.4, §4.8; FR-004-042, FR-004-043).
 *
 * <p>Each runner parses one action's arguments, calls exactly one {@code application} service, and
 * prints that service's bounded outcome — never a second rule of its own. {@code RunnerDispatcher}
 * is the one entry point: it reads the first command-line argument as the action name and routes to
 * the matching runner, so that a runner invocation never starts the ASB listener or the
 * body-maintenance scheduler (FR-004-045). The six actions (replay, resend-leg, pause/resume
 * intake, purge-queue, submit-hearing, and — provisionally, pending FR-004-049's answer — the
 * rollback close-in-flight statement) and the two read-only listings (the request listing,
 * {@code dlq-completed}) each get their own runner class here.
 *
 * <p><strong>Dependency direction.</strong> A driving adapter, as {@code inbound} and {@code api}
 * are: it calls into {@code application} and speaks {@code domain}, and never reaches an outbound
 * adapter or the store directly. Nothing in {@code application}, {@code pipeline} or {@code domain}
 * depends on it.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.cli;
