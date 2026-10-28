package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The component's usage endpoint and the /proc and cgroup readers behind it: every file that may be absent, damaged or unusual. */
class UsageResourceProcessGapsTest {
    @TempDir
    Path dir;

    static final UsageReport.Identity WHO = new UsageReport.Identity("gdown", "0.8.0", "i-1", "n1");

    static SecurityContext caller(final String principal, final Set<String> roles) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return principal == null ? null : () -> principal;
            }

            @Override
            public boolean isUserInRole(final String role) {
                return roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        };
    }

    static UsageContributor contributor(final List<AppShare> rows, final String attribution, final Long storage) {
        return new UsageContributor() {
            @Override
            public Collection<AppShare> byApp() {
                return rows;
            }

            @Override
            public String attribution() {
                return attribution;
            }

            @Override
            public Long storageBytes() {
                return storage;
            }
        };
    }

    static Config config(final Map<String, String> values) {
        return (Config) java.lang.reflect.Proxy.newProxyInstance(UsageResourceProcessGapsTest.class.getClassLoader(), new Class<?>[]{Config.class}, (p, m, a) -> {
            if (m.getName().equals("getOptionalValue")) {
                return java.util.Optional.ofNullable(values.get((String) a[0]));
            }
            throw new UnsupportedOperationException(m.getName());
        });
    }

    UsageResource resource(final List<UsageContributor> contributors) {
        return new UsageResource(new ProcessUsage(new ProcessUsage.Roots(dir.resolve("none"), dir.resolve("none")), 100, () -> 10_000, 0, () -> 5), () -> 42L, () -> contributors, WHO);
    }

    // --- the endpoint ---

    @Test
    void noIdentityIs401AndTheWrongRoleIs403AndEitherRoleOpensIt() {
        final UsageResource r = resource(List.of());
        assertEquals(401, r.usage(null).getStatus());
        assertEquals(401, r.usage(caller(null, Set.of())).getStatus());
        assertEquals("no-store", r.usage(caller(null, Set.of())).getHeaderString("Cache-Control"));
        assertEquals(403, r.usage(caller("svc", Set.of("other"))).getStatus());
        assertEquals(200, r.usage(caller("svc", Set.of(UsageResource.ROLE))).getStatus());
        assertEquals(200, r.usage(caller("svc", Set.of("gluonify:gdown:usage:read"))).getStatus());
        assertEquals(403, r.usage(caller("svc", Set.of("gluonify:higgs:usage:read"))).getStatus());
    }

    @Test
    void theIdentityComesFromTheConfigurationWithItsDefaults() {
        UsageReport.Identity id = UsageResource.identity(config(Map.of()));
        assertEquals("unknown", id.component());
        assertEquals("unknown", id.version());
        assertNull(id.instance());
        assertNull(id.node());
        id = UsageResource.identity(config(Map.of("quarkus.application.name", "gluonify-gdown", "quarkus.application.version", "0.8.0", "gluonify.usage.instance", "i-9", "gluonify.usage.node", "n-2")));
        assertEquals("gdown", id.component(), "the brand prefix is dropped");
        assertEquals("0.8.0", id.version());
        assertEquals("i-9", id.instance());
        assertEquals("n-2", id.node());
        assertEquals("other", UsageResource.identity(config(Map.of("quarkus.application.name", "gluonify-gdown", "gluonify.usage.component", "other"))).component());
        assertEquals("plain", UsageResource.identity(config(Map.of("quarkus.application.name", "plain"))).component());
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedConstructorBuildsTheSameResource() {
        final ProcessUsage usage = new ProcessUsage(new ProcessUsage.Roots(dir, dir), 100, () -> 1, 0, () -> 1);
        final UsageResource r = new UsageResource(usage, () -> 7L, List::of, new UsageReport.Identity("gdown", "1", null, null));
        assertEquals("gdown", r.report().component());
        assertEquals(7L, r.report().at());
    }

    @Test
    void theWeakestConfidenceOfTheContributorsWins() {
        final List<AppShare> one = List.of(new AppShare("a", "u1", 0.001, 10, "measured"));
        final List<AppShare> two = List.of(new AppShare("b", "u2", 0.001, 10, "measured"));
        assertEquals("measured", resource(List.of(contributor(one, "measured", null))).report().attribution());
        assertEquals("estimated", resource(List.of(contributor(one, "measured", null), contributor(two, "estimated", null))).report().attribution());
        assertEquals("prorata", resource(List.of(contributor(one, "measured", null), contributor(two, "prorata", null))).report().attribution());
        assertEquals("estimated", resource(List.of(contributor(one, "prorata", null), contributor(two, "estimated", null))).report().attribution());
        assertEquals("estimated", resource(List.of(contributor(one, "estimated", null), contributor(two, "prorata", null))).report().attribution(), "the weakest wins whatever the order");
        assertEquals("estimated", resource(List.of(contributor(one, null, null))).report().attribution(), "a contributor that does not say is an estimate");
        assertEquals("estimated", resource(List.of(contributor(one, null, null), contributor(two, "measured", null))).report().attribution());
    }

    @Test
    void aContributorThatSaysNothingIsAnEstimateWhateverTheOrder() {
        final List<AppShare> one = List.of(new AppShare("a", "u1", 0.001, 10, "measured"));
        final List<AppShare> two = List.of(new AppShare("b", "u2", 0.001, 10, "measured"));
        assertEquals("estimated", resource(List.of(contributor(one, "measured", null), contributor(two, null, null))).report().attribution());
    }

    @Test
    void storageAddsUpAcrossContributorsEvenWithoutRows() {
        final UsageReport r = resource(List.of(contributor(List.of(), null, 100L), contributor(null, null, 50L), contributor(List.of(), null, null))).report();
        assertEquals(UsageReport.NONE, r.attribution());
        assertEquals(150L, r.storageBytes());
        assertNull(resource(List.of(contributor(List.of(), null, null))).report().storageBytes());
    }

    @Test
    void aFailingContributorLeavesTheProcessOnly() {
        final UsageContributor failing = () -> {
            throw new IllegalStateException("boom");
        };
        final UsageReport r = resource(List.of(contributor(List.of(new AppShare("a", "u", 1, 1, "measured")), "measured", 5L), failing)).report();
        assertEquals(UsageReport.NONE, r.attribution());
        assertEquals(List.of(), r.byApp());
        assertNull(r.storageBytes());
        final Response ok = resource(List.of()).usage(caller("svc", Set.of(UsageResource.ROLE)));
        assertEquals(200, ok.getStatus());
    }

    // --- /proc and cgroup ---

    ProcessUsage usage(final Path proc, final Path cgroup, final long start) {
        return new ProcessUsage(new ProcessUsage.Roots(proc, cgroup), 100, () -> 100_000L, start, () -> 1234L);
    }

    @Test
    void anEmptyOrMissingProcGivesOnlyTheHeapAndTheUptime() {
        final UsageReport.Process p = usage(dir.resolve("missing"), dir.resolve("missing"), 40_000L).process();
        assertNull(p.cpuSecondsTotal());
        assertNull(p.memoryBytes());
        assertNull(p.threads());
        assertEquals(1234L, p.heapBytes());
        assertEquals(60L, p.uptimeSeconds());
        assertEquals("rss", p.memoryKind());
        assertNull(usage(dir.resolve("missing"), dir.resolve("missing"), 0).process().uptimeSeconds(), "an unknown start has no lifetime");
        assertEquals(0L, usage(dir.resolve("missing"), dir.resolve("missing"), 200_000L).process().uptimeSeconds(), "a start in the future is no negative lifetime");
    }

    @Test
    void theHeapIsAbsentWhenTheRuntimeDoesNotKnowItOrFails() {
        final Path none = dir.resolve("missing");
        assertNull(new ProcessUsage(new ProcessUsage.Roots(none, none), 100, () -> 1, 0, () -> -1).process().heapBytes());
        assertNull(new ProcessUsage(new ProcessUsage.Roots(none, none), 100, () -> 1, 0, () -> {
            throw new IllegalStateException("no heap");
        }).process().heapBytes());
        assertNull(new ProcessUsage(new ProcessUsage.Roots(none, none), 100, () -> 1, 0, null).process().heapBytes());
        assertEquals(0L, new ProcessUsage(new ProcessUsage.Roots(none, none), 100, () -> 1, 0, () -> 0).process().heapBytes());
    }

    @Test
    void statAndStatusAreReadAfterTheLastParenthesisAndIgnoreWhatIsDamaged() throws Exception {
        final Path proc = Files.createDirectory(dir.resolve("proc"));
        Files.writeString(proc.resolve("stat"), "123 (we ird) name) S 1 2 3 4 5 6 7 8 9 10 700 300 0 0 20 0 9 0 100 0 0\n");
        Files.writeString(proc.resolve("status"), "Name:\tx\nVmRSS:\t   2048 kB\nThreads:\t9\n");
        final UsageReport.Process p = usage(proc, dir.resolve("none"), 0).process();
        assertEquals(10.0, p.cpuSecondsTotal(), 1e-9, "(700 + 300) ticks at 100 per second");
        assertEquals(2048L * 1024, p.memoryBytes());
        assertEquals(9, p.threads());

        Files.writeString(proc.resolve("stat"), "no closing parenthesis here");
        Files.writeString(proc.resolve("status"), "Name:\tx\nVmRSS:\tabc kB\n");
        final UsageReport.Process damaged = usage(proc, dir.resolve("none"), 0).process();
        assertNull(damaged.cpuSecondsTotal());
        assertNull(damaged.memoryBytes());
        assertNull(damaged.threads());

        Files.writeString(proc.resolve("stat"), "1 (x) S 1 2 3 4 5 6 7 8 9 10 -5 300\n");
        Files.writeString(proc.resolve("status"), "Name:\tx\nVmRSS:\n");
        final UsageReport.Process negative = usage(proc, dir.resolve("none"), 0).process();
        assertNull(negative.cpuSecondsTotal(), "a negative tick count is no count");
        assertNull(negative.memoryBytes());

        Files.writeString(proc.resolve("stat"), "1 (x) S 1 2 3 4 5 6 7 8 9 10 700\n");
        assertNull(usage(proc, dir.resolve("none"), 0).process().cpuSecondsTotal(), "stime missing");
        Files.writeString(proc.resolve("status"), "Name:\tx\n");
        assertNull(usage(proc, dir.resolve("none"), 0).process().memoryBytes(), "no VmRSS line");
    }

    Path cgroupRoot(final boolean controllers) throws Exception {
        final Path root = Files.createDirectories(dir.resolve("cg"));
        if (controllers) {
            Files.writeString(root.resolve("cgroup.controllers"), "cpu memory\n");
        }
        return root;
    }

    @Test
    void withoutUnifiedCgroupsThereIsNoCgroup() throws Exception {
        assertNull(usage(dir.resolve("proc"), cgroupRoot(false), 0).cgroup());
        assertNull(usage(dir.resolve("proc"), dir.resolve("absent"), 0).cgroup());
    }

    @Test
    void theOwnCgroupDirectoryIsFoundThroughTheProcFile() throws Exception {
        final Path root = cgroupRoot(true);
        final Path own = Files.createDirectories(root.resolve("system.slice/app.service"));
        Files.writeString(own.resolve("memory.current"), "4096\n");
        Files.writeString(own.resolve("cpu.stat"), "usage_usec 2500000\nuser_usec 1\n");
        Files.writeString(own.resolve("memory.max"), "max\n");
        final Path proc = Files.createDirectories(dir.resolve("proc"));
        Files.writeString(proc.resolve("cgroup"), "12:memory:/legacy\n0::/system.slice/app.service\n");
        final UsageReport.Cgroup c = usage(proc, root, 0).cgroup();
        assertEquals(4096L, c.memoryBytes());
        assertEquals(2.5, c.cpuSecondsTotal(), 1e-9);
        assertNull(c.memoryLimitBytes(), "max is no limit");
        Files.writeString(own.resolve("memory.max"), "1073741824\n");
        assertEquals(1_073_741_824L, usage(proc, root, 0).cgroup().memoryLimitBytes());
    }

    @Test
    void aContainerWithoutItsOwnDirectoryReadsTheRootAndAnUnsafePathIsNeverFollowed() throws Exception {
        final Path root = cgroupRoot(true);
        Files.writeString(root.resolve("memory.current"), "100\n");
        final Path proc = Files.createDirectories(dir.resolve("proc"));
        for (final String line : new String[]{"0::/", "0::", "0::/../../etc", "0::/not/there", "1:name=x:/y"}) {
            Files.writeString(proc.resolve("cgroup"), line + "\n");
            final UsageReport.Cgroup c = usage(proc, root, 0).cgroup();
            assertEquals(100L, c.memoryBytes(), line);
            assertNull(c.cpuSecondsTotal(), line);
        }
        Files.delete(proc.resolve("cgroup"));
        assertEquals(100L, usage(proc, root, 0).cgroup().memoryBytes(), "no cgroup file: the root");
        Files.writeString(proc.resolve("cgroup"), "0::relative/path\n");
        assertEquals(100L, usage(proc, root, 0).cgroup().memoryBytes(), "a path without a slash is resolved under the root");
    }

    @Test
    void aCgroupWithNothingReadableIsNoCgroup() throws Exception {
        final Path root = cgroupRoot(true);
        assertNull(usage(dir.resolve("proc"), root, 0).cgroup(), "no memory.current, no cpu.stat");
        Files.writeString(root.resolve("memory.current"), "garbage\n");
        Files.writeString(root.resolve("cpu.stat"), "nr_periods 1\nusage_usec notanumber\n");
        assertNull(usage(dir.resolve("proc"), root, 0).cgroup());
        Files.writeString(root.resolve("cpu.stat"), "nr_periods 1\n");
        assertNull(usage(dir.resolve("proc"), root, 0).cgroup());
        Files.writeString(root.resolve("memory.current"), "-5\n");
        Files.writeString(root.resolve("cpu.stat"), "usage_usec 1000000\n");
        final UsageReport.Cgroup onlyCpu = usage(dir.resolve("proc"), root, 0).cgroup();
        assertNull(onlyCpu.memoryBytes());
        assertEquals(1.0, onlyCpu.cpuSecondsTotal(), 1e-9);
    }

    @Test
    void plausibleStartsAreKeptAndOthersFallBack() {
        final long now = 1_800_000_000_000L;
        assertEquals(now - 1000, ProcessUsage.plausibleStart(now - 1000, now, 7));
        assertEquals(7, ProcessUsage.plausibleStart(0, now, 7));
        assertEquals(7, ProcessUsage.plausibleStart(-5, now, 7));
        assertEquals(7, ProcessUsage.plausibleStart(now + 1, now, 7), "in the future");
        assertEquals(7, ProcessUsage.plausibleStart(now - ProcessUsage.MAX_PLAUSIBLE_AGE_MS, now, 7), "ten years or older: an instant near the epoch");
        assertEquals(now - ProcessUsage.MAX_PLAUSIBLE_AGE_MS + 1, ProcessUsage.plausibleStart(now - ProcessUsage.MAX_PLAUSIBLE_AGE_MS + 1, now, 7));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedConstructorAndTheRealProcessWork() {
        final ProcessUsage d = new ProcessUsage(new ProcessUsage.Roots(dir, dir), 0, () -> 1, 0, () -> 1);
        assertNull(d.process().cpuSecondsTotal());
        final UsageReport.Process real = new ProcessUsage().process();
        assertNotNull(real.heapBytes());
        assertNotNull(real.uptimeSeconds());
        final AtomicLong unused = new AtomicLong();
        assertEquals(0, unused.get());
    }

    @Test
    void theQuantityKindsOfTheCatalogueAcceptTheirBillingUnitsOnly() {
        assertTrue(Quantity.SECONDS.accepts("minute"));
        assertFalse(Quantity.SECONDS.accepts("thousand"));
        assertFalse(Quantity.SECONDS.accepts(null));
        assertNull(UsageCatalog.get(null));
        assertNotNull(UsageCatalog.get("strange.instance.cpu"));
        assertFalse(UsageCatalog.all().isEmpty());
        assertEquals(new ArrayList<>(UsageCatalog.all()).size(), UsageCatalog.all().size());
    }
}
