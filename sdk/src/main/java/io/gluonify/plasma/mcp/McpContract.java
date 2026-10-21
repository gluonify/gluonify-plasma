package io.gluonify.plasma.mcp;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The contract of an application's MCP tools ({@code gluonify-codex/app-mcp.md} § 3 to § 5, decisions of 2026-10-15): the wire rules shared by the
 * Quarkus helper ({@code gluonify-plasma-mcp}), the Python SDK ({@code gluonify.fastapi.mcp}) and Prism, which validates an application's
 * {@code tools/list} at discovery and relays the calls. Pure constants and checks: no dependency, no I/O, Java 17.
 */
public final class McpContract {
    private McpContract() {}

    /** Grammar of a tool name; Prism publishes it as {@code app.<application>.<name>}. */
    public static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{0,40}");
    /** At most 50 tools per application (§ 5). */
    public static final int MAX_TOOLS = 50;
    /** Input schema of one tool: 16 KiB at most, compact UTF-8 JSON (§ 5). */
    public static final int MAX_SCHEMA_BYTES = 16 * 1024;
    /** Arguments of one call: 64 KiB at most, compact UTF-8 JSON (§ 8 d5); larger is a JSON-RPC {@code -32602}. */
    public static final int MAX_ARGUMENTS_BYTES = 64 * 1024;
    /** Result of one call: 256 KiB at most (§ 8 d5); larger is an {@code isError} result. */
    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    /** Largest {@code POST /mcp} body read: the arguments plus the JSON-RPC envelope; HTTP 413 beyond. */
    public static final int MAX_BODY_BYTES = MAX_ARGUMENTS_BYTES + 4 * 1024;

    /**
     * Role that the token of a call to a {@code write} tool must carry in its {@code roles} claim (HTTP 403 otherwise, before the tool runs). Prism puts it
     * only in the token of a call it relays for an approved write proposal (§ 8 d4). Under the reserved {@code gluonify:} prefix: a customer's own
     * user or API token can never carry it.
     */
    public static final String WRITE_ROLE = "gluonify:mcp:write";

    /** Protocol versions accepted by {@code initialize}, the first one being the answer to any other version. */
    public static final List<String> PROTOCOL_VERSIONS = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    /** {@code serverInfo.name} of an application's MCP server. */
    public static final String SERVER_NAME = "gluonify-mcp";
    /** The path served on the instance's internal listener (blocked at the public ingress in PRD by Higgs). */
    public static final String PATH = "/mcp";

    /**
     * Marker read by the builder (Bup): this resource, in a jar of the trusted {@code io.gluonify.plasma} group on the application's runtime
     * classpath, says that the application serves MCP tools, and Bup sets {@code mcp: true} (§ 8 d2). It is a capability, not a service: it lives
     * outside {@code META-INF/gluonify/services/} and must never become a {@code uses} entry. Only {@code gluonify-plasma-mcp} carries it.
     */
    public static final String MARKER = "META-INF/gluonify/mcp";

    /** JSON-RPC error codes used by the server. */
    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;

    /** True when {@code name} follows {@link #TOOL_NAME}. */
    public static boolean isValidToolName(final String name) {
        return name != null && TOOL_NAME.matcher(name).matches();
    }

    /** The protocol version answered to a client asking for {@code asked}. */
    public static String negotiate(final String asked) {
        return asked != null && PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0);
    }

    /** Size in bytes of a JSON text, as the limits count it (UTF-8). */
    public static int utf8Size(final String json) {
        return json.getBytes(StandardCharsets.UTF_8).length;
    }
}
