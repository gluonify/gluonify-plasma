package io.gluonify.plasma.quarkus.mcp;

import io.gluonify.plasma.mcp.McpContract;
import java.lang.reflect.InvocationTargetException;
import java.util.logging.Level;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON-RPC 2.0 side of {@code POST /mcp}, without HTTP nor CDI (tested alone): one message per request (no batch, no streaming), methods
 * {@code initialize}, {@code tools/list} and {@code tools/call}. The wire behaviour is that of the Python SDK ({@code gluonify.fastapi.mcp}), so that
 * Prism relays both identically. The caller is already authenticated ({@link McpResource}).
 */
public final class McpServer {
    private static final String JSONRPC_VERSION = "2.0";
    private static final String KEY_NAME = "name";
    private static final String TEXT = "text";
    private static final String KEY_JSONRPC = "jsonrpc";
    private static final String KEY_PROTOCOL_VERSION = "protocolVersion";
    private static final Logger LOG = Logger.getLogger("gluonify.mcp");
    private static final String TOOL = "MCP tool ";
    private static final String JSON = "application/json";

    /** An HTTP answer: status, JSON body ({@code null}: none). */
    public record Reply(int status, String body) {
        static Reply json(final String body) {
            return new Reply(200, body);
        }
    }

    private final McpRegistry registry;
    private final ObjectMapper mapper;
    private final String version;

    public McpServer(final McpRegistry registry, final ObjectMapper mapper, final String version) {
        this.registry = registry;
        this.mapper = mapper;
        this.version = version;
    }

    public McpRegistry registry() {
        return registry;
    }

    /** The answer to one {@code POST /mcp} body of an authenticated caller. */
    public Reply handle(final byte[] body, final McpCaller caller) {
        if (body.length > McpContract.MAX_BODY_BYTES) {
            return tooLarge();
        }
        final JsonNode message;
        try {
            message = mapper.readTree(body);
        } catch (JacksonException e) {
            return error(null, McpContract.PARSE_ERROR, "parse error");
        }
        if (message == null || !message.isObject() || !JSONRPC_VERSION.equals(message.path(KEY_JSONRPC).asString(null)) || !message.path("method").isString()) {
            return error(null, McpContract.INVALID_REQUEST, "not a JSON-RPC 2.0 request");
        }
        if (!message.has("id")) {
            return new Reply(202, null); // a notification: no answer
        }
        final JsonNode id = message.get("id");
        if (!id.isString() && !id.isNumber()) {
            return error(null, McpContract.INVALID_REQUEST, "id must be a string or a number");
        }
        final String method = message.get("method").asString();
        final JsonNode params = message.get("params");
        final RpcMethod served = RpcMethod.of(method);
        if (served == null) {
            return error(id, McpContract.METHOD_NOT_FOUND, "method not found: " + cut(method, 64));
        }
        return served.handler.handle(this, id, params, caller);
    }

    /** The JSON-RPC methods this server answers, each with its handler. */
    private enum RpcMethod {

        /** Handshake: negotiated protocol version, capabilities and server information. */
        INITIALIZE("initialize", (server, id, params, caller) -> server.result(id, server.initialize(params))),

        /** Descriptions of the registered tools. */
        TOOLS_LIST("tools/list", (server, id, params, caller) -> server.result(id, server.toolList())),

        /** Call of one tool. */
        TOOLS_CALL("tools/call", McpServer::call);

        private final String wire;
        private final Handler handler;

        RpcMethod(final String wire, final Handler handler) {

            this.wire = wire;
            this.handler = handler;
        }

        /** The method named {@code wire} on the wire, or null when this server does not serve it. */
        static RpcMethod of(final String wire) {

            for (final RpcMethod m : values()) {
                if (m.wire.equals(wire)) {
                    return m;
                }
            }
            return null;
        }
    }

    /** Answers one JSON-RPC request. */
    @FunctionalInterface
    private interface Handler {

        /**
         * The reply to a request.
         *
         * @param server the server answering
         * @param id     the request id
         * @param params the request parameters (may be null)
         * @param caller the authenticated caller
         * @return the reply
         */
        Reply handle(McpServer server, JsonNode id, JsonNode params, McpCaller caller);
    }

    private ObjectNode toolList() {
        final ObjectNode r = mapper.createObjectNode();
        final ArrayNode list = r.putArray("tools");
        registry.tools().forEach(t -> list.add(t.describe(mapper)));
        return r;
    }

    /** HTTP 413 for a body larger than {@link McpContract#MAX_BODY_BYTES}. */
    public static Reply tooLarge() {
        return new Reply(413, "{\"error\":\"request too large\"}");
    }

    private ObjectNode initialize(final JsonNode params) {
        final String asked = params != null && params.isObject() && params.path(KEY_PROTOCOL_VERSION).isString() ? params.get(KEY_PROTOCOL_VERSION).asString() : null;
        final ObjectNode r = mapper.createObjectNode();
        r.put(KEY_PROTOCOL_VERSION, McpContract.negotiate(asked));
        r.putObject("capabilities").putObject("tools").put("listChanged", false);
        r.putObject("serverInfo").put(KEY_NAME, McpContract.SERVER_NAME).put("version", version);
        return r;
    }

