package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The one token-stream reader every CLI runner's flag parsing shares (FR-004-020; PR #29 review):
 * {@code args[1..]} — everything after the leading action name — is walked once against a declared
 * set of value flags (each usable at most once, each requiring a value that is not itself another
 * flag) and boolean flags (each usable at most once, taking no value). Anything the declared shape
 * cannot explain — an unrecognised flag, a flag given twice, a stray non-flag token, or a value flag
 * followed by nothing or by another flag-shaped token — refuses the whole run rather than reading a
 * neighbouring flag's name as a value or silently dropping a trailing token, as the previous
 * per-runner {@code valueOf} methods each did independently.
 *
 * <p>A declared value flag simply absent from the stream reads as {@link #value(String)} returning
 * empty; whether that absence itself refuses the run (as a required flag) is each runner's own
 * check, not this class's.
 */
final class ArgumentParser {

    private final Map<String, String> values;
    private final Set<String> presentFlags;

    private ArgumentParser(final Map<String, String> values, final Set<String> presentFlags) {
        this.values = values;
        this.presentFlags = presentFlags;
    }

    /**
     * Parses {@code args[1..]} against the declared flags.
     *
     * @param args         the full command-line arguments, including the leading action name
     * @param valueFlags   flags that must be followed by a value, each usable at most once
     * @param booleanFlags flags that take no value, each usable at most once
     * @return the parsed arguments, or the refusal naming why the token stream could not be read
     */
    /* default */ static Result parse(final String[] args, final Set<String> valueFlags,
                                      final Set<String> booleanFlags) {
        final Map<String, String> values = new HashMap<>();
        final Set<String> presentFlags = new HashSet<>();
        final Optional<WholeRunRefusal> refusal = read(args, valueFlags, booleanFlags, values,
                presentFlags);
        return refusal.<Result>map(Result.Refused::new)
                .orElseGet(() -> new Result.Readable(new ArgumentParser(values, presentFlags)));
    }

    // No Result is built here: only the enum refusal, so the one Result this parse ever returns is
    // instantiated once, by its caller, never inside this loop (PMD AvoidInstantiatingObjectsInLoops).
    // Each check exits as soon as it decides, so the first unreadable token names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Optional<WholeRunRefusal> read(final String[] args,
                                                   final Set<String> valueFlags,
                                                   final Set<String> booleanFlags,
                                                   final Map<String, String> values,
                                                   final Set<String> presentFlags) {
        int i = 1;
        while (i < args.length) {
            final String token = args[i];
            if (valueFlags.contains(token)) {
                final Optional<WholeRunRefusal> failure = takeValue(args, i, token, values);
                if (failure.isPresent()) {
                    return failure;
                }
                i += 2;
            } else if (booleanFlags.contains(token)) {
                if (!presentFlags.add(token)) {
                    return Optional.of(WholeRunRefusal.UNREADABLE_ARGUMENT);
                }
                i += 1;
            } else {
                return Optional.of(WholeRunRefusal.UNREADABLE_ARGUMENT);
            }
        }
        return Optional.empty();
    }

    private static Optional<WholeRunRefusal> takeValue(final String[] args, final int i,
                                                        final String flag,
                                                        final Map<String, String> values) {
        final Optional<WholeRunRefusal> failure;
        if (values.containsKey(flag)) {
            failure = Optional.of(WholeRunRefusal.UNREADABLE_ARGUMENT);
        } else {
            final boolean hasNext = i + 1 < args.length;
            final boolean nextLooksLikeAFlag = hasNext && args[i + 1].startsWith("--");
            if (!hasNext || nextLooksLikeAFlag) {
                failure = Optional.of(WholeRunRefusal.MISSING_ARGUMENT);
            } else {
                values.put(flag, args[i + 1]);
                failure = Optional.empty();
            }
        }
        return failure;
    }

    /**
     * A declared value flag's value.
     *
     * @param flag the flag
     * @return the value given, or empty when the flag was not in the stream at all
     */
    /* default */ Optional<String> value(final String flag) {
        return Optional.ofNullable(values.get(flag));
    }

    /**
     * Whether a declared boolean flag was given.
     *
     * @param flag the flag
     * @return {@code true} once the flag appeared in the stream
     */
    /* default */ boolean has(final String flag) {
        return presentFlags.contains(flag);
    }

    /** Either the readable arguments, or the whole-run refusal naming why they are not. */
    /* default */ sealed interface Result {

        /** The token stream read cleanly against the declared flags. */
        record Readable(ArgumentParser arguments) implements Result {
        }

        /** The token stream could not be read as the declared shape. */
        record Refused(WholeRunRefusal refusal) implements Result {
        }
    }
}
