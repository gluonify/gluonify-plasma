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
 *   <li>a record: {@code {"type":"object","properties":{...},"required":[...],"additionalProperties":false}}, every component required except
 *       the {@code Optional}, {@code OptionalInt}, {@code OptionalLong} and {@code OptionalDouble} ones; {@link McpParam} gives a {@code description};</li>
 *   <li>{@code String}, {@code char}: {@code string}; an enum: {@code string} with {@code enum} (the constant names); {@code boolean}: {@code boolean};
 *       {@code byte}, {@code short}, {@code int}, {@code long}, {@code BigInteger}: {@code integer}; {@code float}, {@code double}, {@code BigDecimal}: {@code number};</li>
 *   <li>an array, a {@code Collection}: {@code array} with {@code items}; a {@code Map<String, V>}: {@code object} with {@code additionalProperties};</li>
 *   <li>anything else ({@code Object}, {@code JsonNode}...): {@code {}} (any value).</li>
 * </ul>
 * A record that contains itself is cut at the second occurrence ({@code {"type":"object"}}). For anything richer, give {@link McpTool#inputSchema()}.
 */
public final class McpSchemas {
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
        final ObjectNode s = F.objectNode();
        if (type instanceof WildcardType w) {
            return schema(w.getUpperBounds().length == 0 ? Object.class : w.getUpperBounds()[0], seen);
        }
        if (type instanceof GenericArrayType g) {
            s.put("type", "array");
            s.set("items", schema(g.getGenericComponentType(), seen));
            return s;
        }
        if (type instanceof ParameterizedType p) {
            final Class<?> raw = (Class<?>) p.getRawType();
            final Type[] args = p.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                s.put("type", "array");
                s.set("items", schema(args[0], seen));
                if (Set.class.isAssignableFrom(raw)) {
                    s.put("uniqueItems", true);
                }
                return s;
            }
            if (Map.class.isAssignableFrom(raw)) {
                s.put("type", "object");
                s.set("additionalProperties", schema(args[1], seen));
                return s;
            }
            if (raw == Optional.class) {
                return schema(args[0], seen);
            }
            return schema(raw, seen);
        }
        if (!(type instanceof Class<?> c)) {
            return s;
        }
        if (c == String.class || c == char.class || c == Character.class) {
            s.put("type", "string");
        } else if (c == boolean.class || c == Boolean.class) {
            s.put("type", "boolean");
        } else if (c == int.class || c == long.class || c == short.class || c == byte.class || c == Integer.class || c == Long.class || c == Short.class
                || c == Byte.class || c == BigInteger.class || c == OptionalInt.class || c == OptionalLong.class) {
            s.put("type", "integer");
        } else if (c == double.class || c == float.class || c == Double.class || c == Float.class || c == BigDecimal.class || c == OptionalDouble.class) {
            s.put("type", "number");
        } else if (c.isEnum()) {
            s.put("type", "string");
            final var values = s.putArray("enum");
            for (final Object e : c.getEnumConstants()) {
                values.add(((Enum<?>) e).name());
            }
        } else if (c.isArray()) {
            s.put("type", "array");
            s.set("items", schema(c.getComponentType(), seen));
        } else if (Collection.class.isAssignableFrom(c)) {
            s.put("type", "array");
        } else if (Map.class.isAssignableFrom(c)) {
            s.put("type", "object");
        } else if (c.isRecord()) {
            s.put("type", "object");
            if (!seen.add(c)) {
                return s;
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
        } else if (!JsonNode.class.isAssignableFrom(c) && c != Object.class) {
            s.put("type", "object");
        }
        return s;
    }
}
