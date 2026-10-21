package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CanonicalJsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.CorpusCustodyChecker;

/**
 * The custody gate (FR-001-030, FR-001-032, FR-001-031; spec user story 3) exercised over a scratch
 * corpus, so it is proven to fail the build on a tampered file, a missing provenance field and a
 * file belonging to no case — and proven never to print a corpus value — <em>before</em> any SIT
 * case is committed.
 *
 * <p>Each scenario starts from the well-formed fixture committed under
 * {@code src/test/resources/corpus-custody-fixtures/well-formed/}, copies it into a temporary
 * directory and breaks exactly one thing, so the scenarios are independent and the committed
 * fixtures stay valid.
 */
@DisplayName("CorpusCustodyChecker")
class CorpusCustodyTest {

    private static final String WELL_FORMED = "/corpus-custody-fixtures/well-formed";
    private static final String CASE_DIR = "case-custody-demo";
    private static final String CACHE_FILE = "inputs/cache.json";
    private static final String PAYLOAD_SENTINEL = "SENTINEL-VALUE-MUST-NOT-LEAK-7Q2";
    private static final String EXPECTATION_SENTINEL = "SENTINEL-EXPECTATION-MUST-NOT-LEAK-9F4";
    private static final String META_SENTINEL = "SENTINEL-META-MUST-NOT-LEAK-ZZ9";
    private static final String BELONGS_TO_NO_CASE = "belongs to no case";
    private static final String BOGUS_DIGEST = "0".repeat(64);
    private static final ObjectMapper MAPPER = CanonicalJsonMapper.create();

    @TempDir
    private Path tmp;

    @Nested
    @DisplayName("fails a broken corpus, naming the file or case (T024)")
    class Failures {

        @Test
        @DisplayName("a well-formed corpus produces no violations")
        void findViolations_whenEveryFileIsPinnedAndProvenanced_should_report_nothing()
                throws Exception {
            copyWellFormedInto(tmp);

            assertThat(CorpusCustodyChecker.findViolations(tmp)).isEmpty();
        }

