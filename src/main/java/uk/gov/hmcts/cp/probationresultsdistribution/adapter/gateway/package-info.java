/**
 * The one outbound call: the HMPPS route on the APIM gateway (design §4.4
 * {@code adapter.gateway.HmppsPublisher}, design D4a, H5).
 *
 * <p>An HTTPS POST of the redacted, contract-validated body to the configured HMPPS publish URL,
 * with the gateway subscription key and bounded connect and read timeouts. 408, 429, 5xx and
 * connect or I/O errors are retried, honouring {@code Retry-After} up to a cap. The publisher
 * never throws past its outcome: {@code SENT(status)}, {@code FAILED_NON_TRANSIENT(status)} or
 * {@code TRANSIENT_EXHAUSTED}, which the runner maps to the leg's status and reason. There is no
 * VEP publisher and no client certificate.
 *
 * <p><strong>Dependency direction.</strong> Implements the {@code HmppsSender} port declared in
 * {@code application}, and depends on that port, on {@code domain} and on the shared HTTP retry
 * support only. It never depends on another adapter package, on {@code persistence}, or on the
 * driving adapters {@code inbound} and {@code api}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;
