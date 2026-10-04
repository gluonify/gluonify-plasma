package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Une réplique placée sur un nœud. hostPort est attribué par le control-plane. */
@Schema(description = "Réplique placée sur un nœud.")
public record Instance(@Schema(description = "Nom de l'application", example = "shop") String app, @Schema(description = "Index de la réplique", example = "0") int index, @Schema(description = "Image OCI", example = "registry.example.org/shop:1.0") String image, @Schema(description = "Port du conteneur", example = "8080") int port, @Schema(description = "Port exposé sur l'hôte (attribué par le control-plane)", example = "20000") int hostPort, @Schema(description = "Mémoire (Mo)", example = "64") int memoryMb, @Schema(description = "Namespace gluonify-top à injecter (optionnel)", example = "shop") String vaultNamespace,
                       @Schema(description = "Génération de redémarrage : augmentée par le control-plane, une réplique à la fois, quand les config/secrets sont redéployés", example = "0") long generation,
                       @Schema(description = "Espace de travail temporaire de la réplique (Mo), en mémoire, vidé à chaque démarrage ; compté en plus de la mémoire", example = "64") int scratchMb,
                       @Schema(description = "Monter le NAS de l'hébergeur dans /nas (runtime process, serveur NAS configuré sur le nœud)", example = "false") boolean nas,
                       @Schema(description = "Variables d'environnement non secrètes", example = "{\"GRAPH_BOLT_ENABLED\":\"false\"}") java.util.Map<String, String> env,
                       @Schema(description = "Secrets de démarrage (noms) : fournis par le control-plane au seul nœud de l'instance", example = "[\"GRAPH_AUTH_TOKEN\"]") java.util.List<String> generated,
                       @Schema(description = "Applications dont l'adresse est injectée (SERVICE_<APP>_URL)", example = "[\"graphdb\"]") java.util.List<String> uses,
                       @Schema(description = "Ports TCP supplémentaires (au même numéro sur le nœud) : bind autorisé dans le bac à sable, joignables depuis les autres nœuds ; un seul exemplaire de l'application par nœud", example = "[9100]") java.util.List<Integer> ports,
                       @Schema(description = "Systèmes de fichiers distribués montés (std, tmp)", example = "[\"std\"]") java.util.List<String> distributed,
                       @Schema(description = "Secret de téléchargement de l'exécutable (nom)", example = "PULL_GITLAB") String pullSecret,
                       @Schema(description = "Quota par système de fichiers distribué (Mo) ; absent : plafonds par défaut", example = "2048") Integer distributedQuotaMb,
                       @Schema(description = "Verrous de fichiers entre nœuds (démon gluonify-bottom devant le montage)", example = "true") Boolean distributedLocks) {
    /** Compatibilité : sans verrous entre nœuds. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibilité : sans quota. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, null);
    }

    /** Compatibilité : sans secret de téléchargement. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, null);
    }

    /** Compatibilité : sans systèmes de fichiers distribués. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, env, generated, uses, ports, null);
    }

    public Instance {
        ports = ports == null ? java.util.List.of() : java.util.List.copyOf(ports);
        distributed = distributed == null ? java.util.List.of() : java.util.List.copyOf(distributed);
        env = env == null ? java.util.Map.of() : java.util.Map.copyOf(new java.util.TreeMap<>(env));
        generated = generated == null ? java.util.List.of() : java.util.List.copyOf(generated);
        uses = uses == null ? java.util.List.of() : java.util.List.copyOf(uses);
    }

    /** Compatibilité : instance sans génération (0). */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, 0);
    }

    /** Compatibilité : espace de travail par défaut, sans NAS. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, AppSpec.DEFAULT_SCRATCH_MB, false);
    }

    /** Compatibilité : sans variables, secrets générés ni services. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, null, null, null, null);
    }

    /** Compatibilité : sans ports supplémentaires. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String vaultNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses) {
        this(app, index, image, port, hostPort, memoryMb, vaultNamespace, generation, scratchMb, nas, env, generated, uses, null);
    }

    public String name() { return app + "-" + index; }
}
