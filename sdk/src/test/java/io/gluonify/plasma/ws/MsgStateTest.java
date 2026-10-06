package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MsgStateTest {
    @Test
    void oldConstructorsHaveNoStartups() {
        assertTrue(new Msg.State(List.of(), List.of()).startups().isEmpty());
        assertTrue(new Msg.State(List.of(), List.of(), Map.of(), Map.of(), Map.of(), null).startups().isEmpty());
        assertTrue(new Msg.State(List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, null).startups().isEmpty());
    }

    @Test
    void startupKeepsHistory() {
        var s = new Msg.Startup(1L, true, 0.1, 0.2, 0.3, 0.4, null, 2, 0, null);
        assertTrue(s.history().isEmpty());
        var st = new Msg.State(List.of("a-0"), List.of("a-0"), Map.of(), Map.of(), Map.of(), null, Map.of("a-0", s));
        assertEquals(0.4, st.startups().get("a-0").total());
    }
}
