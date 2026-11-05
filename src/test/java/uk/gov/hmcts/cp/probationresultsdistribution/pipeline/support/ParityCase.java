package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * One corpus case loaded from {@code src/test/resources/corpus/&lt;caseId&gt;/} for the parity
 * harness (data-model.md {@code CorpusCase}; FR-001-017, FR-001-018). It holds the payload in each
 * form it carries and, for each (form, switch state), the oracle's expectation.
 *
 * <p>The expectation is the legacy's own recorded output (FR-001-019, FR-001-022): a body, no
 * message (with the kind the oracle recorded), or — where this spec sets none — no expectation, with
 * its reason (FR-001-019 … FR-001-021). The harness compares the port's {@code redact(...)} result
 * against it; a case-state with no expectation is listed and never counted as a pass.
 *
 * <p>Loading never prints a corpus value: the payloads and expectation bodies stay in the returned
 * {@link JsonNode} trees and are compared value-free by {@link ParityAssertions} (FR-001-031).
 */
public record ParityCase(
        String caseId,
        String environment,
        Set<Form> formsHeld,
        Map<Form, JsonNode> inputs,
        Map<Form, Map<EffectiveSwitchState, Expectation>> expectations) {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();
    private static final String META = "meta.json";
    private static final String INDEX = "index.json";

    /** A payload form the corpus can hold for a share (data-model.md). */
    public enum Form {
        /** The cache form: the {@code EXT_} value the legacy read from Redis. */
        CACHE,
        /** The query-API fallback form. */
        FALLBACK
    }

    /** The kind of a no-message expectation, as the oracle recorded it (data-model.md). */
    public enum NoMessageKind {
        /** R1 emptied the payload (SJP, box or virtual-box hearing). */
        EMPTIED_BY_R1,
        /** R11 emptied the payload (no prosecution case and no court application left). */
        EMPTIED_BY_R11,
        /** A rule threw on the payload's shape (a carried missing guard, P19). */
        RULES_FAILURE,
        /** The source shows no message but not which kind. */
        UNKNOWN
    }

    /**
     * A case-state's expectation: a body, no message, or no expectation at all (data-model.md).
     * The {@code source} on the first two is one of {@code EXECUTED_LEGACY}, {@code SIT_LOG} or
     * {@code DERIVED:&lt;dvId&gt;} (FR-001-018).
     */
    public sealed interface Expectation
            permits Expectation.ExpectedBody, Expectation.NoMessage, Expectation.NoneExpectation {

        /**
         * The oracle's recorded body to compare against (FR-001-019, FR-001-022).
         *
         * @param body   the recorded body
         * @param source the expectation's source
         */
        record ExpectedBody(JsonNode body, String source) implements Expectation {
        }

        /**
         * The oracle recorded no message for this case-state (FR-001-018).
         *
         * @param kind   the no-message kind
         * @param ruleId the failing rule where the kind is a rules failure and the source shows it,
         *               otherwise {@code null}
         * @param source the expectation's source
         */
        record NoMessage(NoMessageKind kind, String ruleId, String source) implements Expectation {
        }

        /**
         * This spec sets no expectation for this case-state (FR-001-019 … FR-001-021).
         *
         * @param reason why there is no expectation
         */
        record NoneExpectation(String reason) implements Expectation {
        }
    }

    /**
     * The expectation for one case-state, or {@code null} when the case does not hold that
     * (form, state).
     *
     * @param form        the payload form
     * @param switchState the effective switch state
     * @return the expectation, or {@code null}
     */
    public Expectation expectationFor(final Form form, final EffectiveSwitchState switchState) {
        final Map<EffectiveSwitchState, Expectation> perState = expectations.get(form);
        return perState == null ? null : perState.get(switchState);
    }

    /**
     * Loads every corpus case under a corpus root (its {@code index.json} and one sub-directory per
     * case). Reads each case's {@code meta.json}, the payload for each form it holds, and the
     * expectation for each (form, switch state).
     *
     * @param corpusRoot the corpus directory
     * @return the cases found, in directory-name order
     */
    public static List<ParityCase> loadAll(final Path corpusRoot) {
        if (!Files.isDirectory(corpusRoot)) {
            return List.of();
        }
        final List<ParityCase> cases = new ArrayList<>();
        for (final Path entry : caseDirectories(corpusRoot)) {
            cases.add(loadCase(entry));
        }
        return List.copyOf(cases);
    }

    private static ParityCase loadCase(final Path caseDir) {
        final JsonNode meta = readTree(caseDir.resolve(META));
        final String caseId = meta.get("caseId").stringValue();
        final String environment = meta.get("environment").stringValue();

        final Set<Form> formsHeld = new LinkedHashSet<>();
        for (final JsonNode form : meta.get("formsHeld")) {
            formsHeld.add(Form.valueOf(form.stringValue()));
        }

        final Map<Form, JsonNode> inputs = new EnumMap<>(Form.class);
        for (final Form form : formsHeld) {
            inputs.put(form, readTree(caseDir.resolve("inputs").resolve(inputFile(form))));
        }

        final Map<Form, Map<EffectiveSwitchState, Expectation>> expectations =
                readExpectations(caseDir, meta.get("expectations"), formsHeld);

        return new ParityCase(
                caseId, environment, Set.copyOf(formsHeld), Map.copyOf(inputs), expectations);
    }

    private static Map<Form, Map<EffectiveSwitchState, Expectation>> readExpectations(
            final Path caseDir, final JsonNode expectationsNode, final Set<Form> formsHeld) {

        final Map<Form, Map<EffectiveSwitchState, Expectation>> byForm = new EnumMap<>(Form.class);
        formsHeld.forEach(form -> byForm.put(form, new EnumMap<>(EffectiveSwitchState.class)));
        if (expectationsNode == null) {
            return byForm;
        }
        for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
            final JsonNode perState = expectationsNode.get(state.name());
            if (perState == null) {
                continue;
            }
            for (final Form form : formsHeld) {
                final JsonNode entry = perState.get(form.name());
                if (entry != null) {
                    byForm.get(form).put(state, readExpectation(caseDir, entry));
                }
            }
        }
        return byForm;
    }

    private static Expectation readExpectation(final Path caseDir, final JsonNode entry) {
        final String kind = entry.get("kind").stringValue();
        return switch (kind) {
            case "BODY" -> new Expectation.ExpectedBody(
                    readTree(caseDir.resolve(entry.get("bodyFile").stringValue())),
                    entry.get("source").stringValue());
            case "NO_MESSAGE" -> new Expectation.NoMessage(
                    NoMessageKind.valueOf(entry.get("noMessageKind").stringValue()),
                    ruleId(entry),
                    entry.get("source").stringValue());
            case "NONE" -> new Expectation.NoneExpectation(entry.get("reason").stringValue());
            default -> throw new IllegalStateException("unknown expectation kind: " + kind);
        };
    }

    private static String ruleId(final JsonNode entry) {
        final JsonNode ruleId = entry.get("ruleId");
        return ruleId == null || ruleId.isNull() ? null : ruleId.stringValue();
    }

    private static String inputFile(final Form form) {
        return form == Form.CACHE ? "cache.json" : "fallback.json";
    }

    private static JsonNode readTree(final Path file) {
        try {
            return MAPPER.readTree(Files.readAllBytes(file));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> caseDirectories(final Path corpusRoot) {
        try (Stream<Path> entries = Files.list(corpusRoot)) {
            return entries
                    .filter(Files::isDirectory)
                    .filter(dir -> !INDEX.equals(dir.getFileName().toString()))
                    .filter(dir -> Files.isRegularFile(dir.resolve(META)))
                    .sorted()
                    .toList();
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
