package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

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

    private static final String D = "sha256:" + "a".repeat(64);

    @Test
    void imagePinsRoundTripAndOldMessagesHaveNone() {
        JsonMapper json = JsonMapper.builder().build();
        var st = new Msg.State(List.of(), List.of(), Map.of(), Map.of(), Map.of(), null, Map.of(), Map.of("shop", new Msg.ImagePin("shop:1.0", D)));
        Msg back = json.readValue(json.writeValueAsString((Msg) st), Msg.class);
        assertEquals(D, ((Msg.State) back).pins().get("shop").digest());
        Msg old = json.readValue("{\"t\":\"state\",\"running\":[],\"ready\":[]}", Msg.class);
        assertTrue(((Msg.State) old).pins().isEmpty());
        assertTrue(new Msg.State(List.of(), List.of()).pins().isEmpty());

        var d = new Msg.Desired(List.of(), List.of(), null, Map.of(), Map.of(), null, List.of(), null, List.of(), null, Map.of(), Map.of("shop", new Msg.ImagePin("shop:1.0", D)));
        assertEquals("shop:1.0", ((Msg.Desired) json.readValue(json.writeValueAsString((Msg) d), Msg.class)).pins().get("shop").image());
        assertTrue(new Msg.Desired(List.of(), List.of()).pins().isEmpty());
        assertTrue(new Msg.Desired(List.of(), List.of(), null, Map.of(), Map.of(), null, List.of(), null, List.of(), null, Map.of()).pins().isEmpty());
    }
}
