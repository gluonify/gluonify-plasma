package io.gluonify.plasma.quarkus.mcp;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Sample toolbox of the tests (a shop): one read tool with a record, one write tool, and tools exercising the edge cases. */
@ApplicationScoped
public class ShopTools implements McpToolbox {

    public enum Period { DAY, WEEK }

    public record StatsInput(@McpParam("Number of days, 1 to 30") int days, Optional<Period> period, List<String> tags) {}

    public record Stats(int days, long orders) {}

    @McpTool(description = "Number of orders over the last N days.")
    public Stats orderStats(final StatsInput in) {
        return new Stats(in.days(), in.days() * 10L);
    }

    @McpTool(name = "reset_cache", description = "Empties the product cache.", write = true)
    public String resetCache(final McpCaller caller) {
        return "cache emptied for " + caller.subject();
    }

    @McpTool(name = "echo", description = "Echoes the arguments.", inputSchema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}")
    public Map<String, Object> echo(final Map<String, Object> args) {
        return args;
    }

    @McpTool(name = "broken", description = "Always fails.")
    public String broken() {
        throw new IllegalStateException("secret detail");
    }

    @McpTool(name = "huge", description = "Answers more than 256 KiB.")
    public String huge() {
        return "x".repeat(300 * 1024);
    }

    @McpTool(name = "count", description = "A list, not an object.")
    public List<Integer> count() {
        return List.of(1, 2, 3);
    }

    @McpTool(name = "opaque", description = "Returns a value Jackson cannot serialise.")
    public Opaque opaque() {
        return new Opaque();
    }

    /** A bean whose getter fails during serialisation. */
    public static class Opaque {
        public String getValue() {
            throw new UnsupportedOperationException("not serialisable");
        }
    }

    /** Not a tool. */
    public String helper() {
        return "";
    }
}
