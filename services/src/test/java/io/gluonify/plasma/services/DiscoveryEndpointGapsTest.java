package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The discovery document's optional parts and the endpoint's helpers. */
class DiscoveryEndpointGapsTest {

    @Test
    void theOpenIdConfigurationIsTheDocumentsOwnOrDerivedFromTheIssuer() {
        final Discovery own = Discovery.parse("{\"issuer\":\"https://a.lan/realms/x\",\"openidConfiguration\":\"https://a.lan/custom/oidc\"}");
        assertEquals(URI.create("https://a.lan/custom/oidc"), own.openidConfigurationUrl().orElseThrow());
        final Discovery derived = Discovery.parse("{\"issuer\":\"https://a.lan/realms/x///\"}");
        assertEquals(URI.create("https://a.lan/realms/x/.well-known/openid-configuration"), derived.openidConfigurationUrl().orElseThrow());
        assertTrue(Discovery.parse("{}").openidConfigurationUrl().isEmpty());
    }

    @Test
    void anInsecureOrMalformedOpenIdConfigurationIsIgnored() {
        assertNull(Discovery.parse("{\"openidConfiguration\":\"http://cluster.example/oidc\"}").openidConfiguration());
        assertNull(Discovery.parse("{\"openidConfiguration\":\"not a uri\"}").openidConfiguration());
        assertNull(Discovery.parse("{\"openidConfiguration\":\"/relative\"}").openidConfiguration());
        assertNull(Discovery.parse("{\"openidConfiguration\":\"https:///nohost\"}").openidConfiguration());
        assertEquals(URI.create("http://localhost:1/oidc"), Discovery.parse("{\"openidConfiguration\":\"http://localhost:1/oidc\"}").openidConfiguration());
    }

    @Test
    void servicesNeedAnObjectWithASecureAddress() {
        final Discovery d = Discovery.parse("{\"services\":{\"Good\":{\"url\":\"https://a.lan/g\",\"audience\":\"gluonify-good\"},\"plain\":{\"url\":\"http://cluster.example/p\"},"
                + "\"nourl\":{},\"string\":\"x\",\"local\":{\"url\":\"http://127.0.0.1:9/l\"}}}");
        assertEquals(java.util.List.of("good", "local"), java.util.List.copyOf(d.services().keySet()));
        assertEquals("gluonify-good", d.service("good").orElseThrow().audience());
        assertNull(d.service("local").orElseThrow().audience());
        assertTrue(d.service("plain").isEmpty());
        assertEquals(Discovery.DEFAULT_CLIENT_ID, d.clientId());
    }

    @Test
    void theCliClientIdIsReadWhenGiven() {
        assertEquals("my-client", Discovery.parse("{\"cli\":{\"clientId\":\"my-client\"}}").clientId());
        assertEquals(Discovery.DEFAULT_CLIENT_ID, Discovery.parse("{\"cli\":\"x\"}").clientId());
        assertEquals(URI.create("https://z.lan/.well-known/gluonify"), Discovery.defaultUrl("z.lan"));
        assertThrows(IllegalArgumentException.class, () -> Discovery.parse("[]"));
    }

    @Test
    void anEndpointJoinsPathsWithOneSlash() {
        final Endpoint e = new Endpoint("crystallon", URI.create("https://a.lan/crystallon///"), () -> "  tok \n", Endpoint.Source.DEVELOPER, "gluonify-crystallon", "a.lan");
        assertEquals(URI.create("https://a.lan/crystallon/v1/x"), e.resolve("/v1/x"));
        assertEquals(URI.create("https://a.lan/crystallon/v1/x"), e.resolve("v1/x"));
        assertEquals("tok", e.token().orElseThrow());
    }

    @Test
    void aBlankOrNullTokenIsNoToken() {
        for (final String t : new String[]{null, "", "   "}) {
            final Endpoint e = new Endpoint("s", URI.create("https://a.lan"), () -> t, Endpoint.Source.OVERRIDE, null, null);
            assertTrue(e.token().isEmpty(), String.valueOf(t));
        }
    }

    @Test
    void anEndpointPrintsNeverItsToken() {
        final Endpoint full = new Endpoint("s", URI.create("https://a.lan"), () -> "secret-token", Endpoint.Source.DEVELOPER, "aud", "a.lan");
        assertEquals("Endpoint[service=s, url=https://a.lan, source=DEVELOPER, audience=aud, zone=a.lan]", full.toString());
        final Endpoint bare = new Endpoint("s", URI.create("https://a.lan"), () -> "secret-token", Endpoint.Source.PLATFORM, null, null);
        assertEquals("Endpoint[service=s, url=https://a.lan, source=PLATFORM]", bare.toString());
    }

    @Test
    void aMissingPartOfAnEndpointIsRefused() {
        final java.util.function.Supplier<String> none = () -> null;
        final URI a = URI.create("https://a");
        assertThrows(NullPointerException.class, () -> new Endpoint(null, a, none, Endpoint.Source.OVERRIDE, null, null));
        assertThrows(NullPointerException.class, () -> new Endpoint("s", null, none, Endpoint.Source.OVERRIDE, null, null));
        assertThrows(NullPointerException.class, () -> new Endpoint("s", a, null, Endpoint.Source.OVERRIDE, null, null));
        assertThrows(NullPointerException.class, () -> new Endpoint("s", a, none, null, null, null));
        assertEquals(Map.of(), Discovery.parse("{}").services());
    }
}
