package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Process;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Mesure du processus courant ({@code usage-mcp.md} § 3.2) : CPU cumulé ({@code utime + stime} de {@code /proc/self/stat}), mémoire résidente
 * ({@code VmRSS}), fils, durée de vie, et cgroup v2 quand il existe. Classe pure, sans dépendance : les chemins sont injectables pour les tests.
 * Ne lève jamais : une valeur illisible ou absente (système sans {@code /proc}, cgroup v1) donne un champ absent, jamais une valeur inventée.
 */
public final class ProcessUsage {
    private static final String MEMORY_CURRENT = "memory.current";
    private static final String CPU_STAT = "cpu.stat";
    private static final String USAGE_USEC = "usage_usec ";
    /** Dans {@code /proc/self/stat}, après la parenthèse fermante du nom : utime = champ 14, stime = champ 15, num_threads = champ 20. */
    private static final int UTIME = 11;
    private static final int STIME = 12;
    private static final int NUM_THREADS = 17;

    private final Path proc;
    private final Path cgroupRoot;
    private final long clockTicksPerSecond;
    private final LongSupplier nowMs;
    private final long startMs;
    private final LongSupplier heapBytes;

    /** Le processus courant : {@code /proc/self}, {@code /sys/fs/cgroup}, 100 ticks par seconde (valeur de tous les noyaux Linux courants). */
    public ProcessUsage() {
        this(Path.of("/proc/self"), Path.of("/sys/fs/cgroup"), 100, System::currentTimeMillis,
                ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L),
                () -> {
                    final Runtime rt = Runtime.getRuntime();
                    return rt.totalMemory() - rt.freeMemory();
                });
    }

    /** @param proc dossier du processus ({@code /proc/self}) ; @param cgroupRoot racine du cgroup v2 ({@code /sys/fs/cgroup}) ; @param startMs début du processus (0 : durée inconnue). */
    public ProcessUsage(final Path proc, final Path cgroupRoot, final long clockTicksPerSecond, final LongSupplier nowMs, final long startMs,
            final LongSupplier heapBytes) {
        this.proc = proc;
        this.cgroupRoot = cgroupRoot;
        this.clockTicksPerSecond = Math.max(1, clockTicksPerSecond);
        this.nowMs = nowMs;
        this.startMs = startMs;
        this.heapBytes = heapBytes;
    }

    /** Le processus : toujours un objet, ses champs illisibles sont {@code null}. */
    public Process process() {
        final String[] stat = statFields(read(proc.resolve("stat")));
        final Long utime = number(stat, UTIME);
        final Long stime = number(stat, STIME);
        final Double cpu = utime != null && stime != null ? (utime + stime) / (double) clockTicksPerSecond : null;
        final Long nt = number(stat, NUM_THREADS);
        final Integer threads = nt == null ? null : (int) Math.min(Integer.MAX_VALUE, nt);
        final Long uptime = startMs > 0 ? Math.max(0, (nowMs.getAsLong() - startMs) / 1000) : null;
        return new Process(cpu, rss(read(proc.resolve("status"))), "rss", heap(), threads, uptime);
    }

    /** Les champs de {@code stat} après « pid (comm) » : comm peut contenir espaces et parenthèses, on repart de la DERNIÈRE parenthèse fermante. */
    private static String[] statFields(final String stat) {
        final int close = stat == null ? -1 : stat.lastIndexOf(')');
        return close < 0 ? new String[0] : stat.substring(close + 1).trim().split("\\s+");
    }

    /** {@code VmRSS} de {@code status}, en octets. */
    private static Long rss(final String status) {
        if (status == null) {
            return null;
        }
        for (final String line : status.split("\n")) {
            if (line.startsWith("VmRSS:")) {
                return kib(line);
            }
        }
        return null;
    }

    /** Le tas si le runtime le sait, sinon absent (jamais une exception). */
    private Long heap() {
        try {
            final long h = heapBytes == null ? -1 : heapBytes.getAsLong();
            return h >= 0 ? h : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Le cgroup v2 du processus ({@code memory.current}, {@code cpu.stat usage_usec}, {@code memory.max}), ou {@code null} s'il n'existe pas (v1, absent). */
    public Cgroup cgroup() {
        final Path dir = cgroupDir();
        if (dir == null) {
            return null;
        }
        final Long memory = longOf(read(dir.resolve(MEMORY_CURRENT)));
        final Double cpu = usageSeconds(read(dir.resolve(CPU_STAT)));
        if (memory == null && cpu == null) {
            return null;
        }
        return new Cgroup(cpu, memory, longOf(read(dir.resolve("memory.max"))));
    }

    /** {@code usage_usec} de {@code cpu.stat}, en secondes. */
    private static Double usageSeconds(final String cpuStat) {
        if (cpuStat == null) {
            return null;
        }
        for (final String line : cpuStat.split("\n")) {
            if (line.startsWith(USAGE_USEC)) {
                final Long us = longOf(line.substring(USAGE_USEC.length()));
                return us == null ? null : us / 1_000_000d;
            }
        }
        return null;
    }

    /** Le dossier cgroup v2 du processus : {@code 0::/chemin} de {@code <proc>/cgroup} sous la racine, sinon la racine elle-même (conteneur). */
    private Path cgroupDir() {
        if (!Files.isRegularFile(cgroupRoot.resolve("cgroup.controllers"))) {
            return null; // pas de v2 unifié
        }
        final Path own = ownCgroup(read(proc.resolve("cgroup")));
        if (own != null) {
            return own;
        }
        return hasCgroupFiles(cgroupRoot) ? cgroupRoot : null;
    }

    /** Le dossier désigné par la ligne {@code 0::/chemin} (jamais la racine, jamais {@code ..}), s'il porte les fichiers attendus. */
    private Path ownCgroup(final String cgroupFile) {
        if (cgroupFile == null) {
            return null;
        }
        for (final String line : cgroupFile.split("\n")) {
            if (!line.startsWith("0::")) {
                continue;
            }
            final String rel = line.substring(3).trim();
            if (rel.isEmpty() || rel.equals("/") || rel.contains("..")) {
                continue;
            }
            final Path p = cgroupRoot.resolve(rel.startsWith("/") ? rel.substring(1) : rel);
            if (hasCgroupFiles(p)) {
                return p;
            }
        }
        return null;
    }

    private static boolean hasCgroupFiles(final Path dir) {
        return Files.isRegularFile(dir.resolve(MEMORY_CURRENT)) || Files.isRegularFile(dir.resolve(CPU_STAT));
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static Long number(final String[] fields, final int i) {
        return i < fields.length ? longOf(fields[i]) : null;
    }

    private static Long longOf(final String s) {
        if (s == null) {
            return null;
        }
        try {
            final long v = Long.parseLong(s.trim());
            return v < 0 ? null : v;
        } catch (NumberFormatException e) {
            return null; // « max » compris
        }
    }

    /** {@code VmRSS:   1234 kB} → octets. */
    private static Long kib(final String line) {
        final String[] p = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
        final Long v = p.length > 0 ? longOf(p[0]) : null;
        return v == null ? null : v * 1024;
    }
}
