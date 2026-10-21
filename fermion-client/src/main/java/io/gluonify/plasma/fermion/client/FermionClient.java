package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.FermionMsg;
import io.gluonify.plasma.fermion.NodeCapacity;
import io.gluonify.plasma.fermion.WorkflowDescriptor;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import io.quarkus.logging.Log;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.quarkus.websockets.next.WebSocketConnector;
import io.vertx.core.buffer.Buffer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import tools.jackson.databind.ObjectMapper;

/**
 * Permanent connection to gluonify-fermion and workflow submission API. A node can submit, run tasks (beans
 * {@link SkillHandler}), or both. Automatic reconnection; the {@code Start} messages emitted during a disconnection wait in a bounded queue.
 */
@ApplicationScoped
public class FermionClient {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Inject
    FermionClientConfig config;

    @Inject
    NodeIdentity identity;

    @Inject
    SkillHandlerRegistry registry;

    @Inject
    Instance<WorkflowUsage> usages;

    @Inject
    Instance<FermionTokenProvider> tokens;

    /**
     * A {@link WebSocketConnector} is a stateful builder: {@code addHeader}, {@code baseUri} and {@code pathParam} accumulate. Reused, it
     * would add headers on every attempt until the server refuses (HTTP 431). So a NEW one is taken for each attempt, then discarded.
     */
    @Inject
    Instance<WebSocketConnector<FermionClientEndpoint>> connectorFactory;

    @Inject
    ObjectMapper mapper;

    private final Object lock = new Object();
    private final Object urlLock = new Object();
    private final Deque<FermionMsg.Start> pending = new ArrayDeque<>();
    private final AtomicReference<WebSocketClientConnection> connection = new AtomicReference<>();
    private final AtomicReference<BiConsumer<String, String>> errorSink = new AtomicReference<>();
    private final AtomicReference<BiConsumer<String, Map<String, Object>>> doneSink = new AtomicReference<>();
    private final AtomicInteger connectorsCreated = new AtomicInteger();
    private final AtomicReference<Optional<String>> resolvedUrl = new AtomicReference<>();

    /** Active client: enabled and fermion URL known (configured, or resolved by {@code GluonifyServices}). */
    public boolean isActive() {
        return config.enabled() && url().isPresent();
    }

    /**
     * The fermion URL: {@code gluonify.fermion.client.url} if set, otherwise (if {@code discover}) the resolution by
     * {@link GluonifyServices#optional(String)}, done once. A resolution failure, or a plaintext URL toward another host than the internal network
     * ({@link FermionTransport}), is logged and leaves the client inactive.
     */
    Optional<String> url() {
        Optional<String> u = resolvedUrl.get();
        if (u == null) {
            synchronized (urlLock) {
                u = resolvedUrl.get();
                if (u == null) {
                    u = config.url().map(String::strip).filter(s -> !s.isEmpty());
                    if (u.isEmpty() && config.discover()) {
                        try {
                            u = GluonifyServices.optional("fermion").map(Endpoint::url).map(Object::toString);
                            u.ifPresent(found -> Log.infov("fermion-client=discovered url={0}", found));
                        }
                        catch (final RuntimeException e) {
                            Log.warnv("fermion-client=discovery error={0}", e.getMessage());
                            u = Optional.empty();
                        }
                    }
                    if (u.isPresent() && !FermionTransport.acceptable(u.get())) {
                        Log.warnv("fermion-client=insecure-url url={0}: plaintext only toward the internal network, use https", u.get());
                        u = Optional.empty();
                    }
                    resolvedUrl.set(u);
                }
            }
        }
        return u;
    }

    public boolean isConnected() {
        final WebSocketClientConnection current = connection.get();
        return current != null && current.isOpen();
    }

    /** Number of {@code Start} messages waiting for reconnection. */
    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Number of connectors created since startup (one per connection attempt). */
    int connectorsCreated() {
        return connectorsCreated.get();
    }

    /** Callback invoked when fermion refuses or abandons a submitted workflow (correlationId, message). Only one: the last replaces the previous. */
    public void onWorkflowError(final BiConsumer<String, String> sink) {
        errorSink.set(sink);
    }

