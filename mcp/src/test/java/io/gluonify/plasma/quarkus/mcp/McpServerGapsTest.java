package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.mcp.McpContract;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Corners of the JSON-RPC server: unusable messages, odd parameters, values that are not objects, tools that cannot be called. */
class McpServerGapsTest {
    static final ObjectMapper M = new ObjectMapper();
    static final McpCaller READER = new McpCaller("app:prism", Set.of(), Optional.empty());

    record Args(String text) {
    }

    public static class Unwritable {
        public String getBoom() {
            throw new IllegalStateException("cannot be read");
        }
    }

    public static class Tools implements McpToolbox {
        @McpTool(description = "returns a StringBuilder")
        public StringBuilder builder() {
            return new StringBuilder("built");
        }

        @McpTool(description = "returns a list")
        public List<Integer> numbers() {
            return List.of(1, 2);
        }

        @McpTool(description = "returns nothing")
        public Object nothing() {
            return null;
        }

        @McpTool(description = "returns a number")
        public int number() {
            return 42;
        }

        @McpTool(description = "echo")
        public String echo(final Args args) {
            return args.text();
        }

        @McpTool(description = "returns something Jackson cannot write")
        public Object unwritable() {
            return new Unwritable();
        }
    }

    final McpServer server = new McpServer(McpRegistry.of(List.of(new Tools()), M, w -> {}), M, "9.9");

    McpServer.Reply send(final String body) {
        return server.handle(body.getBytes(StandardCharsets.UTF_8), READER);
    }

    JsonNode rpc(final String body) {
        final McpServer.Reply r = send(body);
        assertEquals(200, r.status(), r.body());
        return M.readTree(r.body());
    }

