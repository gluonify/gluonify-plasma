package io.gluonify.plasma.usage;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A unit of the closed catalog ({@code metering.md} § 3.1, {@code analysis.md} § 10).
 *
 * @param name      {@code <service>.<unit>}
 * @param service   emitting service
 * @param quantity  quantity kind of the records
 * @param billable  true: {@code confidence = billable}; false: {@code indicative} (never priceable)
 * @param internal  platform cost measured but never billed to the customer (never priceable)
 * @param dims      enumerated dimensions and their allowed values (stable order)
 * @param free      dimensions whose values are not enumerated: any {@link #FREE_VALUE} (an agent id, a ticket key)
 * @param highCardinality the free dimensions whose values are unbounded (a ticket): the calorimeter keeps them at the detailed level for a limited time, then rolls them up without them
 */
public record UsageUnit(String name, String service, Quantity quantity, boolean billable, boolean internal, Map<String, List<String>> dims, Set<String> free, Set<String> highCardinality) {
    /** Most dimensions on one record. */
    public static final int MAX_DIMS = 6;

    /** What a free dimension may hold: a short token (no space, no separator of the key format). */
    public static final Pattern FREE_VALUE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,63}");

    public UsageUnit {
        dims = dims == null ? Map.of() : Map.copyOf(dims);
        free = free == null ? Set.of() : Set.copyOf(free);
        highCardinality = highCardinality == null ? Set.of() : Set.copyOf(highCardinality);
        if (!free.containsAll(highCardinality)) throw new IllegalArgumentException(name + ": a highCardinality dimension is a free one");
    }

    /** A unit whose dimensions are all enumerated. */
    public UsageUnit(final String name, final String service, final Quantity quantity, final boolean billable, final boolean internal, final Map<String, List<String>> dims) {
        this(name, service, quantity, billable, internal, dims, Set.of(), Set.of());
    }

    /** Priceable: billable and not internal. */
    public boolean priceable() { return billable && !internal; }

    /** True when the dimensions of a record are allowed: not too many, each enumerated with an allowed value or free with a well-formed one. */
    public boolean acceptsDims(final Map<String, String> given) {
        if (given == null || given.isEmpty()) return true;
        if (given.size() > MAX_DIMS) return false;
        for (final Map.Entry<String, String> e : given.entrySet()) {
            final List<String> allowed = dims.get(e.getKey());
            final boolean ok = allowed != null ? allowed.contains(e.getValue()) : free.contains(e.getKey()) && e.getValue() != null && FREE_VALUE.matcher(e.getValue()).matches();
            if (!ok) return false;
        }
        return true;
    }

    /** Every dimension a client may send: the enumerated ones and the free ones. */
    public Set<String> allDims() {
        final Set<String> all = new java.util.TreeSet<>(dims.keySet());
        all.addAll(free);
        return all;
    }
}
