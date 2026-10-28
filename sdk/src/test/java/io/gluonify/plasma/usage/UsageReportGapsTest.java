package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Identity;
import io.gluonify.plasma.usage.UsageReport.Process;
import io.gluonify.plasma.usage.UsageReport.Snapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Assembling a usage response: the cap on rows, scaling to the process total, the unattributed share, storage totals, and the deprecated entry points. */
class UsageReportGapsTest {
    static final Identity WHO = new Identity("gdown", "0.8.0", "i-1", "n1");
    static final Process PROCESS = new Process(100.0, 1_000L, "rss", null, null, null);
    static final Snapshot NOW = new Snapshot(1_760_700_000_000L, PROCESS, null);

    static UsageReport report(final String attribution, final List<AppShare> shares, final Long storage) {
        return UsageReport.of(WHO, NOW, attribution, shares, storage);
    }

    static void assertCompliant(final UsageReport r) {
        final List<String> errors = UsageContract.check(JsonMapper.builder().build().writeValueAsBytes(r));
        assertEquals(List.of(), errors);
    }

    @Test
    void withoutSharesOrWithNoneTheAttributionIsNoneAndTheStorageIsKept() {
        for (final List<AppShare> none : new List[]{null, List.of()}) {
            final UsageReport r = report("measured", none, 77L);
            assertEquals(UsageReport.NONE, r.attribution());
            assertEquals(List.of(), r.byApp());
            assertNull(r.unattributedShare());
            assertEquals(77L, r.storageBytes());
            assertEquals("gdown", r.component());
        }
        final UsageReport ignored = report(UsageReport.NONE, List.of(new AppShare("a", "u", 1, 1, "measured")), null);
        assertEquals(UsageReport.NONE, ignored.attribution());
        assertEquals(List.of(), ignored.byApp(), "none: the rows are not reported");
        assertCompliant(ignored);
    }

    @Test
    void theUnattributedShareIsWhatTheRowsLeaveOfTheProcess() {
        final UsageReport r = report("measured", List.of(new AppShare("shop", "u1", 30, 400, "measured"), new AppShare("blog", "u2", 10, 100, "estimated")), null);
        assertEquals("measured", r.attribution());
        assertEquals(60.0, r.unattributedShare().cpuSeconds(), 1e-9);
        assertEquals(500L, r.unattributedShare().memoryBytes());
        assertEquals("shop", r.byApp().get(0).app(), "largest memory first");
        assertCompliant(r);
    }

    @Test
    void aMissingAttributionIsEstimatedAndAMissingProcessTotalCountsAsZero() {
        final Identity who = new Identity("x", "1", null, null);
        final UsageReport r = UsageReport.of(who, new Snapshot(1, new Process(null, null, "rss", null, null, null), new Cgroup(1.0, 1L, null)), null,
                List.of(new AppShare("a", null, 5, 5, "estimated")), null);
        assertEquals(UsageReport.ESTIMATED, r.attribution());
        assertEquals(0.0, r.byApp().get(0).cpuSeconds(), 1e-9, "scaled down to a zero total");
        assertEquals(0L, r.byApp().get(0).memoryBytes());
        assertEquals(0.0, r.unattributedShare().cpuSeconds(), 1e-9);
        assertNull(r.instance());
    }

    @Test
    void sharesAboveTheProcessAreScaledDownProRata() {
        final UsageReport r = report("estimated", List.of(new AppShare("a", "u1", 150, 1_500, "estimated"), new AppShare("b", "u2", 50, 500, "estimated")), null);
        assertEquals(100.0, r.byApp().stream().mapToDouble(AppShare::cpuSeconds).sum(), 1e-6);
        assertTrue(r.byApp().stream().mapToLong(AppShare::memoryBytes).sum() <= 1_000L);
        assertEquals(0.0, r.unattributedShare().cpuSeconds(), 1e-6);
        assertCompliant(r);
        final UsageReport onlyMemory = report("estimated", List.of(new AppShare("a", "u1", 10, 2_000, "estimated")), null);
        assertEquals(10.0, onlyMemory.byApp().get(0).cpuSeconds(), 1e-9, "CPU fits: left alone");
        assertEquals(1_000L, onlyMemory.byApp().get(0).memoryBytes());
    }

