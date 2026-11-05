/**
 * The one outbound call: the HMPPS route on the APIM gateway (design §4.4
 * {@code adapter.gateway.HmppsPublisher}, design D4a, H5).
 *
 * <p>An HTTPS POST of the redacted body to the configured HMPPS publish URL, with the gateway
 * subscription key and bounded timeouts: connect 5 s, read 60 s (design H27). The body is not
 * validated against any schema in this release (design H13). Only a connection failure (the
 * request never left; a connect timeout included) and a 429 are retried in-run, honouring
 * {@code Retry-After} up to a cap. A 5xx, a 408, and a read timeout or I/O failure once the request
 * was written are outcome-unknown and are never retried in-run, because the HMPPS POST is not
 * idempotent: the leg parks for broker redelivery instead (design H11, H15). The retryable set is
 * fixed in code, not configuration. The publisher never throws past its outcome:
 * {@code SENT(status)}, {@code FAILED_NON_TRANSIENT(status)} or {@code TRANSIENT_EXHAUSTED}, which
 * the runner maps to the leg's status and reason. Any 2xx is {@code SENT}; a 2xx other than 200 is
 * counted as an anomaly (design H12). There is no VEP publisher and no client certificate.
 *
 * <p>The publisher hands the runner the exact bytes it sent. The runner records their
 * {@code body_digest} with the leg's outcome and then stores the bytes in {@code outbound_body}, in
 * the same transaction (design H18, H20). The body is never logged, at any level.
 *
 * <p><strong>Dependency direction.</strong> Implements the {@code HmppsSender} port declared in
 * {@code application}, and depends on that port, on {@code domain} and on its in-package
 * {@code RetryPolicy} only. It never depends on another adapter package, on {@code persistence}, or
 * on the driving adapters {@code inbound} and {@code api}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;
