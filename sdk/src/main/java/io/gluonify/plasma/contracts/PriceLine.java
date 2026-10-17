package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import java.util.TreeMap;

/**
 * Line of a price list ({@code contracts.md} § 2.4): catalog unit, dimensions (subset of the enumerated dimensions, at most 3; the most
 * specific line wins), billing unit ({@code per}) and decimal price (at most 9 decimal places).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceLine(String unit, Map<String, String> dims, String per, String price) {
    public PriceLine {
        dims = dims == null || dims.isEmpty() ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
    }
}
