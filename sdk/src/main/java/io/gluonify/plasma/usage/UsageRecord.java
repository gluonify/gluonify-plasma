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
 * Usage record ({@code metering.md} § 4.1): the aggregate of one emitter, one one-minute window and one key {@code (env, org, appUuid, unit, dims)}.
 * Serialized by hand ({@link #toJson()}): the SDK does not bundle jackson-databind, and nothing is read back by Jackson on the emitter side (no reflection entry to provide).
 *
 * @param id        {@code sha256(service|replica|window|key)} in hexadecimal: a resend of the same aggregate has the same identifier (idempotence at the calorimeter)
 * @param seq       increasing number of the emitter (per {@code emitter.epoch}): a gap in the sequence is a lost record, which the calorimeter reports
 *                  in the period manifest ({@code gaps}, {@code dropped})
 * @param windowStart window start (epoch ms, multiple of 60,000)
 * @param quantity  quantity in the catalog's base unit, at most 9 decimal places
 */
public record UsageRecord(String id, long seq, Emitter emitter, String env, long windowStart, Subject subject, String unit, SortedMap<String, String> dims,
                          BigDecimal quantity, boolean internal, String onBehalfOf, String confidence) {
    public static final int WINDOW_SECONDS = 60;
    public static final long WINDOW_MS = WINDOW_SECONDS * 1000L;

    public UsageRecord {
        dims = dims == null ? new TreeMap<>() : new TreeMap<>(dims);
        quantity = quantity.setScale(Math.min(9, Math.max(0, quantity.stripTrailingZeros().scale())), RoundingMode.DOWN); // in the customer's favour
    }

    /**
     * Emitter. {@code replica} is part of the identifier: two emitters that read the SAME quantity (copies of a namespace, Higgs leader) take
     * the same logical value so as to be counted only once. {@code epoch}: creation date of the {@code seq} counter (ms), which tells two sequences apart.
     */
    public record Emitter(String service, String replica, String node, String version, long epoch) {}

    /**
     * Subject of the usage; {@code kind}: {@code service}, {@code user}, {@code node} or {@code unknown}. {@code org} is {@code _unassigned} by default.
     * {@code resource}: the resource from which the attribution is derived when the emitter does not know the application's specification ({@code gdown:<base>},
     * {@code rup:<kind>/<namespace>}); the calorimeter then derives the organization, the name and the UUID from Higgs's specifications (metering.md § 4.1).
     */
    public record Subject(String org, String app, String appUuid, String kind, String resource) {
        public Subject {
            org = org == null || org.isBlank() ? "_unassigned" : org;
            kind = kind == null ? "service" : kind;
        }

        public Subject(String org, String app, String appUuid, String kind) { this(org, app, appUuid, kind, null); }
    }

    /** Key of the aggregate (without the emitter or the window). */
    public static String key(String env, Subject s, String unit, Map<String, String> dims) {
        StringBuilder b = new StringBuilder().append(env).append('|').append(s.org()).append('|').append(s.app() == null ? "" : s.app()).append('|')
                .append(s.appUuid() == null ? "" : s.appUuid()).append('|').append(s.kind()).append('|').append(s.resource() == null ? "" : s.resource()).append('|').append(unit);
        new TreeMap<>(dims == null ? Map.of() : dims).forEach((k, v) -> b.append('|').append(k).append('=').append(v));
        return b.toString();
    }

    /** Deterministic identifier: {@code sha256(service|replica|windowStart|key)}. */
    public static String id(String service, String replica, long windowStart, String key) {
        try {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(d.digest((service + '|' + replica + '|' + windowStart + '|' + key).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** JSON of format v1 (one line, no line break). */
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
