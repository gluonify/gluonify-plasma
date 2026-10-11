package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** Une version d'une liste de prix : immuable, avec sa date d'effet, son auteur, sa raison et ses lignes. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceListVersion(Integer version, Long effectiveFrom, Long createdAt, String createdBy, String note, List<PriceLine> lines) {
    public PriceListVersion {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
