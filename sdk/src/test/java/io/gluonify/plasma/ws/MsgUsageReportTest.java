package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.usage.UsageContract;
import io.gluonify.plasma.usage.UsageReport;
import io.gluonify.plasma.usage.UsageReport.AppShare;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Node usage requested by the control-plane ({@code usage-request} / {@code usage-report}, step 3 of the usage-mcp plan): Jackson round trip, additive fields. */
class MsgUsageReportTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void requestRoundTripWithOptionalFilter() {
        String w = json.writeValueAsString((Msg) new Msg.UsageRequest(9, List.of("shop")));
        assertTrue(w.contains("\"t\":\"usage-request\""), w);
        Msg back = json.readValue(w, Msg.class);
        assertEquals(List.of("shop"), ((Msg.UsageRequest) back).apps());
        assertEquals(9, ((Msg.UsageRequest) back).id());
        // no filter: every instance of the node (an absent field is an empty list, never null)
        Msg all = json.readValue("{\"t\":\"usage-request\",\"id\":3}", Msg.class);
        assertTrue(((Msg.UsageRequest) all).apps().isEmpty());
        assertTrue(new Msg.UsageRequest(4).apps().isEmpty());
    }

    @Test
    void reportRoundTripWithAgentContractAndInstanceLines() {
        UsageReport agent = UsageReport.of("strange", "0.7.0", null, "n1", 1760700000000L,
                new UsageReport.Process(12.0, 8L << 20, "rss", null, 7, 100L), null, UsageReport.ESTIMATED,
                List.of(new AppShare("shop", "u-1", 3.0, 1 << 20, UsageReport.ESTIMATED, "vfs bytes")));
        var active = new Msg.UsageInstance("shop-0", "shop", "u-1", "active", true, 128, 42.5, 50e6, 12.0, false, 1760700000000L);
        var stopped = new Msg.UsageInstance("shop-1", "shop", "u-1", "stopped", false, 128, 40.0, 48e6, null, true, 1760699000000L);
        var report = new Msg.UsageReport(9, 200, null, "n1", 1760700000001L, agent, List.of(active, stopped));
        String w = json.writeValueAsString((Msg) report);
        assertTrue(w.contains("\"t\":\"usage-report\""), w);
        Msg.UsageReport back = (Msg.UsageReport) json.readValue(w, Msg.class);
        assertEquals(9, back.id());
        assertEquals(200, back.status());
        assertNull(back.error());
        assertEquals("n1", back.node());
        assertEquals(2, back.instances().size());
        assertEquals(active, back.instances().get(0));
        assertTrue(back.instances().get(1).lastKnown(), "a stopped instance: last known values");
        assertNull(back.instances().get(1).tasks());
        // the agent's contract travels unchanged, and is valid
        assertEquals("gluonify-usage/1", back.agent().contract());
        assertEquals("strange", back.agent().component());
        assertEquals(1, back.agent().byApp().size());
        JsonNode node = json.readTree(w).get("agent");
        assertEquals(List.of(), UsageContract.check(node));
    }

    @Test
    void reportWithoutContractOrLinesAndWithError() {
        Msg.UsageReport back = (Msg.UsageReport) json.readValue("{\"t\":\"usage-report\",\"id\":5,\"status\":500,\"error\":\"runtime unavailable\",\"node\":\"n1\",\"at\":1}", Msg.class);
        assertEquals(500, back.status());
        assertEquals("runtime unavailable", back.error());
        assertNull(back.agent(), "the agent could not read its contract: absent, never invented");
        assertTrue(back.instances().isEmpty());
        assertTrue(new Msg.UsageReport(1, 200, null, "n1", 1, null, null).instances().isEmpty());
        assertFalse(new Msg.Welcome("n1", List.of(Msg.FEATURE_USAGE)).features().isEmpty(), "the usage family is announced by the control-plane");
    }
}
