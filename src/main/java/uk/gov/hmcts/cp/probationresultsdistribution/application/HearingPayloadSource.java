package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

/**
 * The payload-fetch port (design §4.4, D5). The core reads the hearing payload only through this
 * port; the Redis {@code EXT_} cache and results-query adapters implement it. The payload crosses as
 * a Jackson {@link JsonNode}, the one type the core accepts from an adapter (constitution IV).
 */
public interface HearingPayloadSource {

    /**
     * Fetches the hearing payload for a command, trying the dated cache key, the undated key, then
     * the results query API, and stopping at the first source that yields one.
     *
     * @param command the admitted command
     * @return the fetched payload with its source, or empty when every source misses
     */
    Optional<FetchedPayload> fetch(DistributionCommand command);

    /**
     * A fetched payload with the source it came from and its parsed {@code sharedTime}, when that
     * parses as an instant.
     *
     * @param payload    the hearing payload tree
     * @param source     where the payload was read from
     * @param sharedTime the payload's {@code sharedTime}, when it parsed
     */
    record FetchedPayload(JsonNode payload, PayloadSource source, Optional<Instant> sharedTime) {

        /** Rejects null components. */
        public FetchedPayload {
            Objects.requireNonNull(payload, "payload");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(sharedTime, "sharedTime");
        }
    }
}
