package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.ws.Msg;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class PythonVersionsTest {

    private static AppSpec spec(String python) {
        return new AppSpec("stats", null, 1, 8080, 64, null, Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64)), null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "amd64:v3", python);
    }

    @Test
    void normalisation() {
        assertNull(PythonVersions.normalize(null));
        assertNull(PythonVersions.normalize(" "));
        assertEquals("3.13", PythonVersions.normalize(" 3.13 "));
        assertEquals("3.13", PythonVersions.normalize("3.13.5"));
        assertThrows(IllegalArgumentException.class, () -> PythonVersions.normalize("2.7"));
        assertThrows(IllegalArgumentException.class, () -> PythonVersions.normalize("3"));
        assertThrows(IllegalArgumentException.class, () -> PythonVersions.normalize("3.13; rm -rf /"));
    }

    @Test
    void placement() {
        assertTrue(PythonVersions.runs(null, null), "application non Python : partout");
        assertTrue(PythonVersions.runs("3.13", null));
        assertTrue(PythonVersions.runs("3.13", "3.13"));
        assertFalse(PythonVersions.runs("3.11", "3.13"), "autre version mineure : venv et roues cp313 incompatibles");
        assertFalse(PythonVersions.runs(null, "3.13"), "nœud sans python3 ou ancien agent : aucune application Python");
    }

    @Test
    void specificationEtDeploiement() {
        assertEquals("3.13", spec("3.13.2").pythonVersion());
        assertNull(spec("").pythonVersion());
        assertThrows(IllegalArgumentException.class, () -> spec("python3"));
        assertEquals("3.13", spec(null).withPythonVersion("3.13").pythonVersion());
        AppSpec s = spec("3.13");
        assertEquals("3.13", s.withProvenance(null).withOrganization("acme").withDistributedQuotaMb(null).withArtifactLevel("amd64:v2").pythonVersion(), "conservée par les copies");
        assertNull(new AppSpec("a", null, 1, 8080, 64, null, Map.of("amd64", "u#sha256=" + "a".repeat(64)), null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null).pythonVersion(), "constructeur de compatibilité : pas Python");
        var req = new DeployRequest(null, null, null, null, null, Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64)), null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, "3.13");
        assertEquals("3.13", req.toSpec("stats").pythonVersion());
    }

    @Test
    void json() throws Exception {
        var m = JsonMapper.builder().build();
        assertNull(m.readValue("{\"version\":\"1\",\"cpuCount\":2,\"memoryUsedMb\":1,\"load\":0.1,\"diskFreeMb\":1,\"uptimeSec\":1,\"x86Level\":\"v2\"}", Msg.Host.class).pythonVersion(),
                "ancien agent : sans version");
        Msg.Host h = m.readValue(m.writeValueAsString(new Msg.Host("1", 2, 1, 0.1, 1, 1, "v3", "3.13")), Msg.Host.class);
        assertEquals("3.13", h.pythonVersion());
        assertEquals("v3", h.x86Level());
        AppSpec s = m.readValue(m.writeValueAsString(spec("3.13")), AppSpec.class);
        assertEquals("3.13", s.pythonVersion());
        assertNull(m.readValue("{\"name\":\"a\",\"image\":\"r/a:1\"}", AppSpec.class).pythonVersion(), "ancienne spécification");
        DeployRequest d = m.readValue("{\"artifacts\":{\"amd64\":\"https://b/a#sha256=" + "a".repeat(64) + "\"},\"pythonVersion\":\"3.13\"}", DeployRequest.class);
        assertEquals("3.13", d.pythonVersion());
    }
}
