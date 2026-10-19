/**
 * The per-message youth-switch read from users-groups (design §4.4
 * {@code adapter.usersgroups.FeaturePermissionClient}, design H7).
 *
 * <p>Reads the {@code EMYouthFilter} feature permission on every run of the HMPPS leg, after the
 * payload fetch and before the redaction, as the legacy does. The answer is a
 * {@code YouthFilterDecision}: {@code ACTIVE} (remove youth), {@code INACTIVE}, or
 * {@code UNREADABLE} with a bounded cause. Only the first permission counts. The legacy's retry
 * rule is kept (at most three attempts, a fixed wait, no {@code Retry-After}); finite timeouts are
 * the recorded difference.
 *
 * <p><strong>Dependency direction.</strong> Implements the {@code YouthFilterSource} port declared
 * in {@code application}, and depends on that port, on {@code domain} and on the shared HTTP
 * support only. It never depends on another adapter package, on {@code persistence}, or on the
 * driving adapters {@code inbound} and {@code api}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups;