    @Test
    void beyondTheCapTheSmallestRowsAreMergedIntoOneOtherRow() {
        final List<AppShare> shares = new ArrayList<>();
        for (int i = 0; i < UsageReport.MAX_APPS + 3; i++) {
            shares.add(new AppShare("app" + i, "u" + i, 0.01, 1, i % 2 == 0 ? "measured" : "estimated", null, 10L));
        }
        final UsageReport r = report("measured", shares, null);
        assertEquals(UsageReport.MAX_APPS + 1, r.byApp().size());
        final AppShare other = r.byApp().get(r.byApp().size() - 1);
        assertEquals(UsageReport.OTHER, other.app());
        assertEquals("estimated", other.basis(), "mixed bases are estimated");
        assertEquals(30L, other.storageBytes());
        assertEquals(10L * (UsageReport.MAX_APPS + 3), r.storageBytes());
        assertCompliant(r);
    }

    @Test
    void aCommonBasisSurvivesTheMerge() {
        final List<AppShare> shares = new ArrayList<>();
        for (int i = 0; i < UsageReport.MAX_APPS + 2; i++) {
            shares.add(new AppShare("app" + i, null, 0.001, 1, "measured"));
        }
        final AppShare other = report("measured", shares, null).byApp().get(UsageReport.MAX_APPS);
        assertEquals("measured", other.basis());
        assertNull(other.storageBytes());
    }

    @Test
    void aContributorsOwnOtherRowsJoinTheSingleOtherRow() {
        final UsageReport one = report("estimated", List.of(new AppShare("a", "u", 1, 10, "estimated"), new AppShare(UsageReport.OTHER, null, 2, 20, "estimated")), null);
        assertEquals(2, one.byApp().size(), "one _other row stays as it is");
        final UsageReport two = report("estimated", List.of(new AppShare("a", "u", 1, 10, "estimated"), new AppShare(UsageReport.OTHER, null, 2, 20, "estimated"),
                new AppShare(UsageReport.OTHER, null, 3, 30, "measured")), null);
        assertEquals(2, two.byApp().size());
        final AppShare other = two.byApp().stream().filter(s -> UsageReport.OTHER.equals(s.app())).findFirst().orElseThrow();
        assertEquals(5.0, other.cpuSeconds(), 1e-9);
        assertEquals(50L, other.memoryBytes());
        assertEquals("estimated", other.basis());
        assertCompliant(two);
    }

    @Test
    void theStorageTotalIsRaisedToTheRowsSumOrTakenFromIt() {
        final List<AppShare> rows = List.of(new AppShare("a", "u1", 1, 10, "measured", null, 40L), new AppShare("b", "u2", 1, 10, "measured", null, 60L), new AppShare("c", "u3", 1, 10, "measured"));
        assertEquals(100L, report("measured", rows, null).storageBytes(), "without a total, the rows give it");
        assertEquals(100L, report("measured", rows, 50L).storageBytes(), "a total below the rows' sum is raised");
        assertEquals(500L, report("measured", rows, 500L).storageBytes());
        assertNull(report("measured", List.of(new AppShare("a", "u1", 1, 10, "measured")), null).storageBytes());
        assertEquals(9L, report("measured", List.of(new AppShare("a", "u1", 1, 10, "measured")), 9L).storageBytes());
        assertCompliant(report("measured", rows, 50L));
    }

    @Test
    void theShortFormsOfAShareAndTheFourArgumentEntryPoint() {
        assertNull(new AppShare("a", "u", 1, 2, "measured", "db1").storageBytes());
        assertEquals("db1", new AppShare("a", "u", 1, 2, "measured", "db1").unit());
        assertNull(new AppShare("a", "u", 1, 2, "measured").unit());
        final UsageReport r = UsageReport.of(WHO, NOW, "measured", List.of(new AppShare("a", "u", 1, 2, "measured")));
        assertNull(r.storageBytes());
        assertEquals(UsageReport.CONTRACT_ID, r.contract());
        assertEquals(1_760_700_000_000L, r.at());
        assertEquals("n1", r.node());
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedLongFormsStillAssemble() {
        final List<AppShare> shares = List.of(new AppShare("a", "u", 1, 2, "measured", null, 5L));
        final UsageReport nine = UsageReport.of(new UsageReport.Identity("gdown", "0.8.0", "i-1", "n1"), new UsageReport.Snapshot(1L, PROCESS, null), "measured", shares);
        assertEquals(5L, nine.storageBytes());
        final UsageReport ten = UsageReport.of(new UsageReport.Identity("gdown", "0.8.0", "i-1", "n1"), new UsageReport.Snapshot(1L, PROCESS, null), "measured", shares, 50L);
        assertEquals(50L, ten.storageBytes());
        assertEquals("gdown", ten.component());
    }
}
