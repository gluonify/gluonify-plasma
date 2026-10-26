package io.gluonify.plasma.quarkus.mcp;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * JSON Schema derived from an arguments record, kept deliberately small (what an AI needs to fill the arguments):
 * <ul>
 *   <li>a record: {@code {"type":TYPE_OBJECT,"properties":{...},"required":[...],"additionalProperties":false}}, every component required except
 *       the {@code Optional}, {@code OptionalInt}, {@code OptionalLong} and {@code OptionalDouble} ones; {@link McpParam} gives a {@code description};</li>
 *   <li>{@code String}, {@code char}: {@code string}; an enum: {@code string} with {@code enum} (the constant names); {@code boolean}: {@code boolean};
 *       {@code byte}, {@code short}, {@code int}, {@code long}, {@code BigInteger}: {@code integer}; {@code float}, {@code double}, {@code BigDecimal}: {@code number};</li>
 *   <li>an array, a {@code Collection}: {@code array} with {@code items}; a {@code Map<String, V>}: {@code object} with {@code additionalProperties};</li>
 *   <li>anything else ({@code Object}, {@code JsonNode}...): {@code {}} (any value).</li>
 * </ul>
 * A record that contains itself is cut at the second occurrence ({@code {"type":TYPE_OBJECT}}). For anything richer, give {@link McpTool#inputSchema()}.
 */
public final class McpSchemas {
    private static final String TYPE_ARRAY = "array";
    private static final String KEY_ITEMS = "items";
    private static final String TYPE_OBJECT = "object";
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private McpSchemas() {}

    /** The schema of the arguments of type {@code type}. */
    public static ObjectNode of(final Type type) {
        return schema(type, new HashSet<>());
    }

    /** Names of the required components of a record (those that are not an {@code Optional*}); empty for any other type. */
    public static List<String> required(final Class<?> type) {
        final List<String> out = new ArrayList<>();
        if (type.isRecord()) {
            for (final RecordComponent c : type.getRecordComponents()) {
                if (!optional(c.getType())) {
                    out.add(c.getName());
                }
            }
        }
        return out;
    }

    private static boolean optional(final Class<?> c) {
        return c == Optional.class || c == OptionalInt.class || c == OptionalLong.class || c == OptionalDouble.class;
    }

    private static ObjectNode schema(final Type type, final Set<Class<?>> seen) {
        if (type instanceof WildcardType w) {
            return schema(w.getUpperBounds().length == 0 ? Object.class : w.getUpperBounds()[0], seen);
        }
        if (type instanceof GenericArrayType g) {
            final ObjectNode s = F.objectNode();
            s.put("type", TYPE_ARRAY);
            s.set(KEY_ITEMS, schema(g.getGenericComponentType(), seen));
            return s;
        }
        if (type instanceof ParameterizedType p) {
            return parameterized(p, seen);
        }
        if (!(type instanceof Class<?> c)) {
            return F.objectNode();
        }
        return ofClass(c, seen);
    }

    private static ObjectNode parameterized(final ParameterizedType p, final Set<Class<?>> seen) {
        final ObjectNode s = F.objectNode();
        final Class<?> raw = (Class<?>) p.getRawType();
        final Type[] args = p.getActualTypeArguments();
        if (Collection.class.isAssignableFrom(raw)) {
            s.put("type", TYPE_ARRAY);
            s.set(KEY_ITEMS, schema(args[0], seen));
            if (Set.class.isAssignableFrom(raw)) {
                s.put("uniqueItems", true);
            }
            return s;
        }
        if (Map.class.isAssignableFrom(raw)) {
            s.put("type", TYPE_OBJECT);
            s.set("additionalProperties", schema(args[1], seen));
            return s;
        }
        if (raw == Optional.class) {
            return schema(args[0], seen);
        }
        return schema(raw, seen);
    }

    /** The JSON type of a scalar Java type ({@code string}, {@code boolean}, {@code integer}, {@code number}), or {@code null} if it is not one. */
    private static String scalarType(final Class<?> c) {
        if (c == String.class || c == char.class || c == Character.class) {
            return "string";
        }
        if (c == boolean.class || c == Boolean.class) {
            return "boolean";
        }
        if (c == int.class || c == long.class || c == short.class || c == byte.class || c == Integer.class || c == Long.class || c == Short.class
                || c == Byte.class || c == BigInteger.class || c == OptionalInt.class || c == OptionalLong.class) {
            return "integer";
        }
        if (c == double.class || c == float.class || c == Double.class || c == Float.class || c == BigDecimal.class || c == Number.class || c == OptionalDouble.class) {
            return "number";
        }
        return null;
    }

    private static ObjectNode ofClass(final Class<?> c, final Set<Class<?>> seen) {
        final ObjectNode s = F.objectNode();
        final String scalar = scalarType(c);
        if (scalar != null) {
            s.put("type", scalar);
        } else if (c.isEnum()) {
            s.put("type", "string");
            final var values = s.putArray("enum");
            for (final Object e : c.getEnumConstants()) {
                values.add(((Enum<?>) e).name());
            }
        } else if (c.isArray()) {
            s.put("type", TYPE_ARRAY);
            s.set(KEY_ITEMS, schema(c.getComponentType(), seen));
        } else if (Collection.class.isAssignableFrom(c)) {
            s.put("type", TYPE_ARRAY);
        } else if (Map.class.isAssignableFrom(c)) {
            s.put("type", TYPE_OBJECT);
        } else if (c.isRecord()) {
            recordSchema(c, s, seen);
        } else if (!JsonNode.class.isAssignableFrom(c) && c != Object.class) {
            s.put("type", TYPE_OBJECT);
        }
        return s;
    }

    private static void recordSchema(final Class<?> c, final ObjectNode s, final Set<Class<?>> seen) {
        s.put("type", TYPE_OBJECT);
        if (!seen.add(c)) {
            return;
        }
        final ObjectNode props = s.putObject("properties");
        for (final RecordComponent rc : c.getRecordComponents()) {
            final ObjectNode p = schema(rc.getGenericType(), seen);
            final McpParam d = rc.getAnnotation(McpParam.class);
            if (d != null && !d.value().isBlank()) {
                p.put("description", d.value());
            }
            props.set(rc.getName(), p);
        }
        final List<String> required = required(c);
        if (!required.isEmpty()) {
            final var r = s.putArray("required");
            required.forEach(r::add);
        }
        s.put("additionalProperties", false);
        seen.remove(c);
    }
}
