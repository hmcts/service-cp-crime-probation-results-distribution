/**
 * Composition, configuration and the cross-cutting wiring (design §8.1, §8.2, §10).
 *
 * <p>The validated property catalogue under the {@code distribution.} prefix and its startup
 * validator (every property has a type, a default, an environment source and a startup check), the
 * bean definitions that choose a live or stub implementation for each port, the health indicators
 * and the readiness group, the metric definitions, and the Flyway arrangement that runs the
 * migration before intake starts.
 *
 * <p><strong>Dependency direction.</strong> The composition root: this is the one package allowed
 * to know every other package, because it is where a port is bound to the adapter that implements
 * it. Nothing depends on {@code config} except through the beans it publishes; in particular
 * {@code application}, {@code pipeline} and {@code domain} never import from it, and receive their
 * settings as constructor arguments or plain values.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.config;
