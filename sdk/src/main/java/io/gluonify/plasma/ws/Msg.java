package io.gluonify.plasma.ws;

import io.gluonify.plasma.AppTokensResponse;
import io.gluonify.plasma.Instance;
import io.gluonify.plasma.ServiceToken;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/**
 * Protocol between a node (agent) and a control-plane, over WebSocket (text, JSON, field "t" = type).
 * The node opens the (outgoing) connection then authenticates in its first message — never in the URL:
 * <ul>
 *   <li>node -> CP: {@link Hello} (known identity) or {@link Enroll} (first start, single-use token), then {@link Ready} (an instance becomes ready, or no longer is: immediately) and {@link State}
 *       every 5 s, {@link TokensRequest}, {@link ServiceTokensRequest} and {@link Redeploy} (the top redeployed a namespace) on demand;</li>
 *   <li>CP -> node: {@link Welcome} or {@link Enrolled}, then {@link Desired} (desired state) whenever it changes, {@link Tokens} and {@link ServiceTokens} as replies,
 *       {@link InstanceReportRequest} and {@link UsageRequest} on demand (report and usage, never waking an application), and {@link Error} (followed by the close) on refusal.</li>
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
        @JsonSubTypes.Type(value = Msg.UsageRequest.class, name = "usage-request"),
        @JsonSubTypes.Type(value = Msg.UsageReport.class, name = "usage-report"),
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
    // ---- node -> control-plane
    /** First message from an already enrolled node. */
    record Hello(String node, String secret) implements Msg {}

    /** First message from a new node: consumes a single-use token; empty address = source address of the connection. */
    record Enroll(String token, String address, int memoryMb, String arch, String role) implements Msg {
        public Enroll(final String token, final String address, final int memoryMb, final String arch) {
            this(token, address, memoryMb, arch, null);
        }
    }

    /**
     * First message from an INSTALLATION agent (Strange installer mode, before any enrollment): the token is that of the bootstrap request (POST /nodes/bootstrap); it
     * is not consumed here (the node's later enrollment consumes it). See gluonify-codex/docs/BOOTSTRAP.md.
     */
    record BootstrapHello(String token, String arch, String os, String version) implements Msg {}

    /** Result of a task: status = ok, unchanged (already done: idempotence), failed or cancelled; short message, no secret. */
    record TaskResult(String id, String status, String message) implements Msg {}

    /** Progress of a task (output line of the installer, no secret). */
    record TaskProgress(String id, String message) implements Msg {}

    /**
     * Running instances, ready ones (Quarkus health check UP) and the generation with which each started; also serves as a heartbeat.
     * {@code pins}: digests of the images designated by a tag that THIS node resolved (application -> {@link ImagePin}); the control-plane records the first one
     * for the whole cluster (gluonify-codex/provenance.md § 8). Absent from an old agent: empty.
     */
    record State(List<String> running, List<String> ready, Map<String, Long> generations, Map<String, Double> metrics, Map<String, String> failures, Host host, Map<String, Startup> startups,
                 Map<String, ImagePin> pins) implements Msg {
        public State {
            metrics = metrics == null ? Map.of() : metrics;
            failures = failures == null ? Map.of() : failures;
            startups = startups == null ? Map.of() : startups;
            pins = pins == null ? Map.of() : pins;
        }

        /** Compatibility: state without image digests. */
        public State(final List<String> running, final List<String> ready, final Map<String, Long> generations, final Map<String, Double> metrics, final Map<String, String> failures, final Host host, final Map<String, Startup> startups) {
            this(running, ready, generations, metrics, failures, host, startups, Map.of());
        }

        /** Compatibility: state without startup times. */
        public State(final List<String> running, final List<String> ready, final Map<String, Long> generations, final Map<String, Double> metrics, final Map<String, String> failures, final Host host) {
            this(running, ready, generations, metrics, failures, host, Map.of());
        }

        /** Compatibility: state without host description. */
        public State(final List<String> running, final List<String> ready, final Map<String, Long> generations, final Map<String, Double> metrics, final Map<String, String> failures) {
            this(running, ready, generations, metrics, failures, null, Map.of());
        }

        /** Compatibility: state without measurements. */
        public State(final List<String> running, final List<String> ready, final Map<String, Long> generations) {
            this(running, ready, generations, Map.of(), Map.of(), null, Map.of());
        }

        /** Compatibility: state without generations. */
        public State(final List<String> running, final List<String> ready) {
            this(running, ready, Map.of(), Map.of(), Map.of(), null, Map.of());
        }
    }

    /**
     * Startup times of an instance (attached to {@link State}, key = instance name), measured by the agent on the LAST start: {@code prepare} (start of startup ->
     * unit launched: environment, executable, sandbox), {@code listen} (unit launched -> the port accepts), {@code ready} (unit launched -> readiness probe answering 200) and
     * {@code total} (start -> 200), in seconds; -1 for a phase not reached. {@code at}: end of the startup (epoch ms); {@code ok} false = failure ({@code error}: short cause).
     * {@code history}: the {@code total}s of the last successful starts (most recent last, bounded); {@code starts} and {@code failures}: counters since the agent was launched.
     */
    record Startup(long at, boolean ok, double prepare, double listen, double ready, double total, String error, long starts, long failures, List<Double> history) {
        public Startup {
            history = history == null ? List.of() : history;
        }
    }

    /**
     * Availability change of ONE instance, reported immediately (without waiting for the 5 s cycle {@link State}): {@code ready} true when the readiness probe
     * answers 200 for the first time after a start, false when a periodic check sees it fall back. {@code generation}: generation with which the instance
     * started; {@code sinceStart}: seconds elapsed between the start order and the observation on the agent side (-1 for a fall back). The control-plane applies it at once
     * (the ingress routes to the instance); the next {@link State} remains the reference and corrects any discrepancy. No reply.
     */
    record Ready(String instance, boolean ready, long generation, double sinceStart) implements Msg {}

    /**
     * Acknowledgement from the agent after applying the desired sleep state of ONE instance (see {@link Desired#sleeping()}): {@code state} = "frozen", "stopped" or "active"
     * (the state reached); {@code ok} false: the transition failed ({@code error}: short cause, for example cgroup.freeze missing); {@code ms}: duration of the transition on the agent side.
     * Used for events and measurements; the control-plane never blocks on it. No reply.
     */
    record Slept(String instance, String state, boolean ok, String error, double ms) implements Msg {}

    /**
     * Description of the host attached to every {@link State} (read from /proc by the agent): agent version, number of processors, memory used (MB), 1-minute load,
     * free disk space of the data directory (MB) and machine uptime (seconds). An unknown value is -1 (0 for {@code cpuCount}).
     * {@code x86Level}: x86-64 level of the processor ("v1" to "v4", read from the /proc/cpuinfo flags); null on arm64, macOS, Windows and for an old
     * agent: the scheduler never places an executable of a higher level on this node (see {@link io.gluonify.plasma.AppSpec#artifactLevel}). {@code pythonVersion}: minor
     * version of the node's {@code python3} ("3.13"); null: no python3 (or old agent): the scheduler places no Python application there, and a Python application goes there only
     * if its version ({@link io.gluonify.plasma.AppSpec#pythonVersion}) is the same.
     */
    record Host(String version, int cpuCount, int memoryUsedMb, double load, int diskFreeMb, long uptimeSec, String x86Level, String pythonVersion) {
        /** Compatibility: without Python version. */
        public Host(final String version, final int cpuCount, final int memoryUsedMb, final double load, final int diskFreeMb, final long uptimeSec, final String x86Level) {
            this(version, cpuCount, memoryUsedMb, load, diskFreeMb, uptimeSec, x86Level, null);
        }

        /** Compatibility: without x86-64 level. */
        public Host(final String version, final int cpuCount, final int memoryUsedMb, final double load, final int diskFreeMb, final long uptimeSec) {
            this(version, cpuCount, memoryUsedMb, load, diskFreeMb, uptimeSec, null);
        }
    }

    /**
     * Last lines of the log of the instance {@code instance} of this node, or its Prometheus metrics ({@code what} = "metrics": what the application exposes on
     * /q/metrics; empty or "logs": the log). Reply: {@link Logs}.
     */
    record LogsRequest(long id, String instance, int tail, String what) implements Msg {
        public LogsRequest(final long id, final String instance, final int tail) {
            this(id, instance, tail, null);
        }
    }

    /** Reply to {@link LogsRequest}: status 200 + text, 404 unknown instance, 501 not supported by this runtime, 500 error. */
    record Logs(long id, int status, String text) implements Msg {}

    /**
     * Report on an instance, requested by the control-plane (control-plane -> node): only the agent can provide it for a sleeping instance (frozen or stopped), whose process no longer
     * answers or no longer exists. NEVER touches the application (neither /q/metrics nor its port): it is therefore never woken by this request. {@code tail}: wanted log lines
     * (bounded on the agent side). Reply: {@link InstanceReport}.
     */
    record InstanceReportRequest(long id, String instance, int tail) implements Msg {}

    /**
     * Reply to {@link InstanceReportRequest}: {@code status} 200 (404 unknown instance on this node, 500 error). {@code state}: active | frozen | stopped | absent (desired but without a unit,
     * outside sleep); {@code since}: start of this state (epoch ms, 0 if unknown); {@code running}: the unit exists (a frozen one is still running). {@code cpuSeconds} and {@code memoryBytes}: read
     * from the cgroup (counters of a running instance, frozen included) or, for a stopped instance, last known values ({@code lastKnown} true, measured at {@code measuredAt}, ms
     * epoch); null if never measured. {@code startup}: last measured start (see {@link Startup}); {@code starts}: starts since the agent was launched;
     * {@code logs}: the last log lines (journald keeps those of a stopped instance), secrets masked; {@code at}: epoch ms of the reply.
     */
    record InstanceReport(long id, int status, String error, String instance, String state, long since, boolean running, Double cpuSeconds, Double memoryBytes, boolean lastKnown,
                          long measuredAt, Startup startup, long starts, List<String> logs, long at) implements Msg {}

    /**
     * Usage of the node, requested by the control-plane (control-plane -> node) for the aggregation behind Higgs' {@code GET /usage} (gluonify-codex/plans/usage-mcp.md
     * § 5.1 and § 5.2): the {@code gluonify-usage/1} usage contract of the agent's own process, plus the cgroup counters of EVERY instance of the node, asleep ones included
     * (frozen or stopped: last known values). Like {@link InstanceReportRequest}, it NEVER touches an application (neither its port nor {@code /q/metrics}): no wake-up.
     * {@code apps}: application names wanted; empty or absent = every instance of the node. Reply: {@link UsageReport} with the same {@code id}. Additive message of the
     * {@link #FEATURE_USAGE} family: only a control-plane announcing that feature knows it, and an older agent ignores it without replying (the control-plane applies a
     * timeout and reports the node as {@code reachable: false}, never a global error).
     */
    record UsageRequest(long id, List<String> apps) implements Msg {
        public UsageRequest {
            apps = apps == null ? List.of() : List.copyOf(apps);
        }

        /** Every instance of the node. */
        public UsageRequest(final long id) {
            this(id, null);
        }
    }

    /**
     * Reply to {@link UsageRequest}: {@code status} 200 (500: failure, short {@code error}, never a secret); {@code node}: node id; {@code at}: epoch ms of the reply;
     * {@code agent}: the {@code gluonify-usage/1} contract of the agent's process ({@code process}, {@code cgroup}, per-application shares of what the agent can attribute:
     * the {@code /distributed/*} gateway pro rata of relayed bytes, indicative), {@code null} when the agent could not read it; {@code instances}: one line per instance
     * desired on this node ({@link UsageInstance}), never an unknown instance. The "billable" CPU and memory of an instance are those lines (cgroup read by the agent), not
     * the instance's own {@code /q/usage}.
     */
    record UsageReport(long id, int status, String error, String node, long at, io.gluonify.plasma.usage.UsageReport agent, List<UsageInstance> instances) implements Msg {
        public UsageReport {
            instances = instances == null ? List.of() : List.copyOf(instances);
        }
    }

    /**
     * Usage of one instance in {@link UsageReport}: {@code app} and {@code uuid} (the platform's attribution key; null with an older control-plane that does not send it);
     * {@code state}: active | frozen | stopped | absent (desired but without a unit, outside sleep); {@code running}: the unit exists (a frozen one is still running);
     * {@code reservedMb}: reserved memory (memoryMb + scratchMb); {@code cpuSeconds} (CUMULATIVE counter, never a percentage; null with the {@code nerdctl} runtime) and
     * {@code memoryBytes}: read from the cgroup of a running instance (frozen included) or, for a stopped one, last known values ({@code lastKnown} true, measured at
     * {@code measuredAt}, epoch ms); null if never measured; {@code tasks}: cgroup threads (null if unknown). Same source and same rules as {@link InstanceReport}.
     */
    record UsageInstance(String instance, String app, String uuid, String state, boolean running, int reservedMb, Double cpuSeconds, Double memoryBytes, Double tasks, boolean lastKnown,
                         long measuredAt) {}

    /**
     * Continuous log follow (control-plane -> node): the node replies with a series of {@link LogsChunk} with identifier {@code id} (the last {@code tail} lines, then the
     * new ones), until {@link LogsStop}, the end of the {@code maxMs} duration or the disappearance of the instance.
     */
    record LogsFollow(long id, String instance, int tail, long maxMs) implements Msg {}

    /** Chunk of a follow ({@link LogsFollow}): {@code status} 200 + text (complete lines, secrets masked); {@code end} = last message of the follow (status 404/501/500 on failure). */
    record LogsChunk(long id, int status, String text, boolean end) implements Msg {}

    /** Stop of a follow ({@link LogsFollow}) requested by the control-plane (client gone, duration elapsed, too slow). */
    record LogsStop(long id) implements Msg {}

    /** WireGuard public key of the node (sent at every connection as long as inter-node encryption is enabled): the control-plane assigns it a mesh address. */
    record WgKey(String publicKey) implements Msg {}

    /** The top redeployed namespace ns (revision rev, increasing): the control-plane restarts the affected apps, one replica at a time. */
    record Redeploy(String ns, long rev) implements Msg {}

    /** Short-lived tokens for applications of the node; id links the reply to the request. */
    record TokensRequest(long id, List<String> apps) implements Msg {}

    /**
     * Application-to-service tokens ({@link ServiceToken}) for applications of the node: one per service of their {@code uses} that the control-plane
     * policy covers. An older control-plane ignores this message: the agent never blocks a start on the reply.
     */
    record ServiceTokensRequest(long id, List<String> apps) implements Msg {}

    /**
     * Cross-node file lock (node -> control-plane), requested by the application's mount daemon. {@code op}: "set" (set; 409 if another owner is in the way),
     * "test" (F_GETLK: 200, with the blocking lock if there is one), "unset" (unlocks the range), "release" (removes everything this owner holds on the file),
     * "release-app" (removes everything THIS node holds for the application: mount stopped). {@code key}: "application/kind/path"; {@code end}: {@link Long#MAX_VALUE} = end of file.
     */
    record LockRequest(long id, String op, String key, boolean flock, String owner, boolean write, long start, long end) implements Msg {}

    /**
     * Batch of usage records from the agent (metering.md § 4.3), relayed by the control-plane to the calorimeter: {@code body} is exactly the body
     * {@code {"records":[…]}} of {@code UsageEmitter}. Reply: {@link UsageAck} with the same {@code id}. An old control-plane ignores it (the agent keeps its spool).
     */
    record Usage(long id, String body) implements Msg {}
    // only sent to a control-plane that announces FEATURE_USAGE

    // ---- control-plane -> node
    /** Reply to {@link Usage}: HTTP-like status (2xx accepted, 413 too big, 422 refused, 503 calorimeter unreachable…). */
    record UsageAck(long id, int status) implements Msg {}

    /** Reply to {@link LockRequest}: 200, 409 (blocked: the blocking lock is described) or 400. */
    record LockReply(long id, int status, String conflictOwner, boolean conflictWrite, long conflictStart, long conflictEnd, String error) implements Msg {}

    /**
     * Optional protocol features that the control-plane understands, announced in {@link Welcome} and {@link Enrolled}: an agent only sends an additive message
     * to a control-plane that announces it (an old control-plane refuses an unknown message and closes the connection).
     */
    String FEATURE_USAGE = "usage";

    /** {@code features}: see {@link #FEATURE_USAGE}; empty for an old control-plane. */
    record Welcome(String node, List<String> features) implements Msg {
        public Welcome {
            features = features == null ? List.of() : List.copyOf(features);
        }

        public Welcome(final String node) { this(node, null); }
    }

    record Enrolled(String node, String secret, List<String> features) implements Msg {
        public Enrolled {
            features = features == null ? List.of() : List.copyOf(features);
        }

        public Enrolled(final String node, final String secret) { this(node, secret, null); }
    }

    /** {@code sleeping}: desired sleep state of THIS node's instances (instance -> "frozen" or "stopped"; an absent instance is active): the agent freezes or stops, never restarts a "stopped" instance, and thaws or starts it when it becomes active again. Sleep mode: see gluonify-codex/ARCHITECTURE.md.
     * Desired state of the node, API URLs of the member control-planes (for failover), cluster environment (GLUONIFY_ENVIRONMENT) and address of a ready instance of each application (services: app -> http://host:port, for GLUONIFY_SERVICE_<APP>_URL), address of this node (GLUONIFY_SELF_URL), addresses of all live nodes (peers of the additional ports), WireGuard mesh (wireguard) and addresses of the control-planes in the mesh (overlayControlPlanes: the agent reconnects there, the secrets travel through it) and startup secrets requested by THIS node's instances (name -> value; held by the control-plane, never written in the Raft state). */
    record Desired(List<Instance> desired, List<String> controlPlanes, String envName, Map<String, String> services, Map<String, String> secrets, String nodeAddress, List<String> nodes, Wg wireguard, List<String> overlayControlPlanes, Store store, Map<String, String> sleeping,
                   Map<String, ImagePin> pins, String tokenPolicy) implements Msg {
        public Desired {
            sleeping = sleeping == null ? Map.of() : sleeping;
            pins = pins == null ? Map.of() : pins;
        }

        /**
         * Compatibility: without service-token policy digest. {@code tokenPolicy}: short digest (effective catalogue and platform applications) of the control-plane's
         * application-to-service token policy; when it changes, the agent forgets the tokens it holds and requests them again.
         * Absent ({@code null}, older control-plane): the agent behaves as before (renewal as the expiry approaches).
         */
        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services, final Map<String, String> secrets, final String nodeAddress, final List<String> nodes, final Wg wireguard, final List<String> overlayControlPlanes, final Store store, final Map<String, String> sleeping,
                       final Map<String, ImagePin> pins) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, sleeping, pins, null);
        }

        /** Compatibility: without image digests pinned for the cluster. */
        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services, final Map<String, String> secrets, final String nodeAddress, final List<String> nodes, final Wg wireguard, final List<String> overlayControlPlanes, final Store store, final Map<String, String> sleeping) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, sleeping, Map.of(), null);
        }

        /** Compatibility: without sleeping instances. */
        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services, final Map<String, String> secrets, final String nodeAddress, final List<String> nodes, final Wg wireguard, final List<String> overlayControlPlanes, final Store store) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, store, Map.of(), Map.of(), null);
        }

        /** Compatibility: without storage nodes. */
        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services, final Map<String, String> secrets, final String nodeAddress, final List<String> nodes, final Wg wireguard, final List<String> overlayControlPlanes) {
            this(desired, controlPlanes, envName, services, secrets, nodeAddress, nodes, wireguard, overlayControlPlanes, null);
        }

        /** Compatibility: without environment name or service addresses. */
        public Desired(final List<Instance> desired, final List<String> controlPlanes) {
            this(desired, controlPlanes, null, Map.of(), Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName) {
            this(desired, controlPlanes, envName, Map.of(), Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services) {
            this(desired, controlPlanes, envName, services, Map.of(), null, List.of(), null, List.of(), null);
        }

        public Desired(final List<Instance> desired, final List<String> controlPlanes, final String envName, final Map<String, String> services, final Map<String, String> secrets) {
            this(desired, controlPlanes, envName, services, secrets, null, List.of(), null, List.of(), null);
        }
    }

    /**
     * Digest of an image designated by a tag ({@code image}: the AppSpec string, {@code repository:1.0}; {@code digest}: {@code sha256:…} of the manifest).
     * In {@link State}: resolved by the node at the first start. In {@link Desired}: cluster pin (the first one reported), which every node launches by
     * {@code repository@digest} without resolving the tag any more (gluonify-codex/provenance.md § 8).
     */
    record ImagePin(String image, String digest) {}

    /** Ready storage nodes (addresses of the "store" service instances) and access token (under WireGuard, only through the tunnel). The agent downloads the {@code gluonify-rup:<sha256>} executables from there. */
    record Store(List<String> urls, String token) {}

    /** WireGuard mesh of the node: its address, the UDP port and its peers (the other nodes and the control-planes). */
    record Wg(String self, int port, List<WgPeer> peers) {}

    record WgPeer(String id, String publicKey, String endpoint, String overlay) {}

    /** status 200: tokens filled in; 501: gluonify-charm not configured; 503: gluonify-charm unreachable. */
    record Tokens(long id, int status, Map<String, AppTokensResponse.AppToken> tokens, String error) implements Msg {}

    /**
     * Reply to {@link ServiceTokensRequest}: application -> its tokens (empty list: no service covered; application absent: not placed on this
     * node or unknown). status 200: tokens filled in; 501: gluonify-charm not configured; 503: gluonify-charm unreachable; 403: refused (outside the encrypted tunnel).
     */
    record ServiceTokens(long id, int status, Map<String, List<ServiceToken>> tokens, String error, String policy) implements Msg {
        public ServiceTokens {
            tokens = tokens == null ? Map.of() : tokens;
        }

        /** Compatibility: without {@code policy}, the digest of the policy under which the tokens were issued (see {@link Desired#tokenPolicy()}). */
        public ServiceTokens(final long id, final int status, final Map<String, List<ServiceToken>> tokens, final String error) {
            this(id, status, tokens, error, null);
        }
    }

    /** Recognized bootstrap request: identifier, target role, expected distribution version and lowest manifest sequence accepted (anti-rollback). */
    record BootstrapAccepted(String request, String role, String release, long minSequence) implements Msg {}

    /** A task of the closed catalogue (see TaskCatalogue): a single type at a time; the agent revalidates everything and refuses what is not in the catalogue. */
    record TaskRun(String id, String type, Map<String, String> params, int timeoutSec) implements Msg {
        public TaskRun {
            params = params == null ? Map.of() : params;
        }
    }

    /** Cancels the current task (the agent stops the installer process). */
    record TaskCancel(String id) implements Msg {}

    /** End of bootstrap: ok = all the tasks succeeded; otherwise error says why. The installation agent then hands over (or stops). */
    record BootstrapDone(String request, boolean ok, String error) implements Msg {}

    /** Refusal; the connection is closed afterwards. 401: unknown identity (the node must re-enroll); 403: invalid token. */
    record Error(int code, String message) implements Msg {}
}
