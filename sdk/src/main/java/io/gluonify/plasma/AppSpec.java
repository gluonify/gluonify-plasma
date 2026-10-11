package io.gluonify.plasma;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Déclaration d'une application. Tout est optionnel sauf le nom et l'image (ou les exécutables par architecture) : le reste est déduit. */
@Schema(description = "Application déclarée.")
public record AppSpec(@Schema(description = "Label DNS (a-z, 0-9, -) ; sert de sous-domaine", example = "shop") String name, @Schema(description = "Image OCI", example = "registry.example.org/shop:1.0") String image, @Schema(description = "Répliques", example = "2") Integer replicas, @Schema(description = "Port du conteneur", example = "8080") Integer port, @Schema(description = "Mémoire par réplique (Mo)", example = "64") Integer memoryMb,
                      @Schema(description = "Namespace gluonify-top dont config et secrets sont injectés en variables d'environnement", example = "shop") String topNamespace,
                      @Schema(description = "Exécutables natifs par architecture du nœud (amd64, arm64) : URL#sha256=… ; remplace « image » (runtime process). "
                              + "Seuls les nœuds d'une architecture listée reçoivent l'application.", example = "{\"amd64\":\"https://b/artifacts/ab..#sha256=ab..\",\"arm64\":\"https://b/artifacts/cd..#sha256=cd..\"}") Map<String, String> artifacts,
                      @Schema(description = "Identifiant stable de l'application (attribué par le control-plane) : ses espaces du top sont <uuid>.app et <uuid>.gluonify", example = "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12") String uuid,
                      @Schema(description = "Espace de travail temporaire par réplique (Mo, 0 à 2048, défaut 64) : en mémoire, vidé à chaque démarrage", example = "64") Integer scratchMb,
                      @Schema(description = "Monter le NAS de l'hébergeur dans /nas (option ; runtime process)", example = "false") Boolean nas,
                      @Schema(description = "Variables d'environnement NON secrètes (stockées dans l'état du cluster)", example = "{\"GLUONIFY_GDOWN_BOLT_ENABLED\":\"false\"}") Map<String, String> env,
                      @Schema(description = "Secrets de démarrage (noms de variables, globaux au cluster) : générés par le control-plane s'ils n'existent pas, gardés par lui (fichier 0600, hors de l'état Raft) et envoyés au seul nœud qui exécute l'appli, dans son fichier d'environnement 0600", example = "[\"GLUONIFY_GDOWN_AUTH_TOKEN\"]") java.util.List<String> generated,
                      @Schema(description = "Applications utilisées : l'agent injecte GLUONIFY_SERVICE_<APP>_URL (adresse d'une instance prête), lue au démarrage", example = "[\"gdown\"]") java.util.List<String> uses,
                      @Schema(description = "Identifiant d'un nœud : l'application n'est placée que sur lui (données locales, secrets de démarrage du nœud)", example = "n-1a2b3c4d") String pin,
                      @Schema(description = "true : aucune route publique (l'ingress répond 404) ; l'application n'est joignable que par ses clients (uses)", example = "true") Boolean internal,
                      @Schema(description = "Ports TCP supplémentaires (1025-19999 ou 30000-65535), au même numéro sur le nœud : bind autorisé, joignables depuis les autres nœuds (par exemple le port Raft d'une base répliquée). Au plus un exemplaire de l'application par nœud.", example = "[9100]") java.util.List<Integer> ports,
                      @Schema(description = "Rôle de nœud exigé : seuls les nœuds de ce rôle (par exemple « storage ») reçoivent l'application ; sans rôle, elle va sur les nœuds ordinaires, jamais sur un nœud de stockage", example = "storage") String role,
                      @Schema(description = "Systèmes de fichiers distribués montés dans l'application : « std » (/distributed/std, durable, sans taille maximale) et/ou « tmp » (/distributed/tmp, expire 24 h après la dernière écriture) : données sur les nœuds de stockage ; « gdown » (/distributed/gdown, durable, 60 Mio au plus par fichier) : données dans graph-db. Propres à l'application ; std et tmp exigent le stockage activé, graph la pile.", example = "[\"std\"]") java.util.List<String> distributed,
                      @Schema(description = "Nom d'un secret de téléchargement (PULL_…, enregistré par PUT /stack/secrets/{nom}) : sa valeur est envoyée telle quelle dans l'en-tête Authorization pour télécharger l'exécutable (dépôt privé). Jamais écrit dans l'état du cluster ni injecté dans l'application.", example = "PULL_GITLAB") String pullSecret,
                      @Schema(description = "Quota par système de fichiers distribué (Mo, 1 à 1048576), appliqué à CHACUN des genres montés (std, tmp, graph) : au-delà, l'écriture est refusée (507) ; sans valeur, seuls les plafonds par défaut s'appliquent. Quota souple : la taille est relevée toutes les 20 s, plusieurs écritures simultanées peuvent le dépasser un instant.", example = "2048") Integer distributedQuotaMb,
                      @Schema(description = "true : verrous de fichiers (flock, fcntl/POSIX, O_EXCL) valables ENTRE NŒUDS sur les systèmes de fichiers distribués de l'application : un démon de montage (gluonify-bottom) est placé devant le montage rclone et délègue les verrous au control-plane (bail : un nœud sans signe de vie depuis 30 s perd ses verrous). Sans cela, les verrous ne valent que sur un même nœud. Coûte un second saut FUSE ; à demander explicitement. Pris en compte au démarrage de l'application (POST /apps/{nom}/redeploy après un changement).", example = "true") Boolean distributedLocks,
                      @Schema(description = "Mode endormi (scale-to-zero) : l'application inactive est gelée puis arrêtée, et réveillée par la requête suivante ; absent ou enabled=false : jamais endormie. Voir gluonify-codex/ARCHITECTURE.md, « Mode endormi ».") SleepSpec sleep) {
    /** Compatibilité : sans mode endormi. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports, String role, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, role, distributed, pullSecret, distributedQuotaMb, distributedLocks, null);
    }


    /** Compatibilité : sans verrous entre nœuds. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports, String role, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, role, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibilité : sans quota. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports, String role, java.util.List<String> distributed, String pullSecret) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, role, distributed, pullSecret, null);
    }

    /** Compatibilité : sans secret de téléchargement. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports, String role, java.util.List<String> distributed) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, role, distributed, null);
    }

    /** Compatibilité : sans systèmes de fichiers distribués. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports, String role) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, role, null, null);
    }

    /** Compatibilité : sans rôle de nœud. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal, java.util.List<Integer> ports) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, ports, null, null);
    }

    /** Compatibilité : sans ports supplémentaires. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas, Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, env, generated, uses, pin, internal, null, null);
    }

    /** Compatibilité : sans identifiant (le control-plane en attribue un). */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, null);
    }

    /** Compatibilité : espace de travail par défaut, sans NAS. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, null, null);
    }

    /** Compatibilité : sans variables, secrets générés, services, nœud imposé ni mode interne. */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, Map<String, String> artifacts, String uuid, Integer scratchMb, Boolean nas) {
        this(name, image, replicas, port, memoryMb, topNamespace, artifacts, uuid, scratchMb, nas, null, null, null, null, null, null);
    }
    /** Compatibilité : application mono-livrable (image OCI ou exécutable valable pour tout nœud). */
    public AppSpec(String name, String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace) {
        this(name, image, replicas, port, memoryMb, topNamespace, null);
    }

    /** Vrai si le mode endormi est activé pour cette application. */
    public boolean sleepEnabled() { return sleep != null && sleep.enabled(); }

    /** Namespace top des données saisies par le client (lecture/écriture). */
    public String appNamespace() { return uuid == null ? null : uuid + ".app"; }

    /** Namespace top des données gérées par le PaaS (limites du contrat, liens ; lecture seule pour le client). */
    public String gluonifyNamespace() { return uuid == null ? null : uuid + ".gluonify"; }

    /** Variables que le PaaS fournit lui-même : une application ne peut pas les imposer. */
    public static final Set<String> RESERVED_ENV = Set.of("QUARKUS_HTTP_PORT", "QUARKUS_HTTP_HOST", "GLUONIFY_ENVIRONMENT", "GLUONIFY_REPLICA", "GLUONIFY_SELF_URL", "GLUONIFY_NODE_ADDRESS");
    public static final int DEFAULT_SCRATCH_MB = 64;
    public static final int MAX_SCRATCH_MB = 2048;

    /** Linux : {@code amd64}, {@code arm64} ; nœuds de développement du runtime {@code host} : {@code darwin-*} (macOS) et {@code windows-*}. */
    public static final Set<String> ARCHITECTURES = Set.of("amd64", "arm64", "darwin-amd64", "darwin-arm64", "windows-amd64", "windows-arm64");

    public AppSpec {
        if (name == null || !name.matches("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?")) {
            throw new IllegalArgumentException("nom d'application invalide (label DNS attendu)");
        }
        if (name.equals("api") || name.startsWith("ns") && name.length() <= 4) {
            throw new IllegalArgumentException("nom réservé");
        }
        if (artifacts == null) artifacts = Map.of();
        if (!artifacts.isEmpty()) {
            for (var e : artifacts.entrySet()) {
                if (!ARCHITECTURES.contains(e.getKey())) throw new IllegalArgumentException("architecture inconnue : " + e.getKey() + " (amd64, arm64, darwin-amd64, darwin-arm64, windows-amd64 ou windows-arm64)");
                if (e.getValue() == null || e.getValue().isBlank() || e.getValue().length() > 2048) throw new IllegalArgumentException("exécutable invalide pour " + e.getKey());
            }
            artifacts = Collections.unmodifiableMap(new TreeMap<>(artifacts)); // ordre stable (réplication, instantanés)
        } else if (image == null || image.isBlank()) {
            throw new IllegalArgumentException("image ou artifacts requis");
        }
        replicas = replicas == null ? 1 : replicas;
        port = port == null ? 8080 : port;
        memoryMb = memoryMb == null ? 64 : memoryMb;
        if (topNamespace != null && !topNamespace.matches("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?(\\.(app|gluonify))?")) {
            throw new IllegalArgumentException("topNamespace invalide (label DNS attendu, éventuellement suffixé .app ou .gluonify)");
        }
        if (uuid != null && !uuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IllegalArgumentException("uuid invalide");
        }
        scratchMb = scratchMb == null ? DEFAULT_SCRATCH_MB : scratchMb;
        nas = nas != null && nas;
        if (scratchMb < 0 || scratchMb > MAX_SCRATCH_MB) throw new IllegalArgumentException("scratchMb hors limites (0 à " + MAX_SCRATCH_MB + ")");
        env = env == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(env));
        generated = generated == null ? java.util.List.of() : java.util.List.copyOf(generated);
        uses = uses == null ? java.util.List.of() : java.util.List.copyOf(uses);
        internal = internal != null && internal;
        ports = ports == null ? java.util.List.of() : java.util.List.copyOf(ports);
        if (ports.size() > 4) throw new IllegalArgumentException("trop de ports supplémentaires (4 au plus)");
        for (int q : ports) {
            if (q < 1025 || q > 65535 || (q >= 20000 && q < 30000)) throw new IllegalArgumentException("port supplémentaire invalide : " + q + " (1025-19999 ou 30000-65535)");
        }
        if (new java.util.HashSet<>(ports).size() != ports.size() || (port != null && ports.contains(port))) throw new IllegalArgumentException("ports en double");
        if (env.size() > 64) throw new IllegalArgumentException("trop de variables (64 au plus)");
        for (var e : env.entrySet()) {
            if (!e.getKey().matches("[A-Z_][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("nom de variable invalide : " + e.getKey());
            if (RESERVED_ENV.contains(e.getKey()) || e.getKey().startsWith("GLUONIFY_SERVICE_") || ServiceToken.isReservedEnv(e.getKey())) throw new IllegalArgumentException("variable réservée au PaaS : " + e.getKey());
            if (e.getValue() == null || e.getValue().length() > 4096) throw new IllegalArgumentException("valeur invalide pour " + e.getKey());
        }
        if (generated.size() > 16) throw new IllegalArgumentException("trop de secrets générés (16 au plus)");
        for (String g : generated) {
            if (!g.matches("[A-Z_][A-Z0-9_]{0,127}") || RESERVED_ENV.contains(g) || g.startsWith("GLUONIFY_SERVICE_") || ServiceToken.isReservedEnv(g) || env.containsKey(g)) throw new IllegalArgumentException("secret généré invalide : " + g);
        }
        if (uses.size() > 16) throw new IllegalArgumentException("trop de services utilisés (16 au plus)");
        for (String u : uses) if (!u.matches("\\??[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?")) throw new IllegalArgumentException("service invalide : " + u); // « ?nom » : facultatif (déjà prêt ou non)
        if (pin != null && !pin.matches("[A-Za-z0-9-]{1,40}")) throw new IllegalArgumentException("pin invalide (identifiant de nœud)");
        if (role != null && !role.matches("[a-z]{1,20}")) throw new IllegalArgumentException("rôle invalide");
        if (pullSecret != null && !pullSecret.matches("PULL_[A-Z0-9_]{1,60}")) throw new IllegalArgumentException("pullSecret : nom PULL_… attendu (A-Z, 0-9, _)");
        if (distributedQuotaMb != null && (distributedQuotaMb < 1 || distributedQuotaMb > 1048576)) throw new IllegalArgumentException("distributedQuotaMb : 1 à 1048576 Mo");
        distributed = distributed == null ? java.util.List.of() : java.util.List.copyOf(distributed);
        for (String d : distributed) if (!d.equals("std") && !d.equals("tmp") && !d.equals("gdown")) throw new IllegalArgumentException("distributed : « std », « tmp » ou « gdown » (reçu : " + d + ")");
        if (new java.util.HashSet<>(distributed).size() != distributed.size()) throw new IllegalArgumentException("distributed : doublon");
        if (Boolean.TRUE.equals(distributedLocks) && distributed.isEmpty()) throw new IllegalArgumentException("distributedLocks : aucun système de fichiers distribué déclaré (distributed)");
        if (distributedQuotaMb != null && distributed.isEmpty()) throw new IllegalArgumentException("distributedQuotaMb : aucun système de fichiers distribué déclaré (distributed)");
        if (sleep != null && sleep.enabled()) {
            if (internal) throw new IllegalArgumentException("sleep : une application interne n'a pas de route publique pour la réveiller");
            if (!ports.isEmpty()) throw new IllegalArgumentException("sleep : incompatible avec des ports supplémentaires (pairs répliqués)");
            if (role != null) throw new IllegalArgumentException("sleep : incompatible avec un rôle de nœud (composant de la pile)");
            if (Boolean.TRUE.equals(distributedLocks)) throw new IllegalArgumentException("sleep : incompatible avec distributedLocks");
        }
        if (replicas < 0 || replicas > 100) throw new IllegalArgumentException("replicas hors limites");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port invalide");
        if (memoryMb < 16) throw new IllegalArgumentException("mémoire minimale : 16 Mo");
    }
}
