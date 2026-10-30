/**
 * The Service Bus sender adapters (design §4.8 actions 2 and 5; slice 004, FR-004-015,
 * FR-004-056, FR-004-058): submit-hearing's enqueue onto {@code probation.requests} through the
 * broker's sender role, and the replay enqueue built over it ({@code origin = REPLAY}, T078).
 *
 * <p><strong>Dependency direction.</strong> Implements an {@code application} port and speaks
 * {@code domain}; nothing in {@code application}, {@code pipeline} or {@code domain} depends on it.
 * It sends only: receiving and settling stay in {@code inbound}, the one place a received message
 * is handled.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;
