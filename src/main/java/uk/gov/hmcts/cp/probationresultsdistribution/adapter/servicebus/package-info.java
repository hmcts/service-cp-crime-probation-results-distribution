/**
 * The Service Bus sender adapter (design §4.8 action 5; slice 004, FR-004-056, FR-004-058):
 * submit-hearing's enqueue onto {@code probation.requests} through the broker's sender role.
 *
 * <p><strong>Dependency direction.</strong> Implements an {@code application} port and speaks
 * {@code domain}; nothing in {@code application}, {@code pipeline} or {@code domain} depends on it.
 * It sends only: receiving and settling stay in {@code inbound}, the one place a received message
 * is handled.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;
