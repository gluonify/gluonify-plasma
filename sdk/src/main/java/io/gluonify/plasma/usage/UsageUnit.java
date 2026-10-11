package io.gluonify.plasma.usage;

import java.util.List;
import java.util.Map;

/**
 * Une unité du catalogue fermé ({@code metering.md} § 3.1, {@code analysis.md} § 10).
 *
 * @param name      {@code <service>.<unité>}
 * @param service   service émetteur
 * @param quantity  grandeur des enregistrements
 * @param billable  vrai : {@code confidence = billable} ; faux : {@code indicative} (jamais tarifable)
 * @param internal  coût de plateforme mesuré mais jamais facturé au client (jamais tarifable)
 * @param dims      dimensions énumérées et leurs valeurs permises (ordre stable)
 */
public record UsageUnit(String name, String service, Quantity quantity, boolean billable, boolean internal, Map<String, List<String>> dims) {
    public UsageUnit {
        dims = dims == null ? Map.of() : Map.copyOf(dims);
    }

    /** Tarifable : facturable et non interne ({@code contracts.md} § 2.4). */
    public boolean priceable() { return billable && !internal; }
}
