package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Corps de PUT /apps/{name}. */
@Schema(description = "Spécification de déploiement ; seule l'image (ou les exécutables par architecture) est obligatoire.")
public record DeployRequest(@Schema(description = "Image OCI de l'application Quarkus native", example = "registry.example.org/shop:1.0") String image, @Schema(description = "Nombre de répliques (défaut 1, max 100)", example = "2") Integer replicas, @Schema(description = "Port HTTP du conteneur (défaut 8080)", example = "8080") Integer port, @Schema(description = "Limite mémoire par réplique en Mo (défaut 64, min 16)", example = "64") Integer memoryMb, @Schema(description = "Namespace gluonify-top injecté en variables d'environnement (optionnel)", example = "shop") String vaultNamespace, @Schema(description = "Exécutables natifs par architecture (amd64, arm64), à la place de « image »") java.util.Map<String, String> artifacts,
        @Schema(description = "true : l'application utilise les espaces du vault <uuid>.app et <uuid>.gluonify (à la place de vaultNamespace)", example = "true") Boolean vault,
        @Schema(description = "Espace de travail temporaire par réplique en Mo (0 à 2048, défaut 64)", example = "64") Integer scratchMb,
        @Schema(description = "Monter le NAS de l'hébergeur dans /nas (option)", example = "false") Boolean nas,
        @Schema(description = "Variables d'environnement non secrètes") java.util.Map<String, String> env,
        @Schema(description = "Secrets de démarrage générés et détenus par le control-plane (noms de variables, globaux)") java.util.List<String> generated,
        @Schema(description = "Applications utilisées (injection de SERVICE_<APP>_URL)") java.util.List<String> uses,
        @Schema(description = "Identifiant du nœud imposé") String pin,
        @Schema(description = "true : pas de route publique") Boolean internal,
        @Schema(description = "Ports TCP supplémentaires (même numéro sur le nœud)") java.util.List<Integer> ports,
        @Schema(description = "Systèmes de fichiers distribués : std (durable) et/ou tmp (expire 24 h après la dernière écriture), montés dans /distributed/<nom>", example = "[\"std\"]") java.util.List<String> distributed,
        @Schema(description = "Secret de téléchargement PULL_… (dépôt privé de l'exécutable)", example = "PULL_GITLAB") String pullSecret,
        @Schema(description = "Quota par système de fichiers distribué en Mo (1 à 1048576), appliqué à chaque genre monté ; au-delà, l'écriture est refusée (507). Souple : taille relevée toutes les 20 s", example = "2048") Integer distributedQuotaMb,
        @Schema(description = "true : verrous de fichiers (flock, fcntl/POSIX, O_EXCL) valables ENTRE NŒUDS sur les systèmes de fichiers distribués de l'application : un démon de montage (gluonify-bottom) est placé devant le montage rclone et délègue les verrous au control-plane (bail : un nœud sans signe de vie depuis 30 s perd ses verrous). Sans cela, les verrous ne valent que sur un même nœud. Coûte un second saut FUSE ; à demander explicitement. Pris en compte au démarrage de l'application (POST /apps/{nom}/redeploy après un changement).", example = "true") Boolean distributedLocks,
        @Schema(description = "Mode endormi : {enabled, freezeAfterSeconds (défaut 60), stopAfterSeconds (défaut 900), wakeTimeoutMs (défaut 5000)}") SleepSpec sleep,
        @Schema(description = "true : base gDown DÉDIÉE à l'application (un groupe Raft propre, un compte confiné) : le control-plane la crée et injecte GRAPH_DATABASE, GRAPH_USER, GRAPH_PASSWORD (secret) et SERVICE_GRAPHDB_URL. La base n'est jamais supprimée avec l'application.", example = "true") Boolean graphDatabase) {
    /** Compatibilité : sans base dédiée. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, null);
    }

    /** Compatibilité : sans mode endormi. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, null);
    }

    /** Compatibilité : sans verrous entre nœuds. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibilité : sans quota. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, null);
    }

    /** Compatibilité : sans secret de téléchargement. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, null);
    }

    /** Compatibilité : sans espace de travail ni NAS explicites. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String vaultNamespace, java.util.Map<String, String> artifacts, Boolean vault) {
        this(image, replicas, port, memoryMb, vaultNamespace, artifacts, vault, null, null, null, null, null, null, null, null, null, null);
    }

    public AppSpec toSpec(String name) {
        return toSpec(name, null);
    }

    /** uuid : identifiant de l'application (conservé d'un déploiement à l'autre) ; avec vault=true, le namespace est « <uuid>.app ». */
    public AppSpec toSpec(String name, String uuid) {
        String ns = Boolean.TRUE.equals(vault) && uuid != null ? uuid + ".app" : vaultNamespace;
        java.util.Map<String, String> e = env;
        java.util.List<String> g = generated, u = uses;
        if (Boolean.TRUE.equals(graphDatabase) && uuid != null) { // base dédiée : tout ce qu'il faut à l'application se déduit de la spécification (le control-plane n'a qu'à créer la base)
            String db = graphDatabaseName(uuid);
            e = new java.util.TreeMap<>(env == null ? java.util.Map.of() : env);
            e.put("GRAPH_DATABASE", db);
            e.put("GRAPH_USER", db);
            g = new java.util.ArrayList<>(generated == null ? java.util.List.of() : generated);
            String secret = graphPasswordSecret(uuid); // un secret par application (le même nom pour deux applications d'un nœud s'écraserait) ; l'application le lit sous GRAPH_PASSWORD
            if (!g.contains(secret)) g.add(secret);
            e.put("GRAPH_PASSWORD", "${" + secret + "}");
            u = new java.util.ArrayList<>(uses == null ? java.util.List.of() : uses);
            if (!u.contains("graphdb")) u.add("graphdb");
        }
        return new AppSpec(name, image, replicas, port, memoryMb, ns, artifacts, uuid, scratchMb, nas, e, g, u, pin, internal, ports, null, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep);
    }

    /** Nom du secret de démarrage qui porte le mot de passe de la base dédiée : propre à l'application, valeur générée et gardée par le control-plane. */
    public static String graphPasswordSecret(String uuid) {
        return "GRAPH_PASSWORD_" + graphDatabaseName(uuid).substring(3).toUpperCase(java.util.Locale.ROOT);
    }

    /** Nom de la base dédiée d'une application : stable (dérivé de l'uuid), valide pour gDown. */
    public static String graphDatabaseName(String uuid) {
        return "app" + uuid.replace("-", "").substring(0, 16);
    }
}
