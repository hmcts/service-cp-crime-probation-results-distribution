package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

/** The two kinds of legacy telemetry line alert 11 and the daily reconciliation both watch for. */
public enum LegacyTelemetryLineKind {
    /** The legacy function app sent a result to HMPPS. */
    HMPPS_SEND,
    /** The legacy function app started its orchestration for a hearing. */
    ORCHESTRATION_START
}
