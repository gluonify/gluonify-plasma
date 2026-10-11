package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ligne d'une liste de prix ({@code contracts.md} § 2.4) : unité du catalogue, dimensions (sous-ensemble des dimensions énumérées, 3 au plus ; la ligne la plus
 * précise gagne), grandeur de facturation ({@code per}) et prix décimal (9 décimales au plus).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceLine(String unit, Map<String, String> dims, String per, String price) {
    public PriceLine {
        dims = dims == null || dims.isEmpty() ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
    }
}
