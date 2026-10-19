/**
 * The driving adapter for the {@code probation.requests} queue (design §4.2, §4.4, Appendix B.1).
 *
 * <p>The Service Bus listener and its consumer configuration (explicit settlement: complete,
 * abandon, dead-letter), the parser that validates each body against the closed command schema
 * before anything else happens, the consumer lifecycle controller behind pause-intake and
 * resume-intake, and the store gate that holds intake until the store is reachable and migrated.
 * A message that fails the schema is dead-lettered at once with no {@code processed_request} row.
 *
 * <p><strong>Dependency direction.</strong> This package calls into {@code application} and
 * speaks {@code domain}. It never reaches an outbound adapter or the store directly, and nothing
 * in {@code application}, {@code pipeline} or {@code domain} depends on it: the broker is a
 * delivery mechanism, not part of the use case.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.inbound;
