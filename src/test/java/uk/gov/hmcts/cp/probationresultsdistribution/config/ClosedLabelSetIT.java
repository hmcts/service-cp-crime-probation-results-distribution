package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WireValued;

@DisplayName("closed metric labels")
class ClosedLabelSetIT {

    @Test
    @DisplayName("emits only vocabulary values and bounded fixed labels")
    void all_emitted_labels_are_closed_and_bounded() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final MetricRegistry metrics = new MetricRegistry(meters);
        for (final Leg leg : Leg.values()) {
            for (final LegStatus status : LegStatus.values()) {
                for (final AttemptOrigin origin : AttemptOrigin.values()) {
                    for (final BodyShape shape : BodyShape.values()) {
                        for (final HttpStatusBucket http : HttpStatusBucket.values()) {
                            metrics.legOutcome(leg, status, Optional.empty(), origin, false, shape, http);
                        }
                    }
                }
            }
        }
        for (final LegReason reason : LegReason.values()) {
            metrics.parked(Leg.HMPPS, reason);
        }
        for (final DeadLetterReason reason : DeadLetterReason.values()) {
            metrics.deadLettered(reason);
        }
        metrics.processed("COMPLETED");
        metrics.processed("FAILED");

        final Set<String> allowed = vocabularyValues();
        allowed.addAll(Set.of("true", "false", "none", "database", "COMPLETED", "FAILED"));
        final Set<String> emitted = meters.getMeters().stream().flatMap(meter -> meter.getId().getTags()
                .stream().map(Tag::getValue)).collect(java.util.stream.Collectors.toSet());
        assertThat(emitted).isNotEmpty().allMatch(allowed::contains);
        assertThat(emitted).noneMatch(value -> value.matches(".*[0-9a-f]{8}-[0-9a-f-]{27,}.*")
                || value.contains("user") || value.contains(" "));
    }

    private static Set<String> vocabularyValues() {
        final Set<String> values = new HashSet<>();
        final Class<?>[] vocabularies = {AttemptOrigin.class, BodyShape.class, DeadLetterReason.class,
            HttpStatusBucket.class, Leg.class, LegReason.class, LegStatus.class};
        for (final Class<?> vocabulary : vocabularies) {
            Arrays.stream(vocabulary.getEnumConstants()).map(WireValued.class::cast)
                    .map(WireValued::wireValue).forEach(values::add);
        }
        return values;
    }
}
