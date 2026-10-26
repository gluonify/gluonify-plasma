package io.gluonify.plasma.quarkus.mcp;

import io.gluonify.plasma.mcp.McpContract;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The tools of an application, read from the {@link McpTool} methods of its {@link McpToolbox} beans and checked once (at startup): an invalid
 * declaration throws {@link IllegalStateException}, so the application does not start with it.
 */
public final class McpRegistry {

    /**
     * A declared tool.
     *
     * @param argument   index of the arguments parameter, or -1
     * @param caller     index of the {@link McpCaller} parameter, or -1
     * @param required   required properties of an arguments record (checked before the call)
     */
    public record Tool(String name, String description, boolean write, ObjectNode inputSchema, Object target, Method method, int argument, Type argumentType,
            int caller, List<String> required) {

        /** The {@code tools/list} entry: MCP's {@code name}, {@code description}, {@code inputSchema} and {@code annotations}, plus {@code write}. */
        ObjectNode describe(final ObjectMapper mapper) {
            final ObjectNode o = mapper.createObjectNode();
            o.put("name", name);
            o.put("description", description);
            o.set("inputSchema", inputSchema);
            o.putObject("annotations").put("readOnlyHint", !write);
            o.put("write", write);
            return o;
        }
    }

    private final Map<String, Tool> tools;

    private McpRegistry(final Map<String, Tool> tools) {
        this.tools = tools;
    }

    /** The tools of {@code toolboxes} (CDI instances or plain objects); {@code warn} receives the toolboxes in which no tool could be read. */
    public static McpRegistry of(final Collection<?> toolboxes, final ObjectMapper mapper, final Consumer<String> warn) {
        final Map<String, Tool> out = new TreeMap<>();
        for (final Object target : toolboxes) {
            final Class<?> type = unproxied(target.getClass());
            int found = 0;
            for (final Method m : methods(type)) {
                final McpTool a = m.getAnnotation(McpTool.class);
                if (a == null) {
                    continue;
                }
                final Tool t = build(target, m, a, mapper);
                if (out.putIfAbsent(t.name(), t) != null) {
                    throw new IllegalStateException("MCP tool declared twice: " + t.name());
                }
                if (out.size() > McpContract.MAX_TOOLS) {
                    throw new IllegalStateException("at most " + McpContract.MAX_TOOLS + " MCP tools per application");
                }
                found++;
            }
            if (found == 0) {
                warn.accept("no @McpTool method can be read on " + type.getName() + " (in a native image, annotate it with @RegisterForReflection)");
            }
        }
        return new McpRegistry(out);
    }

    /** The tools sorted by name. */
    public List<Tool> tools() {
        return List.copyOf(tools.values());
    }

