/**
 * The use cases, and the ports they are written against (design §4.4, §4.5).
 *
 * <p>{@code DistributionRunner} orchestrates one message: admit through the
 * {@code IdempotencyGuard} and its claim, fetch the payload, then run the HMPPS leg (switch,
 * redaction, outbound contract validation, send) and settle the request from the leg's result.
 * {@code LegResender} is the resend-leg capability, {@code SubmitHearingAction} and
 * {@code RequestIdRecipe} the submit-hearing repair (design §4.8 action 5), and
 * {@code OutboundContractValidator} the gate that refuses a schema-invalid body before the POST
 * (design §4.4, §6.6).
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
