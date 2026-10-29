package uk.gov.hmcts.cp.probationresultsdistribution.application;

import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;

/**
 * The youth-switch port (design H7). The core reads the {@code EMYouthFilter} permission only through
 * this port, once per message; the users-groups adapter implements it with the legacy retry rule.
 * The port returns the three-valued pre-default {@link YouthSwitchDecision}; applying the configured
 * default is the leg's job, not the adapter's.
 */
public interface YouthFilterSource {

    /**
     * Reads the youth switch for a message.
     *
     * @param command the admitted command
     * @return the three-valued decision, {@code Unreadable} carrying its cause when it could not be
     *     read
     */
    YouthSwitchDecision read(DistributionCommand command);
}
