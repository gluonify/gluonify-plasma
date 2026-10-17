package io.gluonify.plasma.services.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON (RFC 8259) for the module's small documents: discovery, {@code config.json}, token files, Charm responses. No
 * dependency, no reflection (native image without configuration). Reading: objects as {@link LinkedHashMap}, arrays as {@link ArrayList}, integer
 * numbers as {@link Long} (otherwise {@link Double}), {@code true}/{@code false}, {@code null}. Not a public API: may change without notice.
 */
public final class Json {
    private static final int MAX_DEPTH = 64;

    private final String s;
    private int i;

    private Json(final String s) {
        this.s = s;
    }

    /** @throws IllegalArgumentException if the text is not valid JSON */
    public static Object parse(final String text) {
        final Json p = new Json(text);
        p.ws();
        final Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    /** A JSON object. @throws IllegalArgumentException if the text is not a JSON object */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(final String text) {
        final Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("a JSON object is expected");
        }
        return (Map<String, Object>) v;
    }

    /** The value of {@code key} if it is an object, otherwise an empty map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(final Map<String, Object> m, final String key) {
        final Object v = m == null ? null : m.get(key);
        return v instanceof Map ? (Map<String, Object>) v : Map.of();
    }

    /** The value of {@code key} if it is a non-empty string, otherwise {@code null}. */
    public static String str(final Map<String, Object> m, final String key) {
        final Object v = m == null ? null : m.get(key);
        return v instanceof String && !((String) v).isBlank() ? ((String) v).strip() : null;
    }

    /** The value of {@code key} if it is a number, otherwise {@code fallback}. */
    public static long num(final Map<String, Object> m, final String key, final long fallback) {
        final Object v = m == null ? null : m.get(key);
        return v instanceof Number ? ((Number) v).longValue() : fallback;
    }

    /** The strings of {@code key}: an array of strings, or a single string; otherwise an empty list. */
    public static List<String> strings(final Map<String, Object> m, final String key) {
        final Object v = m == null ? null : m.get(key);
        final List<String> out = new ArrayList<>();
        if (v instanceof String) {
            out.add((String) v);
        }
        else if (v instanceof Collection) {
            for (final Object o : (Collection<?>) v) {
                if (o instanceof String) {
                    out.add((String) o);
                }
            }
        }
        return out;
    }

    /** Writes a value ({@code Map} with string keys, {@code Collection}, {@code String}, {@code Number}, {@code Boolean}, {@code null}). */
    public static String write(final Object value) {
        final StringBuilder out = new StringBuilder();
        write(out, value, 0);
        return out.toString();
    }

    private static void write(final StringBuilder out, final Object v, final int indent) {
        if (v == null) {
            out.append("null");
        }
        else if (v instanceof String) {
            quote(out, (String) v);
        }
        else if (v instanceof Number || v instanceof Boolean) {
            out.append(v);
        }
        else if (v instanceof Map) {
            final Map<?, ?> m = (Map<?, ?>) v;
            if (m.isEmpty()) {
                out.append("{}");
                return;
            }
            out.append("{\n");
            boolean first = true;
            for (final Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    out.append(",\n");
                }
                first = false;
                out.append("  ".repeat(indent + 1));
                quote(out, String.valueOf(e.getKey()));
                out.append(": ");
                write(out, e.getValue(), indent + 1);
            }
            out.append('\n').append("  ".repeat(indent)).append('}');
        }
        else if (v instanceof Collection) {
            out.append('[');
            boolean first = true;
            for (final Object o : (Collection<?>) v) {
                if (!first) {
                    out.append(", ");
                }
                first = false;
                write(out, o, indent + 1);
            }
            out.append(']');
        }
        else {
            throw new IllegalArgumentException("not a JSON value: " + v.getClass().getName());
        }
    }

    private static void quote(final StringBuilder out, final String v) {
        out.append('"');
        for (int k = 0; k < v.length(); k++) {
            final char c = v.charAt(k);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    }
                    else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    private Object value(final int depth) {
        if (depth > MAX_DEPTH) {
            throw error("too deeply nested");
        }
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        final char c = s.charAt(i);
        switch (c) {
            case '{': return object(depth);
            case '[': return array(depth);
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || c >= '0' && c <= '9') {
                    return number();
                }
                throw error("unexpected character");
        }
    }

    private Map<String, Object> object(final int depth) {
        final Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw error("a key is expected");
            }
            final String key = string();
            ws();
            expect(':');
            ws();
            m.put(key, value(depth + 1));
            ws();
            final char c = next();
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw error("',' or '}' is expected");
            }
        }
    }

    private List<Object> array(final int depth) {
        final List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            final char c = next();
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw error("',' or ']' is expected");
            }
        }
    }

    private String string() {
        expect('"');
        final StringBuilder out = new StringBuilder();
        while (true) {
            final char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                final char e = next();
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) {
                            throw error("bad unicode escape");
                        }
                        try {
                            out.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        }
                        catch (final NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        i += 4;
                        break;
                    default: throw error("bad escape");
                }
            }
            else if (c < 0x20) {
                throw error("control character in a string");
            }
            else {
                out.append(c);
            }
        }
    }

    private Object number() {
        final int start = i;
        if (peek() == '-') {
            i++;
        }
        boolean decimal = false;
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            }
            else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                decimal = true;
                i++;
            }
            else {
                break;
            }
        }
        final String t = s.substring(start, i);
        try {
            if (!decimal) {
                return Long.parseLong(t);
            }
            return Double.parseDouble(t);
        }
        catch (final NumberFormatException e) {
            try {
                return Double.parseDouble(t);
            }
            catch (final NumberFormatException e2) {
                throw error("bad number");
            }
        }
    }

    private Object literal(final String word, final Object v) {
        if (!s.startsWith(word, i)) {
            throw error("unexpected literal");
        }
        i += word.length();
        return v;
    }

    private void ws() {
        while (i < s.length()) {
            final char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            }
            else {
                break;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw error("unexpected end");
        }
        return s.charAt(i);
    }

    private char next() {
        final char c = peek();
        i++;
        return c;
    }

    private void expect(final char c) {
        if (next() != c) {
            throw error("'" + c + "' is expected");
        }
    }

    private IllegalArgumentException error(final String what) {
        return new IllegalArgumentException("invalid JSON at offset " + i + ": " + what);
    }
}
