package io.gluonify.plasma.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Quantity kind of a catalog unit (the one in which usage is counted, {@code metering.md} § 3.1) and the billing units
 * ({@code per} of a price line, {@code contracts.md} § 2.4) derived from it, with their conversion factor: billed quantity = quantity / factor.
 */
public enum Quantity {
    /** MB x s (reserved or used memory integrated over time). */
    MB_SECONDS("MB-second", Map.of("MB-second", "1", "MB-hour", "3600", Units.GB_HOUR, "3686400")),
    /** Seconds (CPU, build or request duration). */
    SECONDS("second", Map.of("second", "1", "minute", "60", "hour", "3600")),
    /** Counter (requests, documents, messages...). */
    COUNT("unit", Map.of("unit", "1", "thousand", "1000", "million", "1000000")),
    /** Bytes (traffic, bytes written); GB = 2^30 bytes. */
    BYTES("byte", Map.of("byte", "1", "MB", "1048576", "GB", "1073741824")),
    /** GB x h (stored volume integrated over time). */
    GB_HOURS(Units.GB_HOUR, Map.of(Units.GB_HOUR, "1", "GB-month", "720")),
    /** Object x s (domain x s, connection x s, entry x s). */
    OBJECT_SECONDS("unit-second", Map.of("unit-second", "1", "unit-hour", "3600", "unit-month", "2592000"));

    private final String base;
    private final Map<String, BigDecimal> per;

    Quantity(final String base, final Map<String, String> per) {
        this.base = base;
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        per.forEach((k, v) -> m.put(k, new BigDecimal(v)));
        this.per = Collections.unmodifiableMap(m);
    }

    /** Name of the base unit (that of usage records). */
    public String base() { return base; }

    /** Accepted billing units and their factor. */
    public Map<String, BigDecimal> perUnits() { return per; }

    /** True if {@code per} is a billing unit of this quantity kind. */
    public boolean accepts(final String unit) { return unit != null && per.containsKey(unit); }

    /** Converts a base quantity into the unit {@code unit} (34-digit precision, no monetary rounding). */
    public BigDecimal convert(final BigDecimal quantity, final String unit) {
        BigDecimal f = per.get(unit);
        if (f == null) throw new IllegalArgumentException("unit " + unit + " incompatible with " + base);
        return quantity.divide(f, MathContext.DECIMAL128);
    }
}
