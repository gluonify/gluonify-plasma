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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.ObjectMapper;

/**
 * Côté client du canal : exécute les {@code Command} (hors du thread d'événements) et répond par un {@code Result}, relaie les {@code Error}.
 * L'authentification et le {@code Hello} sont émis par {@link FermionClient} dès la connexion obtenue.
 * <p>
 * Traitement {@link InboundProcessingMode#CONCURRENT} : chaque {@code Command} est exécutée sur un fil de travail dès sa réception, sans attendre la
 * fin des précédentes. Une compétence longue (build natif, attente d'une décision humaine) ne bloque donc plus les autres tâches du nœud ; chaque
 * {@code Result} part dès que sa tâche se termine, dans l'ordre des fins et non des arrivées (fermion les apparie par {@code correlationId}).
 * Un handler doit donc être sûr en accès concurrent (ce que sont déjà les beans {@code @ApplicationScoped} sans état).
 * <p>
 * Battement : tant qu'une tâche s'exécute, un {@code Progress} part toutes les {@code gluonify.fermion.client.heartbeat-interval} (60 s par
 * défaut) ; fermion ne renvoie donc pas une tâche longue à un autre nœud tant que celui-ci donne signe de vie.
 */
@WebSocketClient(path = "/ws/fermion/{nodeId}", inboundProcessingMode = InboundProcessingMode.CONCURRENT)
public class FermionClientEndpoint {

    @Inject
    SkillHandlerRegistry registry;

    @Inject
    FermionClient client;

    @Inject
    ObjectMapper mapper;

    @Inject
    FermionClientConfig config;

    /** Fil des battements, partagé par toutes les tâches (créé à la première tâche, jamais en statique). */
    private volatile ScheduledExecutorService heartbeats;

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
        if (msg instanceof final FermionMsg.Error error) {
            client.fireWorkflowError(error.correlationId(), error.message());
            return null;
        }
        if (msg instanceof final FermionMsg.Command command) {
            final ScheduledFuture<?> beat = startHeartbeat(command, connection);
            try {
                return write(execute(command));
            }
            finally {
                if (beat != null) {
                    beat.cancel(false);
                }
            }
        }
        return null;
    }

    /** Programme les battements de {@code command} ; {@code null} si désactivés. */
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

    private FermionMsg.Result execute(final FermionMsg.Command command) {
        final SkillHandler handler = registry.get(command.action());
        if (handler == null) {
            Log.warnv("fermion-client=command action={0} no handler", command.action());
            return failure(command, "No handler for action " + command.action(), "NO_HANDLER");
        }
        try {
            final Map<String, Object> out = handler.handle(
                    new TaskContext(command.globalTransactionId(), command.correlationId(), command.action(), command.payload(), mapper, command.stepId()));
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
