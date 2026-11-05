package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CanonicalJsonMapper;

/**
 * The redaction is a pure function (FR-001-001; SC-001-012): it leaves its input unchanged, writes
 * no log line (DV-09 — R12, which logs the payload, is not ported), and returns an equal result when
 * called again with the same input. This is the pinning test named for DV-09 in
 * {@code doc/DEVIATIONS.md}: it would fail if R12 were ported.
 */
@DisplayName("HmppsRedaction — purity (DV-09)")
class HmppsRedactionPurityTest {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();

    private static final String[] FIXTURES = {
        "hearing.sjp.cases.resulted.in.sjp.session.json",
        "box.hearing.json",
        "hearing.prosection.case.with.laa.reference.json",
        "hearing.only.court.application.json",
        "hearing.multiple.court.applications.courtorder.json",
        "hearing.prosection.case.with.youth.defendant.json",
        "hearing.prosection.case.with.all.youth.defendant.json",
        "hearing.single.prosection.case.with.all.youth.defendant.json",
        "hearing.only.court.application.with.youth.defendant.json",
    };

    private final HmppsRedaction redaction = new HmppsRedaction();

    private Logger rootLogger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachLogCapture() {
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        rootLogger.detachAppender(appender);
    }

    @Test
    @DisplayName("leaves the caller's payload unchanged")
    void redact_anyFixture_should_not_mutate_its_input() {
        for (final String name : FIXTURES) {
            for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
                final JsonNode input = fixture(name);
                final JsonNode snapshot = input.deepCopy();

                redaction.redact(input, state);

                assertThat(input)
                        .as("%s under %s must be left unchanged", name, state)
                        .isEqualTo(snapshot);
            }
        }
    }

    @Test
    @DisplayName("writes no log line")
    void redact_anyFixture_should_write_no_log_line() {
        for (final String name : FIXTURES) {
            for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
                redaction.redact(fixture(name), state);
            }
        }

        assertThat(appender.list)
                .as("the redaction must write no log line (DV-09, R12 not ported)")
                .isEmpty();
    }

    @Test
    @DisplayName("returns an equal result when called again")
    void redact_calledTwice_should_return_an_equal_result() {
        for (final String name : FIXTURES) {
            for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
                final RedactionResult first = redaction.redact(fixture(name), state);
                final RedactionResult second = redaction.redact(fixture(name), state);

                assertThat(first)
                        .as("%s under %s must redact deterministically", name, state)
                        .isEqualTo(second);
            }
        }
    }

    private static JsonNode fixture(final String name) {
        try (InputStream in =
                     HmppsRedactionPurityTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) {
                throw new IllegalStateException("fixture not on the test classpath: " + name);
            }
            return MAPPER.readTree(in);
        } catch (final IOException e) {
            throw new UncheckedIOException("could not read fixture " + name, e);
        }
    }
}
