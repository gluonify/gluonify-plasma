package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.Instance;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class MsgUsageTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void usageAndAckRoundTrip() {
        String body = "{\"records\":[{\"v\":1}]}";
        String w = json.writeValueAsString((Msg) new Msg.Usage(7, body));
        assertTrue(w.contains("\"t\":\"usage\""), w);
        Msg back = json.readValue(w, Msg.class);
        assertEquals(body, ((Msg.Usage) back).body());
        assertEquals(7, ((Msg.Usage) back).id());
        Msg ack = json.readValue("{\"t\":\"usage-ack\",\"id\":7,\"status\":202}", Msg.class);
        assertEquals(202, ((Msg.UsageAck) ack).status());
    }

    @Test
    void featuresAreAdditive() {
        Msg old = json.readValue("{\"t\":\"welcome\",\"node\":\"n1\"}", Msg.class);
        assertTrue(((Msg.Welcome) old).features().isEmpty());
        Msg w = json.readValue(json.writeValueAsString((Msg) new Msg.Welcome("n1", java.util.List.of(Msg.FEATURE_USAGE))), Msg.class);
        assertEquals(java.util.List.of("usage"), ((Msg.Welcome) w).features());
        assertTrue(new Msg.Enrolled("n1", "s").features().isEmpty());
    }

    @Test
    void instanceOrganizationIsAdditive() {
        Instance old = json.readValue("{\"app\":\"shop\",\"index\":0,\"image\":\"i\",\"port\":8080,\"hostPort\":20000,\"memoryMb\":64,\"generation\":0,\"scratchMb\":64,\"nas\":false}", Instance.class);
        assertNull(old.organization());
        assertNull(old.uuid());
        Instance i = new Instance("shop", 0, "i", 8080, 20000, 64, null, 0, 64, false, null, null, null, null, null, null, null, null, "acme", "u-1");
        Instance back = json.readValue(json.writeValueAsString(i), Instance.class);
        assertEquals("acme", back.organization());
        assertEquals("u-1", back.uuid());
        assertNull(new Instance("shop", 0, "i", 8080, 20000, 64, null, 0, 64, false, null, null, null, null, null, null, null, null).organization());
    }
}
