package io.gluonify.plasma.usage;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Compteurs d'usage d'un émetteur, par fenêtre d'une minute alignée sur l'horloge ({@code metering.md} § 4.2) et par clé {@code (env, org, app, appUuid, kind, unit, dims)}.
 * {@link #add} ne bloque jamais (une table concurrente par fenêtre) ; {@link #drain} rend les fenêtres closes en enregistrements, numérotés par un {@code seq} croissant.
 * Une unité hors du catalogue de CE service, ou une dimension non énumérée, est refusée ({@link #invalid()} la compte, jamais d'exception).
 * Au-delà de {@code maxKeysPerWindow} clés dans une fenêtre, les suivantes sont fusionnées dans {@code app = _overflow} de la même organisation (§ 4.4) : jamais abandonnées.
 */
public final class UsageMeter {
    public static final String OVERFLOW = "_overflow";

    private final String service, replica, node, version, env;
    private final int maxKeysPerWindow;
    private final LongSupplier clock;
    private final SeqSource seqs;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, Cell>> windows = new ConcurrentHashMap<>();
    private final AtomicLong invalid = new AtomicLong(), overflowed = new AtomicLong();

    /** Source des numéros de séquence (persistée par {@link UsageSpool}, en mémoire sinon). */
    public interface SeqSource {
        long next();

        long epoch();
    }

    /** Suite en mémoire, qui repart de 1 à chaque démarrage (époque = date de création). */
    public static SeqSource memorySeq(long epoch) {
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

        Cell(UsageRecord.Subject subject, String unit, TreeMap<String, String> dims, boolean internal, String onBehalfOf) {
            this.subject = subject;
            this.unit = unit;
            this.dims = dims;
            this.internal = internal;
            this.onBehalfOf = onBehalfOf;
        }

        synchronized void add(BigDecimal q) { sum = sum.add(q); }
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

    /** Go × h d'un volume de {@code bytes} gardé {@code seconds} s (Go = 2³⁰ octets), 9 décimales, arrondi vers le bas. */
    public static BigDecimal gbHours(long bytes, long seconds) {
        return BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(seconds)).divide(GB_HOUR_BYTE_SECONDS, 9, java.math.RoundingMode.DOWN);
    }

    /** Mo × s de {@code bytes} gardés {@code seconds} s (Mo = 2²⁰ octets), 9 décimales, arrondi vers le bas. */
    public static BigDecimal mbSeconds(long bytes, double seconds) {
        return BigDecimal.valueOf(bytes).multiply(BigDecimal.valueOf(seconds)).divide(MB, 9, java.math.RoundingMode.DOWN);
    }

    /** Unités refusées (hors catalogue du service, dimension inconnue, quantité négative). */
    public long invalid() { return invalid.get(); }

    /** Clés fusionnées dans {@code _overflow}. */
    public long overflowed() { return overflowed.get(); }

    /** Ajoute {@code quantity} à la fenêtre courante. */
    public boolean add(UsageRecord.Subject subject, String unit, Map<String, String> dims, BigDecimal quantity) {
        return add(clock.getAsLong(), subject, unit, dims, quantity, false, null);
    }

    public boolean add(UsageRecord.Subject subject, String unit, Map<String, String> dims, long quantity) {
        return add(subject, unit, dims, BigDecimal.valueOf(quantity));
    }

    /**
     * Ajoute {@code quantity} à la fenêtre qui contient {@code at} (ms). {@code internal}/{@code onBehalfOf} : usage d'un service de la plateforme (§ 3.5).
     * Faux si l'unité est refusée.
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

    private static boolean dimsAllowed(UsageUnit u, Map<String, String> dims) {
        if (dims == null || dims.isEmpty()) return true;
        if (dims.size() > 3) return false;
        for (var e : dims.entrySet()) {
            List<String> allowed = u.dims().get(e.getKey());
            if (allowed == null || !allowed.contains(e.getValue())) return false;
        }
        return true;
    }

    /** Enregistrements des fenêtres closes (fin ≤ maintenant), retirés des compteurs. */
    public List<UsageRecord> drain() {
        return drain(clock.getAsLong());
    }

    /** Toutes les fenêtres closes à {@code now} ; avec {@code now = Long.MAX_VALUE}, aussi la fenêtre en cours (arrêt du service). */
    public synchronized List<UsageRecord> drain(long now) {
        List<UsageRecord> out = new ArrayList<>();
        List<Long> closed = new ArrayList<>();
        for (Long w : windows.keySet()) if (now == Long.MAX_VALUE || w + UsageRecord.WINDOW_MS <= now) closed.add(w);
        closed.sort(Long::compare);
        UsageRecord.Emitter em = new UsageRecord.Emitter(service, replica, node, version, seqs.epoch());
        for (Long w : closed) {
            ConcurrentHashMap<String, Cell> cells = windows.remove(w);
            if (cells == null) continue;
            List<String> keys = new ArrayList<>(cells.keySet());
            keys.sort(String::compareTo);
            for (Iterator<String> it = keys.iterator(); it.hasNext(); ) {
                String k = it.next();
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
        return out;
    }
}
