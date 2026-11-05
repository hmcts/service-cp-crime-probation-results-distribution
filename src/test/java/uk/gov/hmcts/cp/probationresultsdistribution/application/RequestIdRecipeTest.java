package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code RequestIdRecipe} by the vector of the results producer's own helper test (slice 004,
 * FR-004-055; DIGEST §14): the name-based UUID of {@code hearingId|hearingDay|sharedTime} over the
 * raw strings, with no build dependency on a results artefact. Re-taken when Q20 lands.
 */
@DisplayName("the requestId recipe")
class RequestIdRecipeTest {

    private static final String HEARING_ID = "0aa5bf35-1e51-45e5-9e42-64bd57e15c11";
    private static final String HEARING_DAY = "2026-08-19";
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";

    @Test
    @DisplayName("reproduces the producer's pinned vector exactly")
    void requestId_pinnedVector_should_reproduce() {
        assertThat(RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY, SHARED_TIME))
                .isEqualTo(UUID.fromString("e74ed270-0b62-36d9-bb10-12bd6baa5bea"));
    }

    @Test
    @DisplayName("is a version-3 name-based UUID")
    void requestId_should_be_name_based() {
        final UUID requestId = RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY, SHARED_TIME);

        assertThat(requestId.version()).isEqualTo(3);
        assertThat(requestId.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("takes sharedTime as the raw string: another rendering of the same instant differs")
    void requestId_otherRendering_should_differ() {
        assertThat(RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY,
                "2026-08-19T17:42:07.512+01:00"))
                .isNotEqualTo(RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY, SHARED_TIME));
    }

    @Test
    @DisplayName("depends on each of the three parts")
    void requestId_should_depend_on_each_part() {
        final UUID pinned = RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY, SHARED_TIME);

        assertThat(RequestIdRecipe.requestId(UUID.randomUUID().toString(), HEARING_DAY,
                SHARED_TIME)).isNotEqualTo(pinned);
        assertThat(RequestIdRecipe.requestId(HEARING_ID, "2026-08-20", SHARED_TIME))
                .isNotEqualTo(pinned);
        assertThat(RequestIdRecipe.requestId(HEARING_ID, HEARING_DAY,
                "2026-08-19T16:42:07.513Z")).isNotEqualTo(pinned);
    }
}
