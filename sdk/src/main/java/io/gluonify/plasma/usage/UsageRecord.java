package io.gluonify.plasma.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Enregistrement d'usage ({@code metering.md} § 4.1) : l'agrégat d'un émetteur, d'une fenêtre d'une minute et d'une clé {@code (env, org, appUuid, unit, dims)}.
 * Sérialisé à la main ({@link #toJson()}) : le SDK n'embarque pas jackson-databind, et rien n'est relu par Jackson côté émetteur (aucune entrée de réflexion à prévoir).
 *
 * @param id        {@code sha256(service|replica|fenêtre|clé)} en hexadécimal : un renvoi du même agrégat a le même identifiant (idempotence au calorimètre)
 * @param seq       numéro croissant de l'émetteur (par {@code emitter.epoch}) : un trou dans la suite est un enregistrement perdu, que le calorimètre reporte
 *                  dans le manifeste de la période ({@code gaps}, {@code dropped})
 * @param windowStart début de la fenêtre (ms epoch, multiple de 60 000)
 * @param quantity  quantité dans la grandeur de base du catalogue, 9 décimales au plus
 */
public record UsageRecord(String id, long seq, Emitter emitter, String env, long windowStart, Subject subject, String unit, SortedMap<String, String> dims,
                          BigDecimal quantity, boolean internal, String onBehalfOf, String confidence) {
    public static final int WINDOW_SECONDS = 60;
    public static final long WINDOW_MS = WINDOW_SECONDS * 1000L;

    public UsageRecord {
        dims = dims == null ? new TreeMap<>() : new TreeMap<>(dims);
        quantity = quantity.setScale(Math.min(9, Math.max(0, quantity.stripTrailingZeros().scale())), RoundingMode.DOWN); // en faveur du client
    }

    /**
     * Émetteur. {@code replica} fait partie de l'identifiant : deux émetteurs qui relèvent la MÊME grandeur (copies d'un espace de noms, leader de Higgs) prennent
     * la même valeur logique pour n'être comptés qu'une fois. {@code epoch} : date de création du compteur {@code seq} (ms), qui distingue deux suites.
     */
    public record Emitter(String service, String replica, String node, String version, long epoch) {}

    /**
     * Sujet de l'usage ; {@code kind} : {@code service}, {@code user}, {@code node} ou {@code unknown}. {@code org} vaut {@code _unassigned} à défaut.
     * {@code resource} : la ressource d'où l'attribution est tirée quand l'émetteur ne connaît pas la spécification de l'application ({@code gdown:<base>},
     * {@code rup:<genre>/<espace>}) ; le calorimètre en déduit alors l'organisation, le nom et l'UUID d'après les spécifications de Higgs (metering.md § 4.1).
     */
    public record Subject(String org, String app, String appUuid, String kind, String resource) {
        public Subject {
            org = org == null || org.isBlank() ? "_unassigned" : org;
            kind = kind == null ? "service" : kind;
        }

        public Subject(String org, String app, String appUuid, String kind) { this(org, app, appUuid, kind, null); }
    }

    /** Clé de l'agrégat (sans l'émetteur ni la fenêtre). */
    public static String key(String env, Subject s, String unit, Map<String, String> dims) {
        StringBuilder b = new StringBuilder().append(env).append('|').append(s.org()).append('|').append(s.app() == null ? "" : s.app()).append('|')
                .append(s.appUuid() == null ? "" : s.appUuid()).append('|').append(s.kind()).append('|').append(s.resource() == null ? "" : s.resource()).append('|').append(unit);
        new TreeMap<>(dims == null ? Map.of() : dims).forEach((k, v) -> b.append('|').append(k).append('=').append(v));
        return b.toString();
    }

    /** Identifiant déterministe : {@code sha256(service|replica|windowStart|clé)}. */
    public static String id(String service, String replica, long windowStart, String key) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest((service + '|' + replica + '|' + windowStart + '|' + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON du format v1 (une ligne, sans saut de ligne). */
    public String toJson() {
        StringBuilder b = new StringBuilder(384);
        b.append("{\"v\":1,\"id\":").append(q(id)).append(",\"seq\":").append(seq);
        b.append(",\"emitter\":{\"service\":").append(q(emitter.service())).append(",\"replica\":").append(q(emitter.replica()));
        if (emitter.node() != null) b.append(",\"node\":").append(q(emitter.node()));
        if (emitter.version() != null) b.append(",\"version\":").append(q(emitter.version()));
        b.append(",\"epoch\":").append(emitter.epoch()).append('}');
        b.append(",\"env\":").append(q(env));
        b.append(",\"window\":{\"start\":").append(q(Instant.ofEpochMilli(windowStart).toString())).append(",\"seconds\":").append(WINDOW_SECONDS).append('}');
        b.append(",\"subject\":{\"org\":").append(q(subject.org()));
        if (subject.app() != null) b.append(",\"app\":").append(q(subject.app()));
        if (subject.appUuid() != null) b.append(",\"appUuid\":").append(q(subject.appUuid()));
        b.append(",\"kind\":").append(q(subject.kind()));
        if (subject.resource() != null) b.append(",\"resource\":").append(q(subject.resource()));
        b.append('}');
        b.append(",\"unit\":").append(q(unit));
        if (!dims.isEmpty()) {
            b.append(",\"dims\":{");
            boolean first = true;
            for (var e : dims.entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(q(e.getKey())).append(':').append(q(e.getValue()));
            }
            b.append('}');
        }
        b.append(",\"quantity\":").append(q(quantity.toPlainString()));
        b.append(",\"internal\":").append(internal);
        if (onBehalfOf != null) b.append(",\"onBehalfOf\":").append(q(onBehalfOf));
        b.append(",\"confidence\":").append(q(confidence)).append('}');
        return b.toString();
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
