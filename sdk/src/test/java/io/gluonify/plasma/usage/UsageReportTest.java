package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Process;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class UsageReportTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final Process PROCESS = new Process(100.0, 1000L, "rss", 400L, 8, 3600L);

    @Test
    void sansPartAttributionNone() {
        final UsageReport r = UsageReport.of("rup", "0.1", "i-1", "n1", 1L, PROCESS, null, UsageReport.ESTIMATED, List.of());
        assertEquals(UsageReport.NONE, r.attribution());
        assertTrue(r.byApp().isEmpty());
        assertNull(r.unattributedShare());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void partsEtPartNonAttribueFontLeTotal() {
        final UsageReport r = UsageReport.of("gdown", "0.7", "i-1", "n1", 1L, PROCESS, new Cgroup(101.0, 1100L, null), UsageReport.ESTIMATED,
                List.of(new AppShare("a", "u1", 10, 100, "estimated", "db app1"), new AppShare("b", "u2", 30, 300, "estimated")));
        assertEquals("b", r.byApp().get(0).app()); // les plus grosses en mémoire d'abord
        assertEquals(60.0, r.unattributedShare().cpuSeconds(), 1e-9);
        assertEquals(600L, r.unattributedShare().memoryBytes());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void partsEstimeesAuDessusDuTotalSontRamenees() {
        final UsageReport r = UsageReport.of("gdown", "0.7", null, null, 1L, PROCESS, null, UsageReport.ESTIMATED,
                List.of(new AppShare("a", "u1", 90, 900, "estimated"), new AppShare("b", "u2", 90, 900, "estimated")));
        double cpu = r.unattributedShare().cpuSeconds();
        long memory = r.unattributedShare().memoryBytes();
        for (final AppShare s : r.byApp()) {
            cpu += s.cpuSeconds();
            memory += s.memoryBytes();
        }
        assertEquals(100.0, cpu, 1e-6);
        assertTrue(memory <= 1000 && memory >= 998);
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void auDelaDeCinqCentsLignesLeResteVaDansOther() {
        final List<AppShare> many = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            many.add(new AppShare("app" + i, "u" + i, 0.01, i, "estimated"));
        }
        final UsageReport r = UsageReport.of("gdown", "0.7", null, null, 1L, PROCESS, null, UsageReport.ESTIMATED, many);
        assertEquals(UsageReport.MAX_APPS + 1, r.byApp().size());
        assertEquals(UsageReport.OTHER, r.byApp().get(UsageReport.MAX_APPS).app());
        assertEquals("app699", r.byApp().get(0).app());
        assertEquals(200 * 0.01, r.byApp().get(UsageReport.MAX_APPS).cpuSeconds(), 1e-9);
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void jacksonAllerRetourEtChampsAbsentsNonEcrits() {
        final UsageReport r = UsageReport.of("gdown", "0.7", "i-1", "n1", 5L, new Process(1.5, 10L, "rss", null, null, null), null,
                UsageReport.MEASURED, List.of(new AppShare("a", "u1", 1, 5, "measured", "db app1")));
        final String json = JSON.writeValueAsString(r);
        assertFalse(json.contains("heapBytes"));
        assertFalse(json.contains("cgroup"));
        assertEquals(r, JSON.readValue(json, UsageReport.class));
        assertTrue(json.contains("\"contract\":\"gluonify-usage/1\""));
    }
}
