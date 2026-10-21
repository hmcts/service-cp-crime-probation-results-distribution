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
 * provenance.
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
        for (final Path entry : listDirectly(corpusRoot)) {
            final String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                checkCase(corpusRoot, entry, violations);
            } else if (!INDEX.equals(name)) {
                violations.add("file " + name + BELONGS_TO_NO_CASE);
            }
        }
        return List.copyOf(violations);
    }

    private static void checkCase(
            final Path corpusRoot, final Path caseDir, final List<String> violations) {

        final String dirName = caseDir.getFileName().toString();
        final Path metaPath = caseDir.resolve(META);
        if (!Files.isRegularFile(metaPath)) {
            violations.add("case directory " + dirName + " has no " + META);
            for (final Path file : regularFilesUnder(caseDir)) {
                violations.add("file " + rel(corpusRoot, file) + BELONGS_TO_NO_CASE);
            }
            return;
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
            return;
        }

        final String caseId = text(meta, "caseId", dirName);
        if (!caseId.equals(dirName)) {
            violations.add("case directory " + dirName
                    + ": meta.json caseId does not match the directory name");
        }
        checkProvenance(meta, caseId, violations);
        checkDigests(caseDir, caseId, meta.get("digests"), violations);
    }

    private static void checkProvenance(
            final JsonNode meta, final String caseId, final List<String> violations) {

        requireField(meta, "environment", caseId, violations);
        requireNonEmptyArray(meta, "formsHeld", caseId, violations);
        requireObject(meta, "digests", caseId, violations);

        final JsonNode provenance = meta.get("provenance");
        if (!present(provenance) || !provenance.isObject()) {
            violations.add(missing(caseId, "provenance"));
            return;
        }
        requireField(provenance, "captureDates", caseId, violations, PROVENANCE_PREFIX);
        requireField(provenance, "expectationSources", caseId, violations, PROVENANCE_PREFIX);
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
        final JsonNode value = parent.get(name);
        if (!present(value) || !value.isObject() || value.isEmpty()) {
            violations.add(missing(caseId, name));
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
