package io.gluonify.plasma.contracts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Pure contract functions ({@code contracts.md} § 2.3): effective version, computed state of a version, terms fingerprint.
 * Deterministic: the instant is always supplied by the caller (the API clock, or the {@code now} of a Raft command), never read here.
 */
public final class Contracts {
    private Contracts() {}

    public static final String SCHEDULED = "scheduled";
    public static final String ACTIVE = "active";
    public static final String SUPERSEDED = "superseded";
    public static final String CANCELLED = "cancelled";
    public static final String PENDING = "pending";

    /**
     * Effective version at {@code t}: the non-cancelled version (and approved if it had to be) with the greatest {@code effectiveFrom <= t}; on a tie, the
     * greatest number. Null if none.
     */
    public static ContractVersion effective(final List<ContractVersion> versions, final long t) {
        ContractVersion best = null;
        if (versions == null) return null;
        for (ContractVersion v : versions) {
            if (v.cancelled() || v.awaitingApproval() || v.effectiveFrom() == null || v.effectiveFrom() > t) continue;
            if (best == null || v.effectiveFrom() > best.effectiveFrom() || (v.effectiveFrom().equals(best.effectiveFrom()) && v.version() > best.version())) best = v;
        }
        return best;
    }

    /** Computed state of {@code v} among {@code versions} at instant {@code t}. */
    public static String state(final ContractVersion v, final List<ContractVersion> versions, final long t) {
        if (v.cancelled()) return CANCELLED;
        if (v.awaitingApproval()) return PENDING;
        ContractVersion e = effective(versions, t);
        if (e != null && e.version().equals(v.version())) return ACTIVE;
        if (v.effectiveFrom() > t) return SCHEDULED;
        return SUPERSEDED;
    }

    /** Next upcoming version (the nearest effective date after {@code t}), or null. */
    public static ContractVersion next(final List<ContractVersion> versions, final long t) {
        ContractVersion best = null;
        for (ContractVersion v : versions) {
            if (v.cancelled() || v.awaitingApproval() || v.effectiveFrom() <= t) continue;
            if (best == null || v.effectiveFrom() < best.effectiveFrom()) best = v;
        }
        return best;
    }

    /**
     * Terms in force for a contract that follows a plan ({@code subscriptions.md} § 2.2): each key of {@code contract} that is present wins, an absent key takes
     * the plan's value; {@code services} and {@code quotas} are merged field by field. {@code plan} keeps the contract's reference. A null {@code planTerms} (no plan,
     * or an unknown one): the contract as it is.
     */
    public static ContractTerms resolve(final ContractTerms contract, final ContractTerms planTerms) {
        if (contract == null || planTerms == null) return contract;
        ContractTerms c = contract;
        ContractTerms p = planTerms;
        ContractTerms.Quotas q = c.quotas();
        if (p.quotas() != null) q = c.quotas() == null ? p.quotas() : p.quotas().merge(c.quotas());
        java.util.Map<String, ContractTerms.Quotas> byEnv = p.quotasByEnv();
        if (c.quotasByEnv() != null) {
            java.util.Map<String, ContractTerms.Quotas> m = new java.util.TreeMap<>(byEnv == null ? java.util.Map.of() : byEnv);
            c.quotasByEnv().forEach((env, x) -> m.merge(env, x, ContractTerms.Quotas::merge));
            byEnv = m;
        }
        return new ContractTerms(or(c.validity(), p.validity()), or(c.enforcement(), p.enforcement()), or(c.environments(), p.environments()),
                services(c.services(), p.services()), q, byEnv, or(c.ha(), p.ha()), or(c.apps(), p.apps()), or(c.pricing(), p.pricing()), or(c.budget(), p.budget()), c.plan());
    }

    private static ContractTerms.Services services(final ContractTerms.Services c, final ContractTerms.Services p) {
        if (c == null) return p;
        if (p == null) return c;
        return new ContractTerms.Services(or(c.uses(), p.uses()), or(c.gdownDatabase(), p.gdownDatabase()), or(c.distributed(), p.distributed()),
                or(c.distributedLocks(), p.distributedLocks()), or(c.sleep(), p.sleep()), or(c.extraPorts(), p.extraPorts()), or(c.nas(), p.nas()),
                or(c.routeKinds(), p.routeKinds()), or(c.memory(), p.memory()), or(c.consoles(), p.consoles()));
    }

    private static <T> T or(final T own, final T fromPlan) {
        return own != null ? own : fromPlan;
    }

    /** Consoles the terms open: {@code services.consoles} restricted to {@code services.uses} (absent uses: all of them); absent consoles: none. Sorted. */
    public static java.util.List<String> consoles(final ContractTerms t) {
        if (t == null || t.services() == null || t.services().consoles() == null) return java.util.List.of();
        java.util.TreeSet<String> out = new java.util.TreeSet<>(t.services().consoles());
        if (t.services().uses() != null) out.retainAll(t.services().uses());
        return java.util.List.copyOf(out);
    }

    /** Canonical JSON of the terms ({@link Canonical}). */
    public static String canonical(final ContractTerms terms) {
        return Canonical.json(terms);
    }

    /** {@code sha256:<hex>} of the canonical JSON of the terms (no number, no dates, no author, no reason: comparable from one cluster to another). */
    public static String fingerprint(final ContractTerms terms) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical(terms).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
