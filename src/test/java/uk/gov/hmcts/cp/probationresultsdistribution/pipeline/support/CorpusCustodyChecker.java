package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.nio.file.Path;
import java.util.List;

/**
 * The custody gate over {@code src/test/resources/corpus/}: it must hold before any SIT case is
 * committed (FR-001-030, FR-001-032; spec user story 3).
 *
 * <p>It answers one question about a corpus directory — is every file accounted for, pinned and
 * provenanced? — and returns the list of ways it is not, each message naming only the file or the
 * case id and never a byte of corpus content (FR-001-031). An empty list is a clean corpus.
 *
 * <p>Compile-safe seam only at this point: the real checks land in task T027. This stub reports no
 * violation so the custody tests fail on their assertions rather than on a compile error, per this
 * repository's TDD convention.
 */
public final class CorpusCustodyChecker {

    private CorpusCustodyChecker() {
    }

    /**
     * Reports every custody violation under a corpus root, most specific first.
     *
     * @param corpusRoot the directory that holds the corpus (its cases and its {@code index.json})
     * @return the violations found, each naming a file or case id and no corpus value; empty when
     *     the corpus is clean
     */
    public static List<String> findViolations(final Path corpusRoot) {
        return List.of();
    }
}