    void fireWorkflowError(final String correlationId, final String message) {
        final BiConsumer<String, String> sink = errorSink.get();
        Log.warnv("fermion-client=workflow-error correlationId={0} message={1} handled={2}", correlationId, message, sink != null);
        if (sink != null) {
            try {
                sink.accept(correlationId, message);
            }
            catch (final Exception e) {
                Log.errorv(e, "fermion-client=workflow-error-sink correlationId={0} error={1}", correlationId, e.getMessage());
            }
        }
    }

    /**
     * Callback invoked when a workflow this node submitted ends successfully (correlationId, final variables), from fermion's {@code Done} frame.
     * Only one: the last replaces the previous. A fermion before extension E1 sends no such frame.
     *
     * @param sink the callback, or {@code null} to remove it
     */
    public void onWorkflowDone(final BiConsumer<String, Map<String, Object>> sink) {
        doneSink.set(sink);
    }

    void fireWorkflowDone(final FermionMsg.Done done) {
        final BiConsumer<String, Map<String, Object>> sink = doneSink.get();
        Log.infov("fermion-client=workflow-done correlationId={0} workflow={1} handled={2}", done.correlationId(), done.workflow(), sink != null);
        if (sink != null) {
            try {
                sink.accept(done.correlationId(), done.payload());
            }
            catch (final RuntimeException e) {
                Log.errorv(e, "fermion-client=workflow-done-sink correlationId={0} error={1}", done.correlationId(), e.getMessage());
            }
        }
    }

    void onStart(@Observes final StartupEvent event) {
        if (!isActive()) {
            Log.info("fermion-client=disabled (no URL or enabled=false)");
            return;
        }
        connect();
    }

    @Scheduled(every = "${gluonify.fermion.client.reconnect-interval:10s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reconnect() {
        if (!isActive()) {
            return;
        }
        final WebSocketClientConnection current = connection.get();
        // isOpen() can stay true on a half-dead TCP connection: probe with a BOUNDED ping and reconnect if it fails.
        if (current != null && current.isOpen()) {
            try {
                current.sendPing(Buffer.buffer("fermion")).await().atMost(PING_TIMEOUT);
                return;
            }
            catch (final Exception e) {
                Log.warnv("fermion-client=ping node={0} error={1} stale connection, reconnecting", identity.id(), e.getMessage());
                try {
                    current.close().await().atMost(PING_TIMEOUT);
                }
                catch (final Exception ignored) {
                    // already dead
                }
                connection.set(null);
            }
        }
        connect();
    }

    /** Called when the channel closes: forgets the connection (compared by identifier, so as not to overwrite a more recent connection). */
    void connectionLost(final WebSocketClientConnection closed) {
        final WebSocketClientConnection current = connection.get();
        if (current != null && closed != null && current.id().equals(closed.id())) {
            connection.compareAndSet(current, null);
            Log.infov("fermion-client=connection-lost node={0}", identity.id());
        }
    }

    /** Attempts a connection (a new connector); on success: {@code Auth}, {@code Hello}, then replay of the queue. */
    void connect() {
        synchronized (lock) {
            if (isConnected()) {
                return;
            }
            final String token;
            try {
                token = token();
            }
            catch (final RuntimeException e) {
                Log.errorv("fermion-client=token node={0} error={1}", identity.id(), e.getMessage());
                return;
            }
            final String url = url().orElseThrow();
            final WebSocketConnector<FermionClientEndpoint> connector = connectorFactory.get();
            connectorsCreated.incrementAndGet();
            try {
                connector.baseUri(url).pathParam("nodeId", identity.id());
                final WebSocketClientConnection opened = connector.connect().await().atMost(CONNECT_TIMEOUT);
                opened.sendTextAndAwait(mapper.writeValueAsString(new FermionMsg.Auth(token)));
                opened.sendTextAndAwait(mapper.writeValueAsString(hello()));
                connection.set(opened);
                Log.infov("fermion-client=connected node={0} url={1}", identity.id(), url);
                replay(opened);
            }
            catch (final Exception e) {
                Log.errorv("fermion-client=connect node={0} url={1} error={2}", identity.id(), url, e.getMessage());
            }
            finally {
                connectorFactory.destroy(connector);
            }
        }
    }

