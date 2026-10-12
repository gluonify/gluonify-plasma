package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.ws.Msg;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class X86LevelsTest {

    private static AppSpec spec(String level) {
        return new AppSpec("shop", null, 1, 8080, 64, null, Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64)), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, level);
    }

    @Test
    void niveaux() {
        assertEquals(1, X86Levels.parse(null));
        assertEquals(3, X86Levels.parse("V3"));
        assertEquals(2, X86Levels.parse("x86-64-v2"));
        assertTrue(X86Levels.runs("v2", 2));
        assertFalse(X86Levels.runs("v2", 3));
        assertTrue(X86Levels.runs("v4", 3));
        assertTrue(X86Levels.runs(null, 3), "nœud d'un ancien agent : accepté");
        assertTrue(X86Levels.runs("v1", 1));
    }

    @Test
    void niveauDuLivrable() {
        assertEquals(3, spec("AMD64:v3").requiredX86Level("amd64"));
        assertEquals("amd64:v3", spec("AMD64:v3").artifactLevel());
        assertEquals(1, spec("amd64:v3").requiredX86Level("arm64"));
        assertEquals(1, spec(null).requiredX86Level("amd64"), "sans niveau : v1");
        assertNull(spec(" ").artifactLevel());
        assertThrows(IllegalArgumentException.class, () -> spec("arm64:v3"));
        assertThrows(IllegalArgumentException.class, () -> spec("amd64:v5"));
        assertEquals("amd64:v2", spec(null).withArtifactLevel("amd64:v2").artifactLevel());
        assertEquals("amd64:v3", spec("amd64:v3").withProvenance(null).artifactLevel());
        assertEquals("amd64:v3", spec("amd64:v3").withOrganization("acme").withDistributedQuotaMb(null).artifactLevel());
        var req = new DeployRequest(null, null, null, null, null, Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64)), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "amd64:v3");
        assertEquals("amd64:v3", req.toSpec("shop").artifactLevel());
    }

    @Test
    void hoteSansNiveauCompatible() throws Exception {
        var m = JsonMapper.builder().build();
        var h = m.readValue("{\"version\":\"1\",\"cpuCount\":2,\"memoryUsedMb\":1,\"load\":0.1,\"diskFreeMb\":1,\"uptimeSec\":1}", Msg.Host.class);
        assertNull(h.x86Level());
        assertEquals("v3", m.readValue(m.writeValueAsString(new Msg.Host("1", 2, 1, 0.1, 1, 1, "v3")), Msg.Host.class).x86Level());
    }
}
