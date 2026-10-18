package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.mcp.McpContract;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The JSON-RPC behaviour of {@code POST /mcp}, without HTTP (the same wire as the Python SDK). */
class McpServerTest {
    static final ObjectMapper M = new ObjectMapper();
    static final McpCaller READER = new McpCaller("app:prism", Set.of(), Optional.empty());
    static final McpCaller WRITER = new McpCaller("app:prism", Set.of(McpContract.WRITE_ROLE), Optional.empty());

    final List<String> warnings = new ArrayList<>();
    final McpServer server = new McpServer(McpRegistry.of(List.of(new ShopTools()), M, warnings::add), M, "1.2.3");

    McpServer.Reply send(final String body, final McpCaller caller) {
        return server.handle(body.getBytes(StandardCharsets.UTF_8), caller);
    }

    JsonNode rpc(final String body, final McpCaller caller) {
        final McpServer.Reply r = send(body, caller);
        assertEquals(200, r.status(), r.body());
        return M.readTree(r.body());
    }

    JsonNode call(final String tool, final String arguments, final McpCaller caller) {
        return rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}", caller);
    }

    @Test
    void initializeEchoesASupportedVersion() {
        final JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}", READER);
        assertEquals("2025-03-26", r.at("/result/protocolVersion").asString());
        assertFalse(r.at("/result/capabilities/tools/listChanged").asBoolean());
        assertEquals("gluonify-mcp", r.at("/result/serverInfo/name").asString());
        assertEquals("1.2.3", r.at("/result/serverInfo/version").asString());
        assertEquals(1, r.get("id").asInt());
        final JsonNode other = rpc("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2030-01-01\"}}", READER);
        assertEquals("2025-06-18", other.at("/result/protocolVersion").asString());
        assertEquals("a", other.get("id").asString());
        assertEquals("2025-06-18", rpc("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\"}", READER).at("/result/protocolVersion").asString());
    }

    @Test
    void toolsListShape() {
        final JsonNode tools = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", READER).at("/result/tools");
        final List<String> names = new ArrayList<>();
        tools.forEach(t -> names.add(t.get("name").asString()));
        assertEquals(List.of("broken", "count", "echo", "huge", "opaque", "order_stats", "reset_cache"), names); // sorted, helper() is not a tool
        final JsonNode stats = tools.get(5);
        assertEquals(Set.of("name", "description", "inputSchema", "annotations", "write"), fields(stats));
        assertEquals("Number of orders over the last N days.", stats.get("description").asString());
        assertTrue(stats.at("/annotations/readOnlyHint").asBoolean());
        assertFalse(stats.get("write").asBoolean());
        assertEquals(M.readTree("""
                {"type":"object","properties":{"days":{"type":"integer","description":"Number of days, 1 to 30"},
                 "period":{"type":"string","enum":["DAY","WEEK"]},"tags":{"type":"array","items":{"type":"string"}}},
                 "required":["days","tags"],"additionalProperties":false}"""), stats.get("inputSchema"));
        final JsonNode reset = tools.get(6);
        assertFalse(reset.at("/annotations/readOnlyHint").asBoolean());
        assertTrue(reset.get("write").asBoolean());
        assertEquals(M.readTree("{\"type\":\"object\",\"properties\":{}}"), reset.get("inputSchema"));
        assertEquals("string", tools.get(2).at("/inputSchema/properties/text/type").asString()); // explicit schema
        assertTrue(warnings.isEmpty());
    }

    @Test
    void callOfAReadTool() {
        final JsonNode r = call("order_stats", "{\"days\":3,\"period\":\"DAY\",\"tags\":[]}", READER).get("result");
        assertEquals(Set.of("content", "structuredContent", "isError"), fields(r));
        assertEquals("text", r.at("/content/0/type").asString());
        assertEquals("{\"days\":3,\"orders\":30}", r.at("/content/0/text").asString());
        assertEquals(M.readTree("{\"days\":3,\"orders\":30}"), r.get("structuredContent"));
        assertFalse(r.get("isError").asBoolean());
        assertFalse(r.has("untrusted") || r.at("/structuredContent").has("untrusted")); // Prism marks, not the SDK
        final JsonNode list = call("count", "{}", READER).get("result");
        assertEquals("[1,2,3]", list.at("/content/0/text").asString());
        assertFalse(list.has("structuredContent")); // only for an object
        final JsonNode echo = call("echo", "{\"text\":\"hi\"}", READER).get("result");
        assertEquals("hi", echo.at("/structuredContent/text").asString());
        final JsonNode noArgs = rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"count\"}}", READER);
        assertFalse(noArgs.at("/result/isError").asBoolean());
    }

    @Test
    void writeToolNeedsTheWriteRole() {
        final McpServer.Reply refused = send("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"reset_cache\"}}", READER);
        assertEquals(403, refused.status());
        assertEquals(M.readTree("{\"error\":\"missing role: gluonify:mcp:write\"}"), M.readTree(refused.body()));
        final JsonNode ok = call("reset_cache", "{}", WRITER).get("result");
        assertEquals("cache emptied for app:prism", ok.at("/content/0/text").asString());
        assertFalse(ok.has("structuredContent"));
    }

    @Test
    void toolFailuresAreIsErrorResultsWithAGenericText() {
        final JsonNode broken = call("broken", "{}", READER).get("result");
        assertTrue(broken.get("isError").asBoolean());
        assertEquals("the tool failed", broken.at("/content/0/text").asString());
        final JsonNode huge = call("huge", "{}", READER).get("result");
        assertTrue(huge.get("isError").asBoolean());
        assertEquals("the response is larger than 256 KiB", huge.at("/content/0/text").asString());
        final JsonNode opaque = call("opaque", "{}", READER).get("result");
        assertTrue(opaque.get("isError").asBoolean());
        assertEquals("the tool returned a value that cannot be serialised", opaque.at("/content/0/text").asString());
    }

    @Test
    void invalidParams() {
        assertError(McpContract.INVALID_PARAMS, rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{}}", READER));
        assertError(McpContract.INVALID_PARAMS, rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":3}}", READER));
        assertError(McpContract.INVALID_PARAMS, rpc("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\"}", READER));
        assertError(McpContract.INVALID_PARAMS, call("order_stats", "[1]", READER));
        final JsonNode unknown = call("nope", "{}", READER);
        assertError(McpContract.INVALID_PARAMS, unknown);
        assertEquals("unknown tool: nope", unknown.at("/error/message").asString());
        assertEquals(7, unknown.get("id").asInt());
        final JsonNode missing = call("order_stats", "{\"days\":3}", READER);
        assertError(McpContract.INVALID_PARAMS, missing);
        assertEquals("invalid arguments: tags: field required", missing.at("/error/message").asString());
        assertError(McpContract.INVALID_PARAMS, call("order_stats", "{\"days\":\"many\",\"tags\":[]}", READER));
        assertError(McpContract.INVALID_PARAMS, call("order_stats", "{\"days\":3,\"tags\":[],\"extra\":1}", READER));
        assertError(McpContract.INVALID_PARAMS, call("order_stats", "{\"days\":null,\"tags\":[]}", READER));
        assertError(McpContract.INVALID_PARAMS, call("order_stats", "{\"days\":3,\"period\":\"YEAR\",\"tags\":[]}", READER));
        final JsonNode big = call("echo", "{\"text\":\"" + "y".repeat(McpContract.MAX_ARGUMENTS_BYTES) + "\"}", READER);
        assertError(McpContract.INVALID_PARAMS, big);
        assertEquals("arguments larger than 64 KiB", big.at("/error/message").asString());
    }

    @Test
    void jsonRpcErrors() {
        final JsonNode unknown = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"resources/list\"}", READER);
        assertError(McpContract.METHOD_NOT_FOUND, unknown);
        assertEquals("method not found: resources/list", unknown.at("/error/message").asString());
        final JsonNode parse = rpc("{not json", READER);
        assertError(McpContract.PARSE_ERROR, parse);
        assertTrue(parse.get("id").isNull());
        assertError(McpContract.INVALID_REQUEST, rpc("[]", READER)); // no batch
        assertError(McpContract.INVALID_REQUEST, rpc("{\"id\":1,\"method\":\"tools/list\"}", READER)); // no jsonrpc
        assertError(McpContract.INVALID_REQUEST, rpc("{\"jsonrpc\":\"2.0\",\"id\":1}", READER)); // no method
        assertError(McpContract.INVALID_REQUEST, rpc("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"tools/list\"}", READER));
        assertError(McpContract.INVALID_REQUEST, rpc("{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"tools/list\"}", READER));
    }

    @Test
    void notificationsGetNoAnswer() {
        final McpServer.Reply r = send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", READER);
        assertEquals(202, r.status());
        assertNull(r.body());
        assertEquals(202, send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\",\"params\":{\"name\":\"reset_cache\"}}", READER).status()); // never run
    }

    @Test
    void bodyTooLarge() {
        final McpServer.Reply r = server.handle(new byte[McpContract.MAX_BODY_BYTES + 1], READER);
        assertEquals(413, r.status());
        assertEquals("{\"error\":\"request too large\"}", r.body());
    }

    static void assertError(final int code, final JsonNode r) {
        assertEquals(code, r.at("/error/code").asInt(), r.toString());
        assertFalse(r.has("result"));
        assertEquals("2.0", r.get("jsonrpc").asString());
    }

    static Set<String> fields(final JsonNode n) {
        final Set<String> out = new java.util.HashSet<>();
        n.propertyNames().forEach(out::add);
        return out;
    }
}
