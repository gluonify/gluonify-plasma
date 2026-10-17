package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** A version of a price list: immutable, with its effective date, its author, its reason and its lines. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceListVersion(Integer version, Long effectiveFrom, Long createdAt, String createdBy, String note, List<PriceLine> lines) {
    public PriceListVersion {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
