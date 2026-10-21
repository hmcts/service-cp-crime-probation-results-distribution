package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The custody gate over a corpus directory: every file must belong to a case, be pinned by a SHA-256
 * digest and sit beside complete provenance, and nothing but SIT or synthetic-from-SIT data may be
 * present (FR-001-029, FR-001-030, FR-001-032; spec user story 3). It must hold before any SIT case
 * is committed, so it is built and tested with zero cases committed.
 *
 * <p>Every message it returns names only a file name, a case id or a field name — never a byte of
 * corpus content, and never a Jackson parse-error snippet, which would carry corpus content into a
 * public CI log (FR-001-031). An empty list is a clean corpus.
 *
 * <p>A corpus directory holds its {@code index.json} at the top level and one sub-directory per case,
 * each with a {@code meta.json} that pins every sibling file's digest and records the case's
 * provenance. {@code index.json} itself MUST be present, parse as JSON and carry the required
 * {@code CorpusMetadata} fields (data-model.md), and its {@code cases[]} list and counts
 * ({@code hearingCount}, {@code syntheticCount}) are reconciled against the cases actually committed
 * (FR-001-029) — a missing index, a false count or a case id drift between the index and the
 * directories is a custody violation, not a silent gap. {@code index.json} is checked for structure
 * only: unlike a case's own files, it is <strong>not</strong> digest-pinned.
 */
public final class CorpusCustodyChecker {

    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();
    private static final String META = "meta.json";
    private static final String INDEX = "index.json";
    private static final String BELONGS_TO_NO_CASE = " belongs to no case";
    private static final String SIT = "SIT";
    private static final String SYNTHETIC = "SYNTHETIC";
    private static final String EXECUTED_LEGACY = "EXECUTED_LEGACY";
    private static final String PROVENANCE_PREFIX = "provenance.";
    private static final List<String> VALID_FORMS = List.of("CACHE", "FALLBACK");

    private CorpusCustodyChecker() {
    }

