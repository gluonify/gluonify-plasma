package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import java.util.List;
import org.junit.jupiter.api.Test;

/** A contributor that splits the process measured by the resource ({@code byApp(process)}): it receives the totals just read, not a second read of {@code /proc}. */
class UsageContributorTest {
    @Test
    void contributorReceivesTheMeasuredProcess() {
        UsageContributor half = new UsageContributor() {
            @Override
            public List<AppShare> byApp() {
                throw new AssertionError("the resource calls byApp(process)");
            }

            @Override
            public List<AppShare> byApp(final UsageReport.Process p) {
                return List.of(new AppShare("shop", "u1", p.cpuSecondsTotal() / 2, p.memoryBytes() / 2, UsageReport.ESTIMATED, "bytes"));
            }
        };
        UsageReport r = UsageResourceTest.resource(half).report();
        assertEquals(12.0, r.process().cpuSecondsTotal(), 1e-9);
        assertEquals(6.0, r.byApp().get(0).cpuSeconds(), 1e-9);
        assertEquals((8L << 20) / 2, r.byApp().get(0).memoryBytes());
        assertEquals(6.0, r.unattributedShare().cpuSeconds(), 1e-9);
        assertEquals(UsageReport.ESTIMATED, r.attribution());
        assertEquals(List.of(), UsageContract.check(UsageResourceTest.JSON.readTree(UsageResourceTest.JSON.writeValueAsString(r))));
    }
}
