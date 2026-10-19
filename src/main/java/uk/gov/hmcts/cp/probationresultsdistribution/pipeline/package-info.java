/**
 * The HMPPS redaction, R0-R12, as a pure function (design §4.4 {@code pipeline.HmppsRedaction},
 * §5.1).
 *
 * <p>The legacy rules, ported verbatim and applied in the legacy's order, with the youth-switch
 * decision as one of the inputs: R3 and R4 run only when it is {@code ACTIVE} (design H7). The
 * function deep-copies its input and never mutates it; an output of {@code {}} is
 * {@code NOT_APPLICABLE} with a bounded reason ({@code sjp-or-box-hearing},
 * {@code nothing-left-after-redaction}). The JSON and JavaScript-truthiness shims the port needs
 * to keep the legacy's semantics live here too. There is no VEP projection (design H5).
 *
 * <p><strong>Dependency direction.</strong> Pure: no I/O, no clock, no randomness, no logging of
 * payload content, and no Spring. It may depend on {@code domain} and on the JSON tree model, and
 * on nothing else in the service. The application layer calls it; it never calls back. Its
 * behaviour is locked by the corpus harness (design §6), which is only possible because the same
 * input always yields the same output.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;
