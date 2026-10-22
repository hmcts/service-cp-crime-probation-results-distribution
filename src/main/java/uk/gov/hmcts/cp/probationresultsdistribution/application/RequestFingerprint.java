package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * The request fingerprint: a SHA-256 over the four immutable identity fields —
 * {@code hearingId}, {@code hearingDay}, {@code sharedTime} and {@code eventType} — in a fixed
 * canonical form. {@code userId} is excluded (FR-002-005). A mismatch on a known key is a collision.
 */
public final class RequestFingerprint {

    /**
     * Computes the fingerprint of a command.
     *
     * @param command the admitted command
     * @return the lower-case hex SHA-256 over the four canonical identity fields
     */
    public String compute(final DistributionCommand command) {
        final String canonical = command.hearingId()
                + "|" + command.hearingDay()
                + "|" + command.sharedTime()
                + "|" + command.eventType();
        return HexFormat.of().formatHex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(final byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
