package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Platform price list ({@code contracts.md} § 2.4): versioned object, one currency (immutable).
 *
 * @param id       identifier ({@link #ID_PATTERN})
 * @param name     display name
 * @param currency ISO 4217 currency (three uppercase letters)
 * @param versions versions, in increasing order; never null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceList(String id, String name, String currency, Long createdAt, String createdBy, List<PriceListVersion> versions) {
    /** List identifier: a-z, 0-9 and hyphen, 1 to 40 characters. */
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");
    /** @deprecated renamed {@link #ID_PATTERN} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final Pattern ID = ID_PATTERN;

    public PriceList {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public PriceList withName(final String n) { return new PriceList(id, n, currency, createdAt, createdBy, versions); }

    public PriceList withVersion(final PriceListVersion v) {
        List<PriceListVersion> out = new ArrayList<>(versions);
        out.add(v);
        return new PriceList(id, name, currency, createdAt, createdBy, out);
    }

    public int nextVersion() {
        int max = 0;
        for (PriceListVersion v : versions) max = Math.max(max, v.version());
        return max + 1;
    }

    /** The version with this number, or null. */
    public PriceListVersion version(final int v) {
        for (PriceListVersion x : versions) if (x.version() == v) return x;
        return null;
    }

    /** Effective version at {@code t}: greatest {@code effectiveFrom <= t} (on a tie, the greatest number); null if there is none. */
    public PriceListVersion effective(final long t) {
        PriceListVersion best = null;
        for (PriceListVersion v : versions) {
            if (v.effectiveFrom() > t) continue;
            if (best == null || v.effectiveFrom() > best.effectiveFrom() || (v.effectiveFrom().equals(best.effectiveFrom()) && v.version() > best.version())) best = v;
        }
        return best;
    }
}
