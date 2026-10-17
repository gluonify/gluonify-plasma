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
    void withoutShareAttributionNone() {
        final UsageReport r = UsageReport.of("rup", "0.1", "i-1", "n1", 1L, PROCESS, null, UsageReport.ESTIMATED, List.of());
        assertEquals(UsageReport.NONE, r.attribution());
        assertTrue(r.byApp().isEmpty());
        assertNull(r.unattributedShare());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void sharesAndUnattributedShareMakeTheTotal() {
        final UsageReport r = UsageReport.of("gdown", "0.7", "i-1", "n1", 1L, PROCESS, new Cgroup(101.0, 1100L, null), UsageReport.ESTIMATED,
                List.of(new AppShare("a", "u1", 10, 100, "estimated", "db app1"), new AppShare("b", "u2", 30, 300, "estimated")));
        assertEquals("b", r.byApp().get(0).app()); // the largest in memory first
        assertEquals(60.0, r.unattributedShare().cpuSeconds(), 1e-9);
        assertEquals(600L, r.unattributedShare().memoryBytes());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void estimatedSharesAboveTheTotalAreScaledDown() {
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
    void beyondFiveHundredLinesTheRestGoesToOther() {
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

    /** Storage (additive): kept without rows, folded into {@code _other}, untouched by the CPU/memory scaling, total raised to the rows' sum. */
    @Test
    void storageIsCarriedFoldedAndNeverBelowTheRows() {
        final UsageReport none = UsageReport.of("rup", "0.1", null, null, 1L, PROCESS, null, UsageReport.PRORATA, List.of(), 5000L);
        assertEquals(UsageReport.NONE, none.attribution());
        assertEquals(5000L, none.storageBytes());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(none)).isEmpty());

        final List<AppShare> many = new ArrayList<>();
        for (int i = 0; i < 502; i++) {
            many.add(new AppShare("app" + i, null, 0.5, 10L + i, "prorata", null, i < 2 ? 7L : null));
        }
        final UsageReport r = UsageReport.of("rup", "0.1", null, null, 1L, PROCESS, null, UsageReport.PRORATA, many, 100L);
        final AppShare other = r.byApp().get(UsageReport.MAX_APPS);
        assertEquals(UsageReport.OTHER, other.app());
        assertEquals(14L, other.storageBytes(), "the two smallest rows carried 7 bytes each");
        assertEquals(100L, r.storageBytes());
        assertTrue(r.byApp().stream().mapToDouble(AppShare::cpuSeconds).sum() <= 100.0 + 1e-9, "CPU scaled to the process");
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());

        final UsageReport raised = UsageReport.of("rup", "0.1", null, null, 1L, PROCESS, null, UsageReport.PRORATA,
                List.of(new AppShare("a", null, 1, 1, "prorata", null, 70L), new AppShare("b", null, 1, 1, "prorata", null, 50L)), 100L);
        assertEquals(120L, raised.storageBytes(), "a total read from an older snapshot is raised to the rows' sum");
        final UsageReport derived = UsageReport.of("rup", "0.1", null, null, 1L, PROCESS, null, UsageReport.PRORATA,
                List.of(new AppShare("a", null, 1, 1, "prorata", null, 70L)), null);
        assertEquals(70L, derived.storageBytes());
        assertNull(UsageReport.of("gdown", "0.7", null, null, 1L, PROCESS, null, UsageReport.ESTIMATED, List.of(new AppShare("a", null, 1, 1, "estimated")))
                .storageBytes());
    }

    /** A contributor's own {@code _other} row joins the fold: never two {@code _other} lines. */
    @Test
    void aContributorOtherRowJoinsTheFold() {
        final List<AppShare> many = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            many.add(new AppShare("app" + i, null, 0.01, 10L + i, "prorata"));
        }
        many.add(new AppShare(UsageReport.OTHER, null, 0.01, 100_000L, "prorata"));
        final UsageReport r = UsageReport.of("rup", "0.1", null, null, 1L, PROCESS, null, UsageReport.PRORATA, many);
        assertEquals(1, r.byApp().stream().filter(s -> UsageReport.OTHER.equals(s.app())).count());
        assertEquals(UsageReport.MAX_APPS + 1, r.byApp().size());
        assertTrue(UsageContract.check(JSON.writeValueAsBytes(r)).isEmpty());
    }

    @Test
    void jacksonRoundTripWithStorageAndOlderReportsWithout() {
        final UsageReport r = UsageReport.of("rup", "0.1", null, null, 5L, new Process(1.5, 10L, "rss", null, null, null), null, UsageReport.PRORATA,
                List.of(new AppShare("a", null, 1, 5, "prorata", null, 42L)), 100L);
        final String json = JSON.writeValueAsString(r);
        assertTrue(json.contains("\"storageBytes\":42") && json.contains("\"storageBytes\":100"), json);
        assertEquals(r, JSON.readValue(json, UsageReport.class));
        final String old = JSON.writeValueAsString(UsageReport.of("gdown", "0.7", null, null, 5L, PROCESS, null, UsageReport.ESTIMATED,
                List.of(new AppShare("a", "u", 1, 5, "estimated", "db app1"))));
        assertFalse(old.contains("storageBytes"), "absent, never 0 or null");
        final UsageReport back = JSON.readValue(old, UsageReport.class);
        assertNull(back.storageBytes());
        assertNull(back.byApp().get(0).storageBytes());
    }

    @Test
    void jacksonRoundTripAndAbsentFieldsNotWritten() {
        final UsageReport r = UsageReport.of("gdown", "0.7", "i-1", "n1", 5L, new Process(1.5, 10L, "rss", null, null, null), null,
                UsageReport.MEASURED, List.of(new AppShare("a", "u1", 1, 5, "measured", "db app1")));
        final String json = JSON.writeValueAsString(r);
        assertFalse(json.contains("heapBytes"));
        assertFalse(json.contains("cgroup"));
        assertEquals(r, JSON.readValue(json, UsageReport.class));
        assertTrue(json.contains("\"contract\":\"gluonify-usage/1\""));
    }
}
