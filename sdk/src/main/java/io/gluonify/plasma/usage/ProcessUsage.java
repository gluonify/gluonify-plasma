package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Process;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Mesure du processus courant ({@code usage-mcp.md} § 3.2) : CPU cumulé ({@code utime + stime} de {@code /proc/self/stat}), mémoire résidente
 * ({@code VmRSS}), fils, durée de vie, et cgroup v2 quand il existe. Classe pure, sans dépendance : les chemins sont injectables pour les tests.
 * Ne lève jamais : une valeur illisible ou absente (système sans {@code /proc}, cgroup v1) donne un champ absent, jamais une valeur inventée.
 */
public final class ProcessUsage {
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
        Double cpu = null;
        Integer threads = null;
        final String stat = read(proc.resolve("stat"));
        if (stat != null) {
            // « pid (comm) état ppid … » : comm peut contenir espaces et parenthèses, on repart de la DERNIÈRE parenthèse fermante.
            final int close = stat.lastIndexOf(')');
            if (close >= 0) {
                final String[] f = stat.substring(close + 1).trim().split("\\s+");
                // f[0] = état (champ 3) ; utime = champ 14 → f[11], stime = champ 15 → f[12], num_threads = champ 20 → f[17].
                final Long utime = number(f, 11), stime = number(f, 12), nt = number(f, 17);
                if (utime != null && stime != null) {
                    cpu = (utime + stime) / (double) clockTicksPerSecond;
                }
                if (nt != null) {
                    threads = (int) Math.min(Integer.MAX_VALUE, nt);
                }
            }
        }
        Long rss = null;
        final String status = read(proc.resolve("status"));
        if (status != null) {
            for (final String line : status.split("\n")) {
                if (line.startsWith("VmRSS:")) {
                    rss = kib(line);
                    break;
                }
            }
        }
        Long heap = null;
        try {
            final long h = heapBytes == null ? -1 : heapBytes.getAsLong();
            if (h >= 0) {
                heap = h;
            }
        } catch (RuntimeException e) {
            // pas de tas connu : champ absent
        }
        final Long uptime = startMs > 0 ? Math.max(0, (nowMs.getAsLong() - startMs) / 1000) : null;
        return new Process(cpu, rss, "rss", heap, threads, uptime);
    }

    /** Le cgroup v2 du processus ({@code memory.current}, {@code cpu.stat usage_usec}, {@code memory.max}), ou {@code null} s'il n'existe pas (v1, absent). */
    public Cgroup cgroup() {
        final Path dir = cgroupDir();
        if (dir == null) {
            return null;
        }
        final Long memory = longOf(read(dir.resolve("memory.current")));
        Double cpu = null;
        final String cpuStat = read(dir.resolve("cpu.stat"));
        if (cpuStat != null) {
            for (final String line : cpuStat.split("\n")) {
                if (line.startsWith("usage_usec ")) {
                    final Long us = longOf(line.substring("usage_usec ".length()));
                    cpu = us == null ? null : us / 1_000_000d;
                    break;
                }
            }
        }
        if (memory == null && cpu == null) {
            return null;
        }
        return new Cgroup(cpu, memory, longOf(read(dir.resolve("memory.max"))));
    }

    /** Le dossier cgroup v2 du processus : {@code 0::/chemin} de {@code <proc>/cgroup} sous la racine, sinon la racine elle-même (conteneur). */
    private Path cgroupDir() {
        if (!Files.isRegularFile(cgroupRoot.resolve("cgroup.controllers"))) {
            return null; // pas de v2 unifié
        }
        final String own = read(proc.resolve("cgroup"));
        if (own != null) {
            for (final String line : List.of(own.split("\n"))) {
                if (line.startsWith("0::")) {
                    final String rel = line.substring(3).trim();
                    if (!rel.isEmpty() && !rel.equals("/") && !rel.contains("..")) {
                        final Path p = cgroupRoot.resolve(rel.startsWith("/") ? rel.substring(1) : rel);
                        if (Files.isRegularFile(p.resolve("memory.current")) || Files.isRegularFile(p.resolve("cpu.stat"))) {
                            return p;
                        }
                    }
                }
            }
        }
        return Files.isRegularFile(cgroupRoot.resolve("memory.current")) || Files.isRegularFile(cgroupRoot.resolve("cpu.stat")) ? cgroupRoot : null;
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
