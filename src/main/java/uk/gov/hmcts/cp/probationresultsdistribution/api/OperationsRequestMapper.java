package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;

/**
 * Parses and bound-checks every operations-API argument (FR-005-003, FR-005-010, FR-005-011,
 * FR-005-016, FR-005-033): a {@code UUID} for a {@code requestId}, the leg vocabulary for
 * {@code leg}, bounded text for {@code operator}/{@code reason}. Refuses the whole call before any
 * downstream call; holds no eligibility rule of its own — collapsing duplicates, the empty-list
 * refusal and the cap are slice 004's (FR-004-019), applied once the parsed list reaches it.
 */
public final class OperationsRequestMapper {

    private static final int MAX_TEXT_LENGTH = 200;
    private static final String REQUEST_ID = "requestId";
    private static final String REQUEST_IDS = "requestIds";
    private static final String LEG = "leg";
    private static final String OPERATOR = "operator";
    private static final String REASON = "reason";

    /**
     * Parses a {@code requestId} path variable.
     *
     * @param raw the raw path variable
     * @return the canonical {@link UUID}
     * @throws OperationsRefusalException {@code unreadable-argument} naming {@code requestId} when
     *                                     {@code raw} is not a UUID
     */
    public UUID parseRequestId(final String raw) {
        return parseUuid(raw, REQUEST_ID);
    }

    /**
     * Refuses a replay body carrying any field at all (FR-005-009, FR-005-033): replay takes no
     * {@code operator} or {@code reason}.
     *
     * @param body the deserialised body, or {@code null}/empty when none was sent
     * @throws OperationsRefusalException {@code unknown-field} naming the first field present
     */
    public void validateReplayBody(final Map<String, Object> body) {
        if (body != null && !body.isEmpty()) {
            final String firstField = body.keySet().iterator().next();
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.UNKNOWN_FIELD, firstField);
        }
    }

    /**
     * Parses and bound-checks a resend-leg request.
     *
     * @param raw the request as sent
     * @return the parsed command, list order and duplicates preserved
     * @throws OperationsRefusalException the first whole-call refusal found: {@code unknown-field}
     *                                     naming the first field the contract does not name, then
     *                                     each argument's own refusal
     */
    public ParsedResendLegCommand parseResendLeg(final ResendLegRequest raw) {
        if (!raw.unknownFields().isEmpty()) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.UNKNOWN_FIELD,
                    raw.unknownFields().getFirst());
        }
        final List<UUID> requestIds = parseRequestIds(raw.requestIds());
        final Leg leg = parseLeg(raw.leg());
        final String operator = requireBoundedText(raw.operator(), OPERATOR);
        final String reason = requireBoundedText(raw.reason(), REASON);
        return new ParsedResendLegCommand(requestIds, leg, operator, reason, raw.allowTerminalSuccess(),
                raw.allowChangedPayload());
    }

    /**
     * Hands a parsed resend-leg command to slice 004's resend-leg capability as its own input
     * (FR-005-013): the list exactly as sent, duplicates and order included, the operator, the
     * reason and both overrides unchanged. {@code leg} is not carried: {@link Leg#HMPPS} is its only
     * value and 004's input names no leg.
     *
     * @param parsed the parsed command
     * @return slice 004's {@link ResendRequest}
     */
    public ResendRequest toResendRequest(final ParsedResendLegCommand parsed) {
        return new ResendRequest(parsed.requestIds(), parsed.operator(), parsed.reason(),
                parsed.allowChangedPayload(), parsed.allowTerminalSuccess());
    }

    private List<UUID> parseRequestIds(final List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.MISSING_ARGUMENT,
                    REQUEST_IDS);
        }
        final List<UUID> parsed = new ArrayList<>(raw.size());
        for (final String entry : raw) {
            parsed.add(parseUuid(entry, REQUEST_IDS));
        }
        return List.copyOf(parsed);
    }

    private UUID parseUuid(final String raw, final String argumentName) {
        if (raw == null) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.UNREADABLE_ARGUMENT,
                    argumentName);
        }
        try {
            return UUID.fromString(raw);
        } catch (final IllegalArgumentException notAUuid) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.UNREADABLE_ARGUMENT,
                    argumentName);
        }
    }

    private Leg parseLeg(final String raw) {
        if (raw == null || raw.isBlank()) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.MISSING_ARGUMENT, LEG);
        }
        try {
            return Leg.fromValue(raw);
        } catch (final IllegalArgumentException notHmpps) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.UNREADABLE_ARGUMENT, LEG);
        }
    }

    private String requireBoundedText(final String raw, final String argumentName) {
        if (raw == null || raw.isBlank()) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.MISSING_ARGUMENT,
                    argumentName);
        }
        if (raw.length() > MAX_TEXT_LENGTH) {
            throw OperationsRefusalException.ofArgument(OperationsRefusalReason.ARGUMENT_TOO_LONG,
                    argumentName);
        }
        return raw;
    }
}
