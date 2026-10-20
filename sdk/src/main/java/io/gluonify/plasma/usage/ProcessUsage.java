package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.Cgroup;
import io.gluonify.plasma.usage.UsageReport.Process;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.LongSupplier;

/**
 * Measurement of the current process ({@code usage-mcp.md} § 3.2): cumulative CPU ({@code utime + stime} of {@code /proc/self/stat}), resident memory
 * ({@code VmRSS}), threads, lifetime, and cgroup v2 when it exists. Pure class, no dependency: paths can be injected for tests.
 * Never throws: an unreadable or missing value (system without {@code /proc}, cgroup v1) gives an absent field, never an invented value.
 */
public final class ProcessUsage {
    private static final String MEMORY_CURRENT = "memory.current";
    private static final String CPU_STAT = "cpu.stat";
    private static final String USAGE_USEC = "usage_usec ";
    /** In {@code /proc/self/stat}, after the closing parenthesis of the name: utime = field 14, stime = field 15, num_threads = field 20. */
    private static final int UTIME = 11;
    private static final int STIME = 12;
    private static final int NUM_THREADS = 17;

    private final Path proc;
    private final Path cgroupRoot;
    private final long clockTicksPerSecond;
    private final LongSupplier nowMs;
    private final long startMs;
    private final LongSupplier heapBytes;

    /** Ten years: an older start is not a real process start (seen in native images: an instant near the epoch, uptime of 1.79e9 s). */
    static final long MAX_PLAUSIBLE_AGE_MS = 10L * 365 * 24 * 3600 * 1000;

    /** {@code candidate} when it is a plausible process start (in the past, less than ten years ago), else {@code fallback}. */
    static long plausibleStart(final long candidate, final long nowMs, final long fallback) {
        return candidate > 0 && candidate <= nowMs && nowMs - candidate < MAX_PLAUSIBLE_AGE_MS ? candidate : fallback;
    }

    /**
     * The current process: {@code /proc/self}, {@code /sys/fs/cgroup}, 100 ticks per second (value of all common Linux kernels). When the start of the
     * process is unknown or implausible, the creation of this object stands for it (the SDK creates it at startup); the clock is read here, at run
     * time, never in a static initializer that a native image would run at build time.
     */
    public ProcessUsage() {
        this(System.currentTimeMillis());
    }

    private ProcessUsage(final long createdMs) {
        this(Path.of("/proc/self"), Path.of("/sys/fs/cgroup"), 100, System::currentTimeMillis,
                plausibleStart(ProcessHandle.current().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L), createdMs, createdMs),
                () -> {
                    final Runtime rt = Runtime.getRuntime();
                    return rt.totalMemory() - rt.freeMemory();
                });
    }

    /** @param proc process directory ({@code /proc/self}); @param cgroupRoot cgroup v2 root ({@code /sys/fs/cgroup}); @param startMs process start (0: unknown lifetime). */
    public ProcessUsage(final Path proc, final Path cgroupRoot, final long clockTicksPerSecond, final LongSupplier nowMs, final long startMs,
            final LongSupplier heapBytes) {
        this.proc = proc;
        this.cgroupRoot = cgroupRoot;
        this.clockTicksPerSecond = Math.max(1, clockTicksPerSecond);
        this.nowMs = nowMs;
        this.startMs = startMs;
        this.heapBytes = heapBytes;
    }

    /** The process: always an object, its unreadable fields are {@code null}. */
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

    /** The fields of {@code stat} after "pid (comm)": comm may contain spaces and parentheses, so we restart from the LAST closing parenthesis. */
    private static String[] statFields(final String stat) {
        final int close = stat == null ? -1 : stat.lastIndexOf(')');
        return close < 0 ? new String[0] : stat.substring(close + 1).trim().split("\\s+");
    }

    /** {@code VmRSS} of {@code status}, in bytes. */
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

    /** The heap if the runtime knows it, otherwise absent (never an exception). */
    private Long heap() {
        try {
            final long h = heapBytes == null ? -1 : heapBytes.getAsLong();
            return h >= 0 ? h : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The process's cgroup v2 ({@code memory.current}, {@code cpu.stat usage_usec}, {@code memory.max}), or {@code null} if it does not exist (v1, absent). */
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

    /** {@code usage_usec} of {@code cpu.stat}, in seconds. */
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

    /** The process's cgroup v2 directory: {@code 0::/path} of {@code <proc>/cgroup} under the root, otherwise the root itself (container). */
    private Path cgroupDir() {
        if (!Files.isRegularFile(cgroupRoot.resolve("cgroup.controllers"))) {
            return null; // no unified v2
        }
        final Path own = ownCgroup(read(proc.resolve("cgroup")));
        if (own != null) {
            return own;
        }
        return hasCgroupFiles(cgroupRoot) ? cgroupRoot : null;
    }

    /** The directory designated by the {@code 0::/path} line (never the root, never {@code ..}), if it has the expected files. */
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
            return null; // "max" included
        }
    }

    /** {@code VmRSS:   1234 kB} to bytes. */
    private static Long kib(final String line) {
        final String[] p = line.substring(line.indexOf(':') + 1).trim().split("\\s+");
        final Long v = p.length > 0 ? longOf(p[0]) : null;
        return v == null ? null : v * 1024;
    }
}
