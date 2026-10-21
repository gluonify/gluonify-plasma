package io.gluonify.plasma.contracts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical JSON: sorted keys (Java string order), null values omitted, no whitespace, integers as is, strings escaped to the minimum
 * ({@code "}, {@code \}, control characters as a "u" sequence followed by four hexadecimal digits). Records are walked through their components. Used for the terms fingerprint: two
 * clusters, or a closed period, compare the same bytes.
 */
public final class Canonical {
    private Canonical() {}

    public static String json(final Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(final StringBuilder sb, final Object o) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String s) {
            string(sb, s);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            sb.append(o);
        } else if (o instanceof java.math.BigDecimal d) {
            sb.append(d.toPlainString());
        } else if (o instanceof Enum<?> e) {
            string(sb, e.name());
        } else if (o instanceof Map<?, ?> m) {
            Map<String, Object> sorted = new TreeMap<>();
            m.forEach((k, v) -> { if (v != null) sorted.put(String.valueOf(k), v); });
            object(sb, sorted);
        } else if (o instanceof Collection<?> c) {
            collection(sb, c);
        } else if (o.getClass().isRecord()) {
            object(sb, recordFields(o));
        } else {
            throw new IllegalArgumentException("non-canonical type: " + o.getClass().getName());
        }
    }

    private static void collection(final StringBuilder sb, final Collection<?> c) {
        sb.append('[');
        boolean first = true;
        for (Object x : c) {
            if (!first) sb.append(',');
            first = false;
            write(sb, x);
        }
        sb.append(']');
    }

    private static Map<String, Object> recordFields(final Object o) {
        Map<String, Object> fields = new TreeMap<>();
        for (RecordComponent rc : o.getClass().getRecordComponents()) {
            Object v;
            try {
                v = rc.getAccessor().invoke(o);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalStateException("unreadable component: " + rc.getName(), e);
            }
            if (v != null) fields.put(rc.getName(), v);
        }
        return fields;
    }

    private static void object(final StringBuilder sb, final Map<String, Object> m) {
        sb.append('{');
        boolean first = true;
        for (var e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            string(sb, e.getKey());
            sb.append(':');
            write(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void string(final StringBuilder sb, final String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        sb.append('"');
    }
}
