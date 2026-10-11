package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Liste de prix de la plateforme ({@code contracts.md} § 2.4) : objet versionné, une devise (immuable).
 *
 * @param id       identifiant ({@link #ID})
 * @param name     nom d'affichage
 * @param currency devise ISO 4217 (trois lettres majuscules)
 * @param versions versions, dans l'ordre croissant ; jamais null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriceList(String id, String name, String currency, Long createdAt, String createdBy, List<PriceListVersion> versions) {
    /** Identifiant d'une liste : a-z, 0-9 et tiret, 1 à 40 caractères. */
    public static final Pattern ID = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");

    public PriceList {
        versions = versions == null ? List.of() : List.copyOf(versions);
    }

    public PriceList withName(String n) { return new PriceList(id, n, currency, createdAt, createdBy, versions); }

    public PriceList withVersion(PriceListVersion v) {
        List<PriceListVersion> out = new ArrayList<>(versions);
        out.add(v);
        return new PriceList(id, name, currency, createdAt, createdBy, out);
    }

    public int nextVersion() {
        int max = 0;
        for (PriceListVersion v : versions) max = Math.max(max, v.version());
        return max + 1;
    }

    /** La version de ce numéro, ou null. */
    public PriceListVersion version(int v) {
        for (PriceListVersion x : versions) if (x.version() == v) return x;
        return null;
    }

    /** Version effective à {@code t} : plus grand {@code effectiveFrom ≤ t} (à égalité, le plus grand numéro) ; null s'il n'y en a pas. */
    public PriceListVersion effective(long t) {
        PriceListVersion best = null;
        for (PriceListVersion v : versions) {
            if (v.effectiveFrom() > t) continue;
            if (best == null || v.effectiveFrom() > best.effectiveFrom() || (v.effectiveFrom().equals(best.effectiveFrom()) && v.version() > best.version())) best = v;
        }
        return best;
    }
}
