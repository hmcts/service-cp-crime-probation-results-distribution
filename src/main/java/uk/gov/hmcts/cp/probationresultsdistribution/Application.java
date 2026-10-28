package uk.gov.hmcts.cp.probationresultsdistribution;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ReplayController;
import uk.gov.hmcts.cp.probationresultsdistribution.config.OperatorCommandLine;

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
 *
 * <p>{@link ReplayController} is excluded from this scan and registered instead by an explicit,
 * {@code @ConditionalOnBean}-gated {@code @Bean} method in {@code config.OperationsConfiguration}.
 * A class-level {@code @Conditional} on a component-scanned {@code @RestController} is evaluated
 * during the scan itself, before sibling {@code @Configuration} classes (including test-only
 * {@code @TestConfiguration} ones supplying a stand-in {@link
 * uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction}) are reliably known to
 * have registered the bean it depends on — confirmed empirically: it intermittently failed to see
 * a sibling bean that was present. A {@code @Bean}-method condition does not have this problem.
 */
@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = ReplayController.class))
public class Application {

    /**
     * Runs a command and exits, or starts the service when no action is supplied.
     *
     * @param args the action or normal Boot options
     */
    public static void main(final String[] args) {
        if (args.length > 0 && !args[0].startsWith("-")) {
            System.exit(OperatorCommandLine.run(args));
        } else {
            SpringApplication.run(Application.class, args);
        }
    }
}
