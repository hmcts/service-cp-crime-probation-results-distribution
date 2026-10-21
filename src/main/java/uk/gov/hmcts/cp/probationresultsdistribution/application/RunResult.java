package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;

/**
 * What a run produced, for the message listener to settle on (design §4.5). It carries the run
 * origin captured at admission, the leg's status and reason, and whether the guarded outcome write
 * took effect; a write that matched no row is a stale-runner rejection the listener must not settle
 * as success.
 *
 * @param runOrigin the run origin
 * @param status    the leg status the run ended on
 * @param reason    the bounded reason, empty on {@code SENT}
 * @param recorded  whether the outcome write took effect
 */
public record RunResult(
        AttemptOrigin runOrigin,
        LegStatus status,
        Optional<LegReason> reason,
        boolean recorded) {

    /** Rejects null components. */
    public RunResult {
        Objects.requireNonNull(runOrigin, "runOrigin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
    }
}
