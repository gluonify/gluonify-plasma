package io.gluonify.plasma.ws;

import io.gluonify.plasma.AppTokensResponse;
import io.gluonify.plasma.Instance;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Protocole entre un nœud (agent) et un control-plane, sur WebSocket (texte, JSON, champ « t » = type).
 * Le nœud ouvre la connexion (sortante) puis s'authentifie dans son premier message — jamais dans l'URL :
 * <ul>
 *   <li>nœud -> CP : {@link Hello} (identité connue) ou {@link Enroll} (premier démarrage, jeton à usage unique), puis {@link Ready} (une instance devient prête, ou ne l'est plus : tout de suite) et {@link State}
 *       toutes les 5 s, {@link TokensRequest} et {@link Redeploy} (le vault a redéployé un namespace) à la demande ;</li>
 *   <li>CP -> nœud : {@link Welcome} ou {@link Enrolled}, puis {@link Desired} (état désiré) dès qu'il change, {@link Tokens} en réponse
 *       et {@link Error} (suivie de la fermeture) en cas de refus.</li>
 * </ul>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
@JsonSubTypes({
        @JsonSubTypes.Type(value = Msg.Hello.class, name = "hello"),
        @JsonSubTypes.Type(value = Msg.Enroll.class, name = "enroll"),
        @JsonSubTypes.Type(value = Msg.State.class, name = "state"),
        @JsonSubTypes.Type(value = Msg.Ready.class, name = "ready"),
        @JsonSubTypes.Type(value = Msg.Slept.class, name = "slept"),
        @JsonSubTypes.Type(value = Msg.TokensRequest.class, name = "tokens-request"),
        @JsonSubTypes.Type(value = Msg.LockRequest.class, name = "lock-request"),
        @JsonSubTypes.Type(value = Msg.LockReply.class, name = "lock-reply"),
        @JsonSubTypes.Type(value = Msg.Redeploy.class, name = "redeploy"),
        @JsonSubTypes.Type(value = Msg.WgKey.class, name = "wg-key"),
        @JsonSubTypes.Type(value = Msg.Logs.class, name = "logs"),
        @JsonSubTypes.Type(value = Msg.LogsRequest.class, name = "logs-request"),
        @JsonSubTypes.Type(value = Msg.LogsFollow.class, name = "logs-follow"),
        @JsonSubTypes.Type(value = Msg.LogsChunk.class, name = "logs-chunk"),
        @JsonSubTypes.Type(value = Msg.LogsStop.class, name = "logs-stop"),
        @JsonSubTypes.Type(value = Msg.BootstrapHello.class, name = "bootstrap-hello"),
        @JsonSubTypes.Type(value = Msg.BootstrapAccepted.class, name = "bootstrap-accepted"),
        @JsonSubTypes.Type(value = Msg.BootstrapDone.class, name = "bootstrap-done"),
        @JsonSubTypes.Type(value = Msg.TaskRun.class, name = "task-run"),
        @JsonSubTypes.Type(value = Msg.TaskProgress.class, name = "task-progress"),
        @JsonSubTypes.Type(value = Msg.TaskResult.class, name = "task-result"),
        @JsonSubTypes.Type(value = Msg.TaskCancel.class, name = "task-cancel"),
        @JsonSubTypes.Type(value = Msg.Welcome.class, name = "welcome"),
        @JsonSubTypes.Type(value = Msg.Enrolled.class, name = "enrolled"),
        @JsonSubTypes.Type(value = Msg.Desired.class, name = "desired"),
        @JsonSubTypes.Type(value = Msg.Tokens.class, name = "tokens"),
        @JsonSubTypes.Type(value = Msg.Error.class, name = "error")
})
public sealed interface Msg {
    // ---- nœud -> control-plane
    /** Premier message d'un nœud déjà enrôlé. */
    record Hello(String node, String secret) implements Msg {}

    /** Premier message d'un nœud neuf : consomme un jeton à usage unique ; address vide = adresse source de la connexion. */
    record Enroll(String token, String address, int memoryMb, String arch, String role) implements Msg {
        public Enroll(String token, String address, int memoryMb, String arch) {
            this(token, address, memoryMb, arch, null);
        }
    }

    /**
     * Premier message d'un agent d'INSTALLATION (mode installer de Strange, avant tout enrôlement) : le jeton est celui de la demande d'amorçage (POST /nodes/bootstrap) ; il
     * n'est pas consommé ici (l'enrôlement du nœud, plus tard, le consomme). Voir gluonify-codex/docs/BOOTSTRAP.md.
     */
    record BootstrapHello(String token, String arch, String os, String version) implements Msg {}

    /** Résultat d'une tâche : status = ok, unchanged (déjà fait : idempotence), failed ou cancelled ; message court, sans secret. */
    record TaskResult(String id, String status, String message) implements Msg {}

    /** Avancement d'une tâche (ligne de sortie de l'installateur, sans secret). */
    record TaskProgress(String id, String message) implements Msg {}

    /** Instances en cours, prêtes (health check Quarkus UP) et génération avec laquelle chacune a démarré ; sert aussi de battement de cœur. */
    record State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures, Host host, Map<String, Startup> startups) implements Msg {
        public State {
            metrics = metrics == null ? Map.of() : metrics;
            failures = failures == null ? Map.of() : failures;
            startups = startups == null ? Map.of() : startups;
        }

        /** Compatibilité : état sans temps de démarrage. */
        public State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures, Host host) {
            this(running, ready, generations, metrics, failures, host, Map.of());
        }

        /** Compatibilité : état sans description de l'hôte. */
        public State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures) {
            this(running, ready, generations, metrics, failures, null, Map.of());
        }

        /** Compatibilité : état sans mesures. */
        public State(List<String> running, List<String> ready, Map<String, Long> generations) {
            this(running, ready, generations, Map.of(), Map.of(), null, Map.of());
        }

        /** Compatibilité : état sans générations. */
        public State(List<String> running, List<String> ready) {
            this(running, ready, Map.of(), Map.of(), Map.of(), null, Map.of());
        }
    }

    /**
     * Temps de démarrage d'une instance (joint à {@link State}, clé = nom de l'instance), mesurés par l'agent sur le DERNIER démarrage : {@code prepare} (début du démarrage ->
     * unité lancée : environnement, exécutable, sandbox), {@code listen} (unité lancée -> le port accepte), {@code ready} (unité lancée -> sonde de disponibilité en 200) et
     * {@code total} (début -> 200), en secondes ; -1 pour une phase non atteinte. {@code at} : fin du démarrage (ms epoch) ; {@code ok} faux = échec ({@code error} : cause courte).
     * {@code history} : les {@code total} des derniers démarrages réussis (le plus récent en dernier, bornée) ; {@code starts} et {@code failures} : compteurs depuis le lancement de l'agent.
     */
    record Startup(long at, boolean ok, double prepare, double listen, double ready, double total, String error, long starts, long failures, List<Double> history) {
        public Startup {
            history = history == null ? List.of() : history;
        }
    }

    /**
     * Changement de disponibilité d'UNE instance, signalé tout de suite (sans attendre le {@link State} du cycle de 5 s) : {@code ready} vrai quand la sonde de disponibilité
     * répond 200 pour la première fois après un démarrage, faux quand un contrôle périodique la voit retomber. {@code generation} : génération avec laquelle l'instance a
     * démarré ; {@code sinceStart} : secondes écoulées entre l'ordre de démarrage et l'observation côté agent (-1 pour une retombée). Le control-plane l'applique aussitôt
     * (l'ingress route vers l'instance) ; le {@link State} suivant reste la référence et corrige tout écart. Aucune réponse.
     */
    record Ready(String instance, boolean ready, long generation, double sinceStart) implements Msg {}

    /**
     * Accusé de l'agent après avoir appliqué l'état de sommeil voulu d'UNE instance (voir {@link Desired#sleeping()}) : {@code state} = « frozen », « stopped » ou « active »
     * (l'état atteint) ; {@code ok} faux : la transition a échoué ({@code error} : cause courte, par exemple cgroup.freeze absent) ; {@code ms} : durée de la transition côté agent.
     * Sert aux évènements et aux mesures ; le control-plane ne bloque jamais dessus. Pas de réponse.
     */
    record Slept(String instance, String state, boolean ok, String error, double ms) implements Msg {}

    /**
     * Description de l'hôte jointe à chaque {@link State} (lue dans /proc par l'agent) : version de l'agent, nombre de processeurs, mémoire utilisée (Mo), charge sur 1 minute,
     * espace disque libre du répertoire de données (Mo) et durée de fonctionnement de la machine (secondes). Une valeur inconnue vaut -1 (0 pour {@code cpuCount}).
     */
    record Host(String version, int cpuCount, int memoryUsedMb, double load, int diskFreeMb, long uptimeSec) {}

    /**
     * Dernières lignes du journal de l'instance {@code instance} de ce nœud, ou ses mesures Prometheus ({@code what} = "metrics" : ce que l'application expose sur
     * /q/metrics ; vide ou "logs" : le journal). Réponse : {@link Logs}.
     */
    record LogsRequest(long id, String instance, int tail, String what) implements Msg {
        public LogsRequest(long id, String instance, int tail) {
            this(id, instance, tail, null);
        }
    }

    /** Réponse à {@link LogsRequest} : status 200 + texte, 404 instance inconnue, 501 non pris en charge par ce runtime, 500 erreur. */
    record Logs(long id, int status, String text) implements Msg {}

    /**
     * Suivi du journal en continu (control-plane -> nœud) : le nœud répond par une suite de {@link LogsChunk} d'identifiant {@code id} (les {@code tail} dernières lignes, puis les
     * nouvelles), jusqu'à {@link LogsStop}, à la fin de la durée {@code maxMs} ou à la disparition de l'instance.
     */
    record LogsFollow(long id, String instance, int tail, long maxMs) implements Msg {}

    /** Morceau d'un suivi ({@link LogsFollow}) : {@code status} 200 + texte (lignes complètes, secrets masqués) ; {@code end} = dernier message du suivi (status 404/501/500 si échec). */
    record LogsChunk(long id, int status, String text, boolean end) implements Msg {}

    /** Arrêt d'un suivi ({@link LogsFollow}) demandé par le control-plane (client parti, durée écoulée, trop lent). */
    record LogsStop(long id) implements Msg {}

    /** Clé publique WireGuard du nœud (envoyée à chaque connexion tant que le chiffrement entre nœuds est activé) : le control-plane lui attribue une adresse du maillage. */
    record WgKey(String publicKey) implements Msg {}

    /** Le vault a redéployé le namespace ns (révision rev, croissante) : le control-plane redémarre les applis concernées, une réplique à la fois. */
    record Redeploy(String ns, long rev) implements Msg {}

    /** Jetons à courte durée pour des applications du nœud ; id relie la réponse à la demande. */
    record TokensRequest(long id, List<String> apps) implements Msg {}

    /**
     * Verrou de fichier entre nœuds (nœud -> control-plane), demandé par le démon de montage de l'application. {@code op} : « set » (pose ; 409 si un autre propriétaire gêne),
     * « test » (F_GETLK : 200, avec le verrou gênant s'il y en a un), « unset » (déverrouille la plage), « release » (retire tout ce que ce propriétaire tient sur le fichier),
     * « release-app » (retire tout ce que CE nœud tient pour l'application : montage arrêté). {@code key} : « application/genre/chemin » ; {@code end} : {@link Long#MAX_VALUE} = fin du fichier.
     */
    record LockRequest(long id, String op, String key, boolean flock, String owner, boolean write, long start, long end) implements Msg {}

    // ---- control-plane -> nœud
    /** Réponse à {@link LockRequest} : 200, 409 (gêné : le verrou gênant est décrit) ou 400. */
    record LockReply(long id, int status, String conflictOwner, boolean conflictWrite, long conflictStart, long conflictEnd, String error) implements Msg {}

    record Welcome(String node) implements Msg {}

    record Enrolled(String node, String secret) implements Msg {}

    /** {@code sleeping} : état de sommeil voulu des instances de CE nœud (instance -> « frozen » ou « stopped » ; une instance absente est active) : l'agent gèle ou arrête, jamais ne redémarre une instance « stopped », et dégèle ou démarre quand elle redevient active. Mode endormi : voir gluonify-codex/ARCHITECTURE.md.
     * État désiré du nœud, URLs API des control-planes membres (pour le basculement) environnement du cluster (ENV_NAME) et adresse d'une instance prête de chaque application (services : app -> http://hôte:port, pour SERVICE_<APP>_URL) adresse de ce nœud (GLUONIFY_SELF_URL), adresses de tous les nœuds vivants (pairs des ports supplémentaires), maillage WireGuard (wireguard) et adresses des control-planes dans le maillage (overlayControlPlanes : l'agent s'y reconnecte, c'est par là que passent les secrets) et secrets de démarrage demandés par les instances de CE nœud (nom -> valeur ; détenus par le control-plane, jamais écrits dans l'état Raft). */
    record Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store, Map<String, String> sleeping) implements Msg {
        public Desired {
            sleeping = sleeping == null ? Map.of() : sleeping;
        }

        /** Compatibilité : sans instances endormies. */
        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, Map.of());
        }

        /** Compatibilité : sans nœuds de stockage. */
        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, null);
        }

        /** Compatibilité : sans nom d'environnement ni adresses de services. */
        public Desired(List<Instance> desired, List<String> controlPlanes) {
            this(desired, controlPlanes, null, Map.of(), Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(List<Instance> desired, List<String> controlPlanes, String envName) {
            this(desired, controlPlanes, envName, Map.of(), Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services) {
            this(desired, controlPlanes, envName, services, Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets) {
            this(desired, controlPlanes, envName, services, secrets, null, List.of(), null, List.of(), null);
        }
    }

    /** Nœuds de stockage prêts (adresses des instances du service « store ») et jeton d'accès (sous WireGuard, seulement par le tunnel). L'agent y télécharge les exécutables {@code gluonify-rup:<sha256>}. */
    record Store(List<String> urls, String token) {}

    /** Maillage WireGuard du nœud : son adresse, le port UDP et ses pairs (les autres nœuds et les control-planes). */
    record Wg(String self, int port, List<WgPeer> peers) {}

    record WgPeer(String id, String publicKey, String endpoint, String overlay) {}

    /** status 200 : tokens renseigné ; 501 : gluonify-charm non configuré ; 503 : gluonify-charm injoignable. */
    record Tokens(long id, int status, Map<String, AppTokensResponse.AppToken> tokens, String error) implements Msg {}

    /** Demande d'amorçage reconnue : identifiant, rôle visé, version de distribution attendue et plus petite séquence de manifeste acceptée (anti-retour en arrière). */
    record BootstrapAccepted(String request, String role, String release, long minSequence) implements Msg {}

    /** Une tâche du catalogue fermé (voir TaskCatalogue) : un seul type à la fois ; l'agent revalide tout et refuse ce qui n'est pas au catalogue. */
    record TaskRun(String id, String type, Map<String, String> params, int timeoutSec) implements Msg {
        public TaskRun {
            params = params == null ? Map.of() : params;
        }
    }

    /** Annule la tâche en cours (l'agent arrête le processus de l'installateur). */
    record TaskCancel(String id) implements Msg {}

    /** Fin de l'amorçage : ok = toutes les tâches ont réussi ; sinon error dit pourquoi. L'agent d'installation passe alors la main (ou s'arrête). */
    record BootstrapDone(String request, boolean ok, String error) implements Msg {}

    /** Refus ; la connexion est fermée ensuite. 401 : identité inconnue (le nœud doit se ré-enrôler) ; 403 : jeton invalide. */
    record Error(int code, String message) implements Msg {}
}
