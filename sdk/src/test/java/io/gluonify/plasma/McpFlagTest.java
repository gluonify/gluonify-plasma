package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The {@code mcp} flag of the specification (gluonify-codex/app-mcp.md § 3): optional, null and false mean "no MCP tools". */
class McpFlagTest {

    private static final Map<String, String> ART = Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64));

    private static AppSpec spec(Boolean mcp) {
        return new AppSpec("shop", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, mcp);
    }

    @Test
    void normalization() {
        assertTrue(spec(true).mcpEnabled());
        assertEquals(Boolean.TRUE, spec(true).mcp());
        assertNull(spec(false).mcp(), "false is stored as absent");
        assertNull(spec(null).mcp());
        assertFalse(spec(null).mcpEnabled());
        assertEquals(spec(false), spec(null), "stable equality of the stored specification");
    }

    @Test
    void compatibilityConstructorsAndCopies() {
        assertNull(new AppSpec("a", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, "3.13").mcp(), "compatibility constructor: no MCP tools");
        assertNull(new AppSpec("a", "r/a:1", 1, 8080, 64, null).mcp());
        AppSpec s = spec(true);
        assertTrue(s.withProvenance(null).withOrganization("acme").withDistributedQuotaMb(null).withArtifactLevel("amd64:v2").withPythonVersion(null).mcpEnabled(), "kept by the copies");
        assertFalse(s.withMcp(false).mcpEnabled());
        assertTrue(spec(null).withMcp(true).mcpEnabled());
        var old = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "3.13");
        assertNull(old.mcp());
        assertFalse(old.toSpec("shop").mcpEnabled());
        var req = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, true);
        assertTrue(req.toSpec("shop").mcpEnabled(), "carried from the deployment to the specification");
        assertTrue(req.toSpec("shop", "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12").mcpEnabled());
    }

    @Test
    void json() throws Exception {
        var m = JsonMapper.builder().build();
        assertNull(m.readValue("{\"name\":\"a\",\"image\":\"r/a:1\"}", AppSpec.class).mcp(), "old specification (Raft snapshot): no MCP tools");
        AppSpec back = m.readValue(m.writeValueAsString(spec(true)), AppSpec.class);
        assertTrue(back.mcpEnabled());
        assertTrue(m.writeValueAsString(spec(true)).contains("\"mcp\":true"));
        DeployRequest d = m.readValue("{\"image\":\"r/a:1\",\"mcp\":true}", DeployRequest.class);
        assertEquals(Boolean.TRUE, d.mcp());
        assertNull(m.readValue("{\"image\":\"r/a:1\"}", DeployRequest.class).mcp());
    }
}
