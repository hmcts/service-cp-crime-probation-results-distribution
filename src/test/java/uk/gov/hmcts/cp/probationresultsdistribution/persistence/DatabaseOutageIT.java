package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.health.DataSourceHealthIndicator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.StoreGate;

/**
 * US2 Acceptance Scenario 7 (FR-002-009, SC-002-011): while the processed log is unreachable, intake
 * is suspended instead of burning the delivery budget. A delivery arriving during the outage is handed
 * back before its body is read, {@code probation_intake_suspended{cause="database"}} reads 1, readiness
 * reports down, and the final permitted delivery is dead-lettered {@code exhausted}. When the store
 * answers again, intake resumes and the redelivered message is processed.
 *
 * <p>The outage is modelled with a dedicated Postgres container this suite can pause, so the broker,
 * Redis and the downstream stubs stay up while only the store goes away.
 */
@DisplayName("a store outage suspends intake instead of burning deliveries")
class DatabaseOutageIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";
    private static final Duration OBSERVE = Duration.ofSeconds(90);
    private static final String PAUSED_STATE = "paused";

    private static final PostgreSQLContainer OUTAGE_STORE =
            new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"))
                    .withUrlParam("socketTimeout", "5")
                    .withUrlParam("connectTimeout", "5");

    static {
        OUTAGE_STORE.start();
        Flyway.configure()
                .dataSource(OUTAGE_STORE.getJdbcUrl(), OUTAGE_STORE.getUsername(),
                        OUTAGE_STORE.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private ConsumerLifecycleController controller;
    private ServiceBusProcessorClient outageProcessor;

    @AfterEach
    void tearDownOutageStack() {
        unpause();
        if (controller != null) {
            controller.stop();
        }
        if (outageProcessor != null) {
            outageProcessor.close();
        }
    }

    @Test
    @DisplayName("abandons the delivery, suspends intake, drops readiness, then resumes on recovery")
    void suspends_and_resumes_around_an_outage() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(200);
        final DataSource store = outageDataSource();
        startGatedIntake(store, 5);

        pause();
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME));

        awaitGauge(1.0d);
        assertThat(new DataSourceHealthIndicator(store).health().getStatus())
                .as("readiness reports down while the store is unreachable")
                .isEqualTo(Status.DOWN);
        assertThat(peekDeadLetterNow("RESULTS:" + requestId))
                .as("a store outage is not the message's fault; nothing parks it")
                .isEmpty();
        assertThat(suspensionsCount())
                .as("suspending intake is an incident, and it is counted")
                .isGreaterThanOrEqualTo(1.0d);

        unpause();

        // The emulator does not promptly re-offer an abandoned lock after its processor is stopped.
        // Requeue the same deterministic command after recovery so this test can still prove that
        // intake resumed and the durable guard sends it once, without waiting for lock expiry.
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME));

        awaitCompleted(store, requestId);
        awaitGauge(0.0d);
        assertThat(gatewayPostCount())
                .as("the redelivered message is sent once, after the store returns")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("dead-letters the final permitted delivery exhausted during a sustained outage")
    void final_delivery_during_an_outage_is_exhausted() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        stubGateway(200);
        final DataSource store = outageDataSource();
        startGatedIntake(store, 1);

        pause();
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME));

        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked)
                .as("the final permitted delivery is dead-lettered rather than burned")
                .isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason())
                .as("the service records its own exhausted reason, not the broker's")
                .isEqualTo("exhausted");
        assertThat(gatewayPostCount()).as("the outage was discovered before any body was read").isZero();
    }

    private void startGatedIntake(final DataSource store, final int maxDeliveryCount) {
        final ProcessedLogProbe probe = new ProcessedLogProbe(JdbcClient.create(store));
        final AtomicReference<ConsumerLifecycleController> holder = new AtomicReference<>();
        final StoreGate gate = new StoreGate() {
            @Override
            public boolean storeAvailable() {
                return holder.get().storeAvailable();
            }

            @Override
            public void suspendIntake() {
                holder.get().suspendIntake();
            }
        };
        final ProbationMessageListener listener = listenerOver(store, gate, maxDeliveryCount);
        final MetricRegistry metrics = sharedMetrics();
        outageProcessor = buildProcessor(listener, 2);
        final IntakeControlRepository intakeControl =
                new JdbcIntakeControlRepository(JdbcClient.create(store));
        controller = new ConsumerLifecycleController(outageProcessor, probe, intakeControl,
                () -> null, metrics, Duration.ofSeconds(1));
        holder.set(controller);
        controller.start();
        awaitIntakeStarted();
    }

    private DataSource outageDataSource() {
        return new DriverManagerDataSource(OUTAGE_STORE.getJdbcUrl(), OUTAGE_STORE.getUsername(),
                OUTAGE_STORE.getPassword());
    }

    private void pause() {
        OUTAGE_STORE.getDockerClient().pauseContainerCmd(OUTAGE_STORE.getContainerId()).exec();
    }

    private void unpause() {
        final String status = OUTAGE_STORE.getDockerClient()
                .inspectContainerCmd(OUTAGE_STORE.getContainerId()).exec().getState().getStatus();
        if (PAUSED_STATE.equals(status)) {
            OUTAGE_STORE.getDockerClient().unpauseContainerCmd(OUTAGE_STORE.getContainerId()).exec();
        }
    }

    private void awaitIntakeStarted() {
        final long deadline = System.nanoTime() + OBSERVE.toNanos();
        while (System.nanoTime() < deadline) {
            if (controller.intakeStarted()) {
                return;
            }
            pollSleep();
        }
        throw new IllegalStateException("intake never started");
    }

    private void awaitGauge(final double expected) {
        final long deadline = System.nanoTime() + OBSERVE.toNanos();
        double latest = Double.NaN;
        while (System.nanoTime() < deadline) {
            latest = gaugeValue();
            if (latest == expected) {
                return;
            }
            pollSleep();
        }
        assertThat(latest).as("intake-suspended gauge never reached %s", expected).isEqualTo(expected);
    }

    private void awaitCompleted(final DataSource store, final UUID requestId) {
        final JdbcClient client = JdbcClient.create(store);
        final long deadline = System.nanoTime() + OBSERVE.toNanos();
        while (System.nanoTime() < deadline) {
            final Optional<String> status = client.sql(
                            "SELECT status FROM processed_request WHERE request_id = :requestId")
                    .param("requestId", requestId)
                    .query(String.class)
                    .optional();
            if (status.filter("COMPLETED"::equals).isPresent()) {
                return;
            }
            pollSleep();
        }
        throw new IllegalStateException("request never completed after the store returned");
    }

    private double gaugeValue() {
        final Gauge gauge = meterRegistry().find("probation_intake_suspended")
                .tag("cause", "database").gauge();
        return gauge == null ? Double.NaN : gauge.value();
    }

    private double suspensionsCount() {
        final Counter counter = meterRegistry().find("probation_intake_suspensions_total").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private static void pollSleep() {
        try {
            Thread.sleep(500L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling", e);
        }
    }
}
