package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

/**
 * What FR-006-008's five pre-run checks read, supplied by the caller — the owning team's own
 * connection to STE and its pod, never built or read by this pure logic (research.md Decision 2).
 *
 * @param steEmYouthFilterActive        check (a): STE's {@code EMYouthFilter} permission read
 * @param configuredDefaultActive       check (b): STE's {@code
 *                                       distribution.youth-switch.default-when-unreadable}
 * @param switchReadableNotDefaulted    check (c): the switch read from the service's own pod,
 *                                       under its own system user, succeeded and was not defaulted
 * @param hmppsTargetIsStubAnswering200NoForward check (d): the service's HMPPS target in STE is
 *                                       the stub gateway, answering 200 and forwarding nothing
 * @param stubReachedOverHttps          check (e): the stub is reached over https
 */
public record PreRunContext(boolean steEmYouthFilterActive, boolean configuredDefaultActive,
                            boolean switchReadableNotDefaulted,
                            boolean hmppsTargetIsStubAnswering200NoForward,
                            boolean stubReachedOverHttps) {
}
