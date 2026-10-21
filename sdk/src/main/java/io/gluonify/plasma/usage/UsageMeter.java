package io.gluonify.plasma.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Usage counters of an emitter, per one-minute window aligned on the clock ({@code metering.md} § 4.2) and per key {@code (env, org, app, appUuid, kind, unit, dims)}.
 * {@link #add} never blocks (one concurrent table per window); {@link #drain} returns the closed windows as records, numbered by an increasing {@code seq}.
 * A unit outside THIS service's catalog, or a non-enumerated dimension, is refused ({@link #invalid()} counts it, never an exception).
 * Beyond {@code maxKeysPerWindow} keys in a window, the following ones are merged into {@code app = _overflow} of the same organization (§ 4.4): never dropped.
 */
public final class UsageMeter {
    public static final String OVERFLOW = "_overflow";

    private final String service;
    private final String replica;
    private final String node;
    private final String version;
    private final String env;
    private final int maxKeysPerWindow;
    private final LongSupplier clock;
    private final SeqSource seqs;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Cell>> windows = new ConcurrentHashMap<>();
    private final AtomicLong invalid = new AtomicLong();
    private final AtomicLong overflowed = new AtomicLong();

    /** Source of sequence numbers (persisted by {@link UsageSpool}, in memory otherwise). */
    public interface SeqSource {
        long next();

        long epoch();
    }

    /** In-memory sequence, restarting from 1 at each startup (epoch = creation date). */
    public static SeqSource memorySeq(final long epoch) {
        AtomicLong n = new AtomicLong();
        return new SeqSource() {
            @Override
            public long next() { return n.incrementAndGet(); }

            @Override
            public long epoch() { return epoch; }
        };
    }

    private static final class Cell {
        final UsageRecord.Subject subject;
        final String unit;
        final TreeMap<String, String> dims;
        final boolean internal;
        final String onBehalfOf;
        BigDecimal sum = BigDecimal.ZERO;

        Cell(final UsageRecord.Subject subject, final String unit, final TreeMap<String, String> dims, final boolean internal, final String onBehalfOf) {
            this.subject = subject;
            this.unit = unit;
            this.dims = dims;
            this.internal = internal;
            this.onBehalfOf = onBehalfOf;
        }

        synchronized void add(final BigDecimal q) { sum = sum.add(q); }
    }

    public UsageMeter(String service, String replica, String node, String version, String env, int maxKeysPerWindow, LongSupplier clock, SeqSource seqs) {
        this.service = service;
        this.replica = replica;
        this.node = node;
        this.version = version;
        this.env = env == null || env.isBlank() ? "PRD" : env;
        this.maxKeysPerWindow = Math.max(1, maxKeysPerWindow);
        this.clock = clock;
        this.seqs = seqs;
    }

    public String service() { return service; }

    private static final BigDecimal GB_HOUR_BYTE_SECONDS = BigDecimal.valueOf(1L << 30).multiply(BigDecimal.valueOf(3600));
    private static final BigDecimal MB = BigDecimal.valueOf(1L << 20);

    /** GB x h of a volume of {@code bytes} kept for {@code seconds} s (GB = 2^30 bytes), 9 decimal places, rounded down. */
    public static BigDecimal gbHours(final long bytes, final long seconds) {
        return BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(seconds)).divide(GB_HOUR_BYTE_SECONDS, 9, java.math.RoundingMode.DOWN);
    }

    /** MB x s of {@code bytes} kept for {@code seconds} s (MB = 2^20 bytes), 9 decimal places, rounded down. */
    public static BigDecimal mbSeconds(final long bytes, final double seconds) {
        return BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(seconds)).divide(MB, 9, java.math.RoundingMode.DOWN);
    }

    /** Refused units (not in the service's catalog, unknown dimension, negative quantity). */
    public long invalid() { return invalid.get(); }

    /** Keys merged into {@code _overflow}. */
    public long overflowed() { return overflowed.get(); }

    /** Adds {@code quantity} to the current window. */
    public boolean add(final UsageRecord.Subject subject, final String unit, final Map<String, String> dims, final BigDecimal quantity) {
        return add(clock.getAsLong(), subject, unit, dims, quantity, false, null);
    }

    public boolean add(final UsageRecord.Subject subject, final String unit, final Map<String, String> dims, final long quantity) {
        return add(subject, unit, dims, BigDecimal.valueOf(quantity));
    }

    /**
     * Adds {@code quantity} to the window that contains {@code at} (ms). {@code internal}/{@code onBehalfOf}: usage by a platform service (§ 3.5).
     * False if the unit is refused.
     */
    public boolean add(long at, UsageRecord.Subject subject, String unit, Map<String, String> dims, BigDecimal quantity, boolean internal, String onBehalfOf) {
        UsageUnit u = UsageCatalog.get(unit);
        if (u == null || !u.service().equals(service) || quantity == null || quantity.signum() < 0 || subject == null || !dimsAllowed(u, dims)) {
            invalid.incrementAndGet();
            return false;
        }
        if (quantity.signum() == 0) return true;
        long window = at - Math.floorMod(at, UsageRecord.WINDOW_MS);
        ConcurrentHashMap<String, Cell> cells = windows.computeIfAbsent(window, w -> new ConcurrentHashMap<>());
        boolean inner = internal || u.internal();
        TreeMap<String, String> d = new TreeMap<>(dims == null ? Map.of() : dims);
        String k = UsageRecord.key(env, subject, unit, d) + (inner ? "|internal" : "") + (onBehalfOf == null ? "" : "|for=" + onBehalfOf);
        Cell c = cells.get(k);
        if (c == null) {
            if (cells.size() >= maxKeysPerWindow) {
                overflowed.incrementAndGet();
                UsageRecord.Subject o = new UsageRecord.Subject(subject.org(), OVERFLOW, null, subject.kind(), null);
                k = UsageRecord.key(env, o, unit, d) + (inner ? "|internal" : "");
                final String fk = k;
                c = cells.computeIfAbsent(fk, x -> new Cell(o, unit, d, inner, null));
            } else {
                final UsageRecord.Subject s = subject;
                c = cells.computeIfAbsent(k, x -> new Cell(s, unit, d, inner, onBehalfOf));
            }
        }
        c.add(quantity);
        return true;
    }

    private static boolean dimsAllowed(final UsageUnit u, final Map<String, String> dims) {
        if (dims == null || dims.isEmpty()) return true;
        if (dims.size() > 3) return false;
        for (var e : dims.entrySet()) {
            List<String> allowed = u.dims().get(e.getKey());
            if (allowed == null || !allowed.contains(e.getValue())) return false;
        }
        return true;
    }

    /** The records of one closed window, in key order; cells with a zero sum are skipped. */
    private void records(final long w, final ConcurrentHashMap<String, Cell> cells, final UsageRecord.Emitter em, final List<UsageRecord> out) {
        List<String> keys = new ArrayList<>(cells.keySet());
        keys.sort(String::compareTo);
        for (String k : keys) {
            Cell c = cells.get(k);
            BigDecimal sum;
            synchronized (c) {
                sum = c.sum;
            }
            if (sum.signum() == 0) continue;
            UsageUnit u = UsageCatalog.get(c.unit);
            String key = UsageRecord.key(env, c.subject, c.unit, c.dims) + (c.internal ? "|internal" : "") + (c.onBehalfOf == null ? "" : "|for=" + c.onBehalfOf);
            out.add(new UsageRecord(UsageRecord.id(service, replica, w, key), seqs.next(), em, env, w, c.subject, c.unit, c.dims, sum, c.internal, c.onBehalfOf,
                    u.billable() ? "billable" : "indicative"));
        }
    }

    /** Records of the closed windows (end <= now), removed from the counters. */
    public List<UsageRecord> drain() {
        return drain(clock.getAsLong());
    }

    /** All the windows closed at {@code now}; with {@code now = Long.MAX_VALUE}, the current window too (service shutdown). */
    public synchronized List<UsageRecord> drain(final long now) {
        List<UsageRecord> out = new ArrayList<>();
        List<Long> closed = new ArrayList<>();
        for (Long w : windows.keySet()) if (now == Long.MAX_VALUE || w + UsageRecord.WINDOW_MS <= now) closed.add(w);
        closed.sort(Long::compare);
        UsageRecord.Emitter em = new UsageRecord.Emitter(service, replica, node, version, seqs.epoch());
        for (Long w : closed) {
            ConcurrentHashMap<String, Cell> cells = windows.remove(w);
            if (cells != null) records(w, cells, em, out);
        }
        return out;
    }
}
