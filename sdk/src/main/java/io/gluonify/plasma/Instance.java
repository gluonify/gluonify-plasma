package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** A replica placed on a node. hostPort is assigned by the control-plane. */
@Schema(description = "Replica placed on a node.")
public record Instance(@Schema(description = "Application name", example = "shop") String app, @Schema(description = "Replica index", example = "0") int index, @Schema(description = "OCI image", example = "registry.example.org/shop:1.0") String image, @Schema(description = "Container port", example = "8080") int port, @Schema(description = "Port exposed on the host (assigned by the control-plane)", example = "20000") int hostPort, @Schema(description = "Memory (MB)", example = "64") int memoryMb, @Schema(description = "gluonify-top namespace to inject (optional)", example = "shop") String topNamespace,
                       @Schema(description = "Restart generation: incremented by the control-plane, one replica at a time, when config/secrets are redeployed", example = "0") long generation,
                       @Schema(description = "Temporary workspace of the replica (MB), in memory, emptied at every start; counted on top of the memory", example = "64") int scratchMb,
                       @Schema(description = "Mount the host's NAS at /nas (process runtime, NAS server configured on the node)", example = "false") boolean nas,
                       @Schema(description = "Non-secret environment variables", example = "{\"GLUONIFY_GDOWN_BOLT_ENABLED\":\"false\"}") java.util.Map<String, String> env,
                       @Schema(description = "Startup secrets (names): provided by the control-plane only to the instance's node", example = "[\"GLUONIFY_GDOWN_AUTH_TOKEN\"]") java.util.List<String> generated,
                       @Schema(description = "Applications whose address is injected (GLUONIFY_SERVICE_<APP>_URL)", example = "[\"gdown\"]") java.util.List<String> uses,
                       @Schema(description = "Additional TCP ports (with the same number on the node): bind allowed in the sandbox, reachable from the other nodes; a single instance of the application per node", example = "[9100]") java.util.List<Integer> ports,
                       @Schema(description = "Mounted distributed file systems (std, tmp)", example = "[\"std\"]") java.util.List<String> distributed,
                       @Schema(description = "Download secret of the executable (name)", example = "PULL_GITLAB") String pullSecret,
                       @Schema(description = "Quota per distributed file system (MB); absent: default caps", example = "2048") Integer distributedQuotaMb,
                       @Schema(description = "Cross-node file locks (gluonify-bottom daemon in front of the mount)", example = "true") Boolean distributedLocks,
                       @Schema(description = "Organization owning the application (usage attribution by the agent; absent: _unassigned)", example = "acme") String organization,
                       @Schema(description = "UUID of the application (usage attribution)", example = "0b6f7c1e-0000-4000-8000-000000000001") String uuid) {
    /** Compatibility: no organization or UUID. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, null, null);
    }

    /** Compatibility: no cross-node locks. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibility: no quota. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, null);
    }

    /** Compatibility: no download secret. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports, java.util.List<String> distributed) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, null);
    }

    /** Compatibility: no distributed file systems. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, java.util.List<Integer> ports) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, null);
    }

    public Instance {
        ports = ports == null ? java.util.List.of() : java.util.List.copyOf(ports);
        distributed = distributed == null ? java.util.List.of() : java.util.List.copyOf(distributed);
        env = env == null ? java.util.Map.of() : java.util.Map.copyOf(new java.util.TreeMap<>(env));
        generated = generated == null ? java.util.List.of() : java.util.List.copyOf(generated);
        uses = uses == null ? java.util.List.of() : java.util.List.copyOf(uses);
    }

    /** Compatibility: instance without generation (0). */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, 0);
    }

    /** Compatibility: default workspace, no NAS. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, AppSpec.DEFAULT_SCRATCH_MB, false);
    }

    /** Compatibility: no variables, generated secrets or services. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, null, null, null, null);
    }

    /** Compatibility: no additional ports. */
    public Instance(String app, int index, String image, int port, int hostPort, int memoryMb, String topNamespace, long generation, int scratchMb, boolean nas,
                    java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, null);
    }

    public String name() { return app + "-" + index; }
}
