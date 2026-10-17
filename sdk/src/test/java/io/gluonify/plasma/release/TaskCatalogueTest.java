package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskCatalogueTest {
    static final String B = TaskCatalogue.BOOTSTRAP, A = TaskCatalogue.AGENT;

    static Map<String, String> higgs() {
        Map<String, String> p = new HashMap<>(Map.of("version", "1.2.3", "zone", "apps.example.org", "publicIp", "203.0.113.10,2001:db8::1", "httpUrl", "https://cp2.example.org:8081",
                "nodeId", "cp2", "advertise", "cp2.example.org", "seed", "cp1.example.org:8081", "seedToken", "0123456789abcdef"));
        return p;
    }

    @Test void validTasks() {
        assertEquals(Map.of(), TaskCatalogue.validate("report-status", Map.of(), B));
        TaskCatalogue.validate("install-strange", Map.of("version", "1.2.3", "role", "worker", "runtime", "process", "address", "10.0.0.5", "wireguard", "true"), B);
        TaskCatalogue.validate("install-higgs", higgs(), B);
        TaskCatalogue.validate("update", Map.of("component", "strange", "version", "1.3.0"), A);
        TaskCatalogue.validate("rollback", Map.of("component", "higgs"), A);
        TaskCatalogue.validate("uninstall", Map.of("component", "all", "purge", "false"), A);
    }

    @Test void unknownOrAbsentTypeRefused() {
        for (String t : new String[]{"exec", "shell", "install-strange ", "INSTALL-STRANGE", "", null, "../report-status", "report-status;id"})
            assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate(t, Map.of(), B), String.valueOf(t));
    }

    @Test void outOfModeTaskRefused() {
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-higgs", higgs(), A));
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("update", Map.of("component", "strange", "version", "1.3.0"), B));
    }

    @Test void invalidParametersRefused() {
        var base = Map.of("version", "1.2.3", "role", "worker");
        for (String[] bad : new String[][]{{"version", "1.2"}, {"version", "1.2.3; rm -rf /"}, {"version", "$(id)"}, {"role", "root"}, {"role", "worker --token x"}, {"runtime", "docker"},
                {"address", "-x"}, {"address", "a b"}, {"address", "a;b"}, {"address", "`id`"}, {"address", "../x"}, {"wireguard", "yes"}, {"version", "1.2.3\n"}}) {
            var p = new HashMap<>(base);
            p.put(bad[0], bad[1]);
            assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-strange", p, B), bad[0] + "=" + bad[1]);
        }
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-strange", Map.of("version", "1.2.3"), B)); // role obligatoire
        var extra = new HashMap<>(base);
        extra.put("command", "id");
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-strange", extra, B)); // unknown parameter
        var long1 = new HashMap<>(base);
        long1.put("address", "a".repeat(600));
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-strange", long1, B));
        var nul = new HashMap<String, String>(base);
        nul.put("address", null);
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-strange", nul, B));
    }

    @Test void pathOrUrlParametersRefused() {
        for (String v : List.of("/etc/passwd", "../../x", "file:///etc/x", "http://evil/x.sh", "a/b"))
            assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("update", Map.of("component", v, "version", "1.0.0"), A), v);
        var h = higgs();
        h.put("httpUrl", "https://cp2.example.org/../x");
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-higgs", h, B));
    }

    @Test void maskedSecrets() {
        var m = TaskCatalogue.masked("install-higgs", higgs());
        assertEquals("***", m.get("seedToken"));
        assertEquals("cp2", m.get("nodeId"));
    }
}
