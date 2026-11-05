package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * A request claim: the owner that took the lease and the fencing token that guards every write the
 * run makes (design §4.4 claim contract). A later delivery that reclaims an expired lease gets a
 * fresh pair, so a stale runner's token no longer matches and its writes update no rows.
 *
 * @param owner the claimant
 * @param token the fencing token
 */
public record RunClaim(UUID owner, UUID token) {

    /** Rejects null components. */
    public RunClaim {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(token, "token");
    }

    /**
     * Mints a fresh claim with a random owner and token.
     *
     * @return a new claim
     */
    public static RunClaim fresh() {
        return new RunClaim(UUID.randomUUID(), UUID.randomUUID());
    }
}
