package io.gluonify.plasma;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Body of PUT /apps/{name}. */
@Schema(description = "Deployment specification; only the image (or the per-architecture executables) is mandatory.")
public record DeployRequest(@Schema(description = "OCI image of the native Quarkus application", example = "registry.example.org/shop:1.0") String image, @Schema(description = "Number of replicas (default 1, max 100)", example = "2") Integer replicas, @Schema(description = "HTTP port of the container (default 8080)", example = "8080") Integer port, @Schema(description = "Memory limit per replica in MB (default 64, min 16)", example = "64") Integer memoryMb, @Schema(description = "gluonify-top namespace injected as environment variables (optional)", example = "shop") String topNamespace, @Schema(description = "Native executables per architecture (amd64, arm64), instead of \"image\"") java.util.Map<String, String> artifacts,
        @Schema(description = "true: the application uses the top namespaces <uuid>.app and <uuid>.gluonify (instead of topNamespace)", example = "true") Boolean top,
        @Schema(description = "Temporary workspace per replica in MB (0 to 2048, default 64)", example = "64") Integer scratchMb,
        @Schema(description = "Mount the host's NAS at /nas (option)", example = "false") Boolean nas,
        @Schema(description = "Non-secret environment variables") java.util.Map<String, String> env,
        @Schema(description = "Startup secrets generated and held by the control-plane (variable names, global)") java.util.List<String> generated,
        @Schema(description = "Applications used (injection of GLUONIFY_SERVICE_<APP>_URL)") java.util.List<String> uses,
        @Schema(description = "Identifier of the pinned node") String pin,
        @Schema(description = "true: no public route") Boolean internal,
        @Schema(description = "Additional TCP ports (same number on the node)") java.util.List<Integer> ports,
        @Schema(description = "Distributed file systems: std (durable) and/or tmp (expires 24 h after the last write), mounted at /distributed/<name>", example = "[\"std\"]") java.util.List<String> distributed,
        @Schema(description = "PULL_… download secret (private repository of the executable)", example = "PULL_GITLAB") String pullSecret,
        @Schema(description = "Quota per distributed file system in MB (1 to 1048576), applied to each mounted kind; beyond it, the write is refused (507). Soft: size sampled every 20 s", example = "2048") Integer distributedQuotaMb,
        @Schema(description = "true: file locks (flock, fcntl/POSIX, O_EXCL) valid ACROSS NODES on the application's distributed file systems: a mount daemon (gluonify-bottom) is placed in front of the rclone mount and delegates the locks to the control-plane (lease: a node with no sign of life for 30 s loses its locks). Without it, locks only hold on a single node. Costs a second FUSE hop; to be requested explicitly. Taken into account when the application starts (POST /apps/{name}/redeploy after a change).", example = "true") Boolean distributedLocks,
        @Schema(description = "Sleep mode: {enabled, freezeAfterSeconds (default 60), stopAfterSeconds (default 900), wakeTimeoutMs (default 5000)}") SleepSpec sleep,
        @Schema(description = "true: gDown database DEDICATED to the application (its own Raft group, a confined account): the control-plane creates it and injects GLUONIFY_GDOWN_DATABASE, GLUONIFY_GDOWN_USER, GLUONIFY_GDOWN_PASSWORD (secret) and GLUONIFY_SERVICE_GDOWN_URL. The database is never deleted with the application.", example = "true") Boolean gdownDatabase,
        @Schema(description = "Organization (the customer) of the application: DNS label (a-z, 0-9 and -, neither leading nor trailing), at most 40 characters. Only a platform administrator can set or change it; when absent, the stored organization is kept; \"_unassigned\": remove it (the specification then has none).", example = "acme") String organization,
                            @Schema(description = "Provenance token (gluonify-codex/provenance.md), signed by a trusted key (Bup attaches it to every deployment): base64url(statement).base64url(signature). "
                                    + "Without it, changing the executable or the image requires gluonify:higgs:deploy:binary or gluonify:higgs:deploy:image, roles granted to nobody.") String provenance,
        @Schema(description = "Instruction-set level required by the executable: \"amd64:v1\" to \"amd64:v4\" (Bup sends it with the build); absent: v1, placed everywhere", example = "amd64:v3") String artifactLevel,
        @Schema(description = "Python application: targeted python3 minor version, \"3.13\" (Bup sends it with a python framework archive); placed only on nodes that advertise the same version. Absent: not a Python application", example = "3.13") String pythonVersion,
        @Schema(description = "true: the application exposes MCP tools on POST /mcp, relayed by Prism to the organization's AI assistants (gluonify-codex/app-mcp.md); Bup sends it when the SDK marker is present. Absent or false: no MCP tools", example = "true") Boolean mcp) {
    /** Compatibility: no MCP tools. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep, Boolean gdownDatabase, String organization, String provenance, String artifactLevel, String pythonVersion) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, gdownDatabase, organization, provenance, artifactLevel, pythonVersion, null);
    }

    /** Compatibility: not a Python application. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep, Boolean gdownDatabase, String organization, String provenance, String artifactLevel) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, gdownDatabase, organization, provenance, artifactLevel, null);
    }

    /** Compatibility: no executable level. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep, Boolean gdownDatabase, String organization, String provenance) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, gdownDatabase, organization, provenance, null);
    }

    /** Compatibility: no provenance token. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep, Boolean gdownDatabase, String organization) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, gdownDatabase, organization, null);
    }

    /** Compatibility: no organization. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep, Boolean gdownDatabase) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, gdownDatabase, null);
    }

    /** Compatibility: no dedicated database. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks, SleepSpec sleep) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, null);
    }

    /** Compatibility: no sleep mode. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb, Boolean distributedLocks) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, distributedLocks, null);
    }

    /** Compatibility: no cross-node locks. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret, Integer distributedQuotaMb) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, distributedQuotaMb, null);
    }

    /** Compatibility: no quota. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed, String pullSecret) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, pullSecret, null);
    }

    /** Compatibility: no download secret. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top, Integer scratchMb,
                         Boolean nas, java.util.Map<String, String> env, java.util.List<String> generated, java.util.List<String> uses, String pin, Boolean internal,
                         java.util.List<Integer> ports, java.util.List<String> distributed) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, scratchMb, nas, env, generated, uses, pin, internal, ports, distributed, null);
    }

    /** Compatibility: no explicit workspace or NAS. */
    public DeployRequest(String image, Integer replicas, Integer port, Integer memoryMb, String topNamespace, java.util.Map<String, String> artifacts, Boolean top) {
        this(image, replicas, port, memoryMb, topNamespace, artifacts, top, null, null, null, null, null, null, null, null, null, null);
    }

    /** Specification of the application; {@code organization} is {@link Organizations#UNASSIGNED}: none (the control-plane tells "absent" from "removed" through {@link #organization()}). */
    public AppSpec toSpec(String name) {
        return toSpec(name, null);
    }

    /** uuid: identifier of the application (kept from one deployment to the next); with top=true, the namespace is "<uuid>.app". */
    public AppSpec toSpec(String name, String uuid) {
        String ns = Boolean.TRUE.equals(top) && uuid != null ? uuid + ".app" : topNamespace;
        java.util.Map<String, String> e = env;
        java.util.List<String> g = generated, u = uses;
        if (Boolean.TRUE.equals(gdownDatabase) && uuid != null) { // dedicated database: everything the application needs is derived from the specification (the control-plane only has to create the database)
            String db = gdownDatabaseName(uuid);
            e = new java.util.TreeMap<>(env == null ? java.util.Map.of() : env);
            e.put("GLUONIFY_GDOWN_DATABASE", db);
            e.put("GLUONIFY_GDOWN_USER", db);
            g = new java.util.ArrayList<>(generated == null ? java.util.List.of() : generated);
            String secret = gdownPasswordSecret(uuid); // one secret per application (the same name for two applications on a node would overwrite itself); the application reads it as GLUONIFY_GDOWN_PASSWORD
            if (!g.contains(secret)) g.add(secret);
            e.put("GLUONIFY_GDOWN_PASSWORD", "${" + secret + "}");
            u = new java.util.ArrayList<>(uses == null ? java.util.List.of() : uses);
            if (!u.contains("gdown")) u.add("gdown");
        }
        return new AppSpec(name, image, replicas, port, memoryMb, ns, artifacts, uuid, scratchMb, nas, e, g, u, pin, internal, ports, null, distributed, pullSecret, distributedQuotaMb, distributedLocks, sleep, Organizations.UNASSIGNED.equals(organization) ? null : organization, provenance, artifactLevel, pythonVersion, mcp);
    }

    /** Name of the startup secret that carries the password of the dedicated database: specific to the application, value generated and kept by the control-plane. */
    public static String gdownPasswordSecret(String uuid) {
        return "GLUONIFY_GDOWN_PASSWORD_" + gdownDatabaseName(uuid).substring(3).toUpperCase(java.util.Locale.ROOT);
    }

    /** Name of the dedicated database of an application: stable (derived from the uuid), valid for gDown. */
    public static String gdownDatabaseName(String uuid) {
        return "app" + uuid.replace("-", "").substring(0, 16);
    }
}
