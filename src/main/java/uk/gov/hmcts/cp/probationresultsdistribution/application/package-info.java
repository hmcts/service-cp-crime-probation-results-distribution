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
 * <p>{@code LegResender} is the resend-leg capability, in two steps: an accept step that applies
 * every whole-run check and assigns the run id, claiming nothing, and a run step that takes the
 * accepted ids in turn and stops claiming on a plain stop signal as on a pause; {@code resend} is
 * the two in turn. It takes an explicit list of
 * {@code requestId}s and checks and reports each one on its own: an ineligible one is refused with
 * a bounded code ({@code unknown-request}, {@code not-eligible}, {@code resend-in-progress} or
 * {@code request-claimed}) while the rest proceed (design H16). Directly after the payload fetch,
 * before any leg stage, it compares the payload's {@code sharedTime} with the request's stored
 * one, as instants (design H17). Its forced-resend override ({@code allowTerminalSuccess}) sends a
 * leg already {@code SENT} or {@code NOT_APPLICABLE} again, with operator and reason, as a
 * deliberate duplicate on a new resend attempt labelled {@code forced}, leaving the request
 * {@code COMPLETED} (design H9); without it such a leg is {@code not-eligible}.
 * {@code SubmitHearingAction} and {@code RequestIdRecipe} are the submit-hearing repair (design
 * §4.8 action 5). It builds the six-field command body as a Jackson {@code ObjectNode} and passes
 * its text to the {@code CommandEnqueuer} port: an accepted, one-off use of the {@code JsonNode}
 * exception, needing no {@code ObjectMapper}, pinned byte for byte by
 * {@code PackagedSubmitHearingCommandIT}.
 *
 * <p>The outbound ports are declared here, as interfaces, by the side that uses them:
 * {@code HearingPayloadSource}, {@code YouthFilterSource}, {@code HmppsSender} and
 * {@code OutboundLog}; slice 004 adds the broker ports {@code DeadLetterReader} (peek only) and
 * {@code CommandEnqueuer} (send only). The processed-log store the guard claims through
 * ({@code persistence.ProcessedRequestRepository}) is an infra-free interface declared in
 * {@code persistence} with the rest of the store, per {@code design_rules.md}'s package tree, and
 * carries only {@code domain} types; the guard depends on it from here. The claim and outcome
 * shapes those store interfaces carry ({@code RunClaim}, {@code LegOutcome}) live in {@code domain},
 * not here, precisely so that {@code persistence} depends on {@code domain} only and never back on
 * this package.
 *
 * <p><strong>Dependency direction.</strong> This package depends on {@code domain},
 * {@code pipeline}, its own outbound port interfaces and the persistence-declared
 * {@code ProcessedRequestRepository} port the guard admits through. It never imports from
 * {@code inbound}, {@code api}, {@code adapter} or {@code config}, and from {@code persistence}
 * only its infra-free store interfaces ({@code ProcessedRequestRepository} and the slice 004
 * {@code IntakeControlRepository}, {@code RollbackFencingRepository} and
 * {@code RequestListingRepository}; never a JDBC adapter): an adapter is reached only through the port
 * it implements, and the concrete choice is made in {@code config}. That is what lets the stub
 * sources stand in for the live ones in the local and test profiles. The dependency is one
 * direction only: {@code application} → {@code persistence} (those interfaces) and
 * {@code application} → {@code domain}; nothing in {@code persistence} imports from
 * {@code application}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.application;
