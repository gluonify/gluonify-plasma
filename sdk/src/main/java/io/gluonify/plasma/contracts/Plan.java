package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Subscription plan ({@code gluonify-codex/subscriptions.md} § 2.1): a named, versioned preset of contract terms that an organization's contract follows.
 *
 * @param id       identifier ({@link #ID_PATTERN}), e.g. {@code developer}
 * @param name     display name per language ({@code en} at least)
 * @param versions versions, in increasing order; never null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Plan(String id, Map<String, String> name, Long createdAt, String createdBy, List<PlanVersion> versions) {
    /** Plan identifier: a-z, 0-9 and hyphen, 1 to 40 characters (the rule of {@link PriceList#ID_PATTERN}). */
    public static final Pattern ID_PATTERN = PriceList.ID_PATTERN;
    /** @deprecated renamed {@link #ID_PATTERN} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final Pattern ID = ID_PATTERN;

    public Plan {
        name = name == null ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(name));
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public Plan withName(final Map<String, String> n) { return new Plan(id, n, createdAt, createdBy, versions); }

    public Plan withVersion(final PlanVersion v) {
        List<PlanVersion> out = new ArrayList<>(versions);
        out.add(v);
        return new Plan(id, name, createdAt, createdBy, out);
    }

    public int nextVersion() {
        int max = 0;
        for (PlanVersion v : versions) max = Math.max(max, v.version());
        return max + 1;
    }

    /** The version with this number, or null. */
    public PlanVersion version(final int v) {
        for (PlanVersion x : versions) if (x.version() == v) return x;
        return null;
    }

    /** Effective version at {@code t}: greatest {@code effectiveFrom <= t} (on a tie, the greatest number); null if there is none. */
    public PlanVersion effective(final long t) {
        PlanVersion best = null;
        for (PlanVersion v : versions) {
            if (v.effectiveFrom() > t) continue;
            if (best == null || v.effectiveFrom() > best.effectiveFrom() || (v.effectiveFrom().equals(best.effectiveFrom()) && v.version() > best.version())) best = v;
        }
        return best;
    }

    /** True for a well-formed display name: an English name, every name 1 to 120 characters. */
    public static boolean validName(final Map<String, String> name) {
        if (name == null || !Organization.validName(name.get("en"))) return false;
        for (var e : name.entrySet()) if (!e.getKey().matches("[a-z]{2}") || !Organization.validName(e.getValue())) return false;
        return true;
    }
}
