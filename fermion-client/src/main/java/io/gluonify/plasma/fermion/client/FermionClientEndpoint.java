package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.FermionMsg;
import io.quarkus.logging.Log;
import io.quarkus.websockets.next.InboundProcessingMode;
import io.quarkus.websockets.next.OnClose;
import io.quarkus.websockets.next.OnError;
import io.quarkus.websockets.next.OnTextMessage;
import io.quarkus.websockets.next.WebSocketClient;
import io.quarkus.websockets.next.WebSocketClientConnection;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

/**
 * Client side of the channel: runs the {@code Command}s (off the event thread) and answers with a {@code Result}, relays the {@code Error}s.
 * Authentication and the {@code Hello} are sent by {@link FermionClient} as soon as the connection is obtained.
 * <p>
 * {@link InboundProcessingMode#CONCURRENT} processing: each {@code Command} is run on a worker thread as soon as it is received, without waiting for the
 * end of the previous ones. A long skill (native build, waiting for a human decision) therefore no longer blocks the node's other tasks; each
 * {@code Result} is sent as soon as its task ends, in order of completion and not of arrival (fermion matches them by {@code correlationId}).
 * A handler must therefore be safe under concurrent access (as stateless {@code @ApplicationScoped} beans already are).
 * <p>
 * Heartbeat: while a task is running, a {@code Progress} is sent every {@code gluonify.fermion.client.heartbeat-interval} (60 s by
 * default); fermion therefore does not resend a long task to another node as long as this one gives a sign of life. A handler may also send
 * structured events ({@link TaskContext#progress(Map)}).
 * <p>
 * Cancellation: a {@code Cancel} reaches the handler of the running task through {@link SkillHandler#onCancel}; when the task is not running
 * here (already finished, or lost by a restart), the endpoint acknowledges it at once with a failure of kind {@code interrupted}.
 */
@WebSocketClient(path = "/ws/fermion/{nodeId}", inboundProcessingMode = InboundProcessingMode.CONCURRENT)
public class FermionClientEndpoint {

    private static final String KEY_SEPARATOR = "/";

    @Inject
    SkillHandlerRegistry registry;

    @Inject
    FermionClient client;

    @Inject
    ObjectMapper mapper;

    @Inject
    FermionClientConfig config;

    /** Heartbeat thread, shared by all tasks (created on the first task, never statically). */
    private volatile ScheduledExecutorService heartbeats;

    /** Tasks in progress, by {@code correlationId} and {@code stepId}: where a {@code Cancel} finds its handler. */
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /** A task in progress: its handler and its context. */
    private record Running(SkillHandler handler, TaskContext context) {
    }

    @OnTextMessage
    @Blocking
    public String onMessage(final String raw, final WebSocketClientConnection connection) {
        final FermionMsg msg;
        try {
            msg = mapper.readValue(raw, FermionMsg.class);
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=parse error={0}", e.getMessage());
            return null;
        }
        return switch (msg) {
            case final FermionMsg.Error error -> {
                client.fireWorkflowError(error.correlationId(), error.message());
                yield null;
            }
            case final FermionMsg.Done done -> {
                client.fireWorkflowDone(done);
                yield null;
            }
            case final FermionMsg.Cancel cancel -> cancel(cancel);
            case final FermionMsg.Command command -> run(command, connection);
            case FermionMsg.Auth _, FermionMsg.Hello _, FermionMsg.Start _, FermionMsg.Result _, FermionMsg.Progress _ -> {
                Log.warnv("fermion-client=unexpected frame type={0}", msg.getClass().getSimpleName());
                yield null;
            }
        };
    }

    private String run(final FermionMsg.Command command, final WebSocketClientConnection connection) {
        final ScheduledFuture<?> beat = startHeartbeat(command, connection);
        try {
            return write(execute(command, connection));
        }
        finally {
            if (beat != null) {
                beat.cancel(false);
            }
        }
    }

    /** Passes the cancellation to the running handler, or acknowledges it when the task does not run here. */
    private String cancel(final FermionMsg.Cancel cancel) {
        final Running task = cancel.stepId() == null
                ? running.entrySet().stream().filter(e -> e.getKey().startsWith(cancel.correlationId() + KEY_SEPARATOR)).map(Map.Entry::getValue).findFirst()
                        .orElse(null)
                : running.get(key(cancel.correlationId(), cancel.stepId()));
        if (task == null) {
            Log.infov("fermion-client=cancel correlationId={0} step={1} not running, acknowledged", cancel.correlationId(), cancel.stepId());
            return write(new FermionMsg.Result(cancel.correlationId(), null, cancel.action(), false, cancel.reason(), FermionMsg.Cancel.INTERRUPTED, null,
                    cancel.stepId()));
        }
        Log.infov("fermion-client=cancel correlationId={0} action={1} resumable={2}", cancel.correlationId(), cancel.action(), cancel.resumable());
        try {
            task.handler().onCancel(task.context(), cancel);
        }
        catch (final RuntimeException e) {
            Log.errorv(e, "fermion-client=cancel correlationId={0} handler error={1}", cancel.correlationId(), e.getMessage());
        }
        return null;
    }

