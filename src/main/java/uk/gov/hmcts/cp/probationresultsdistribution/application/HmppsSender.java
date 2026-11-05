package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;

/**
 * The HMPPS send port (design §9). The core hands the redacted tree to this port; the gateway
 * adapter serialises it once to UTF-8, POSTs those exact bytes, and returns the outcome together
 * with the exact text it sent, so the core can digest and store the same bytes (FR-002-033,
 * FR-002-037). The sender never throws past its outcome.
 */
public interface HmppsSender {

    /**
     * Serialises and POSTs the redacted body, returning the outcome and the exact UTF-8 text sent.
     *
     * @param redactedBody the redacted hearing tree
     * @return the publish outcome and the exact body text POSTed
     */
    SentBody send(JsonNode redactedBody);

    /**
     * The outcome of a send and the exact UTF-8 body text POSTed. The text is carried, rather than a
     * byte array, so the exact bytes are {@code body.getBytes(UTF_8)} for both the digest and the
     * stored {@code outbound_body} row.
     *
     * @param outcome the publish outcome
     * @param body    the exact UTF-8 body text POSTed
     */
    record SentBody(PublishOutcome outcome, String body) {

        /** Rejects null components. */
        public SentBody {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(body, "body");
        }
    }
}
