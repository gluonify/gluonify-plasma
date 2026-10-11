package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.services.internal.Json;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryTest {
    @TempDir
    Path home;

    @Test
    void lectureDuDocument() {
        final Discovery d = Discovery.parse("{\"zone\":\"gluonify.lan\",\"environment\":\"SBX\",\"issuer\":\"https://gluonify.lan/realms/gluonify\","
                + "\"services\":{\"crystallon\":{\"url\":\"https://adm.gluonify.lan/crystallon\",\"audience\":\"gluonify-crystallon\"},"
                + "\"evil\":{\"url\":\"http://attacker.example/x\"},\"bad\":\"x\",\"local\":{\"url\":\"http://127.0.0.1:8080\"}},\"extra\":[1,2.5,true,null]}");
        assertEquals("gluonify.lan", d.zone());
        assertEquals("SBX", d.environment());
        assertEquals("gluonify-cli", d.clientId());
        assertEquals(List.of("crystallon", "local"), List.copyOf(d.services().keySet()), "adresse en clair hors boucle locale ignorée");
        assertEquals(URI.create("https://gluonify.lan/realms/gluonify/.well-known/openid-configuration"), d.openidConfigurationUrl().orElseThrow());
        assertEquals("gluonify-crystallon", d.service("crystallon").orElseThrow().audience());
        assertNull(d.service("local").orElseThrow().audience());
    }

    @Test
    void json() {
        final Map<String, Object> m = Json.object("{\"a\":\"\\u00e9\\n\\\"\",\"b\":[1,-2,3.5e1],\"c\":{\"d\":false}}");
        assertEquals("é\n\"", m.get("a"));
        assertEquals(List.of(1L, -2L, 35.0), m.get("b"));
        assertEquals(m, Json.object(Json.write(m)));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"a\":1,}"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("{\"a\":1} x"));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Json.object("{\"secret-token\":"));
        assertFalse(e.getMessage().contains("secret"), "le texte lu n'apparaît jamais dans le message");
        assertThrows(IllegalArgumentException.class, () -> Json.parse("[".repeat(100)));
    }

    @Test
    void configuration() {
        assertTrue(DeveloperConfig.read(home).isEmpty());
        DeveloperConfig c = DeveloperConfig.empty().with(new DeveloperConfig.Zone("a.lan", Discovery.defaultUrl("a.lan"), "SBX", null))
                .with(new DeveloperConfig.Zone("b.lan", URI.create("https://b.lan/.well-known/gluonify"), null, Path.of("/ca.pem")));
        c.write(home);
        c = DeveloperConfig.read(home);
        assertEquals("b.lan", c.current().orElseThrow().name());
        assertEquals(Path.of("/ca.pem"), c.zone("b.lan").orElseThrow().caFile());
        assertEquals("SBX", c.zone("a.lan").orElseThrow().environment());
        c = c.without("b.lan");
        assertEquals("a.lan", c.current().orElseThrow().name());
        assertTrue(c.without("a.lan").isEmpty());
        assertFalse(DeveloperConfig.validZone("https://x"));
        assertFalse(DeveloperConfig.validZone("../x"));
        assertFalse(DeveloperConfig.validZone("x..y"));
        assertTrue(DeveloperConfig.validZone("gluonify.lan"));
        assertThrows(IllegalArgumentException.class, () -> DeveloperTokens.file(home, "../../etc/passwd"));
    }
}
