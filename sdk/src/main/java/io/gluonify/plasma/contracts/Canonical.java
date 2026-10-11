package io.gluonify.plasma.contracts;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;

/**
 * JSON canonique : clés triées (ordre des chaînes Java), valeurs nulles omises, aucun espace, nombres entiers tels quels, chaînes échappées au minimum
 * ({@code "}, {@code \}, caractères de contrôle en séquence « u » à quatre chiffres hexadécimaux). Les records sont parcourus par leurs composants. Sert à l'empreinte des termes : deux
 * clusters, ou une période close, comparent les mêmes octets.
 */
public final class Canonical {
    private Canonical() {}

    public static String json(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o) {
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
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else if (o.getClass().isRecord()) {
            Map<String, Object> fields = new TreeMap<>();
            for (RecordComponent rc : o.getClass().getRecordComponents()) {
                Object v;
                try {
                    v = rc.getAccessor().invoke(o);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    throw new IllegalStateException("composant illisible : " + rc.getName(), e);
                }
                if (v != null) fields.put(rc.getName(), v);
            }
            object(sb, fields);
        } else {
            throw new IllegalArgumentException("type non canonique : " + o.getClass().getName());
        }
    }

    private static void object(StringBuilder sb, Map<String, Object> m) {
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

    private static void string(StringBuilder sb, String s) {
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
