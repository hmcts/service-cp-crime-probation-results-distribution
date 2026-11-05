package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * FR-002-012, FR-002-047: a crash between a leg's final outcome write landing and the request's own
 * transition committing must never re-run the leg on reclaim — the eligibility table's "skip it" row
 * for an already-{@code FAILED} attempt-1, proved end to end for both halves: the terminal reasons
 * ({@code receiver-rejected}, {@code rules-exception}), which settle {@code COMPLETED}, and the
 * exhaustion-eligible reasons ({@code payload-unavailable}, {@code gateway-transient}), which settle
 * {@code FAILED(leg-exhausted)}. Each test lets the real pipeline POST once, simulates the crash with a
 * {@link ProcessedRequestRepository} that throws from the one method the interrupted transition would
 * have called, then reclaims under a fresh, fully real listener and asserts the gateway never answers a
 * second POST.
 */
@DisplayName("a crash between the leg's outcome write and the request's own transition (FR-002-012)")
class CrashBeforeSettlementIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    @Test
    @DisplayName("completes the request on reclaim, with no second POST, after a crash past "
            + "FAILED(receiver-rejected)")
    void completes_after_a_crash_past_a_terminal_failure() throws InterruptedException {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(400);
        final CountDownLatch markAttempted = new CountDownLatch(1);
        startProcessorWith(listenerOverWithProcessedRequests(sharedDataSource(),
                alwaysAvailableStore(), MAX_DELIVERY_COUNT, crashOn("markCompleted", markAttempted)), 1);
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        assertThat(markAttempted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(Duration.ofMillis(150));
        stopProcessorNow();
        assertThat(gatewayPostCount()).isEqualTo(1);
        expireClaim(requestId);

        startProcessor();
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        awaitRequestStatus(requestId, "COMPLETED");
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("fails the request leg-exhausted on reclaim, with no second POST, after a crash past "
            + "FAILED(gateway-transient)")
    void fails_leg_exhausted_after_a_crash_past_a_recorded_exhaustion() throws InterruptedException {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(503);
        final CountDownLatch markAttempted = new CountDownLatch(1);
        // maxDeliveryCount 1: the only way gateway-transient reaches FAILED (not PARKED) is the final
        // permitted delivery, so the very first delivery must already be it.
        startProcessorWith(listenerOverWithProcessedRequests(sharedDataSource(),
                alwaysAvailableStore(), 1, crashOn("markFailed", markAttempted)), 1);
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        assertThat(markAttempted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(Duration.ofMillis(150));
        stopProcessorNow();
        assertThat(gatewayPostCount()).isEqualTo(1);
        expireClaim(requestId);

        startProcessorWith(listenerOver(sharedDataSource(), alwaysAvailableStore(), 1), 1);
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        awaitRequestStatus(requestId, "FAILED");
        assertThat(gatewayPostCount()).isEqualTo(1);
        assertThat(jdbc().sql("SELECT failure_reason FROM processed_request WHERE request_id = :id")
                .param("id", requestId).query(String.class).single())
                .isEqualTo("leg-exhausted");
    }

    private void expireClaim(final UUID requestId) {
        jdbc().sql("UPDATE processed_request SET claim_expires_at = now() - interval '1 second' "
                        + "WHERE request_id = :requestId")
                .param("requestId", requestId)
                .update();
    }

    /**
     * Wraps a real {@link JdbcProcessedRequestRepository} over the shared data source, throwing only
     * when the named method is called — simulating a crash before that transition's write commits,
     * after every write its caller already expects to have landed.
     */
    private ProcessedRequestRepository crashOn(final String methodName,
                                               final CountDownLatch attempted) {
        final ProcessedRequestRepository real = new JdbcProcessedRequestRepository(
                JdbcClient.create(sharedDataSource()),
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource())),
                Duration.ofMinutes(4).plusSeconds(30));
        return (ProcessedRequestRepository) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {ProcessedRequestRepository.class},
                (proxyInstance, method, args) -> {
                    if (method.getName().equals(methodName)) {
                        attempted.countDown();
                        throw new IllegalStateException(
                                "simulated process crash before " + methodName + " commits");
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (final InvocationTargetException invocationFailure) {
                        throw invocationFailure.getCause();
                    }
                });
    }
}
