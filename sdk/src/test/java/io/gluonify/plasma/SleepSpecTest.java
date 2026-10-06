package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SleepSpecTest {
    @Test
    void defaults() {
        var s = new SleepSpec(null, null, null, null);
        assertFalse(s.enabled());
        assertEquals(60, s.freezeAfterSeconds());
        assertEquals(900, s.stopAfterSeconds());
        assertEquals(5000, s.wakeTimeoutMs());
        assertEquals(3660, new SleepSpec(true, 3600, null, null).stopAfterSeconds(), "arrêt par défaut après le gel");
    }

    @Test
    void rejectsBadValues() {
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 10, 10, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 10, 5, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, null, null, 50));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, null, null, 60001));
    }

    @Test
    void disabledByDefaultInAppSpec() {
        assertFalse(new AppSpec("a", "img", null, null, null, null).sleepEnabled());
        assertFalse(new DeployRequest("img", null, null, null, null, null, null).toSpec("a").sleepEnabled());
    }

    @Test
    void exclusionCriteria() {
        var on = new SleepSpec(true, 3, 8, null);
        // une application interne, à ports de pairs, à rôle ou à verrous entre nœuds ne peut pas dormir
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, true, null, null, null, null, null, null, on));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, List.of(9100), null, null, null, null, null, on));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, null, "storage", null, null, null, null, on));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, List.of("std"), null, null, true, on));
        // désactivé : les mêmes combinaisons passent
        assertDoesNotThrow(() -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, true, null, null, null, null, null, null, SleepSpec.off()));
        assertTrue(new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, on).sleepEnabled());
    }

    @Test
    void desiredCompatibility() {
        var d = new io.gluonify.plasma.ws.Msg.Desired(List.of(), List.of());
        assertTrue(d.sleeping().isEmpty(), "ancien constructeur : personne n'est endormi");
        var d2 = new io.gluonify.plasma.ws.Msg.Desired(List.of(), List.of(), null, java.util.Map.of(), java.util.Map.of(), null, List.of(), null, List.of(), null, java.util.Map.of("a-0", "frozen"));
        assertEquals("frozen", d2.sleeping().get("a-0"));
    }
}
