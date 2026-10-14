package io.gluonify.plasma.ws;

import io.gluonify.plasma.AppTokensResponse;
import io.gluonify.plasma.Instance;
import io.gluonify.plasma.ServiceToken;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Protocole entre un nœud (agent) et un control-plane, sur WebSocket (texte, JSON, champ « t » = type).
 * Le nœud ouvre la connexion (sortante) puis s'authentifie dans son premier message — jamais dans l'URL :
 * <ul>
 *   <li>nœud -> CP : {@link Hello} (identité connue) ou {@link Enroll} (premier démarrage, jeton à usage unique), puis {@link Ready} (une instance devient prête, ou ne l'est plus : tout de suite) et {@link State}
 *       toutes les 5 s, {@link TokensRequest}, {@link ServiceTokensRequest} et {@link Redeploy} (le top a redéployé un namespace) à la demande ;</li>
 *   <li>CP -> nœud : {@link Welcome} ou {@link Enrolled}, puis {@link Desired} (état désiré) dès qu'il change, {@link Tokens} et {@link ServiceTokens} en réponse
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
        @JsonSubTypes.Type(value = Msg.ServiceTokensRequest.class, name = "service-tokens-request"),
        @JsonSubTypes.Type(value = Msg.LockRequest.class, name = "lock-request"),
        @JsonSubTypes.Type(value = Msg.LockReply.class, name = "lock-reply"),
        @JsonSubTypes.Type(value = Msg.Redeploy.class, name = "redeploy"),
        @JsonSubTypes.Type(value = Msg.Usage.class, name = "usage"),
        @JsonSubTypes.Type(value = Msg.UsageAck.class, name = "usage-ack"),
        @JsonSubTypes.Type(value = Msg.WgKey.class, name = "wg-key"),
        @JsonSubTypes.Type(value = Msg.Logs.class, name = "logs"),
        @JsonSubTypes.Type(value = Msg.LogsRequest.class, name = "logs-request"),
        @JsonSubTypes.Type(value = Msg.LogsFollow.class, name = "logs-follow"),
        @JsonSubTypes.Type(value = Msg.LogsChunk.class, name = "logs-chunk"),
        @JsonSubTypes.Type(value = Msg.LogsStop.class, name = "logs-stop"),
        @JsonSubTypes.Type(value = Msg.InstanceReportRequest.class, name = "instance-report-request"),
        @JsonSubTypes.Type(value = Msg.InstanceReport.class, name = "instance-report"),
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
        @JsonSubTypes.Type(value = Msg.ServiceTokens.class, name = "service-tokens"),
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
    /**
     * {@code pins} : empreintes des images désignées par une étiquette que CE nœud a résolues (application -> {@link ImagePin}) ; le control-plane enregistre la première
     * pour tout le cluster (gluonify-codex/provenance.md § 8). Absent d'un ancien agent : vide.
     */
    record State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures, Host host, Map<String, Startup> startups,
                 Map<String, ImagePin> pins) implements Msg {
        public State {
            metrics = metrics == null ? Map.of() : metrics;
            failures = failures == null ? Map.of() : failures;
            startups = startups == null ? Map.of() : startups;
            pins = pins == null ? Map.of() : pins;
        }

        /** Compatibilité : état sans empreintes d'images. */
        public State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures, Host host, Map<String, Startup> startups) {
            this(running, ready, generations, metrics, failures, host, startups, Map.of());
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
    /**
     * Description de l'hôte. {@code x86Level} : niveau x86-64 du processeur (« v1 » à « v4 », lu dans les drapeaux de /proc/cpuinfo) ; null sur arm64, macOS, Windows et pour un ancien
     * agent : le scheduler ne place jamais un exécutable d'un niveau supérieur sur ce nœud (voir {@link io.gluonify.plasma.AppSpec#artifactLevel}). {@code pythonVersion} : version
     * mineure du {@code python3} du nœud (« 3.13 ») ; null : pas de python3 (ou ancien agent) : le scheduler n'y place aucune application Python, et une application Python n'y va
     * que si sa version ({@link io.gluonify.plasma.AppSpec#pythonVersion}) est la même.
     */
    record Host(String version, int cpuCount, int memoryUsedMb, double load, int diskFreeMb, long uptimeSec, String x86Level, String pythonVersion) {
        /** Compatibilité : sans version de Python. */
        public Host(String version, int cpuCount, int memoryUsedMb, double load, int diskFreeMb, long uptimeSec, String x86Level) {
            this(version, cpuCount, memoryUsedMb, load, diskFreeMb, uptimeSec, x86Level, null);
        }

        /** Compatibilité : sans niveau x86-64. */
        public Host(String version, int cpuCount, int memoryUsedMb, double load, int diskFreeMb, long uptimeSec) {
            this(version, cpuCount, memoryUsedMb, load, diskFreeMb, uptimeSec, null);
        }
    }

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
     * Rapport d'une instance, demandé par le control-plane (control-plane -> nœud) : seul l'agent peut le fournir pour une instance endormie (gelée ou arrêtée), dont le processus ne
     * répond plus ou n'existe plus. Ne touche JAMAIS l'application (ni /q/metrics, ni son port) : elle n'est donc jamais réveillée par cette demande. {@code tail} : lignes de journal
     * voulues (bornées côté agent). Réponse : {@link InstanceReport}.
     */
    record InstanceReportRequest(long id, String instance, int tail) implements Msg {}

    /**
     * Réponse à {@link InstanceReportRequest} : {@code status} 200 (404 instance inconnue sur ce nœud, 500 erreur). {@code state} : active | frozen | stopped | absent (désirée mais sans unité,
     * hors sommeil) ; {@code since} : début de cet état (ms epoch, 0 si inconnu) ; {@code running} : l'unité existe (une gelée tourne encore). {@code cpuSeconds} et {@code memoryBytes} : lus
     * dans le cgroup (compteurs d'une instance qui tourne, y compris gelée) ou, pour une instance arrêtée, dernières valeurs connues ({@code lastKnown} vrai, mesurées à {@code measuredAt}, ms
     * epoch) ; null si jamais mesurées. {@code startup} : dernier démarrage mesuré (voir {@link Startup}) ; {@code starts} : démarrages depuis le lancement de l'agent ;
     * {@code logs} : les dernières lignes du journal (journald conserve celui d'une instance arrêtée), secrets masqués ; {@code at} : ms epoch de la réponse.
     */
    record InstanceReport(long id, int status, String error, String instance, String state, long since, boolean running, Double cpuSeconds, Double memoryBytes, boolean lastKnown,
                          long measuredAt, Startup startup, long starts, List<String> logs, long at) implements Msg {}

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

    /** Le top a redéployé le namespace ns (révision rev, croissante) : le control-plane redémarre les applis concernées, une réplique à la fois. */
    record Redeploy(String ns, long rev) implements Msg {}

    /** Jetons à courte durée pour des applications du nœud ; id relie la réponse à la demande. */
    record TokensRequest(long id, List<String> apps) implements Msg {}

    /**
     * Jetons d'application vers service ({@link ServiceToken}) pour des applications du nœud : un par service de leur {@code uses} que la politique
     * du control-plane couvre. Un control-plane plus ancien ignore ce message : l'agent ne bloque jamais un démarrage sur la réponse.
     */
    record ServiceTokensRequest(long id, List<String> apps) implements Msg {}

    /**
     * Verrou de fichier entre nœuds (nœud -> control-plane), demandé par le démon de montage de l'application. {@code op} : « set » (pose ; 409 si un autre propriétaire gêne),
     * « test » (F_GETLK : 200, avec le verrou gênant s'il y en a un), « unset » (déverrouille la plage), « release » (retire tout ce que ce propriétaire tient sur le fichier),
     * « release-app » (retire tout ce que CE nœud tient pour l'application : montage arrêté). {@code key} : « application/genre/chemin » ; {@code end} : {@link Long#MAX_VALUE} = fin du fichier.
     */
    record LockRequest(long id, String op, String key, boolean flock, String owner, boolean write, long start, long end) implements Msg {}

    /**
     * Lot d'enregistrements d'usage de l'agent (metering.md § 4.3), relayé par le control-plane au calorimètre : {@code body} est exactement le corps
     * {@code {"records":[…]}} de {@code UsageEmitter}. Réponse : {@link UsageAck} de même {@code id}. Un control-plane ancien l'ignore (l'agent garde son spool).
     */
    record Usage(long id, String body) implements Msg {}
    // n'est envoyé qu'à un control-plane qui annonce FEATURE_USAGE

    // ---- control-plane -> nœud
    /** Réponse à {@link Usage} : statut de type HTTP (2xx accepté, 413 trop gros, 422 refusé, 503 calorimètre injoignable…). */
    record UsageAck(long id, int status) implements Msg {}

    /** Réponse à {@link LockRequest} : 200, 409 (gêné : le verrou gênant est décrit) ou 400. */
    record LockReply(long id, int status, String conflictOwner, boolean conflictWrite, long conflictStart, long conflictEnd, String error) implements Msg {}

    /**
     * Fonctions facultatives du protocole que le control-plane comprend, annoncées dans {@link Welcome} et {@link Enrolled} : un agent n'envoie un message additif
     * qu'à un control-plane qui l'annonce (un control-plane ancien refuse un message inconnu et ferme la connexion).
     */
    String FEATURE_USAGE = "usage";

    /** {@code features} : voir {@link #FEATURE_USAGE} ; vide pour un control-plane ancien. */
    record Welcome(String node, List<String> features) implements Msg {
        public Welcome {
            features = features == null ? List.of() : List.copyOf(features);
        }

        public Welcome(String node) { this(node, null); }
    }

    record Enrolled(String node, String secret, List<String> features) implements Msg {
        public Enrolled {
            features = features == null ? List.of() : List.copyOf(features);
        }

        public Enrolled(String node, String secret) { this(node, secret, null); }
    }

    /** {@code sleeping} : état de sommeil voulu des instances de CE nœud (instance -> « frozen » ou « stopped » ; une instance absente est active) : l'agent gèle ou arrête, jamais ne redémarre une instance « stopped », et dégèle ou démarre quand elle redevient active. Mode endormi : voir gluonify-codex/ARCHITECTURE.md.
     * État désiré du nœud, URLs API des control-planes membres (pour le basculement) environnement du cluster (GLUONIFY_ENVIRONMENT) et adresse d'une instance prête de chaque application (services : app -> http://hôte:port, pour GLUONIFY_SERVICE_<APP>_URL) adresse de ce nœud (GLUONIFY_SELF_URL), adresses de tous les nœuds vivants (pairs des ports supplémentaires), maillage WireGuard (wireguard) et adresses des control-planes dans le maillage (overlayControlPlanes : l'agent s'y reconnecte, c'est par là que passent les secrets) et secrets de démarrage demandés par les instances de CE nœud (nom -> valeur ; détenus par le control-plane, jamais écrits dans l'état Raft). */
    record Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store, Map<String, String> sleeping,
                   Map<String, ImagePin> pins, String tokenPolicy) implements Msg {
        public Desired {
            sleeping = sleeping == null ? Map.of() : sleeping;
            pins = pins == null ? Map.of() : pins;
        }

        /**
         * Compatibilité : sans empreinte de la politique des jetons de service. {@code tokenPolicy} : empreinte courte (catalogue effectif et applications de la
         * plateforme) de la politique de jetons d'application vers service du control-plane ; quand elle change, l'agent oublie les jetons détenus et les redemande.
         * Absente ({@code null}, control-plane plus ancien) : l'agent fait comme avant (renouvellement à l'approche de l'expiration).
         */
        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store, Map<String, String> sleeping,
                       Map<String, ImagePin> pins) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, sleeping, pins, null);
        }

        /** Compatibilité : sans empreintes d'images épinglées pour le cluster. */
        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store, Map<String, String> sleeping) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, sleeping, Map.of(), null);
        }

        /** Compatibilité : sans instances endormies. */
        public Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, Map.of(), Map.of(), null);
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

    /**
     * Empreinte d'une image désignée par une étiquette ({@code image} : la chaîne de l'AppSpec, {@code dépôt:1.0} ; {@code digest} : {@code sha256:…} du manifeste).
     * Dans {@link State} : résolue par le nœud au premier démarrage. Dans {@link Desired} : épingle du cluster (la première signalée), que chaque nœud lance par
     * {@code dépôt@digest} sans plus résoudre l'étiquette (gluonify-codex/provenance.md § 8).
     */
    record ImagePin(String image, String digest) {}

    /** Nœuds de stockage prêts (adresses des instances du service « store ») et jeton d'accès (sous WireGuard, seulement par le tunnel). L'agent y télécharge les exécutables {@code gluonify-rup:<sha256>}. */
    record Store(List<String> urls, String token) {}

    /** Maillage WireGuard du nœud : son adresse, le port UDP et ses pairs (les autres nœuds et les control-planes). */
    record Wg(String self, int port, List<WgPeer> peers) {}

    record WgPeer(String id, String publicKey, String endpoint, String overlay) {}

    /** status 200 : tokens renseigné ; 501 : gluonify-charm non configuré ; 503 : gluonify-charm injoignable. */
    record Tokens(long id, int status, Map<String, AppTokensResponse.AppToken> tokens, String error) implements Msg {}

    /**
     * Réponse à {@link ServiceTokensRequest} : application -> ses jetons (liste vide : aucun service couvert ; application absente : pas placée sur ce
     * nœud ou inconnue). status 200 : tokens renseigné ; 501 : gluonify-charm non configuré ; 503 : gluonify-charm injoignable ; 403 : refusé (hors tunnel chiffré).
     */
    record ServiceTokens(long id, int status, Map<String, List<ServiceToken>> tokens, String error, String policy) implements Msg {
        public ServiceTokens {
            tokens = tokens == null ? Map.of() : tokens;
        }

        /** Compatibilité : sans {@code policy}, l'empreinte de la politique sous laquelle les jetons ont été émis (voir {@link Desired#tokenPolicy()}). */
        public ServiceTokens(long id, int status, Map<String, List<ServiceToken>> tokens, String error) {
            this(id, status, tokens, error, null);
        }
    }

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
