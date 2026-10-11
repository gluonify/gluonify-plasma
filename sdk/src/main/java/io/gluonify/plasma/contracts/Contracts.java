package io.gluonify.plasma.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Fonctions pures des contrats ({@code contracts.md} § 2.3) : version effective, état calculé d'une version, empreinte des termes.
 * Déterministes : l'instant est toujours fourni par l'appelant (l'horloge de l'API, ou le {@code now} d'une commande Raft), jamais lu ici.
 */
public final class Contracts {
    private Contracts() {}

    public static final String SCHEDULED = "scheduled";
    public static final String ACTIVE = "active";
    public static final String SUPERSEDED = "superseded";
    public static final String CANCELLED = "cancelled";
    public static final String PENDING = "pending";

    /**
     * Version effective à {@code t} : la version non annulée (et approuvée si elle devait l'être) de plus grand {@code effectiveFrom ≤ t} ; à égalité, le plus
     * grand numéro. Null si aucune.
     */
    public static ContractVersion effective(List<ContractVersion> versions, long t) {
        ContractVersion best = null;
        if (versions == null) return null;
        for (ContractVersion v : versions) {
            if (v.cancelled() || v.awaitingApproval() || v.effectiveFrom() == null || v.effectiveFrom() > t) continue;
            if (best == null || v.effectiveFrom() > best.effectiveFrom() || (v.effectiveFrom().equals(best.effectiveFrom()) && v.version() > best.version())) best = v;
        }
        return best;
    }

    /** État calculé de {@code v} parmi {@code versions} à l'instant {@code t}. */
    public static String state(ContractVersion v, List<ContractVersion> versions, long t) {
        if (v.cancelled()) return CANCELLED;
        if (v.awaitingApproval()) return PENDING;
        ContractVersion e = effective(versions, t);
        if (e != null && e.version().equals(v.version())) return ACTIVE;
        if (v.effectiveFrom() > t) return SCHEDULED;
        return SUPERSEDED;
    }

    /** Prochaine version à venir (la plus proche date d'effet après {@code t}), ou null. */
    public static ContractVersion next(List<ContractVersion> versions, long t) {
        ContractVersion best = null;
        for (ContractVersion v : versions) {
            if (v.cancelled() || v.awaitingApproval() || v.effectiveFrom() <= t) continue;
            if (best == null || v.effectiveFrom() < best.effectiveFrom()) best = v;
        }
        return best;
    }

    /** JSON canonique des termes ({@link Canonical}). */
    public static String canonical(ContractTerms terms) {
        return Canonical.json(terms);
    }

    /** {@code sha256:<hex>} du JSON canonique des termes (ni numéro, ni dates, ni auteur, ni raison : comparable d'un cluster à l'autre). */
    public static String fingerprint(ContractTerms terms) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(terms).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
