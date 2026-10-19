/**
 * The vocabulary of the service: the values every other package speaks in (design §4.4, §4.5,
 * §4.6).
 *
 * <p>The inbound {@code DistributionCommand}, the {@code Leg} (HMPPS only, design H5; the per-leg
 * structure is kept so a re-added leg is a new enum value rather than a new shape), the leg's
 * status and bounded reason, the payload source, the youth-switch decision and its unreadable
 * cause, and the completion, failure and dead-letter reasons. Records and enums, immutable, with
 * their invariants checked in their constructors.
 *
 * <p><strong>Dependency direction.</strong> This package depends on nothing in the service and on
 * nothing outside the JDK: no Spring, no Jackson, no JDBC, no Azure SDK. Every other package may
 * depend on it. A domain type that needed an import from another package of this service would be
 * the first sign that it belongs there instead.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.domain;