    /**
     * Reports every custody violation under a corpus root.
     *
     * @param corpusRoot the directory that holds the corpus (its cases and its {@code index.json})
     * @return the violations found, each naming a file, case id or field and no corpus value; empty
     *     when the corpus is clean
     */
    public static List<String> findViolations(final Path corpusRoot) {
        final List<String> violations = new ArrayList<>();
        if (!Files.isDirectory(corpusRoot)) {
            violations.add("corpus root is not a directory");
            return List.copyOf(violations);
        }
        final List<CaseSummary> knownCases = new ArrayList<>();
        for (final Path entry : listDirectly(corpusRoot)) {
            final String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                final CaseSummary summary = checkCase(corpusRoot, entry, violations);
                if (summary != null) {
                    knownCases.add(summary);
                }
            } else if (!INDEX.equals(name)) {
                violations.add("file " + name + BELONGS_TO_NO_CASE);
            }
        }
        checkIndex(corpusRoot, knownCases, violations);
        return List.copyOf(violations);
    }

    private static CaseSummary checkCase(
            final Path corpusRoot, final Path caseDir, final List<String> violations) {

        final String dirName = caseDir.getFileName().toString();
        final Path metaPath = caseDir.resolve(META);
        if (!Files.isRegularFile(metaPath)) {
            violations.add("case directory " + dirName + " has no " + META);
            for (final Path file : regularFilesUnder(caseDir)) {
                violations.add("file " + rel(corpusRoot, file) + BELONGS_TO_NO_CASE);
            }
            return null;
        }

        final JsonNode meta;
        try {
            meta = MAPPER.readTree(Files.readAllBytes(metaPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException e) {
            violations.add("case directory " + dirName + ": " + META + " is not valid JSON");
            for (final Path file : regularFilesUnder(caseDir)) {
                if (!META.equals(rel(caseDir, file))) {
                    violations.add("file " + rel(corpusRoot, file) + BELONGS_TO_NO_CASE);
                }
            }
            return null;
        }

        final String caseId = text(meta, "caseId", dirName);
        if (present(meta.get("caseId"))) {
            if (!caseId.equals(dirName)) {
                violations.add("case directory " + dirName
                        + ": meta.json caseId does not match the directory name");
            }
        } else {
            violations.add(missing(dirName, "caseId"));
        }
        checkProvenance(meta, caseId, violations);
        checkDigests(caseDir, caseId, meta.get("digests"), violations);
        return new CaseSummary(
                caseId,
                text(meta, "environment", ""),
                hasExpectation(meta, "ACTIVE"),
                hasExpectation(meta, "INACTIVE"));
    }

    /** A case's id and declared environment, as read from its own {@code meta.json} (not the index). */
    private record CaseSummary(
            String caseId, String environment, boolean activeExpectation, boolean inactiveExpectation) {
    }

    private static boolean hasExpectation(final JsonNode meta, final String state) {
        final JsonNode expectations = meta.get("expectations");
        final JsonNode byForm = expectations == null ? null : expectations.get(state);
        if (byForm == null || !byForm.isObject()) {
            return false;
        }
        for (final String form : byForm.propertyNames()) {
            final JsonNode entry = byForm.get(form);
            if (entry != null && !entry.isNull()
                    && !"NONE".equals(text(entry, "kind", ""))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Validates {@code index.json}'s presence and structure, and reconciles its case ids and counts
     * against the cases actually committed (FR-001-029). It is not digest-pinned: unlike a case's own
     * files, nothing pins {@code index.json} itself to a recorded hash.
     *
     * @param corpusRoot  the corpus directory
     * @param knownCases  the cases successfully read from disk (a case whose {@code meta.json} could
     *                    not be read is already reported by {@link #checkCase} and is excluded here)
     * @param violations  the violations list to append to
     */
    private static void checkIndex(
            final Path corpusRoot, final List<CaseSummary> knownCases, final List<String> violations) {

        final Path indexPath = corpusRoot.resolve(INDEX);
        if (!Files.isRegularFile(indexPath)) {
            violations.add(INDEX + " is missing");
            return;
        }
        final JsonNode index;
        try {
            index = MAPPER.readTree(Files.readAllBytes(indexPath));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException e) {
            violations.add(INDEX + " is not valid JSON");
            return;
        }

        requireIndexField(index, "environment", violations);
        requireIndexField(index, "captureDateRange", violations);
        requireIndexField(index, "casesPerSwitchState", violations);
        requireIndexField(index, "comparatorVectorCount", violations);
        final JsonNode excludedCases = index.get("excludedCases");
        if (present(excludedCases) && excludedCases.isArray()) {
            for (final JsonNode excluded : excludedCases) {
                if (!nonBlankString(excluded.get("caseId"))
                        || !nonBlankString(excluded.get("reason"))) {
                    violations.add(INDEX + ": an excludedCases[] entry needs caseId and reason");
                }
            }
        } else {
            violations.add(INDEX + ": missing required field 'excludedCases'");
        }
        final JsonNode casesNode = index.get("cases");
        if (!present(casesNode) || !casesNode.isArray()) {
            violations.add(INDEX + ": missing required field 'cases'");
            return;
        }

        final Map<String, String> indexed = new LinkedHashMap<>();
        for (final JsonNode entry : casesNode) {
            final JsonNode idNode = entry.get("caseId");
            if (!present(idNode) || !idNode.isString()) {
                violations.add(INDEX + ": a cases[] entry is missing caseId");
                continue;
            }
            indexed.put(idNode.stringValue(), text(entry, "environment", ""));
        }

        final Map<String, String> actual = new LinkedHashMap<>();
        for (final CaseSummary summary : knownCases) {
            actual.put(summary.caseId(), summary.environment());
        }

        for (final String caseId : actual.keySet()) {
            if (!indexed.containsKey(caseId)) {
                violations.add(INDEX + ": case " + caseId + " is not listed in cases[]");
            }
        }
        for (final String caseId : indexed.keySet()) {
            if (!actual.containsKey(caseId)) {
                violations.add(INDEX + ": cases[] lists " + caseId + ", which has no case directory");
            }
        }
        for (final Map.Entry<String, String> entry : indexed.entrySet()) {
            final String actualEnvironment = actual.get(entry.getKey());
            if (actualEnvironment != null
                    && !actualEnvironment.isBlank()
                    && !actualEnvironment.equals(entry.getValue())) {
                violations.add(INDEX + ": case " + entry.getKey()
                        + " environment does not match its " + META);
            }
        }

        requireIndexCountMatches(index, "hearingCount", actual.size(), violations);
        requireIndexCountMatches(
                index,
                "syntheticCount",
                actual.values().stream().filter(SYNTHETIC::equals).count(),
                violations);
        final JsonNode perSwitch = index.get("casesPerSwitchState");
        if (perSwitch != null && perSwitch.isObject()) {
            requireIndexCountMatches(
                    perSwitch,
                    "ACTIVE",
                    knownCases.stream().filter(CaseSummary::activeExpectation).count(),
                    violations);
            requireIndexCountMatches(
                    perSwitch,
                    "INACTIVE",
                    knownCases.stream().filter(CaseSummary::inactiveExpectation).count(),
                    violations);
        } else {
            violations.add(INDEX + ": casesPerSwitchState must be an object");
        }
    }

    private static boolean nonBlankString(final JsonNode node) {
        return node != null && node.isString() && !node.stringValue().isBlank();
    }

    private static void requireIndexField(
            final JsonNode index, final String name, final List<String> violations) {
        if (!present(index.get(name))) {
            violations.add(INDEX + ": missing required field '" + name + "'");
        }
    }

    private static void requireIndexCountMatches(
            final JsonNode index, final String field, final long expected, final List<String> violations) {
        final JsonNode value = index.get(field);
        if (!present(value) || !value.isIntegralNumber() || value.longValue() != expected) {
            violations.add(INDEX + ": " + field + " does not match the committed corpus");
        }
    }

    private static void checkProvenance(
            final JsonNode meta, final String caseId, final List<String> violations) {

        requireField(meta, "environment", caseId, violations);
        requireNonEmptyArray(meta, "formsHeld", caseId, violations);
        requireObject(meta, "digests", caseId, violations);
        final JsonNode formsHeld = meta.get("formsHeld");
        requireRecognisedForms(formsHeld, caseId, violations);

        final JsonNode provenance = meta.get("provenance");
        if (!present(provenance) || !provenance.isObject()) {
            violations.add(missing(caseId, "provenance"));
            return;
        }
        requireCompleteCaptureDates(provenance, formsHeld, caseId, violations);
        requireObject(provenance, "expectationSources", caseId, violations, PROVENANCE_PREFIX);
        if (hasExecutedLegacyExpectation(provenance.get("expectationSources"))) {
            requireField(provenance, "legacyCommit", caseId, violations, PROVENANCE_PREFIX);
            requireField(provenance, "runtime", caseId, violations, PROVENANCE_PREFIX);
        }

        final String environment = text(meta, "environment", "");
        if (SYNTHETIC.equals(environment)) {
            requireField(provenance, "syntheticFrom", caseId, violations, PROVENANCE_PREFIX);
            requireField(provenance, "change", caseId, violations, PROVENANCE_PREFIX);
        } else if (SIT.equals(environment)) {
            requireField(meta, "payloadSourceRead", caseId, violations);
        } else if (present(meta.get("environment"))) {
            violations.add("case " + caseId
                    + ": environment must be " + SIT + " or " + SYNTHETIC + " built from " + SIT);
        }
    }

    private static boolean hasExecutedLegacyExpectation(final JsonNode expectationSources) {
        if (!present(expectationSources) || !expectationSources.isObject()) {
            return false;
        }
        for (final String key : expectationSources.propertyNames()) {
            final JsonNode value = expectationSources.get(key);
            if (present(value) && value.isString() && EXECUTED_LEGACY.equals(value.stringValue())) {
                return true;
            }
        }
        return false;
    }

    private static void checkDigests(
            final Path caseDir,
            final String caseId,
            final JsonNode digests,
            final List<String> violations) {

        final Map<String, String> pinned = new LinkedHashMap<>();
        if (present(digests) && digests.isObject()) {
            for (final String key : digests.propertyNames()) {
                final JsonNode value = digests.get(key);
                if (present(value) && value.isString()) {
                    pinned.put(key, value.stringValue());
                }
            }
        }

        for (final Path file : regularFilesUnder(caseDir)) {
            final String relToCase = rel(caseDir, file);
            if (META.equals(relToCase)) {
                continue;
            }
            final String pin = pinned.remove(relToCase);
            if (pin == null) {
                violations.add("case " + caseId + ": file " + relToCase + BELONGS_TO_NO_CASE);
            } else if (!pin.equalsIgnoreCase(sha256Hex(file))) {
                violations.add(
                        "case " + caseId + ": file " + relToCase + " does not match its pinned digest");
            }
        }
        for (final String orphanPin : pinned.keySet()) {
            violations.add("case " + caseId + ": pinned file " + orphanPin + " is missing");
        }
    }

    private static void requireField(
            final JsonNode parent,
            final String name,
            final String caseId,
            final List<String> violations) {
        requireField(parent, name, caseId, violations, "");
    }

    private static void requireField(
            final JsonNode parent,
            final String name,
            final String caseId,
            final List<String> violations,
            final String prefix) {
        if (!present(parent.get(name))) {
            violations.add(missing(caseId, prefix + name));
        }
    }

    private static void requireObject(
            final JsonNode parent,
            final String name,
            final String caseId,
            final List<String> violations) {
        requireObject(parent, name, caseId, violations, "");
    }

    private static void requireObject(
            final JsonNode parent,
            final String name,
            final String caseId,
            final List<String> violations,
            final String prefix) {
        final JsonNode value = parent.get(name);
        if (!present(value) || !value.isObject() || value.isEmpty()) {
            violations.add(missing(caseId, prefix + name));
        }
    }

    private static void requireRecognisedForms(
            final JsonNode formsHeld, final String caseId, final List<String> violations) {
        if (!present(formsHeld) || !formsHeld.isArray()) {
            return;
        }
        for (final JsonNode form : formsHeld) {
            if (!form.isString() || !VALID_FORMS.contains(form.stringValue())) {
                violations.add("case " + caseId + ": formsHeld contains an unrecognised value");
                return;
            }
        }
    }

    private static void requireCompleteCaptureDates(
            final JsonNode provenance,
            final JsonNode formsHeld,
            final String caseId,
            final List<String> violations) {
        final JsonNode captureDates = provenance.get("captureDates");
        if (!present(captureDates) || !captureDates.isObject() || captureDates.isEmpty()) {
            violations.add(missing(caseId, PROVENANCE_PREFIX + "captureDates"));
            return;
        }
        if (!present(captureDates.get("outcome"))) {
            violations.add(missing(caseId, PROVENANCE_PREFIX + "captureDates.outcome"));
        }
        if (!present(formsHeld) || !formsHeld.isArray()) {
            return;
        }
        for (final JsonNode form : formsHeld) {
            if (!form.isString()) {
                continue;
            }
            final String key = form.stringValue().toLowerCase(Locale.ROOT);
            if (!present(captureDates.get(key))) {
                violations.add(missing(caseId, PROVENANCE_PREFIX + "captureDates." + key));
            }
        }
    }

    private static void requireNonEmptyArray(
            final JsonNode parent,
            final String name,
            final String caseId,
            final List<String> violations) {
        final JsonNode value = parent.get(name);
        if (!present(value) || !value.isArray() || value.isEmpty()) {
            violations.add(missing(caseId, name));
        }
    }

    private static String missing(final String caseId, final String field) {
        return "case " + caseId + ": missing required field '" + field + "'";
    }

    private static boolean present(final JsonNode node) {
        return node != null && !node.isNull() && !node.isMissingNode();
    }

    private static String text(final JsonNode node, final String name, final String fallback) {
        final JsonNode value = node.get(name);
        return present(value) && value.isString() ? value.stringValue() : fallback;
    }

    private static String rel(final Path root, final Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private static List<Path> listDirectly(final Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<Path> regularFilesUnder(final Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String sha256Hex(final Path file) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(Files.readAllBytes(file));
            final StringBuilder hex = new StringBuilder(hash.length * 2);
            for (final byte octet : hash) {
                hex.append(Character.forDigit((octet >> 4) & 0xF, 16))
                        .append(Character.forDigit(octet & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available in this JVM", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
