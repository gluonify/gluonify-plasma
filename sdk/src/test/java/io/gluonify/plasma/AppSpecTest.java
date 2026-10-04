package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class AppSpecTest {
    @Test
    void defaults() {
        var s = new AppSpec("shop", "reg/shop:1", null, null, null, null);
        assertEquals(1, s.replicas());
        assertEquals(8080, s.port());
        assertEquals(64, s.memoryMb());
    }

    @Test
    void rejectsBadNames() {
        for (String n : new String[]{"Shop", "a.b", "-x", "api", "ns1", "", null}) {
            assertThrows(IllegalArgumentException.class, () -> new AppSpec(n, "img", null, null, null, null), String.valueOf(n));
        }
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", " ", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", 101, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, 8, null));
    }

    @org.junit.jupiter.api.Test
    void executablesParArchitecture() {
        var s = new AppSpec("shop", null, 2, null, null, null, java.util.Map.of("arm64", "https://b/a#sha256=" + "a".repeat(64), "amd64", "https://b/b#sha256=" + "b".repeat(64)));
        assertEquals(java.util.List.of("amd64", "arm64"), new java.util.ArrayList<>(s.artifacts().keySet()), "ordre stable");
        assertNull(s.image());
        assertTrue(new AppSpec("a", "img", null, null, null, null).artifacts().isEmpty(), "mono-livrable : aucune map");
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", null, null, null, null, null, null), "ni image ni artifacts");
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", null, null, null, null, null, java.util.Map.of("riscv", "u")));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", null, null, null, null, null, java.util.Map.of("arm64", " ")));
        assertThrows(UnsupportedOperationException.class, () -> s.artifacts().put("amd64", "x"));
    }

    @Test
    void variablesSecretsGeneresServicesEtPin() {
        var s = new AppSpec("graphdb", "img", null, null, null, null, null, null, 0, false, Map.of("GRAPH_BOLT_ENABLED", "false"), java.util.List.of("GRAPH_AUTH_TOKEN"), java.util.List.of("id"), "n-1a2b", true);
        assertEquals("n-1a2b", s.pin());
        assertTrue(s.internal());
        assertEquals(0, s.scratchMb());
        for (String reserve : java.util.List.of("QUARKUS_HTTP_PORT", "ENV_NAME", "SERVICE_X_URL")) {
            assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, null, null, null, null, null, Map.of(reserve, "x"), null, null, null, null), reserve);
        }
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, null, null, null, null, null, Map.of("minuscule", "x"), null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, null, null, null, null, null, Map.of("X", "1"), java.util.List.of("X"), null, null, null), "même nom en clair et en secret");
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, null, null, null, null, null, null, null, java.util.List.of("Pas Un Label"), null, null));
    }

    @Test
    void portsSupplementaires() {
        var ok = new AppSpec("db", "img", null, 7474, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of(9100));
        assertEquals(java.util.List.of(9100), ok.ports());
        for (java.util.List<Integer> bad : java.util.List.of(java.util.List.of(80), java.util.List.of(20001), java.util.List.of(9100, 9100), java.util.List.of(7474), java.util.List.of(9001, 9002, 9003, 9004, 9005))) {
            assertThrows(IllegalArgumentException.class, () -> new AppSpec("db", "img", null, 7474, null, null, null, null, null, null, null, null, null, null, null, bad), bad.toString());
        }
    }

    @Test
    void uuidEtNamespacesDerives() {
        String u = "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12";
        var s = new AppSpec("shop", "img", null, null, null, u + ".app", null, u);
        assertEquals(u + ".app", s.appNamespace());
        assertEquals(u + ".gluonify", s.gluonifyNamespace());
        assertNull(new AppSpec("a", "img", null, null, null, null).appNamespace());
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, null, null, "pas-un-uuid"));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "img", null, null, null, u + ".autre", null, u));
    }

    @Test
    void deployRequestVaultTrueUtiliseLeNamespaceAppDeLUuid() {
        String u = "3f2b8c1e-9d4a-4e57-b6a1-0c7d5e8f9a12";
        var req = new DeployRequest("img", null, null, null, "ignore", null, true);
        assertEquals(u + ".app", req.toSpec("shop", u).vaultNamespace());
        assertEquals("ignore", new DeployRequest("img", null, null, null, "ignore", null, false).toSpec("shop", u).vaultNamespace());
    }

    @Test
    void systemesDeFichiersDistribues() {
        var ok = new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("std", "tmp"));
        assertEquals(java.util.List.of("std", "tmp"), ok.distributed());
        assertEquals(java.util.List.of(), new AppSpec("shop", "img", 1, 8080, 64, null).distributed(), "aucun par défaut");
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("nfs")));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("std", "std")));
        assertEquals(java.util.List.of("graph"), new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("graph")).distributed());
        var req = new DeployRequest("img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("std"));
        assertEquals(java.util.List.of("std"), req.toSpec("shop").distributed());
    }

    @Test
    void secretDeTelechargement() {
        var ok = new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "PULL_GITLAB");
        assertEquals("PULL_GITLAB", ok.pullSecret());
        assertNull(new AppSpec("shop", "img", 1, 8080, 64, null).pullSecret());
        for (String bad : new String[] {"gitlab", "PULL_", "pull_x", "PULL_A B", "TOKEN_X"}) {
            assertThrows(IllegalArgumentException.class, () -> new AppSpec("shop", "img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, bad), bad);
        }
        var req = new DeployRequest("img", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "PULL_X");
        assertEquals("PULL_X", req.toSpec("shop").pullSecret());
    }

    @org.junit.jupiter.api.Test
    void quotaDesSystemesDeFichiersDistribues() {
        var base = new DeployRequest("img:1", 1, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("std"), null, 2048);
        assertEquals(2048, base.toSpec("shop").distributedQuotaMb());
        for (int bad : new int[] {0, -1, 1048577}) {
            var r = new DeployRequest("img:1", 1, null, null, null, null, null, null, null, null, null, null, null, null, null, java.util.List.of("std"), null, bad);
            assertThrows(IllegalArgumentException.class, () -> r.toSpec("shop"), "quota " + bad);
        }
        var sans = new DeployRequest("img:1", 1, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, 100);
        assertThrows(IllegalArgumentException.class, () -> sans.toSpec("shop"), "un quota sans système de fichiers distribué");
    }
}
