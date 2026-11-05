/**
 * The golden-parity comparator (001-parity-kernel) and the one-week SIT &rarr; STE replay
 * comparator (006-production-readiness): both read two JSON trees and typed records and produce
 * a typed, value-free result, over the same {@link
 * uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.JsonParity} and {@link
 * uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.RegisteredFieldDeviations}
 * (constitution Principle I's own comparator and deviation register).
 *
 * <p><strong>Dependency direction.</strong> Pure: no I/O, no clock, no randomness, no Spring. May
 * depend on the JSON tree model and on nothing else in the service; nothing in the service depends
 * on it except the test harnesses that invoke it and the owning team's own replay tooling.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;
