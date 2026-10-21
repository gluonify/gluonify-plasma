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
        assertEquals(3660, new SleepSpec(true, 3600, null, null).stopAfterSeconds(), "default stop after the freeze");
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
    void wakePolicyIsOptionalAndValidated() {
        assertNull(new SleepSpec(true, 3, 8, null).wake(), "absent: cluster values");
        var w = new SleepSpec.Wake(2, new SleepSpec.AdditionalWhen(10, 2, 300, 50, 2, 5));
        assertEquals(2, new SleepSpec(true, 3, 8, null, w).wake().initialReplicas());
        assertNull(new SleepSpec.AdditionalWhen(null, null, null, null, null, null).stepReplicas(), "field absent: cluster default");
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.Wake(0, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(-1, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, 60001, null, null, null));
    }

    @Test
    void disabledByDefaultInAppSpec() {
        assertFalse(new AppSpec("a", "img", null, null, null, null).sleepEnabled());
        assertFalse(new DeployRequest("img", null, null, null, null, null, null).toSpec("a").sleepEnabled());
    }

    @Test
    void exclusionCriteria() {
        var on = new SleepSpec(true, 3, 8, null);
        // an internal application, with peer ports, a role or locks between nodes cannot sleep
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, true, null, null, null, null, null, null, on));
        final var input2 = List.of(9100);
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, input2, null, null, null, null, null, on));
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, null, "storage", null, null, null, null, on));
        final var input = List.of("std");
        assertThrows(IllegalArgumentException.class, () -> new AppSpec("a", "i", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, input, null, null, true, on));
        // disabled: the same combinations pass
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
