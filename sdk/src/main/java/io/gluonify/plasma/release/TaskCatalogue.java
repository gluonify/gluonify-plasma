package io.gluonify.plasma.release;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CLOSED catalogue of the tasks a node accepts from the control-plane (see gluonify-codex/docs/BOOTSTRAP.md). A type absent from here is refused; each parameter has a strict
 * pattern, and no unknown parameter is tolerated. A parameter is NEVER a command, a path or a URL: only a name, an address, a version or a boolean.
 * The same catalogue serves the control-plane (before sending) and the node (before executing): the node does not trust the sender.
 */
public final class TaskCatalogue {
    private static final String KEY_VERSION = "version";
    private static final String KEY_COMPONENT = "component";
    private TaskCatalogue() {}

    public static final String BOOTSTRAP = "bootstrap";
    public static final String AGENT = "agent";

    /** @param secret true: the value is never written to logs or events. */
    public record Param(String name, Pattern pattern, boolean required, boolean secret) {}

    /** @param modes "bootstrap" (installation agent) and/or "agent" (installed node). */
    public record Type(String name, Set<String> modes, List<Param> params, int timeoutSec) {
        public Param param(final String n) {
            return params.stream().filter(p -> p.name().equals(n)).findFirst().orElse(null);
        }
    }

    public static final class Invalid extends RuntimeException {
        public Invalid(final String message) {
            super(message);
        }
    }

    public static final int MAX_PARAMS = 16;
    public static final int MAX_VALUE = 512;
    public static final int MAX_TOTAL = 2048;

    static final Pattern VERSION = ReleaseManifest.VERSION_PATTERN;
    static final Pattern BOOL = Pattern.compile("true|false");
    static final Pattern NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9][A-Za-z0-9.-]{0,251}|[0-9A-Fa-f:]{2,45})");
    static final Pattern IPS = Pattern.compile("[0-9A-Fa-f:.]{2,45}(,[0-9A-Fa-f:.]{2,45}){0,7}");
    static final Pattern HOSTPORT = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?:\\d{1,5}");
    static final Pattern URL = Pattern.compile("https?://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:\\d{1,5})?");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~-]{8,256}");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,200}");
    static final Pattern ROLE = Pattern.compile("worker|storage");
    static final Pattern RUNTIME = Pattern.compile("process|containerd");
    static final Pattern CP_MODE = Pattern.compile("full|ingress-only|management");
    static final Pattern DNS_MODE = Pattern.compile("authoritative|cloudflare|external");
    static final Pattern COMPONENT = Pattern.compile("strange|higgs");
    static final Pattern UNINSTALL = Pattern.compile("strange|higgs|all");

    private static Param req(final String n, final Pattern p) { return new Param(n, p, true, false); }
    private static Param opt(final String n, final Pattern p) { return new Param(n, p, false, false); }

    public static final Map<String, Type> TYPES;

    static {
        Set<String> both = Set.of(BOOTSTRAP, AGENT);
        Set<String> boot = Set.of(BOOTSTRAP);
        Set<String> agent = Set.of(AGENT);
        Map<String, Type> t = new LinkedHashMap<>();
        t.put("report-status", new Type("report-status", both, List.of(), 30));
        t.put("install-wireguard", new Type("install-wireguard", both, List.of(req(KEY_VERSION, VERSION)), 600));
        t.put("install-containerd", new Type("install-containerd", both, List.of(req(KEY_VERSION, VERSION)), 900));
        t.put("install-strange", new Type("install-strange", boot, List.of(req(KEY_VERSION, VERSION), req("role", ROLE), opt("runtime", RUNTIME),
                opt("address", ADDRESS), opt("wireguard", BOOL)), 900));
        t.put("install-higgs", new Type("install-higgs", boot, List.of(req(KEY_VERSION, VERSION), req("zone", HOST), req("publicIp", IPS), req("httpUrl", URL),
                req("nodeId", NAME), req("advertise", HOST), req("seed", HOSTPORT), new Param("seedToken", TOKEN, true, true), opt("mode", CP_MODE),
                opt("dnsMode", DNS_MODE), opt("acmeEmail", EMAIL), opt("wireguard", BOOL)), 900));
        // the new key comes from the version's signed announcement (rollover.json), NEVER from a parameter: only the version to read and "finalize"
        t.put("rotate-release-key", new Type("rotate-release-key", both, List.of(req(KEY_VERSION, VERSION), opt("finalize", BOOL)), 120));
        t.put("update", new Type("update", agent, List.of(req(KEY_COMPONENT, COMPONENT), req(KEY_VERSION, VERSION)), 900));
        t.put("rollback", new Type("rollback", agent, List.of(req(KEY_COMPONENT, COMPONENT)), 300));
        t.put("uninstall", new Type("uninstall", agent, List.of(req(KEY_COMPONENT, UNINSTALL), opt("purge", BOOL)), 300));
        TYPES = java.util.Collections.unmodifiableMap(t);
    }

    /**
     * Validates a task for an execution mode; returns the parameters (copy). Rejects: unknown type or type not allowed in this mode, unknown or missing parameter, too long
     * or outside the pattern, too many parameters.
     */
    public static Map<String, String> validate(final String type, final Map<String, String> params, final String mode) {
        Type t = type == null ? null : TYPES.get(type);
        if (t == null) throw new Invalid("unknown task type");
        if (!t.modes().contains(mode)) throw new Invalid("task \"" + type + "\" not allowed in mode " + mode);
        Map<String, String> p = params == null ? Map.of() : params;
        if (p.size() > MAX_PARAMS) throw new Invalid("too many parameters");
        int total = 0;
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : p.entrySet()) {
            total += checkedLength(t, e.getKey(), e.getValue());
            out.put(e.getKey(), e.getValue());
        }
        if (total > MAX_TOTAL) throw new Invalid("parameters too large");
        for (Param spec : t.params()) if (spec.required() && !out.containsKey(spec.name())) throw new Invalid("missing mandatory parameter: " + spec.name());
        return out;
    }

    /** The length of a parameter value, once the parameter is known and the value inside its limits and pattern. */
    private static int checkedLength(final Type t, final String key, final String v) {
        Param spec = t.param(key);
        if (spec == null) throw new Invalid("unknown parameter: " + key);
        if (v == null || v.length() > MAX_VALUE || !spec.pattern().matcher(v).matches()) throw new Invalid("invalid value for \"" + key + "\"");
        return v.length();
    }

    /** Parameters without the secret values (log, events, results). */
    public static Map<String, String> masked(final String type, final Map<String, String> params) {
        Type t = TYPES.get(type);
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        params.forEach((k, v) -> out.put(k, t != null && t.param(k) != null && t.param(k).secret() ? "***" : v));
        return out;
    }
}
