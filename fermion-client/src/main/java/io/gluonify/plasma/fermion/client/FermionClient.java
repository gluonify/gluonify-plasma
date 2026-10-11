package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.fermion.FermionMsg;
import io.gluonify.plasma.fermion.WorkflowDescriptor;
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
import java.util.function.BiConsumer;
import tools.jackson.databind.ObjectMapper;

/**
 * Connexion permanente à gluonify-fermion et API de soumission de workflows. Un nœud peut soumettre, exécuter des tâches (beans
 * {@link SkillHandler}), ou les deux. Reconnexion automatique ; les {@code Start} émis pendant une coupure attendent dans une file bornée.
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
     * Un {@link WebSocketConnector} est un constructeur à état : {@code addHeader}, {@code baseUri} et {@code pathParam} s'accumulent. Réutilisé, il
     * ajouterait des en-têtes à chaque tentative jusqu'au refus du serveur (HTTP 431). On en prend donc un NEUF par tentative, détruit ensuite.
     */
    @Inject
    Instance<WebSocketConnector<FermionClientEndpoint>> connectorFactory;

    @Inject
    ObjectMapper mapper;

    private final Object lock = new Object();
    private final Deque<FermionMsg.Start> pending = new ArrayDeque<>();
    private volatile WebSocketClientConnection connection;
    private volatile BiConsumer<String, String> errorSink;
    private volatile int connectorsCreated;

    /** Client actif : activé et URL de fermion renseignée. */
    public boolean isActive() {
        return config.enabled() && config.url().filter(u -> !u.isBlank()).isPresent();
    }

    public boolean isConnected() {
        final WebSocketClientConnection current = connection;
        return current != null && current.isOpen();
    }

    /** Nombre de {@code Start} en attente de reconnexion. */
    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    /** Nombre de connecteurs créés depuis le démarrage (un par tentative de connexion). */
    int connectorsCreated() {
        return connectorsCreated;
    }

    /** Callback appelé quand fermion refuse ou abandonne un workflow soumis (correlationId, message). Un seul : le dernier remplace. */
    public void onWorkflowError(final BiConsumer<String, String> sink) {
        this.errorSink = sink;
    }

    void fireWorkflowError(final String correlationId, final String message) {
        final BiConsumer<String, String> sink = errorSink;
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
        final WebSocketClientConnection current = connection;
        // isOpen() peut rester vrai sur une connexion TCP à moitié morte : on sonde par un ping BORNÉ et on reconnecte s'il échoue.
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
                    // déjà morte
                }
                connection = null;
            }
        }
        connect();
    }

    /** Appelé à la fermeture du canal : oublie la connexion (comparée par identifiant, pour ne pas écraser une connexion plus récente). */
    void connectionLost(final WebSocketClientConnection closed) {
        final WebSocketClientConnection current = connection;
        if (current != null && closed != null && current.id().equals(closed.id())) {
            connection = null;
            Log.infov("fermion-client=connection-lost node={0}", identity.id());
        }
    }

    /** Tente une connexion (un connecteur neuf) ; en cas de succès : {@code Auth}, {@code Hello}, puis rejeu de la file. */
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
            final String url = config.url().orElseThrow();
            final WebSocketConnector<FermionClientEndpoint> connector = connectorFactory.get();
            connectorsCreated++;
            try {
                connector.baseUri(url).pathParam("nodeId", identity.id());
                final WebSocketClientConnection opened = connector.connect().await().atMost(CONNECT_TIMEOUT);
                opened.sendTextAndAwait(mapper.writeValueAsString(new FermionMsg.Auth(token)));
                opened.sendTextAndAwait(mapper.writeValueAsString(hello()));
                connection = opened;
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

    /** Soumet un workflow ; la transaction globale prend par défaut le premier {@code correlationId} (côté fermion). */
    public void startWorkflow(final String workflow, final String correlationId, final Map<String, Object> payload) {
        startWorkflow(workflow, correlationId, null, payload);
    }

    /**
     * Soumet un workflow rattaché à une transaction métier existante. Pendant une coupure, le {@code Start} est gardé dans la file bornée et rejoué à
     * la reconnexion.
     *
     * @throws FermionClientException si le client est inactif
     * @throws FermionQueueFullException si la coupure dure et que la file est pleine
     */
    public void startWorkflow(final String workflow, final String correlationId, final String globalTransactionId, final Map<String, Object> payload) {
        startWorkflow(workflow, correlationId, globalTransactionId, payload, null);
    }

    /**
     * Comme {@link #startWorkflow(String, String, String, Map)}, en fixant l'organisation de l'exécution : fermion la transmet à chaque tâche à la
     * place de toute valeur {@code organization} de la charge utile. Réservé aux nœuds de la plateforme qui connaissent l'organisation de l'appelant.
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
                    connection.sendTextAndAwait(mapper.writeValueAsString(start));
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

    /** Ré-annonce compétences et workflows utilisés (à appeler quand cet ensemble change). Sans effet hors connexion : le prochain {@code Hello} les porte. */
    public void announceUsage() {
        synchronized (lock) {
            if (!isConnected()) {
                Log.infov("fermion-client=announce-usage node={0} not connected, deferred to next hello", identity.id());
                return;
            }
            try {
                connection.sendTextAndAwait(mapper.writeValueAsString(hello()));
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
        return new FermionMsg.Hello(identity.appId(), identity.nodeId(), registry.skills(), used, self);
    }
}
