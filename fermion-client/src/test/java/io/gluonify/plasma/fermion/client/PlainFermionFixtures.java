package io.gluonify.plasma.fermion.client;

import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Hand-made stand-ins for the CDI and WebSocket types the Fermion client talks to, so that its logic runs without a server. */
final class PlainFermionFixtures {
    private PlainFermionFixtures() {
    }

    /** The configuration with the test's overrides over the usual defaults. */
    static FermionClientConfig config(final Map<String, Object> overrides) {
        final FermionClientConfig.Capacity capacity = (FermionClientConfig.Capacity) Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{FermionClientConfig.Capacity.class},
                (p, m, a) -> switch (m.getName()) {
                    case "slots" -> Map.of("heavy", 1);
                    case "closed" -> Optional.<List<String>>empty();
                    case "zone" -> "Europe/Paris";
                    default -> throw new UnsupportedOperationException(m.getName());
                });
        return (FermionClientConfig) Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{FermionClientConfig.class}, (p, m, a) -> {
            if (overrides.containsKey(m.getName())) {
                return overrides.get(m.getName());
            }
            return switch (m.getName()) {
                case "enabled" -> true;
                case "url" -> Optional.<String>empty();
                case "discover" -> false;
                case "appId" -> Optional.<String>empty();
                case "nodeId" -> "0";
                case "selfUrl" -> Optional.<String>empty();
                case "queueSize" -> 2;
                case "reconnectInterval" -> "10s";
                case "heartbeatInterval" -> Duration.ZERO;
                case "capacity" -> capacity;
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }

    /** An {@code Instance} that iterates over, and resolves to, these beans. */
    @SuppressWarnings("unchecked")
    static <T> Instance<T> instance(final Class<?> type, final List<T> beans) {
        return (Instance<T>) Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{Instance.class}, (p, m, a) -> switch (m.getName()) {
            case "iterator" -> beans.iterator();
            case "isResolvable" -> !beans.isEmpty();
            case "get" -> beans.get(0);
            case "destroy" -> null;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    /** A connection that records what is sent; {@code open} and {@code failures} drive its behaviour. */
    static final class Link {
        final String id;
        volatile boolean open = true;
        volatile boolean pingFails;
        volatile boolean closeFails;
        volatile int sendFailures;
        final List<String> sent = new CopyOnWriteArrayList<>();
        final List<String> events = new CopyOnWriteArrayList<>();
        volatile Consumer<String> onSend = s -> { };

        Link(final String id) {
            this.id = id;
        }

        WebSocketClientConnection connection() {
            return (WebSocketClientConnection) Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{WebSocketClientConnection.class}, (p, m, a) -> switch (m.getName()) {
                case "id" -> id;
                case "isOpen" -> open;
                case "sendTextAndAwait" -> {
                    if (sendFailures > 0) {
                        sendFailures--;
                        throw new IllegalStateException("send failed");
                    }
                    sent.add((String) a[0]);
                    onSend.accept((String) a[0]);
                    yield null;
                }
                case "sendPing" -> pingFails ? Uni.createFrom().failure(new IllegalStateException("no pong")) : Uni.createFrom().voidItem();
                case "close" -> closeFails ? Uni.createFrom().failure(new IllegalStateException("already dead")) : Uni.createFrom().voidItem();
                case "toString" -> "Link[" + id + "]";
                case "hashCode" -> id.hashCode();
                case "equals" -> p == a[0];
                default -> throw new UnsupportedOperationException(m.getName());
            });
        }
    }

    /** A connector factory whose connectors hand out this link, or fail when {@code link} is null. */
    @SuppressWarnings("unchecked")
    static Instance<WebSocketConnector<FermionClientEndpoint>> connectors(final Link[] next, final List<String> calls) {
        return (Instance<WebSocketConnector<FermionClientEndpoint>>) Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{Instance.class}, (p, m, a) -> {
            if (m.getName().equals("destroy")) {
                calls.add("destroy");
                return null;
            }
            if (!m.getName().equals("get")) {
                throw new UnsupportedOperationException(m.getName());
            }
            calls.add("get");
            final Object[] self = new Object[1];
            self[0] = Proxy.newProxyInstance(PlainFermionFixtures.class.getClassLoader(), new Class<?>[]{WebSocketConnector.class}, (q, n, b) -> switch (n.getName()) {
                case "baseUri" -> {
                    calls.add("baseUri " + b[0]);
                    yield self[0];
                }
                case "pathParam" -> {
                    calls.add("pathParam " + b[0] + "=" + b[1]);
                    yield self[0];
                }
                case "connect" -> next[0] == null ? Uni.createFrom().failure(new java.io.IOException("refused")) : Uni.createFrom().item(next[0].connection());
                default -> throw new UnsupportedOperationException(n.getName());
            });
            return self[0];
        });
    }
}