    JsonNode call(final String tool, final String arguments) {
        return rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + arguments + "}}");
    }

    @Test
    void messagesThatAreNotJsonRpcRequestsAreInvalid() {
        for (final String bad : new String[]{"[1,2]", "\"text\"", "42", "null", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"tools/list\"}", "{\"id\":1,\"method\":\"tools/list\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":7}", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":null}"}) {
            final JsonNode r = rpc(bad);
            assertEquals(McpContract.INVALID_REQUEST, r.at("/error/code").asInt(), bad);
            assertTrue(r.get("id").isNull(), bad);
        }
    }

    @Test
    void anUnparsableBodyIsAParseError() {
        assertEquals(McpContract.PARSE_ERROR, rpc("{not json").at("/error/code").asInt());
        assertEquals(McpContract.INVALID_REQUEST, rpc("").at("/error/code").asInt(), "an empty body is no message at all");
    }

    @Test
    void anIdThatIsNeitherAStringNorANumberIsInvalid() {
        for (final String id : new String[]{"true", "null", "[1]", "{}"}) {
            final JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\"}");
            assertEquals(McpContract.INVALID_REQUEST, r.at("/error/code").asInt(), id);
        }
    }

    @Test
    void aMessageWithoutIdIsANotificationWithoutAnswer() {
        final McpServer.Reply r = send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
        assertEquals(202, r.status());
        assertNull(r.body());
    }

    @Test
    void anOversizedBodyIsRefusedBeforeBeingRead() {
        final McpServer.Reply r = server.handle(new byte[McpContract.MAX_BODY_BYTES + 1], READER);
        assertEquals(413, r.status());
        assertEquals(McpServer.tooLarge().body(), r.body());
    }

    @Test
    void initializeIgnoresParametersThatAreNotAnObjectWithAStringVersion() {
        for (final String params : new String[]{"[]", "\"2025-03-26\"", "{\"protocolVersion\":5}", "{}", "null"}) {
            final JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + params + "}");
            assertEquals(McpContract.PROTOCOL_VERSIONS.get(0), r.at("/result/protocolVersion").asString(), params);
        }
    }

    @Test
    void aCallNeedsANameAndObjectArguments() {
        for (final String params : new String[]{"null", "[]", "\"x\"", "{}", "{\"name\":5}"}) {
            final JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":" + params + "}");
            assertEquals(McpContract.INVALID_PARAMS, r.at("/error/code").asInt(), params);
        }
        assertEquals(McpContract.INVALID_PARAMS, rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"number\",\"arguments\":[1]}}").at("/error/code").asInt());
        assertEquals(42, M.readTree(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"number\",\"arguments\":null}}").body()).at("/result/content/0/text").asInt(),
                "null arguments are no arguments");
        assertEquals(42, M.readTree(send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"number\"}}").body()).at("/result/content/0/text").asInt());
    }

    @Test
    void anUnknownMethodOrToolIsNamedWithoutEchoingAHugeName() {
        final String longName = "x".repeat(500);
        final JsonNode method = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + longName + "\"}");
        assertEquals(McpContract.METHOD_NOT_FOUND, method.at("/error/code").asInt());
        assertTrue(method.at("/error/message").asString().length() < 100);
        final JsonNode tool = call(longName, "{}");
        assertEquals(McpContract.INVALID_PARAMS, tool.at("/error/code").asInt());
        assertTrue(tool.at("/error/message").asString().length() < 100);
        assertTrue(call("short", "{}").at("/error/message").asString().endsWith("unknown tool: short"));
    }

    @Test
    void aStringBuilderIsTextAndAListIsTextWithoutStructuredContent() {
        final JsonNode builder = call("builder", "{}").get("result");
        assertEquals("built", builder.at("/content/0/text").asString());
        assertFalse(builder.has("structuredContent"));
        final JsonNode numbers = call("numbers", "{}").get("result");
        assertEquals("[1,2]", numbers.at("/content/0/text").asString().replace(" ", ""));
        assertFalse(numbers.has("structuredContent"), "only an object is structured content");
        assertFalse(numbers.get("isError").asBoolean());
    }

    @Test
    void aNullResultIsTheTextNull() {
        assertEquals("null", call("nothing", "{}").at("/result/content/0/text").asString());
    }

    @Test
    void aValueThatCannotBeWrittenIsAToolError() {
        final JsonNode r = call("unwritable", "{}").get("result");
        assertTrue(r.get("isError").asBoolean());
        assertEquals("the tool returned a value that cannot be serialised", r.at("/content/0/text").asString());
    }

    @Test
    void argumentsThatDoNotFitTheRecordAreInvalidParams() {
        final JsonNode unknown = call("echo", "{\"text\":\"a\",\"extra\":1}");
        assertEquals(McpContract.INVALID_PARAMS, unknown.at("/error/code").asInt());
        assertTrue(unknown.at("/error/message").asString().startsWith("invalid arguments:"));
        final JsonNode missing = call("echo", "{}");
        assertEquals("invalid arguments: text: field required", missing.at("/error/message").asString());
        final JsonNode nulled = call("echo", "{\"text\":null}");
        assertEquals("invalid arguments: text: field required", nulled.at("/error/message").asString());
    }

    @Test
    void argumentsLargerThanTheLimitAreRefused() {
        final String big = "{\"text\":\"" + "x".repeat(McpContract.MAX_ARGUMENTS_BYTES) + "\"}";
        final JsonNode r = rpc("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":" + big + "}}".replace("}}", "}}"));
        assertEquals(McpContract.INVALID_PARAMS, r.at("/error/code").asInt());
        assertTrue(r.at("/error/message").asString().startsWith("arguments larger than"));
    }

    @Test
    void aToolThatCannotBeCalledAtAllIsAToolErrorNotACrash() throws Exception {
        final McpRegistry.Tool real = McpRegistry.of(List.of(new Tools()), M, w -> {}).get("number").orElseThrow();
        // the target is not an instance of the method's class: the reflective call itself fails
        final McpRegistry.Tool broken = new McpRegistry.Tool("broken", "x", false, real.inputSchema(), "not a Tools instance", real.method(), -1, null, -1, List.of());
        final Constructor<McpRegistry> constructor = McpRegistry.class.getDeclaredConstructor(Map.class);
        constructor.setAccessible(true);
        final McpServer wrong = new McpServer(constructor.newInstance(Map.of("broken", broken)), M, "1");
        final JsonNode r = M.readTree(wrong.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"broken\"}}".getBytes(StandardCharsets.UTF_8), READER).body()).get("result");
        assertTrue(r.get("isError").asBoolean());
        assertEquals("the tool failed", r.at("/content/0/text").asString());
        final Method unused = real.method();
        assertNotNull(unused);
    }

    @Test
    void theMediaTypeIsJson() {
        assertEquals("application/json", McpServer.mediaType());
        assertSame(server.registry(), server.registry());
    }

    @Test
    void aCallerPrintsItsSubjectAndRolesAndNeverItsToken() {
        final McpCaller caller = new McpCaller("app:prism", Set.of("r1"), null);
        assertEquals(Optional.empty(), caller.token());
        assertTrue(caller.toString().contains("app:prism") && caller.toString().contains("r1"), caller.toString());
        assertTrue(caller.hasRole("r1"));
        assertFalse(caller.hasRole("r2"));
        assertEquals(new ArrayList<>(List.of("app:prism")), new ArrayList<>(List.of(caller.subject())));
    }
}
