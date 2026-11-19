package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins the shared token-stream reader every CLI runner's flag parsing now uses (FR-004-020; PR #29
 * review): the whole argument list, after the leading action name, is validated in one pass, so an
 * unknown flag, a duplicate flag, a stray trailing token, or a value flag given no real value —
 * including one immediately followed by another flag-shaped token — refuses the whole run rather
 * than silently reading the wrong thing.
 */
@DisplayName("the shared CLI argument parser")
class ArgumentParserTest {

    private static final String VALUE_FLAG_A = "--operator";
    private static final String VALUE_FLAG_B = "--reason";
    private static final String BOOLEAN_FLAG = "--force";
    private static final Set<String> VALUE_FLAGS = Set.of(VALUE_FLAG_A, VALUE_FLAG_B);
    private static final Set<String> BOOLEAN_FLAGS = Set.of(BOOLEAN_FLAG);

    @Test
    @DisplayName("reads every declared value and boolean flag given once each")
    void reads_a_well_formed_invocation() {
        final ArgumentParser.Result result = ArgumentParser.parse(
                new String[] {"cmd", VALUE_FLAG_A, "op", VALUE_FLAG_B, "why", BOOLEAN_FLAG},
                VALUE_FLAGS, BOOLEAN_FLAGS);

        assertThat(result).isInstanceOf(ArgumentParser.Result.Readable.class);
        final ArgumentParser arguments = ((ArgumentParser.Result.Readable) result).arguments();
        assertThat(arguments.value(VALUE_FLAG_A)).contains("op");
        assertThat(arguments.value(VALUE_FLAG_B)).contains("why");
        assertThat(arguments.has(BOOLEAN_FLAG)).isTrue();
    }

    @Test
    @DisplayName("a value flag absent from the stream reads as absent, not refused")
    void absent_value_flag_reads_as_empty() {
        final ArgumentParser.Result result = ArgumentParser.parse(
                new String[] {"cmd", VALUE_FLAG_A, "op"}, VALUE_FLAGS, BOOLEAN_FLAGS);

        assertThat(result).isInstanceOf(ArgumentParser.Result.Readable.class);
        final ArgumentParser arguments = ((ArgumentParser.Result.Readable) result).arguments();
        assertThat(arguments.value(VALUE_FLAG_B)).isEmpty();
        assertThat(arguments.has(BOOLEAN_FLAG)).isFalse();
    }

    @Test
    @DisplayName("a value flag at the end of the arguments, with nothing after it, is missing-argument")
    void value_flag_with_nothing_after_it() {
        assertRefused(new String[] {"cmd", VALUE_FLAG_A}, WholeRunRefusal.MISSING_ARGUMENT);
    }

    @Test
    @DisplayName("a value flag immediately followed by a recognised flag is missing-argument, not "
            + "given that flag's own name as its value")
    void value_flag_followed_by_a_recognised_flag() {
        assertRefused(new String[] {"cmd", VALUE_FLAG_A, VALUE_FLAG_B, "why"},
                WholeRunRefusal.MISSING_ARGUMENT);
    }

    @Test
    @DisplayName("a value flag immediately followed by an unrecognised flag-shaped token is "
            + "missing-argument too, not given that token as its value")
    void value_flag_followed_by_an_unknown_flag_shaped_token() {
        assertRefused(new String[] {"cmd", VALUE_FLAG_B, "--ticket", "INC-123"},
                WholeRunRefusal.MISSING_ARGUMENT);
    }

    @Test
    @DisplayName("an unrecognised flag is unreadable-argument")
    void unrecognised_flag() {
        assertRefused(new String[] {"cmd", "--bogus", "x"}, WholeRunRefusal.UNREADABLE_ARGUMENT);
    }

    @Test
    @DisplayName("a stray trailing token that is not a flag is unreadable-argument")
    void stray_trailing_token() {
        assertRefused(new String[] {"cmd", VALUE_FLAG_A, "op", "extra"},
                WholeRunRefusal.UNREADABLE_ARGUMENT);
    }

    @Test
    @DisplayName("a value flag given twice is unreadable-argument")
    void duplicate_value_flag() {
        assertRefused(
                new String[] {"cmd", VALUE_FLAG_A, "op", VALUE_FLAG_A, "op2"},
                WholeRunRefusal.UNREADABLE_ARGUMENT);
    }

    @Test
    @DisplayName("a boolean flag given twice is unreadable-argument")
    void duplicate_boolean_flag() {
        assertRefused(new String[] {"cmd", BOOLEAN_FLAG, BOOLEAN_FLAG},
                WholeRunRefusal.UNREADABLE_ARGUMENT);
    }

    private static void assertRefused(final String[] args, final WholeRunRefusal refusal) {
        final ArgumentParser.Result result = ArgumentParser.parse(args, VALUE_FLAGS, BOOLEAN_FLAGS);
        assertThat(result).isInstanceOf(ArgumentParser.Result.Refused.class);
        assertThat(((ArgumentParser.Result.Refused) result).refusal()).isEqualTo(refusal);
    }
}