        @Test
        @DisplayName("a file whose bytes no longer match its pinned digest fails, naming case + file")
        void findViolations_whenPinnedDigestNoLongerMatches_should_name_the_case_and_file()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> digests(meta).put(CACHE_FILE, BOGUS_DIGEST));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains(CACHE_FILE));
        }

        @Test
        @DisplayName("a case missing a required provenance field fails, naming the case and field")
        void findViolations_whenAProvenanceFieldIsMissing_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> provenance(meta).remove("captureDates"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("captureDates"));
        }

        @Test
        @DisplayName("a file belonging to no case fails, naming the file")
        void findViolations_whenAFileBelongsToNoCase_should_name_the_file() throws Exception {
            copyWellFormedInto(tmp);
            Files.writeString(tmp.resolve("stray.json"), "{\"x\":1}");

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains("stray.json") && v.contains("belongs to no case"));
        }

        @Test
        @DisplayName("a pinned file that is no longer on disk fails, naming case + file, value-free")
        void findViolations_whenAPinnedFileIsMissing_should_name_the_case_and_file() throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> digests(meta).put("inputs/extra-missing.json", BOGUS_DIGEST));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR)
                            && v.contains("extra-missing.json")
                            && v.contains("is missing"))
                    .noneMatch(v -> v.contains(BOGUS_DIGEST));
        }
    }

    @Nested
    @DisplayName("never prints a corpus value (T025)")
    class Privacy {

        @Test
        @DisplayName("no custody message contains a corpus value, passing or failing (FR-001-031)")
        void findViolations_overBrokenCorpora_should_name_files_and_cases_but_never_a_value()
                throws Exception {
            final List<String> all = new ArrayList<>();

            final Path mismatch = Files.createDirectories(tmp.resolve("mismatch"));
            copyWellFormedInto(mismatch);
            rewriteMeta(mismatch, meta -> digests(meta).put(CACHE_FILE, BOGUS_DIGEST));
            all.addAll(CorpusCustodyChecker.findViolations(mismatch));

            final Path missing = Files.createDirectories(tmp.resolve("missing"));
            copyWellFormedInto(missing);
            rewriteMeta(missing, meta -> provenance(meta).remove("captureDates"));
            all.addAll(CorpusCustodyChecker.findViolations(missing));

            final Path orphan = Files.createDirectories(tmp.resolve("orphan"));
            copyWellFormedInto(orphan);
            Files.writeString(orphan.resolve("stray.json"), "{\"x\":1}");
            all.addAll(CorpusCustodyChecker.findViolations(orphan));

            assertThat(all)
                    .as("the broken corpora must actually produce violations to prove the point")
                    .isNotEmpty();
            assertThat(all)
                    .as("no corpus value may appear in any custody message")
                    .noneMatch(v -> v.contains(PAYLOAD_SENTINEL) || v.contains(EXPECTATION_SENTINEL));
        }

        @Test
        @DisplayName("a malformed meta.json is reported value-free, never as a Jackson parse snippet")
        void findViolations_whenMetaIsNotValidJson_should_name_the_case_without_leaking_content()
                throws Exception {
            copyWellFormedInto(tmp);
            // Truncated, non-parseable JSON that embeds a sentinel: a Jackson parse-error snippet
            // would echo these bytes into a public CI log (FR-001-031). It must not.
            Files.writeString(
                    tmp.resolve(CASE_DIR).resolve("meta.json"),
                    "{\"caseId\":\"" + CASE_DIR + "\",\"leak\":\"" + META_SENTINEL + "\",");

            final List<String> violations = CorpusCustodyChecker.findViolations(tmp);

            assertThat(violations)
                    .as("the malformed case must be named and reported as invalid JSON")
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("is not valid JSON"));
            assertThat(violations)
                    .as("files under an unverifiable case must be flagged, as for a missing meta.json")
                    .anyMatch(v -> v.contains(CACHE_FILE) && v.contains(BELONGS_TO_NO_CASE));
            assertThat(violations)
                    .as("no meta.json byte, parse position or Jackson text may appear")
                    .noneMatch(v -> v.contains(META_SENTINEL))
                    .noneMatch(v -> v.toLowerCase(Locale.ROOT).contains("unexpected")
                            || v.contains("line:")
                            || v.contains("column:")
                            || v.contains("at [Source"));
        }
    }

    @Nested
    @DisplayName("rejects anything but SIT or synthetic-from-SIT (T027, FR-001-030)")
    class Environment {

        @Test
        @DisplayName("an environment other than SIT or SYNTHETIC fails, naming the case")
        void findViolations_whenEnvironmentIsNeitherSitNorSynthetic_should_name_the_case()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.put("environment", "PRD"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("environment must be"));
        }
    }

    @Nested
    @DisplayName("flags files that belong to no verifiable case (T027, FR-001-032)")
    class Orphans {

        @Test
        @DisplayName("a case directory with files but no meta.json names the case and its files")
        void findViolations_whenCaseHasNoMeta_should_name_the_case_and_its_files() throws Exception {
            copyWellFormedInto(tmp);
            Files.delete(tmp.resolve(CASE_DIR).resolve("meta.json"));

            final List<String> violations = CorpusCustodyChecker.findViolations(tmp);

            assertThat(violations).anyMatch(v -> v.contains(CASE_DIR) && v.contains("has no meta.json"));
            assertThat(violations)
                    .anyMatch(v -> v.contains(CACHE_FILE) && v.contains(BELONGS_TO_NO_CASE));
        }

        @Test
        @DisplayName("a stray file inside a case directory, absent from its digests, is flagged")
        void findViolations_whenAFileInsideACaseIsNotPinned_should_name_the_case_and_file()
                throws Exception {
            copyWellFormedInto(tmp);
            Files.writeString(tmp.resolve(CASE_DIR).resolve("inputs").resolve("stray.json"), "{}");

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR)
                            && v.contains("stray.json")
                            && v.contains(BELONGS_TO_NO_CASE));
        }
    }

    @Nested
    @DisplayName("requires complete provenance on every case (T027, FR-001-029)")
    class Provenance {

        @Test
        @DisplayName("an executed-legacy expectation without legacyCommit fails, naming the case")
        void findViolations_whenExecutedLegacyLacksLegacyCommit_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> provenance(meta).remove("legacyCommit"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("legacyCommit"));
        }

        @Test
        @DisplayName("an executed-legacy expectation without runtime fails, naming the case")
        void findViolations_whenExecutedLegacyLacksRuntime_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> provenance(meta).remove("runtime"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("runtime"));
        }

        @Test
        @DisplayName("no executed-legacy source means legacyCommit and runtime are not required")
        void findViolations_whenNoSourceIsExecutedLegacy_should_not_require_legacyCommit_or_runtime()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> {
                final ObjectNode prov = provenance(meta);
                prov.remove("legacyCommit");
                prov.remove("runtime");
                prov.putObject("expectationSources").put("ACTIVE:CACHE", "SIT_LOGGED");
            });

            assertThat(CorpusCustodyChecker.findViolations(tmp)).isEmpty();
        }

        @Test
        @DisplayName("a caseId that does not match the directory name fails, naming the case")
        void findViolations_whenCaseIdDiffersFromDirectory_should_name_the_case() throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.put("caseId", "a-different-id"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains("caseId does not match"));
        }

        @Test
        @DisplayName("a meta.json with no caseId field fails, naming the case and field")
        void findViolations_whenCaseIdIsAbsent_should_name_the_case_and_field() throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.remove("caseId"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("caseId"));
        }

        @Test
        @DisplayName("an empty formsHeld array fails, naming the case and field")
        void findViolations_whenFormsHeldIsEmpty_should_name_the_case_and_field() throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.putArray("formsHeld"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("formsHeld"));
        }

        @Test
        @DisplayName("an empty digests object fails, naming the case and field")
        void findViolations_whenDigestsIsEmpty_should_name_the_case_and_field() throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.putObject("digests"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("digests"));
        }

        @Test
        @DisplayName("captureDates missing an entry for a form in formsHeld fails, naming the field")
        void findViolations_whenCaptureDatesMissingAHeldForm_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.putArray("formsHeld").add("CACHE").add("FALLBACK"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("captureDates.fallback"));
        }

        @Test
        @DisplayName("captureDates missing its outcome date fails, naming the case and field")
        void findViolations_whenCaptureDatesMissingOutcome_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> provenance(meta).putObject("captureDates").put("cache", "2026-01-01"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("captureDates.outcome"));
        }

        @Test
        @DisplayName("an empty expectationSources object fails, naming the case and field")
        void findViolations_whenExpectationSourcesIsEmpty_should_name_the_case_and_field()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> provenance(meta).putObject("expectationSources"));

            assertThat(CorpusCustodyChecker.findViolations(tmp))
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("expectationSources"));
        }

        @Test
        @DisplayName("formsHeld containing a value outside CACHE/FALLBACK fails, value-free")
        void findViolations_whenFormsHeldHasAnUnrecognisedValue_should_name_the_case_only()
                throws Exception {
            copyWellFormedInto(tmp);
            rewriteMeta(tmp, meta -> meta.putArray("formsHeld").add("BOGUS-FORM"));

            final List<String> violations = CorpusCustodyChecker.findViolations(tmp);

            assertThat(violations)
                    .anyMatch(v -> v.contains(CASE_DIR) && v.contains("formsHeld"));
            assertThat(violations).noneMatch(v -> v.contains("BOGUS-FORM"));
        }
    }

    private static ObjectNode digests(final ObjectNode meta) {
        return (ObjectNode) meta.get("digests");
    }

    private static ObjectNode provenance(final ObjectNode meta) {
        return (ObjectNode) meta.get("provenance");
    }

    private static void rewriteMeta(final Path corpusRoot, final Consumer<ObjectNode> mutator)
            throws IOException {
        final Path metaPath = corpusRoot.resolve(CASE_DIR).resolve("meta.json");
        final ObjectNode meta = (ObjectNode) MAPPER.readTree(Files.readAllBytes(metaPath));
        mutator.accept(meta);
        Files.writeString(metaPath, MAPPER.writeValueAsString(meta));
    }

    private static void copyWellFormedInto(final Path corpusRoot)
            throws IOException, URISyntaxException {
        final URL url = CorpusCustodyTest.class.getResource(WELL_FORMED);
        if (url == null) {
            throw new IllegalStateException(
                    "custody fixture not on the test classpath: " + WELL_FORMED);
        }
        final Path srcRoot = Path.of(url.toURI());
        final List<Path> entries;
        try (Stream<Path> walk = Files.walk(srcRoot)) {
            entries = walk.toList();
        }
        for (final Path entry : entries) {
            final Path target = corpusRoot.resolve(srcRoot.relativize(entry).toString());
            if (Files.isDirectory(entry)) {
                Files.createDirectories(target);
            } else {
                Files.createDirectories(target.getParent());
                Files.copy(entry, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
