package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Process;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessUsageTest {
    @TempDir
    Path tmp;

    private Path proc() throws IOException {
        return Files.createDirectories(tmp.resolve("proc"));
    }

    private Path cg() throws IOException {
        return Files.createDirectories(tmp.resolve("cg"));
    }

    private ProcessUsage usage(final long start) throws IOException {
        return new ProcessUsage(proc(), cg(), 100, () -> 100_000, start, () -> 4096);
    }

    /** Un {@code /proc/<pid>/stat} réaliste : comm avec espace et parenthèse, utime 1000, stime 234, 7 fils. */
    private void stat(final int utime, final int stime) throws IOException {
        Files.writeString(proc().resolve("stat"),
                "42 (my (app) x) S 1 42 42 0 -1 4194560 100 0 0 0 " + utime + " " + stime + " 0 0 20 0 7 0 123456 1000000 500 18446744073709551615 0 0\n");
    }

    @Test
    void processusDepuisProc() throws IOException {
        stat(1000, 234);
        Files.writeString(proc().resolve("status"), "Name:\tapp\nVmPeak:\t  9000 kB\nVmRSS:\t   1234 kB\nThreads:\t7\n");
        final Process p = usage(40_000).process();
        assertEquals(12.34, p.cpuSecondsTotal(), 1e-9);
        assertEquals(1234L * 1024, p.memoryBytes());
        assertEquals("rss", p.memoryKind());
        assertEquals(7, p.threads());
        assertEquals(60L, p.uptimeSeconds());
        assertEquals(4096L, p.heapBytes());
    }

    @Test
    void cpuCumuleEtRemiseAZero() throws IOException {
        stat(100, 0);
        final ProcessUsage u = usage(0);
        assertEquals(1.0, u.process().cpuSecondsTotal());
        stat(250, 50);
        assertEquals(3.0, u.process().cpuSecondsTotal());
        stat(5, 0); // processus relancé : le compteur diminue, l'appelant y voit une remise à zéro
        assertEquals(0.05, u.process().cpuSecondsTotal(), 1e-9);
    }

    @Test
    void procAbsentOuIllisibleNeLevePasEtNInventeRien() throws IOException {
        final Process p = new ProcessUsage(tmp.resolve("nope"), tmp.resolve("nope2"), 100, () -> 0, 0, () -> {
            throw new IllegalStateException();
        }).process();
        assertNull(p.cpuSecondsTotal());
        assertNull(p.memoryBytes());
        assertNull(p.threads());
        assertNull(p.heapBytes());
        assertNull(p.uptimeSeconds());
        assertEquals("rss", p.memoryKind());
        Files.writeString(proc().resolve("stat"), "garbage");
        Files.writeString(proc().resolve("status"), "VmRSS: abc kB\n");
        final Process q = usage(0).process();
        assertNull(q.cpuSecondsTotal());
        assertNull(q.memoryBytes());
    }

    @Test
    void cgroupV2DuProcessus() throws IOException {
        Files.writeString(cg().resolve("cgroup.controllers"), "cpu memory\n");
        Files.writeString(proc().resolve("cgroup"), "0::/system.slice/gluonify-app-x-1.service\n");
        final Path dir = Files.createDirectories(cg().resolve("system.slice/gluonify-app-x-1.service"));
        Files.writeString(dir.resolve("memory.current"), "830000000\n");
        Files.writeString(dir.resolve("memory.max"), "1073741824\n");
        Files.writeString(dir.resolve("cpu.stat"), "usage_usec 1240100000\nuser_usec 1\nsystem_usec 2\n");
        final Cgroup c = usage(0).cgroup();
        assertEquals(1240.1, c.cpuSecondsTotal(), 1e-9);
        assertEquals(830000000L, c.memoryBytes());
        assertEquals(1073741824L, c.memoryLimitBytes());
    }

    @Test
    void cgroupDeLaRacineDUnConteneurEtLimiteMax() throws IOException {
        Files.writeString(cg().resolve("cgroup.controllers"), "cpu memory\n");
        Files.writeString(proc().resolve("cgroup"), "0::/\n");
        Files.writeString(cg().resolve("memory.current"), "100\n");
        Files.writeString(cg().resolve("memory.max"), "max\n");
        final Cgroup c = usage(0).cgroup();
        assertEquals(100L, c.memoryBytes());
        assertNull(c.memoryLimitBytes());
        assertNull(c.cpuSecondsTotal());
    }

    @Test
    void cgroupV1OuAbsentDonneNull() throws IOException {
        assertNull(usage(0).cgroup()); // pas de cgroup.controllers : pas de v2 unifié
        Files.writeString(cg().resolve("cgroup.controllers"), "cpu\n");
        assertNull(usage(0).cgroup()); // v2 mais ni memory.current ni cpu.stat lisibles
    }

    @Test
    void cheminRelatifHorsRacineIgnore() throws IOException {
        Files.writeString(cg().resolve("cgroup.controllers"), "cpu memory\n");
        Files.writeString(proc().resolve("cgroup"), "0::/../../etc\n");
        assertNull(usage(0).cgroup());
    }

    @Test
    void processusReel() {
        final Process p = new ProcessUsage().process();
        // Sous Linux les valeurs existent ; ailleurs (macOS) les champs lisibles seuls : jamais d'exception.
        if (Files.isReadable(Path.of("/proc/self/stat"))) {
            assertEquals(true, p.cpuSecondsTotal() >= 0);
            assertEquals(true, p.memoryBytes() > 0);
        }
    }
}
