package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code tls} flag of a specification: false and absent are one value, kept by the copies, carried from the deployment, read from JSON.
 *
 * @author David CABILLIC
 */
class TlsFlagTest {

    private static final Map<String, String> ART = Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64));

    private static AppSpec spec(final Boolean tls) {

        return new AppSpec("shop", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true, tls);
    }

    @Test
    void normalization() {

        assertTrue(spec(true).tlsEnabled());
        assertNull(spec(false).tls(), "false is stored as absent");
        assertFalse(spec(null).tlsEnabled());
        assertEquals(spec(false), spec(null), "stable equality of the stored specification");
    }

    @Test
    void compatibilityConstructorsAndCopies() {

        assertNull(new AppSpec("a", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, true).tls(), "compatibility constructor: plain HTTP");
        final AppSpec s = spec(true);
        assertTrue(s.withProvenance(null).withOrganization("acme").withDistributedQuotaMb(null).withArtifactLevel("amd64:v2").withPythonVersion(null).withMcp(false).tlsEnabled(),
                "kept by the copies");
        assertFalse(s.withTls(false).tlsEnabled());
        assertTrue(spec(null).withTls(true).withMcp(true).tlsEnabled());
        final var old = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, true);
        assertNull(old.tls());
        assertFalse(old.toSpec("shop").tlsEnabled());
        final var req = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true);
        assertTrue(req.toSpec("shop").tlsEnabled(), "carried from the deployment to the specification");
    }

    @Test
    void json() throws Exception {

        final var m = JsonMapper.builder().build();
        assertNull(m.readValue("{\"name\":\"a\",\"image\":\"r/a:1\"}", AppSpec.class).tls(), "old specification (Raft snapshot): plain HTTP");
        assertTrue(m.readValue(m.writeValueAsString(spec(true)), AppSpec.class).tlsEnabled());
        assertEquals(Boolean.TRUE, m.readValue("{\"image\":\"r/a:1\",\"tls\":true}", DeployRequest.class).tls());
    }
}
