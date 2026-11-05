package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * A {@link StatefulRedisConnection} that defers its first {@code connect()} to the first real call
 * instead of the owning bean's construction (FR-002-016): a cache outage at startup must not fail
 * context refresh. {@code RedisConnectionException} extends {@code RedisException}
 * ({@link io.lettuce.core.RedisException}), so a failed first connect surfaces to
 * {@code CachedHearingPayloadAdapter}'s existing miss-handling exactly as a command failure already
 * does — no adapter change needed. A failed connect is never cached: the next call tries again, so
 * the connection recovers on its own once Redis comes back; once a connection is established,
 * Lettuce's own auto-reconnect takes over from there.
 */
final class LazyRedisConnection {

    private LazyRedisConnection() {
    }

    /**
     * Wraps a client in a connection that connects lazily.
     *
     * @param client the Redis client, cheap to construct, opening no connection of its own
     * @return a {@code StatefulRedisConnection} that connects on first use
     */
    /* default */ @SuppressWarnings("unchecked") // the proxy implements exactly one interface
    static StatefulRedisConnection<String, String> over(final RedisClient client) {
        return (StatefulRedisConnection<String, String>) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {StatefulRedisConnection.class}, new LazyHandler(client));
    }

    private static final class LazyHandler implements InvocationHandler {

        private final RedisClient client;
        private volatile StatefulRedisConnection<String, String> established;

        private LazyHandler(final RedisClient client) {
            this.client = client;
        }

        @Override
        public Object invoke(final Object proxy, final Method method, final Object[] args)
                throws Throwable {
            final String name = method.getName();
            final int paramCount = method.getParameterCount();
            final Object result;
            // A JDK dynamic proxy routes Object methods through this handler too (it has no
            // no-op Object identity of its own): equals/hashCode/toString must resolve on the
            // proxy's own identity, and close() has its own rule below, so that nothing — a log
            // line, a debugger watch, a bean-inspection path — can force a connect merely by
            // looking at the lazy connection, re-opening FR-002-016's failure mode through a
            // different door.
            if ("close".equals(name) && paramCount == 0) {
                closeIfConnected();
                result = null;
            } else if ("equals".equals(name) && paramCount == 1) {
                // Reference identity is exactly what "this proxy equals that object" means here;
                // calling equals() on the lazily-resolved connection is what this branch exists to
                // avoid.
                @SuppressWarnings("PMD.CompareObjectsWithEquals")
                final boolean sameProxy = proxy == args[0];
                result = sameProxy;
            } else if ("hashCode".equals(name) && paramCount == 0) {
                result = System.identityHashCode(proxy);
            } else if ("toString".equals(name) && paramCount == 0) {
                result = "LazyRedisConnection{"
                        + (established == null ? "not yet connected" : "connected") + "}";
            } else {
                try {
                    result = method.invoke(resolveConnection(), args);
                } catch (final InvocationTargetException invocationFailure) {
                    throw invocationFailure.getCause();
                }
            }
            return result;
        }

        // Ownership stays with this handler for the lifetime of the lazy proxy; closeIfConnected()
        // is the one place that closes it, called only when the proxy itself is closed.
        @SuppressWarnings("PMD.CloseResource")
        private StatefulRedisConnection<String, String> resolveConnection() {
            StatefulRedisConnection<String, String> current = established;
            if (current == null) {
                synchronized (this) {
                    current = established;
                    if (current == null) {
                        current = client.connect();
                        established = current;
                    }
                }
            }
            return current;
        }

        // The connection this closes is the one field this handler owns and is closing on the
        // proxy's own close() — not a resource leaked elsewhere.
        @SuppressWarnings("PMD.CloseResource")
        private void closeIfConnected() {
            final StatefulRedisConnection<String, String> current = established;
            if (current != null) {
                current.close();
            }
        }
    }
}
