/**
 * The use cases, and the ports they are written against (design §4.4, §4.5).
 *
 * <p>{@code DistributionRunner} orchestrates one message: admit through the
 * {@code IdempotencyGuard} and its claim, fetch the payload, then run the HMPPS leg (switch,
 * redaction, body digest, send) and settle the request from the leg's result. There is no outbound
 * schema validation in this release (design H13): {@code OutboundContractValidator} is a parked
 * design and is not built. The leg's outcome is written with its {@code body_digest} and
 * {@code service_version}, and then the exact bytes POSTed, in the same transaction (design
 * H18-H20). The queue has no broker duplicate detection (design H14), so the guard is the only
 * duplicate protection and every copy of a message reaches it; it admits a fresh copy of a
 * {@code FAILED} request under its exhausted {@code messageId}, at broker delivery count 1, as a
 * replay (DV-26).
 *
 * <p>{@code LegResender} is the resend-leg capability. It takes an explicit list of
 * {@code requestId}s and checks and reports each one on its own: an ineligible one is refused with
 * a bounded code ({@code unknown-request}, {@code not-eligible}, {@code resend-in-progress} or
 * {@code request-claimed}) while the rest proceed (design H16). Directly after the payload fetch,
 * before any leg stage, it compares the payload's {@code sharedTime} with the request's stored
 * one, as instants (design H17). Its forced-resend override sends a leg already {@code SENT} or
 * {@code NOT_APPLICABLE} again, with operator and reason, as a deliberate duplicate (design H9).
 * {@code SubmitHearingAction} and {@code RequestIdRecipe} are the submit-hearing repair (design
 * §4.8 action 5).
 *
 * <p>The ports are declared here, as interfaces, by the side that uses them:
 * {@code HearingPayloadSource}, {@code YouthFilterSource}, {@code HmppsSender},
 * {@code OutboundLog} and the request store the guard settles through.
 *
 * <p><strong>Dependency direction.</strong> This package depends on {@code domain},
 * {@code pipeline} and its own port interfaces only. It never imports from {@code inbound},
 * {@code api}, {@code adapter}, {@code persistence} or {@code config}: an adapter is reached only
 * through the port it implements, and the concrete choice is made in {@code config}. That is what
 * lets the stub sources stand in for the live ones in the local and test profiles.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.application;
