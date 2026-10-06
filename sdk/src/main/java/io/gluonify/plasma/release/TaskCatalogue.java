package io.gluonify.plasma.release;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Catalogue FERMÉ des tâches qu'un nœud accepte du control-plane (voir gluonify-codex/docs/BOOTSTRAP.md). Un type absent d'ici est refusé ; chaque paramètre a un motif
 * strict, et aucun paramètre inconnu n'est toléré. Un paramètre n'est JAMAIS une commande, un chemin ou une URL : seulement un nom, une adresse, une version ou un booléen.
 * Le même catalogue sert au control-plane (avant d'envoyer) et au nœud (avant d'exécuter) : le nœud ne fait pas confiance à l'émetteur.
 */
public final class TaskCatalogue {
    private TaskCatalogue() {}

    public static final String BOOTSTRAP = "bootstrap";
    public static final String AGENT = "agent";

    /** @param secret true : la valeur n'est jamais écrite dans les journaux ni les évènements. */
    public record Param(String name, Pattern pattern, boolean required, boolean secret) {}

    /** @param modes « bootstrap » (agent d'installation) et/ou « agent » (nœud installé). */
    public record Type(String name, Set<String> modes, List<Param> params, int timeoutSec) {
        public Param param(String n) {
            return params.stream().filter(p -> p.name().equals(n)).findFirst().orElse(null);
        }
    }

    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    public static final int MAX_PARAMS = 16;
    public static final int MAX_VALUE = 512;
    public static final int MAX_TOTAL = 2048;

    static final Pattern VERSION = ReleaseManifest.VERSION;
    static final Pattern BOOL = Pattern.compile("true|false");
    static final Pattern NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    static final Pattern HOST = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    static final Pattern ADDRESS = Pattern.compile("([A-Za-z0-9][A-Za-z0-9.-]{0,251}|[0-9A-Fa-f:]{2,45})");
    static final Pattern IPS = Pattern.compile("[0-9A-Fa-f:.]{2,45}(,[0-9A-Fa-f:.]{2,45}){0,7}");
    static final Pattern HOSTPORT = Pattern.compile("[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?:[0-9]{1,5}");
    static final Pattern URL = Pattern.compile("https?://[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?(:[0-9]{1,5})?");
    static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~-]{8,256}");
    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,200}");
    static final Pattern ROLE = Pattern.compile("worker|storage");
    static final Pattern RUNTIME = Pattern.compile("process|containerd");
    static final Pattern CP_MODE = Pattern.compile("full|ingress-only|management");
    static final Pattern DNS_MODE = Pattern.compile("authoritative|cloudflare|external");
    static final Pattern COMPONENT = Pattern.compile("strange|higgs");
    static final Pattern UNINSTALL = Pattern.compile("strange|higgs|all");

    private static Param req(String n, Pattern p) { return new Param(n, p, true, false); }
    private static Param opt(String n, Pattern p) { return new Param(n, p, false, false); }

    public static final Map<String, Type> TYPES;

    static {
        Set<String> both = Set.of(BOOTSTRAP, AGENT), boot = Set.of(BOOTSTRAP), agent = Set.of(AGENT);
        Map<String, Type> t = new LinkedHashMap<>();
        t.put("report-status", new Type("report-status", both, List.of(), 30));
        t.put("install-wireguard", new Type("install-wireguard", both, List.of(req("version", VERSION)), 600));
        t.put("install-containerd", new Type("install-containerd", both, List.of(req("version", VERSION)), 900));
        t.put("install-strange", new Type("install-strange", boot, List.of(req("version", VERSION), req("role", ROLE), opt("runtime", RUNTIME),
                opt("address", ADDRESS), opt("wireguard", BOOL)), 900));
        t.put("install-higgs", new Type("install-higgs", boot, List.of(req("version", VERSION), req("zone", HOST), req("publicIp", IPS), req("httpUrl", URL),
                req("nodeId", NAME), req("advertise", HOST), req("seed", HOSTPORT), new Param("seedToken", TOKEN, true, true), opt("mode", CP_MODE),
                opt("dnsMode", DNS_MODE), opt("acmeEmail", EMAIL), opt("wireguard", BOOL)), 900));
        // la nouvelle clé vient de l'annonce signée de la version (rollover.json), JAMAIS d'un paramètre : seulement la version à lire et « finalize »
        t.put("rotate-release-key", new Type("rotate-release-key", both, List.of(req("version", VERSION), opt("finalize", BOOL)), 120));
        t.put("update", new Type("update", agent, List.of(req("component", COMPONENT), req("version", VERSION)), 900));
        t.put("rollback", new Type("rollback", agent, List.of(req("component", COMPONENT)), 300));
        t.put("uninstall", new Type("uninstall", agent, List.of(req("component", UNINSTALL), opt("purge", BOOL)), 300));
        TYPES = java.util.Collections.unmodifiableMap(t);
    }

    /**
     * Valide une tâche pour un mode d'exécution ; retourne les paramètres (copie). Rejette : type inconnu ou non permis dans ce mode, paramètre inconnu, absent, trop long
     * ou hors motif, trop de paramètres.
     */
    public static Map<String, String> validate(String type, Map<String, String> params, String mode) {
        Type t = type == null ? null : TYPES.get(type);
        if (t == null) throw new Invalid("type de tâche inconnu");
        if (!t.modes().contains(mode)) throw new Invalid("tâche « " + type + " » non permise en mode " + mode);
        Map<String, String> p = params == null ? Map.of() : params;
        if (p.size() > MAX_PARAMS) throw new Invalid("trop de paramètres");
        int total = 0;
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : p.entrySet()) {
            Param spec = t.param(e.getKey());
            if (spec == null) throw new Invalid("paramètre inconnu : " + e.getKey());
            String v = e.getValue();
            if (v == null || v.length() > MAX_VALUE || !spec.pattern().matcher(v).matches()) throw new Invalid("valeur invalide pour « " + e.getKey() + " »");
            total += v.length();
            out.put(e.getKey(), v);
        }
        if (total > MAX_TOTAL) throw new Invalid("paramètres trop volumineux");
        for (Param spec : t.params()) if (spec.required() && !out.containsKey(spec.name())) throw new Invalid("paramètre obligatoire absent : " + spec.name());
        return out;
    }

    /** Paramètres sans les valeurs secrètes (journal, évènements, résultats). */
    public static Map<String, String> masked(String type, Map<String, String> params) {
        Type t = TYPES.get(type);
        Map<String, String> out = new LinkedHashMap<>();
        if (params == null) return out;
        params.forEach((k, v) -> out.put(k, t != null && t.param(k) != null && t.param(k).secret() ? "***" : v));
        return out;
    }
}
