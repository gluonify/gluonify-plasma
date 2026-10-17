package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeployRequestGraphTest {
    private static DeployRequest req(Boolean graph, Map<String, String> env, List<String> generated, List<String> uses) {
        return new DeployRequest("img", null, null, null, null, null, null, null, null, env, generated, uses, null, null, null, null, null, null, null, null, graph);
    }

    @Test void dedicatedBaseInjectsBaseAccountSecretAndService() {
        AppSpec s = req(true, Map.of("A", "1"), List.of("X"), null).toSpec("shop", "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12");
        assertEquals("app3f2b8c1e9d4a4e57", s.env().get("GLUONIFY_GDOWN_DATABASE"));
        assertEquals("app3f2b8c1e9d4a4e57", s.env().get("GLUONIFY_GDOWN_USER"));
        assertEquals("1", s.env().get("A"), "les variables de l'utilisateur restent");
        assertEquals(List.of("X", "GLUONIFY_GDOWN_PASSWORD_3F2B8C1E9D4A4E57"), s.generated());
        assertEquals("${GLUONIFY_GDOWN_PASSWORD_3F2B8C1E9D4A4E57}", s.env().get("GLUONIFY_GDOWN_PASSWORD"), "the application reads GLUONIFY_GDOWN_PASSWORD; the secret is specific to the application");
        assertTrue(s.uses().contains("gdown"));
    }

    @Test void withoutOptionNothingChanges() {
        AppSpec s = req(null, null, null, null).toSpec("shop", "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12");
        assertTrue(s.env() == null || s.env().isEmpty());
        assertTrue(s.uses() == null || s.uses().isEmpty());
    }

    @Test void idempotent() {
        String u = "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12";
        AppSpec a = req(true, null, null, List.of("gdown")).toSpec("shop", u);
        assertEquals(List.of("gdown"), a.uses(), "pas de doublon");
        assertEquals(a, req(true, null, null, List.of("gdown")).toSpec("shop", u));
        assertTrue(DeployRequest.gdownDatabaseName(u).matches("[A-Za-z][A-Za-z0-9]{2,62}"));
    }
}
