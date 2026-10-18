package io.gluonify.plasma.quarkus.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a public method of an {@link McpToolbox} bean as an MCP tool of the application, served at {@code POST /mcp} and relayed by Prism as
 * {@code app.<application>.<name>} ({@code gluonify-codex/app-mcp.md}).
 * <p>Signature: at most one <b>arguments</b> parameter, a record (the input schema is derived from its components, see {@link McpSchemas}), a
 * {@code Map<String, ?>} or a Jackson {@code JsonNode} (then give {@link #inputSchema()}); and optionally one {@link McpCaller} parameter (the
 * caller's identity). No parameter: a tool without argument. Result: a {@code String} (returned as is) or anything the application's Jackson
 * {@code ObjectMapper} serialises (a record or a map is also returned as {@code structuredContent}). An exception becomes an {@code isError} result
 * saying only {@code the tool failed} (the detail goes to the log, never to the AI).
 * <p>Checked at startup (the application does not start otherwise): the name grammar, unique names, at most 50 tools, an input schema of type
 * {@code object} of at most 16 KiB, the signature.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface McpTool {
    /** {@code [a-z][a-z0-9_]{0,40}}; empty (default): the method name in snake case ({@code orderStats} -> {@code order_stats}). */
    String name() default "";

    /** What the AI reads to decide to call the tool; required. */
    String description();

    /** True when the tool changes something: the call then needs the role {@code gluonify:mcp:write}, and Prism turns it into a proposal to approve. */
    boolean write() default false;

    /** Explicit JSON Schema (of type {@code object}) of the arguments; empty (default): derived from the arguments record, or an empty object schema. */
    String inputSchema() default "";
}