    private Reply call(final JsonNode id, final JsonNode params, final McpCaller caller) {
        if (params == null || !params.isObject() || !params.path(KEY_NAME).isString()) {
            return error(id, McpContract.INVALID_PARAMS, "params.name (string) is required");
        }
        JsonNode arguments = params.get("arguments");
        if (arguments == null || arguments.isNull()) {
            arguments = mapper.createObjectNode();
        }
        if (!arguments.isObject()) {
            return error(id, McpContract.INVALID_PARAMS, "params.arguments must be an object");
        }
        final String name = params.get(KEY_NAME).asString();
        final McpRegistry.Tool tool = registry.get(name).orElse(null);
        if (tool == null) {
            return error(id, McpContract.INVALID_PARAMS, "unknown tool: " + cut(name, 64));
        }
        return invoke(id, tool, arguments, caller);
    }

    /** Checks the size of the arguments and the role, binds the arguments, calls the tool and renders its value. */
    private Reply invoke(final JsonNode id, final McpRegistry.Tool tool, final JsonNode arguments, final McpCaller caller) {
        if (McpContract.utf8Size(mapper.writeValueAsString(arguments)) > McpContract.MAX_ARGUMENTS_BYTES) {
            return error(id, McpContract.INVALID_PARAMS, "arguments larger than " + McpContract.MAX_ARGUMENTS_BYTES / 1024 + " KiB");
        }
        if (tool.write() && !caller.hasRole(McpContract.WRITE_ROLE)) {
            return new Reply(403, mapper.createObjectNode().put("error", "missing role: " + McpContract.WRITE_ROLE).toString());
        }
        final Object[] values = new Object[tool.method().getParameterCount()];
        if (tool.caller() >= 0) {
            values[tool.caller()] = caller;
        }
        final Reply invalid = bindArgument(id, tool, arguments, values);
        if (invalid != null) {
            return invalid;
        }
        final Object value;
        try {
            value = tool.method().invoke(tool.target(), values);
        } catch (InvocationTargetException e) {
            LOG.log(Level.WARNING, e.getCause(), () -> TOOL + tool.name() + " failed");
            return toolError(id, "the tool failed");
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.WARNING, e, () -> TOOL + tool.name() + " could not be called");
            return toolError(id, "the tool failed");
        }
        return render(id, tool, value);
    }

    /** Reads the argument object into its parameter; the refusal to answer with if a required field is missing or the object is invalid, otherwise {@code null}. */
    private Reply bindArgument(final JsonNode id, final McpRegistry.Tool tool, final JsonNode arguments, final Object[] values) {
        if (tool.argument() < 0) {
            return null;
        }
        for (final String r : tool.required()) {
            if (arguments.path(r).isMissingNode() || arguments.get(r).isNull()) {
                return error(id, McpContract.INVALID_PARAMS, cut("invalid arguments: " + r + ": field required", 1024));
            }
        }
        try {
            values[tool.argument()] = mapper.readerFor(mapper.constructType(tool.argumentType()))
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                    .readValue(arguments);
            return null;
        } catch (JacksonException e) {
            return error(id, McpContract.INVALID_PARAMS, cut("invalid arguments: " + e.getOriginalMessage(), 1024));
        }
    }

    /** The result of the tool call: its value as text, and as structured content when it is an object. */
    private Reply render(final JsonNode id, final McpRegistry.Tool tool, final Object value) {
        final ObjectNode r = mapper.createObjectNode();
        try {
            JsonNode structured = null;
            final String text;
            if (value instanceof String s) {
                text = s;
            } else {
                final JsonNode data = mapper.valueToTree(value);
                text = data.isString() ? data.asString() : mapper.writeValueAsString(data);
                structured = data.isObject() ? data : null;
            }
            r.putArray("content").addObject().put("type", TEXT).put(TEXT, text);
            if (structured != null) {
                r.set("structuredContent", structured);
            }
            r.put("isError", false);
        } catch (JacksonException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, e, () -> TOOL + tool.name() + " returned a value that is not serialisable");
            return toolError(id, "the tool returned a value that cannot be serialised");
        }
        if (McpContract.utf8Size(mapper.writeValueAsString(r)) > McpContract.MAX_RESPONSE_BYTES) {
            return toolError(id, "the response is larger than " + McpContract.MAX_RESPONSE_BYTES / 1024 + " KiB");
        }
        return result(id, r);
    }

    private Reply toolError(final JsonNode id, final String message) {
        final ObjectNode r = mapper.createObjectNode();
        r.putArray("content").addObject().put("type", TEXT).put(TEXT, message);
        r.put("isError", true);
        return result(id, r);
    }

    private Reply result(final JsonNode id, final JsonNode result) {
        final ObjectNode r = mapper.createObjectNode();
        r.put(KEY_JSONRPC, JSONRPC_VERSION);
        r.set("id", id);
        r.set("result", result);
        return Reply.json(mapper.writeValueAsString(r));
    }

    private Reply error(final JsonNode id, final int code, final String message) {
        final ObjectNode r = mapper.createObjectNode();
        r.put(KEY_JSONRPC, JSONRPC_VERSION);
        if (id == null) {
            r.putNull("id");
        } else {
            r.set("id", id);
        }
        r.putObject("error").put("code", code).put("message", message);
        return Reply.json(mapper.writeValueAsString(r));
    }

    private static String cut(final String s, final int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Media type of every body this server writes. */
    public static String mediaType() {
        return JSON;
    }
}