    public Optional<Tool> get(final String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public int size() {
        return tools.size();
    }

    /** The bean class behind an ArC client proxy or intercepted subclass (both generated subclasses of it). */
    private static Class<?> unproxied(final Class<?> c) {
        Class<?> k = c;
        while (k.getSuperclass() != null && k.getSuperclass() != Object.class && (k.isSynthetic() || k.getName().contains("_ClientProxy")
                || k.getName().contains("_Subclass"))) {
            k = k.getSuperclass();
        }
        return k;
    }

    /** Declared methods of the class and its superclasses (an overriding method, annotated or not, hides the parent's), sorted for a stable order. */
    private static List<Method> methods(final Class<?> type) {
        final List<Method> out = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        for (Class<?> k = type; k != null && k != Object.class; k = k.getSuperclass()) {
            for (final Method m : k.getDeclaredMethods()) {
                if (!m.isSynthetic() && !m.isBridge() && !Modifier.isStatic(m.getModifiers()) && seen.add(m.getName() + Arrays.toString(m.getParameterTypes()))) {
                    out.add(m);
                }
            }
        }
        out.sort(Comparator.comparing(Method::getName));
        return out;
    }

    private static Tool build(final Object target, final Method m, final McpTool a, final ObjectMapper mapper) {
        final String where = m.getDeclaringClass().getName() + "." + m.getName();
        final String name = a.name().isEmpty() ? snake(m.getName()) : a.name();
        if (!McpContract.isValidToolName(name)) {
            throw new IllegalStateException("invalid MCP tool name '" + name + "' (" + where + "): must match " + McpContract.TOOL_NAME.pattern());
        }
        if (a.description().isBlank()) {
            throw new IllegalStateException("MCP tool " + name + " (" + where + "): a description is required");
        }
        if (!Modifier.isPublic(m.getModifiers())) {
            throw new IllegalStateException("MCP tool " + name + " (" + where + "): the method must be public");
        }
        final Class<?>[] types = m.getParameterTypes();
        final int[] positions = parameterPositions(types, name, where);
        final int argument = positions[0];
        final int caller = positions[1];
        final Type argumentType = argument < 0 ? null : m.getGenericParameterTypes()[argument];
        final ObjectNode schema = inputSchema(a, argumentType, name + " (" + where + ")", mapper);
        m.setAccessible(true); // a package-private toolbox class
        final List<String> required = argument < 0 ? List.of() : McpSchemas.required(types[argument]);
        return new Tool(name, a.description().strip(), a.write(), schema, target, m, argument, argumentType, caller, required);
    }

    /** The position of the arguments parameter and of the optional {@link McpCaller} parameter ({@code -1}: absent), as {@code {argument, caller}}. */
    private static int[] parameterPositions(final Class<?>[] types, final String name, final String where) {
        int argument = -1;
        int caller = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == McpCaller.class && caller < 0) {
                caller = i;
            } else if (argument < 0 && types[i] != McpCaller.class) {
                argument = i;
            } else {
                throw new IllegalStateException("MCP tool " + name + " (" + where + "): one arguments parameter and optionally an McpCaller, found also "
                        + types[i].getSimpleName());
            }
        }
        return new int[] {argument, caller};
    }

    /** The input schema: the declared one, otherwise derived from the argument type, otherwise an empty object schema; checked to be an object schema within the size limit. */
    private static ObjectNode inputSchema(final McpTool a, final Type argumentType, final String what, final ObjectMapper mapper) {
        final ObjectNode schema;
        if (!a.inputSchema().isBlank()) {
            schema = declaredSchema(a.inputSchema(), what, mapper);
        } else if (argumentType != null) {
            schema = McpSchemas.of(argumentType);
        } else {
            schema = mapper.createObjectNode();
            schema.put("type", "object");
            schema.putObject("properties");
        }
        if (!"object".equals(schema.path("type").asString(""))) {
            throw new IllegalStateException("MCP tool " + what + ": the input schema must be an object schema (\"type\": \"object\")");
        }
        if (McpContract.utf8Size(mapper.writeValueAsString(schema)) > McpContract.MAX_SCHEMA_BYTES) {
            throw new IllegalStateException("MCP tool " + what + ": the input schema is larger than " + McpContract.MAX_SCHEMA_BYTES / 1024 + " KiB");
        }
        return schema;
    }

    private static ObjectNode declaredSchema(final String text, final String what, final ObjectMapper mapper) {
        final JsonNode n;
        try {
            n = mapper.readTree(text);
        } catch (JacksonException e) {
            throw new IllegalStateException("MCP tool " + what + ": the input schema is not valid JSON");
        }
        if (!(n instanceof ObjectNode o)) {
            throw new IllegalStateException("MCP tool " + what + ": the input schema must be a JSON object");
        }
        return o;
    }

    /** {@code orderStats} -> {@code order_stats}; {@code HTTPStatus} -> {@code httpstatus}. */
    static String snake(final String java) {
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < java.length(); i++) {
            final char c = java.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0 && !Character.isUpperCase(java.charAt(i - 1))) {
                    b.append('_');
                }
                b.append(Character.toLowerCase(c));
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }
}