    private static String key(final String correlationId, final String stepId) {
        return correlationId + KEY_SEPARATOR + (stepId == null ? "" : stepId);
    }

    /** Schedules the heartbeats of {@code command}; {@code null} if disabled. */
    private ScheduledFuture<?> startHeartbeat(final FermionMsg.Command command, final WebSocketClientConnection connection) {
        final Duration every = config.heartbeatInterval();
        if (every == null || every.isZero() || every.isNegative() || connection == null) {
            return null;
        }
        final String frame = write(FermionMsg.Progress.of(command, null));
        if (frame == null) {
            return null;
        }
        final long millis = every.toMillis();
        return scheduler().scheduleAtFixedRate(() -> {
            try {
                if (connection.isOpen()) {
                    connection.sendTextAndAwait(frame);
                }
            }
            catch (final RuntimeException e) {
                Log.debugv("fermion-client=progress correlationId={0} error={1}", command.correlationId(), e.getMessage());
            }
        }, millis, millis, TimeUnit.MILLISECONDS);
    }

    @SuppressWarnings("PMD.CloseResource") // one daemon scheduler, created on first use and kept for the endpoint's lifetime
    private ScheduledExecutorService scheduler() {
        ScheduledExecutorService s = heartbeats;
        if (s == null) {
            synchronized (this) {
                s = heartbeats;
                if (s == null) {
                    s = Executors.newSingleThreadScheduledExecutor(r -> {
                        final Thread t = new Thread(r, "fermion-client-heartbeat");
                        t.setDaemon(true);
                        return t;
                    });
                    heartbeats = s;
                }
            }
        }
        return s;
    }

    private FermionMsg.Result execute(final FermionMsg.Command command, final WebSocketClientConnection connection) {
        final SkillHandler handler = registry.get(command.action());
        if (handler == null) {
            Log.warnv("fermion-client=command action={0} no handler", command.action());
            return failure(command, "No handler for action " + command.action(), "NO_HANDLER");
        }
        final TaskContext context = new TaskContext(command.globalTransactionId(), command.correlationId(), command.action(), command.payload(), mapper,
                command.stepId(), data -> sendEvent(command, data, connection));
        final String key = key(command.correlationId(), command.stepId());
        running.put(key, new Running(handler, context));
        try {
            final Map<String, Object> out = handler.handle(context);
            return FermionMsg.Result.ok(command, out);
        }
        catch (final SkillFailureException e) {
            Log.warnv("fermion-client=task correlationId={0} action={1} errorKind={2} error={3}", command.correlationId(), command.action(), e.errorKind(), e.getMessage());
            return failure(command, e.getMessage(), e.errorKind());
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=task correlationId={0} action={1} error={2}", command.correlationId(), command.action(), e.getMessage());
            return failure(command, e.getMessage() == null ? e.getClass().getName() : e.getMessage(), null);
        }
        finally {
            running.remove(key);
        }
    }

    /** Sends a structured progress event of {@code command}; dropped (logged) when the connection is gone. */
    private void sendEvent(final FermionMsg.Command command, final Map<String, Object> data, final WebSocketClientConnection connection) {
        final String frame = write(FermionMsg.Progress.of(command, null, data));
        if (frame == null || connection == null || !connection.isOpen()) {
            Log.debugv("fermion-client=progress correlationId={0} dropped (not connected)", command.correlationId());
            return;
        }
        try {
            connection.sendTextAndAwait(frame);
        }
        catch (final RuntimeException e) {
            Log.debugv("fermion-client=progress correlationId={0} error={1}", command.correlationId(), e.getMessage());
        }
    }

    private static FermionMsg.Result failure(final FermionMsg.Command command, final String error, final String errorKind) {
        return FermionMsg.Result.failure(command, error, errorKind);
    }

    @OnClose
    public void onClose(final WebSocketClientConnection connection) {
        client.connectionLost(connection);
    }

    @OnError
    public void onError(final Throwable t) {
        Log.warnv("fermion-client=error error={0}", t.getMessage());
    }

    private String write(final FermionMsg msg) {
        try {
            return mapper.writeValueAsString(msg);
        }
        catch (final Exception e) {
            Log.errorv(e, "fermion-client=write error={0}", e.getMessage());
            return null;
        }
    }
}
