/**
 * The driven adapter that bridges the application's {@code OutboundLog} port to the persistence store
 * (design §4.4, §4.6).
 *
 * <p>{@code OutboundLogAdapter} implements {@code application.OutboundLog} against
 * {@code persistence.OutboundMessageRepository}, so the application core records a leg outcome and its
 * exact sent bytes without importing a JDBC type, and {@code persistence} never imports from
 * {@code application}. The outcome UPDATE and the {@code outbound_body} insert run in the repository's
 * one transaction, fenced by the request claim (design H18-H20).
 *
 * <p><strong>Dependency direction.</strong> This package depends on {@code application} (the port it
 * implements), {@code persistence} (the repository it calls) and {@code domain} only. Nothing depends
 * on it except the composition root in {@code config}, which wires it as the core's {@code OutboundLog}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence;
