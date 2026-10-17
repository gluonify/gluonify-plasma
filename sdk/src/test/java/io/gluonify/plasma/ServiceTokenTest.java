package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.ws.Msg;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ServiceTokenTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void namingConventions() {
        assertEquals("GLUONIFY_CRYSTALLON_TOKEN_FILE", ServiceToken.envName("crystallon"));
        assertEquals("GLUONIFY_MY_SVC_TOKEN_FILE", ServiceToken.envName("my-svc"));
        assertEquals("crystallon.token", ServiceToken.fileName("crystallon"));
        assertEquals("/run/gluonify-tokens", ServiceToken.DIR);
        assertTrue(ServiceToken.isReservedEnv("GLUONIFY_CRYSTALLON_TOKEN_FILE"));
        assertTrue(ServiceToken.isReservedEnv(ServiceToken.envName("fermion")));
        for (String libre : new String[] {"GLUONIFY__TOKEN_FILE", "MY_TOKEN_FILE", "GLUONIFY_CRYSTALLON_TOKEN", "APP_X", null}) assertFalse(ServiceToken.isReservedEnv(libre), String.valueOf(libre));
    }

    @Test
    void theTokenNeverAppearsInToString() {
        String s = new ServiceToken("crystallon", "gluonify-crystallon", "eyJ.secret.sig", 42L).toString();
        assertFalse(s.contains("eyJ"), s);
        assertTrue(s.contains("crystallon") && s.contains("42"), s);
    }

    @Test
    void messagesRoundTrip() {
        Msg req = json.readValue(json.writeValueAsString(new Msg.ServiceTokensRequest(7, List.of("shop"))), Msg.class);
        assertEquals(new Msg.ServiceTokensRequest(7, List.of("shop")), req);
        assertTrue(json.writeValueAsString(req).contains("\"t\":\"service-tokens-request\""));
        var tok = new ServiceToken("crystallon", "gluonify-crystallon", "jwt", 1700000900000L);
        Msg res = json.readValue(json.writeValueAsString(new Msg.ServiceTokens(7, 200, Map.of("shop", List.of(tok)), null)), Msg.class);
        assertEquals(new Msg.ServiceTokens(7, 200, Map.of("shop", List.of(tok)), null), res);
        assertTrue(new Msg.ServiceTokens(8, 501, null, "charm not configured").tokens().isEmpty(), "null tokens becomes an empty map");
    }

    @Test
    void policyFingerprintOptional() {
        var tok = new ServiceToken("crystallon", "gluonify-crystallon", "jwt", 1700000900000L);
        var avec = new Msg.ServiceTokens(7, 200, Map.of("shop", List.of(tok)), null, "a1b2c3d4e5f60718");
        assertEquals(avec, json.readValue(json.writeValueAsString((Msg) avec), Msg.class));
        // older control-plane: field absent from the JSON, read as null
        Msg ancien = json.readValue("{\"t\":\"service-tokens\",\"id\":7,\"status\":200,\"tokens\":{},\"error\":null}", Msg.class);
        assertEquals(null, ((Msg.ServiceTokens) ancien).policy());
        var d = new Msg.Desired(List.of(), List.of(), null, Map.of(), Map.of(), null, List.of(), null, List.of(), null, Map.of(), Map.of(), "a1b2c3d4e5f60718");
        assertEquals("a1b2c3d4e5f60718", ((Msg.Desired) json.readValue(json.writeValueAsString((Msg) d), Msg.class)).tokenPolicy());
        assertEquals(null, new Msg.Desired(List.of(), List.of()).tokenPolicy());
    }
}
