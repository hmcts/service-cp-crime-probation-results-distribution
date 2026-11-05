package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.util.Set;

/**
 * The MDC field names every processing log line carries (design §10.2, FR-002-063). The listener
 * sets them per message and clears them in a finally. {@code userId} is deliberately absent: it is
 * never put in the MDC or logged (FR-002-065). {@code logback.xml}'s {@code <mdc/>} provider emits
 * exactly whatever is in the MDC, so this set is the single definition of what may appear.
 */
public final class MdcFields {

    /** The request identity. */
    public static final String REQUEST_ID = "requestId";
    /** The hearing identity. */
    public static final String HEARING_ID = "hearingId";
    /** The hearing day. */
    public static final String HEARING_DAY = "hearingDay";
    /** The command source. */
    public static final String SOURCE = "source";
    /** The broker sequence number. */
    public static final String SEQUENCE_NUMBER = "sequenceNumber";
    /** The broker delivery count. */
    public static final String DELIVERY_COUNT = "deliveryCount";
    /** The leg. */
    public static final String LEG = "leg";

    /** The complete, closed set of MDC keys; {@code userId} is not among them. */
    public static final Set<String> KEYS = Set.of(
            REQUEST_ID, HEARING_ID, HEARING_DAY, SOURCE, SEQUENCE_NUMBER, DELIVERY_COUNT, LEG);

    private MdcFields() {
    }
}
