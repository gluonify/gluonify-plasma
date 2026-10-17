package io.gluonify.plasma.usage;

import java.util.List;
import java.util.Map;

/**
 * A unit of the closed catalog ({@code metering.md} § 3.1, {@code analysis.md} § 10).
 *
 * @param name      {@code <service>.<unit>}
 * @param service   emitting service
 * @param quantity  quantity kind of the records
 * @param billable  true: {@code confidence = billable}; false: {@code indicative} (never priceable)
 * @param internal  platform cost measured but never billed to the customer (never priceable)
 * @param dims      enumerated dimensions and their allowed values (stable order)
 */
public record UsageUnit(String name, String service, Quantity quantity, boolean billable, boolean internal, Map<String, List<String>> dims) {
    public UsageUnit {
        dims = dims == null ? Map.of() : Map.copyOf(dims);
    }

    /** Priceable: billable and not internal ({@code contracts.md} § 2.4). */
    public boolean priceable() { return billable && !internal; }
}
