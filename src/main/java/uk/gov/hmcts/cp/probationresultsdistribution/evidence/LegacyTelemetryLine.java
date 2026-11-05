package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the legacy function app's own telemetry: an HMPPS send or an orchestration-start
 * trace (slice 006, FR-006-020). Carries nothing from the hearing itself — a timestamp and a kind
 * only.
 *
 * @param timestamp when the legacy app logged it
 * @param kind      {@link LegacyTelemetryLineKind#HMPPS_SEND} or
 *                  {@link LegacyTelemetryLineKind#ORCHESTRATION_START}
 */
public record LegacyTelemetryLine(Instant timestamp, LegacyTelemetryLineKind kind) {

    /** Rejects null components. */
    public LegacyTelemetryLine {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(kind, "kind");
    }
}
