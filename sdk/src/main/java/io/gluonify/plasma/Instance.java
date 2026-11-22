package io.gluonify.plasma;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A replica placed on a node. hostPort is assigned by the control-plane.
 *
 * @author David CABILLIC
 */
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
                       @Schema(description = "UUID of the application (usage attribution)", example = "0b6f7c1e-0000-4000-8000-000000000001") String uuid,
                       @Schema(description = "The replica keeps its state on its node (gdown, an application declaring stateful): the agent refuses to start it on a missing state directory unless initialize is set; sent only to an agent that announces stateful-instances", example = "true")
                       @JsonInclude(JsonInclude.Include.NON_NULL) Boolean stateful,
                       @Schema(description = "First start of a stateful replica on its node: the agent may create its state directory; set by the control-plane only on the replica's first placement", example = "true")
                       @JsonInclude(JsonInclude.Include.NON_NULL) Boolean initialize,
                       @Schema(description = "Identity of a new state: on the first start of a stateful replica under this identity the agent sets an existing state directory aside (renamed, never deleted) and starts with an empty one, once per identity; only with stateful and initialize, sent only to an agent that announces fresh-state-folders", example = "gdown-2-2")
                       @JsonInclude(JsonInclude.Include.NON_NULL) String freshState) {

    /**
     * Compatibility: a replica without the fresh-state mark (an older control-plane, or one that sends it to an agent which does not announce
     * {@link io.gluonify.plasma.ws.Msg#AGENT_FEATURE_FRESH_STATE}).
     *
     * @param app application name
     * @param index replica index
     * @param image OCI image
     * @param port container port
     * @param hostPort port on the host
     * @param memoryMb memory (MB)
     * @param topNamespace top namespace, or null
     * @param generation restart generation
     * @param scratchMb temporary workspace (MB)
     * @param nas NAS mounted
     * @param env non-secret variables
     * @param generated startup secret names
     * @param uses services used
     * @param ports additional ports
     * @param distributed distributed file systems
     * @param pullSecret download secret name, or null
     * @param distributedQuotaMb quota per distributed file system, or null
     * @param distributedLocks cross-node file locks, or null
     * @param organization owning organization, or null
     * @param uuid application UUID, or null
     * @param stateful the replica keeps its state on its node, or null
     * @param initialize first start of a stateful replica on its node, or null
     */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed, final String pullSecret, final Integer distributedQuotaMb, final Boolean distributedLocks,
                    final String organization, final String uuid, final Boolean stateful, final Boolean initialize) {

        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, organization, uuid, stateful, initialize, null);
    }

    /**
     * Compatibility: a replica without stateful marking (an older control-plane, or one that sends it to an agent which does not announce
     * {@link io.gluonify.plasma.ws.Msg#AGENT_FEATURE_STATEFUL_INSTANCES}).
     *
     * @param app application name
     * @param index replica index
     * @param image OCI image
     * @param port container port
     * @param hostPort port on the host
     * @param memoryMb memory (MB)
     * @param topNamespace top namespace, or null
     * @param generation restart generation
     * @param scratchMb temporary workspace (MB)
     * @param nas NAS mounted
     * @param env non-secret variables
     * @param generated startup secret names
     * @param uses services used
     * @param ports additional ports
     * @param distributed distributed file systems
     * @param pullSecret download secret name, or null
     * @param distributedQuotaMb quota per distributed file system, or null
     * @param distributedLocks cross-node file locks, or null
     * @param organization owning organization, or null
     * @param uuid application UUID, or null
     */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed, final String pullSecret, final Integer distributedQuotaMb, final Boolean distributedLocks,
                    final String organization, final String uuid) {

        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, organization, uuid, null, null, null);
    }

    /** Compatibility: no organization or UUID. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed, final String pullSecret, final Integer distributedQuotaMb, final Boolean distributedLocks) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, null, null);
    }

    /** Compatibility: no cross-node locks. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed, final String pullSecret, final Integer distributedQuotaMb) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibility: no quota. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed, final String pullSecret) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, null);
    }

    /** Compatibility: no download secret. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports, final java.util.List<String> distributed) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, null);
    }

    /** Compatibility: no distributed file systems. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses, final java.util.List<Integer> ports) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, null);
    }

    /**
     * A replica whose collections are never null and cannot be changed, and whose {@code stateful} and {@code initialize} marks are {@code true} or absent.
     *
     * <p>{@code freshState} only goes with {@code stateful} and {@code initialize} (a set-aside folder is meaningful only for the first start of a stateful
     * replica), and a blank one is absent.
     */
    public Instance {
        ports = ports == null ? java.util.List.of() : java.util.List.copyOf(ports);
        distributed = distributed == null ? java.util.List.of() : java.util.List.copyOf(distributed);
        env = env == null ? java.util.Map.of() : java.util.Map.copyOf(new java.util.TreeMap<>(env));
        generated = generated == null ? java.util.List.of() : java.util.List.copyOf(generated);
        uses = uses == null ? java.util.List.of() : java.util.List.copyOf(uses);
        stateful = Boolean.TRUE.equals(stateful) ? Boolean.TRUE : null; // false and absent are the same: stable equality and JSON
        initialize = Boolean.TRUE.equals(initialize) ? Boolean.TRUE : null;
        freshState = freshState == null || freshState.isBlank() || stateful == null || initialize == null ? null : freshState.strip();
    }

    /**
     * The same replica marked for the agent: stateful or not, and whether its state may be created now.
     *
     * @param keepsState true when the replica keeps its state on its node
     * @param mayInitialize true when this is its first start on the node (its state directory may be created)
     * @return the copy
     */
    public Instance withState(final boolean keepsState, final boolean mayInitialize) {

        return new Instance(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb,
                distributedLocks, organization, uuid, keepsState, keepsState && mayInitialize, freshState);
    }

    /**
     * The same replica asking the agent, or not, to set an existing state directory aside before its first start under a new identity. Kept only on a stateful
     * replica that carries {@code initialize}.
     *
     * @param identity identity of the new state (for gDown the member's new node id), never given twice for the same replica; null or blank: no request
     * @return the copy
     */
    public Instance withFreshState(final String identity) {

        return new Instance(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, ports, distributed, pullSecret, distributedQuotaMb,
                distributedLocks, organization, uuid, stateful, initialize, identity);
    }

    /**
     * Whether the agent must set an existing state directory of the replica aside (renamed, never deleted) and start it with an empty one, unless that directory is
     * the one it created for the same {@link #freshState()} identity (a restart of the new state, after a liveness failure for example).
     *
     * @return true for the first start of a stateful replica under a new identity
     */
    public boolean requiresFreshState() {

        return freshState != null;
    }

    /**
     * Whether the agent must find the replica's state already on the node: stateful and not on its first start.
     *
     * @return true when a missing state directory must stop the start
     */
    public boolean requiresExistingState() {

        return Boolean.TRUE.equals(stateful) && !Boolean.TRUE.equals(initialize);
    }

    /** Compatibility: instance without generation (0). */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, 0);
    }

    /** Compatibility: default workspace, no NAS. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, AppSpec.DEFAULT_SCRATCH_MB, false);
    }

    /** Compatibility: no variables, generated secrets or services. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, null, null, null, null);
    }

    /** Compatibility: no additional ports. */
    public Instance(final String app, final int index, final String image, final int port, final int hostPort, final int memoryMb, final String topNamespace, final long generation, final int scratchMb, final boolean nas,
                    final java.util.Map<String, String> env, final java.util.List<String> generated, final java.util.List<String> uses) {
        this(app, index, image, port, hostPort, memoryMb, topNamespace, generation, scratchMb, nas, env, generated, uses, null);
    }

    /**
     * Name of the replica on its node.
     *
     * @return {@code <app>-<index>}
     */
    public String name() {

        return app + "-" + index;
    }
}
