package uk.gov.hmcts.cp.probationresultsdistribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Boot entry point for the probation results distribution service.
 *
 * <p><strong>The package is load-bearing.</strong> This class sits in
 * {@code uk.gov.hmcts.cp.probationresultsdistribution}, not in {@code uk.gov.hmcts.cp} as the
 * sibling services' entry points do, so the component scan {@code @SpringBootApplication} implies
 * covers this service's own tree and nothing else. {@code cp-audit-filter-springboot}'s package
 * root is {@code uk.gov.hmcts.cp.filter.audit}: five of that library's classes still carry a Spring
 * stereotype, and one of them - {@code OpenApiSpecificationParser}, a {@code @Component} whose only
 * constructor takes a {@code String} - cannot be constructed by the container at all. A scan rooted
 * at {@code uk.gov.hmcts.cp} would pick it up and fail the refresh whether auditing is on or off,
 * which is why the siblings carry an explicit exclusion filter. Rooting the scan here makes that
 * filter unnecessary; moving this class up a package brings the failure back.
 *
 * <p>The library's {@code @AutoConfiguration} classes are not found by component scanning at all,
 * so the audit and authorisation starters still configure themselves wherever they are switched
 * on. {@code cp-auth-rules-filter}'s package root is {@code uk.gov.moj.cpp.authz}, outside either
 * tree.
 */
@SpringBootApplication
public class Application {

    /**
     * Starts the Spring container.
     *
     * @param args the command line arguments, passed through to Boot untouched
     */
    public static void main(final String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
