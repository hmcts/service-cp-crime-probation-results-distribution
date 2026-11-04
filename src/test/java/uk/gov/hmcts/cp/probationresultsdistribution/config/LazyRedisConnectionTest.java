package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// Every mocked RedisClient/StatefulRedisConnection here is a Mockito double, never a real socket;
// PMD's CloseResource check cannot tell the difference.
@SuppressWarnings("PMD.CloseResource")
@DisplayName("the lazy Redis connection (FR-002-016)")
class LazyRedisConnectionTest {

    @Test
    @DisplayName("does not connect until the first real call")
    void does_not_connect_until_first_call() {
        final RedisClient client = mock(RedisClient.class);

        LazyRedisConnection.over(client);

        verify(client, never()).connect();
    }

    @Test
    @DisplayName("connects on the first real call and delegates to the real connection")
    void connects_on_first_call_and_delegates() {
        final RedisClient client = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        final StatefulRedisConnection<String, String> real = mock(StatefulRedisConnection.class);
        @SuppressWarnings("unchecked")
        final RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(client.connect()).thenReturn(real);
        when(real.sync()).thenReturn(commands);

        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);
        final Object result = lazy.sync();

        assertThat(result).isSameAs(commands);
        verify(client).connect();
    }

    @Test
    @DisplayName("reuses the established connection on later calls, connecting only once")
    void reuses_the_established_connection() {
        final RedisClient client = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        final StatefulRedisConnection<String, String> real = mock(StatefulRedisConnection.class);
        when(client.connect()).thenReturn(real);

        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);
        lazy.sync();
        lazy.sync();

        verify(client, times(1)).connect();
    }

    @Test
    @DisplayName("lets a failed first connect propagate as a RedisException, never caching the failure")
    void failed_connect_propagates_and_is_not_cached() {
        final RedisClient client = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        final StatefulRedisConnection<String, String> real = mock(StatefulRedisConnection.class);
        when(client.connect())
                .thenThrow(new RedisConnectionException("connection refused"))
                .thenReturn(real);

        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);

        assertThatThrownBy(lazy::sync).isInstanceOf(RedisException.class);
        // Retried, not permanently failed: the second call reaches the client again and succeeds.
        assertThat(lazy.sync()).isNull();
        verify(client, times(2)).connect();
    }

    @Test
    @DisplayName("closes the real connection if one was ever established")
    void closes_the_established_connection() {
        final RedisClient client = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        final StatefulRedisConnection<String, String> real = mock(StatefulRedisConnection.class);
        when(client.connect()).thenReturn(real);
        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);
        lazy.sync();

        lazy.close();

        verify(real).close();
    }

    @Test
    @DisplayName("closing without ever having connected is a no-op, never forcing a connection")
    void closing_without_connecting_is_a_no_op() {
        final RedisClient client = mock(RedisClient.class);

        LazyRedisConnection.over(client).close();

        verify(client, never()).connect();
    }

    @Test
    @DisplayName("toString, hashCode and equals resolve on the proxy's own identity, never "
            + "forcing a connection")
    void object_methods_never_force_a_connection() {
        final RedisClient client = mock(RedisClient.class);
        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);

        assertThat(lazy.toString()).isNotNull();
        assertThat(lazy.hashCode()).isNotZero();
        assertThat(lazy.equals(lazy)).isTrue();
        assertThat(lazy.equals(LazyRedisConnection.over(client))).isFalse();

        verify(client, never()).connect();
    }

    @Test
    @DisplayName("connects exactly once under concurrent first use")
    void connects_exactly_once_under_concurrent_first_use() throws InterruptedException {
        final RedisClient client = mock(RedisClient.class);
        @SuppressWarnings("unchecked")
        final StatefulRedisConnection<String, String> real = mock(StatefulRedisConnection.class);
        @SuppressWarnings("unchecked")
        final RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(client.connect()).thenReturn(real);
        when(real.sync()).thenReturn(commands);
        final StatefulRedisConnection<String, String> lazy = LazyRedisConnection.over(client);
        final int threadCount = 16;
        final CountDownLatch ready = new CountDownLatch(threadCount);
        final CountDownLatch go = new CountDownLatch(1);
        final ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        try {
            for (int i = 0; i < threadCount; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    lazy.sync();
                });
            }
            ready.await(5, TimeUnit.SECONDS);
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(client, times(1)).connect();
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to race the connect", interrupted);
        }
    }
}