    private String token() {
        if (!tokens.isResolvable()) {
            throw new IllegalStateException("no FermionTokenProvider bean");
        }
        final String token = tokens.get().token();
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("FermionTokenProvider returned no token");
        }
        return token;
    }

    private void replay(final WebSocketClientConnection target) throws Exception {
        FermionMsg.Start next;
        while ((next = pending.peekFirst()) != null) {
            target.sendTextAndAwait(mapper.writeValueAsString(next));
            pending.pollFirst();
        }
    }

    /** Submits a workflow; the global transaction defaults to the first {@code correlationId} (on the fermion side). */
    public void startWorkflow(final String workflow, final String correlationId, final Map<String, Object> payload) {
        startWorkflow(workflow, correlationId, null, payload);
    }

    /**
     * Submits a workflow attached to an existing business transaction. During a disconnection, the {@code Start} is kept in the bounded queue and replayed on
     * reconnection.
     *
     * @throws FermionClientException if the client is inactive
     * @throws FermionQueueFullException if the disconnection lasts and the queue is full
     */
    public void startWorkflow(final String workflow, final String correlationId, final String globalTransactionId, final Map<String, Object> payload) {
        startWorkflow(workflow, correlationId, globalTransactionId, payload, null);
    }

    /**
     * Like {@link #startWorkflow(String, String, String, Map)}, setting the organization of the execution: fermion passes it to each task in
     * place of any {@code organization} value in the payload. Reserved for platform nodes that know the caller's organization.
     */
    public void startWorkflow(final String workflow, final String correlationId, final String globalTransactionId, final Map<String, Object> payload,
                              final String organization) {
        if (!isActive()) {
            throw new FermionClientException("fermion client is not active (no URL or enabled=false)");
        }
        final FermionMsg.Start start = new FermionMsg.Start(workflow, correlationId, globalTransactionId, payload, organization);
        synchronized (lock) {
            if (pending.isEmpty() && isConnected()) {
                try {
                    connection.get().sendTextAndAwait(mapper.writeValueAsString(start));
                    return;
                }
                catch (final Exception e) {
                    Log.warnv("fermion-client=send correlationId={0} error={1}, queued", correlationId, e.getMessage());
                }
            }
            if (pending.size() >= config.queueSize()) {
                throw new FermionQueueFullException(config.queueSize());
            }
            pending.addLast(start);
            Log.infov("fermion-client=queued correlationId={0} pending={1}", correlationId, pending.size());
        }
    }

    /** Re-announces skills and workflows used (call when this set changes). No effect while disconnected: the next {@code Hello} carries them. */
    public void announceUsage() {
        synchronized (lock) {
            if (!isConnected()) {
                Log.infov("fermion-client=announce-usage node={0} not connected, deferred to next hello", identity.id());
                return;
            }
            try {
                connection.get().sendTextAndAwait(mapper.writeValueAsString(hello()));
            }
            catch (final Exception e) {
                Log.errorv("fermion-client=announce-usage node={0} error={1}", identity.id(), e.getMessage());
            }
        }
    }

    FermionMsg.Hello hello() {
        final List<WorkflowDescriptor> used = new ArrayList<>();
        for (final WorkflowUsage usage : usages) {
            used.addAll(usage.getUsedWorkflows());
        }
        final String self = config.selfUrl().filter(s -> !s.isBlank()).map(s -> s.replaceAll("/+$", "") + "/q/openapi").orElse(null);
        return new FermionMsg.Hello(identity.appId(), identity.nodeId(), registry.skills(), used, self, capacity());
    }

    /** The capacity of {@code gluonify.fermion.client.capacity.*}; an invalid window or slot fails the connection with a clear message. */
    NodeCapacity capacity() {
        final FermionClientConfig.Capacity capacity = config.capacity();
        return NodeCapacity.of(capacity.slots(), capacity.closed().orElse(List.of()), capacity.zone());
    }
}
