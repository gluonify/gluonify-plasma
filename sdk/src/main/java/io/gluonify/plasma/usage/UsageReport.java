package io.gluonify.plasma.usage;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Response of the usage contract {@code gluonify-usage/1} ({@code usage-mcp.md} § 3.2), served by each component's {@code GET /q/usage}.
 * Additive: a reader ignores unknown fields; {@link #CONTRACT_ID} changes only for a breaking change. Absent fields are not written
 * (never an invented value). CPU is a CUMULATIVE counter in seconds, never a percentage.
 * <p>{@code storageBytes} (optional, additive since 2026-10-17, still {@code gluonify-usage/1}): the bytes stored by the component (on its disk, measured or
 * from its last snapshot), absent when the component stores nothing. A {@code byApp} row may carry its own {@code storageBytes}; the rows' sum is at most this total
 * (the remainder is stored but attributed to nobody, e.g. platform objects without an application). Storage is a quantity of its own: it never enters the CPU or
 * memory split, and it may be reported with {@code attribution: none} (no row).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UsageReport(String contract, String component, String version, String instance, String node, long at, Process process, Cgroup cgroup,
        String attribution, List<AppShare> byApp, Share unattributedShare, Long storageBytes) {

    /** Compatibility constructor (reports without storage, before 2026-10-17). */
    public UsageReport(final String contract, final String component, final String version, final String instance, final String node, final long at,
            final Process process, final Cgroup cgroup, final String attribution, final List<AppShare> byApp, final Share unattributedShare) {
        this(contract, component, version, instance, node, at, process, cgroup, attribution, byApp, unattributedShare, null);
    }

    /** The value of the {@code contract} field: name and version of the contract. */
    public static final String CONTRACT_ID = "gluonify-usage/1";

    /** {@code attribution}: nothing to attribute. */
    public static final String NONE = "none";
    /** {@code attribution} or {@code basis}: counted at the source, without estimation. */
    public static final String MEASURED = "measured";
    /** {@code attribution} or {@code basis}: pro rata of a measured quantity (indicative). */
    public static final String ESTIMATED = "estimated";
    /** {@code attribution} or {@code basis}: pro rata of time or requests (indicative). */
    public static final String PRORATA = "prorata";

    /** Maximum number of {@code byApp} rows of real applications (the {@link #OTHER} row does not count). */
    public static final int MAX_APPS = 500;

    /** Fictitious application, a single row, that groups the rows beyond {@link #MAX_APPS}. */
    public static final String OTHER = "_other";

    /** The component's process: required. {@code memoryKind}: {@code rss}, {@code pss} or {@code cgroup}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Process(Double cpuSecondsTotal, Long memoryBytes, String memoryKind, Long heapBytes, Integer threads, Long uptimeSeconds) {}

    /** The process's cgroup v2, when it exists; {@code memoryLimitBytes} absent if the limit is {@code max}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Cgroup(Double cpuSecondsTotal, Long memoryBytes, Long memoryLimitBytes) {}

    /**
     * Share attributable to an application (identified by its UUID, the name is for display). {@code storageBytes} (optional, additive): the bytes the component
     * stores for this application, null when it stores nothing for it or does not know; never part of the CPU or memory split.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AppShare(String app, String uuid, double cpuSeconds, long memoryBytes, String basis, String unit, Long storageBytes) {
        public AppShare(final String app, final String uuid, final double cpuSeconds, final long memoryBytes, final String basis, final String unit) {
            this(app, uuid, cpuSeconds, memoryBytes, basis, unit, null);
        }

        public AppShare(final String app, final String uuid, final double cpuSeconds, final long memoryBytes, final String basis) {
            this(app, uuid, cpuSeconds, memoryBytes, basis, null, null);
        }
    }

    /** What is attributed to no application (the component's fixed share). */
    public record Share(double cpuSeconds, long memoryBytes) {}

    /**
     * Assembles a valid response: brings the shares down to {@link #MAX_APPS} rows (the largest in memory, the rest in {@link #OTHER}), scales them down
     * proportionally if their sum exceeds the process total (they are estimates), and computes the unattributed share so that
     * {@code sum of byApp + unattributedShare = process} (CPU and memory). Without a share ({@code shares} empty or {@code null}), the attribution is {@link #NONE}.
     */
    public static UsageReport of(final Identity identity, final Snapshot snapshot, final String attribution, final List<AppShare> shares) {
        return of(identity, snapshot, attribution, shares, null);
    }

    /**
     * Who reports.
     *
     * @param component component name
     * @param version   component version
     * @param instance  instance identifier, or {@code null}
     * @param node      node identifier, or {@code null}
     */
    public record Identity(String component, String version, String instance, String node) {
    }

    /**
     * What was measured, and when.
     *
     * @param at      time of the measure (epoch milliseconds)
     * @param process measures of the process
     * @param cgroup  measures of the cgroup, or {@code null}
     */
    public record Snapshot(long at, Process process, Cgroup cgroup) {
    }

    /**
     * Same as {@link #of(Identity, Snapshot, String, List)} with the identity and the snapshot as separate arguments.
     *
     * @param component   component name
     * @param version     component version
     * @param instance    instance identifier, or {@code null}
     * @param node        node identifier, or {@code null}
     * @param at          time of the measure
     * @param process     measures of the process
     * @param cgroup      measures of the cgroup, or {@code null}
     * @param attribution confidence of the attribution
     * @param shares      shares per application
     * @return the response
     * @deprecated since 0.7.2, use {@link #of(Identity, Snapshot, String, List)}; removed in the version after.
     */
    @Deprecated(since = "0.7.2", forRemoval = true)
    public static UsageReport of(final String component, final String version, final String instance, final String node, final long at, final Process process,
            final Cgroup cgroup, final String attribution, final List<AppShare> shares) {
        return of(new Identity(component, version, instance, node), new Snapshot(at, process, cgroup), attribution, shares, null);
    }

    /**
     * Same as the deprecated nine-argument form, with the storage.
     *
     * @param component    component name
     * @param version      component version
     * @param instance     instance identifier, or {@code null}
     * @param node         node identifier, or {@code null}
     * @param at           time of the measure
     * @param process      measures of the process
     * @param cgroup       measures of the cgroup, or {@code null}
     * @param attribution  confidence of the attribution
     * @param shares       shares per application
     * @param storageBytes bytes stored by the component, or {@code null}
     * @return the response
     * @deprecated since 0.7.2, use {@link #of(Identity, Snapshot, String, List, Long)}; removed in the version after.
     */
    @Deprecated(since = "0.7.2", forRemoval = true)
    public static UsageReport of(final String component, final String version, final String instance, final String node, final long at, final Process process,
            final Cgroup cgroup, final String attribution, final List<AppShare> shares, final Long storageBytes) {
        return of(new Identity(component, version, instance, node), new Snapshot(at, process, cgroup), attribution, shares, storageBytes);
    }

    /**
     * Same, with the bytes stored by the component ({@code storageBytes}, null: it stores nothing). The total is kept even without any row ({@code attribution:
     * none}). A total below the rows' sum (two snapshots read at different moments) is raised to that sum; without a total, rows that carry storage give it
     * their sum. Rows beyond {@link #MAX_APPS} add their storage to {@link #OTHER}.
     */
    public static UsageReport of(final Identity identity, final Snapshot snapshot, final String attribution, final List<AppShare> shares, final Long storageBytes) {
        final String component = identity.component();
        final String version = identity.version();
        final String instance = identity.instance();
        final String node = identity.node();
        final long at = snapshot.at();
        final Process process = snapshot.process();
        final Cgroup cgroup = snapshot.cgroup();
        if (shares == null || shares.isEmpty() || NONE.equals(attribution)) {
            return new UsageReport(CONTRACT_ID, component, version, instance, node, at, process, cgroup, NONE, List.of(), null, storageBytes);
        }
        final double totalCpu = process.cpuSecondsTotal() == null ? 0 : process.cpuSecondsTotal();
        final long totalMemory = process.memoryBytes() == null ? 0 : process.memoryBytes();
        final List<AppShare> rows = fitToTotal(fold(sorted(shares)), totalCpu, totalMemory);
        final Share rest = new Share(Math.max(0, totalCpu - sumCpu(rows)), Math.max(0, totalMemory - sumMemory(rows)));
        final Long rowsStorage = sumStorage(rows);
        final long reported = storageBytes == null ? 0 : storageBytes;
        final Long storage = rowsStorage == null ? storageBytes : Long.valueOf(Math.max(rowsStorage, reported));
        return new UsageReport(CONTRACT_ID, component, version, instance, node, at, process, cgroup, attribution == null ? ESTIMATED : attribution,
                List.copyOf(rows), rest, storage);
    }

    /** The shares, largest first (memory, then CPU, then name). */
    private static List<AppShare> sorted(final List<AppShare> shares) {
        final List<AppShare> rows = new ArrayList<>(shares);
        rows.sort(Comparator.comparingLong(AppShare::memoryBytes).reversed().thenComparing(Comparator.comparingDouble(AppShare::cpuSeconds).reversed())
                .thenComparing(AppShare::app));
        return rows;
    }

    /**
     * Beyond {@link #MAX_APPS} rows, the rest is merged into one {@link #OTHER} row (common {@code basis}, otherwise {@link #ESTIMATED}). A row named
     * {@link #OTHER} given by a contributor (its own overflow) joins that line, so there is never more than one.
     */
    private static List<AppShare> fold(final List<AppShare> all) {
        final List<AppShare> rows = new ArrayList<>(all.size());
        final List<AppShare> others = new ArrayList<>();
        for (final AppShare s : all) {
            (OTHER.equals(s.app()) ? others : rows).add(s);
        }
        if (rows.size() <= MAX_APPS && others.size() <= 1) {
            return all;
        }
        final List<AppShare> rest = new ArrayList<>(rows.size() > MAX_APPS ? rows.subList(MAX_APPS, rows.size()) : List.of());
        rest.addAll(others);
        String basis = rest.get(0).basis();
        for (final AppShare s : rest) {
            if (basis != null && !basis.equals(s.basis())) {
                basis = ESTIMATED;
            }
        }
        final List<AppShare> kept = new ArrayList<>(rows.subList(0, Math.min(MAX_APPS, rows.size())));
        kept.add(new AppShare(OTHER, null, sumCpu(rest), sumMemory(rest), basis, null, sumStorage(rest)));
        return kept;
    }

    /** Shares whose sum exceeds the process are estimates: scaled down pro rata to fit in the total. */
    private static List<AppShare> fitToTotal(final List<AppShare> rows, final double totalCpu, final long totalMemory) {
        final double sumCpu = sumCpu(rows);
        final long sumMemory = sumMemory(rows);
        final double cpuScale = sumCpu > totalCpu && sumCpu > 0 ? totalCpu / sumCpu : 1;
        final double memoryScale = sumMemory > totalMemory && sumMemory > 0 ? (double) totalMemory / sumMemory : 1;
        if (cpuScale == 1 && memoryScale == 1) {
            return rows;
        }
        final List<AppShare> out = new ArrayList<>(rows.size());
        for (final AppShare s : rows) {
            out.add(new AppShare(s.app(), s.uuid(), s.cpuSeconds() * cpuScale, (long) Math.floor(s.memoryBytes() * memoryScale), s.basis(), s.unit(),
                    s.storageBytes()));
        }
        return out;
    }

    private static double sumCpu(final List<AppShare> rows) {
        double cpu = 0;
        for (final AppShare s : rows) {
            cpu += s.cpuSeconds();
        }
        return cpu;
    }

    /** The rows' stored bytes; null when no row carries any. */
    private static Long sumStorage(final List<AppShare> rows) {
        Long storage = null;
        for (final AppShare s : rows) {
            if (s.storageBytes() != null) {
                storage = (storage == null ? 0 : storage) + s.storageBytes();
            }
        }
        return storage;
    }

    private static long sumMemory(final List<AppShare> rows) {
        long memory = 0;
        for (final AppShare s : rows) {
            memory += s.memoryBytes();
        }
        return memory;
    }
}
