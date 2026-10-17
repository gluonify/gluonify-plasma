package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Terms of an organization contract version ({@code gluonify-codex/contracts.md} § 2.2): everything covered by the fingerprint.
 * <strong>A missing field means "no cap"</strong> for a quota and "everything is allowed" for a service; an empty list means "nothing".
 *
 * @param validity     commercial duration (calendar dates, displayed; stops nothing)
 * @param enforcement  {@value #ENFORCE} (refused with 422) or {@value #WARN} (accepted with a warning); absent: {@value #ENFORCE}
 * @param environments environnements ({@code gluonify.higgs.env-name}) where the organization may deploy; absent: all
 * @param services     allowed services and options
 * @param quotas       quotas
 * @param quotasByEnv  quota overrides, key by key, per environment
 * @param ha           replica spread ({@code contracts.md} § 4); absent: legacy placement (best effort)
 * @param apps         overrides per application name
 * @param pricing      pricing
 * @param budget       monthly budget and alert thresholds
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractTerms(Validity validity, String enforcement, List<String> environments, Services services, Quotas quotas, Map<String, Quotas> quotasByEnv,
                            Ha ha, Map<String, AppOverride> apps, PricingTerms pricing, Budget budget) {
    public static final String ENFORCE = "enforce";
    public static final String WARN = "warn";

    public ContractTerms {
        environments = environments == null ? null : List.copyOf(environments);
        quotasByEnv = quotasByEnv == null ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(quotasByEnv));
        apps = apps == null ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(apps));
    }

    /** True if violations are refused (422); {@code warn} accepts them. */
    public boolean enforcing() { return !WARN.equals(enforcement); }

    /** Copy with another enforcement mode. */
    public ContractTerms withEnforcement(String e) {
        return new ContractTerms(validity, e, environments, services, quotas, quotasByEnv, ha, apps, pricing, budget);
    }

    /** Copy with other per-application overrides. */
    public ContractTerms withApps(Map<String, AppOverride> a) {
        return new ContractTerms(validity, enforcement, environments, services, quotas, quotasByEnv, ha, a, pricing, budget);
    }

    /** Effective quotas of an application {@code app} on a cluster of environment {@code env}: {@code quotas} + {@code quotasByEnv[env]} + {@code apps[app]}. */
    public Quotas quotasFor(String env, String app) {
        Quotas q = quotas == null ? Quotas.NONE : quotas;
        if (env != null && quotasByEnv != null && quotasByEnv.get(env) != null) q = q.merge(quotasByEnv.get(env));
        AppOverride o = app == null || apps == null ? null : apps.get(app);
        if (o != null) q = q.merge(new Quotas(null, null, o.replicasPerApp(), o.memoryMbPerReplica(), o.scratchMbPerReplica(), o.distributedQuotaMbPerApp(), null, null, null));
        return q;
    }

    /** Wanted spread for {@code app}: that of its override, otherwise that of the contract; null: legacy placement. */
    public Ha haFor(String app) {
        AppOverride o = app == null || apps == null ? null : apps.get(app);
        if (o != null && o.ha() != null) return o.ha().over(ha);
        return ha;
    }

    /** Calendar dates {@code yyyy-mm-dd}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validity(String from, String until) {}

    /**
     * Allowed services. {@code uses}: names of {@code AppSpec.uses} (without the "?"); {@code distributed}: kinds {@code std}, {@code tmp}, {@code gdown};
     * {@code routeKinds}: {@code app}, {@code gdown}, {@code upstream}; {@code memory}: the gDown memory database of the organization's AI assistants. A missing boolean: allowed.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Services(List<String> uses, Boolean gdownDatabase, List<String> distributed, Boolean distributedLocks, Boolean sleep, Boolean extraPorts, Boolean nas,
                           List<String> routeKinds, Boolean memory) {
        public Services {
            uses = uses == null ? null : List.copyOf(uses);
            distributed = distributed == null ? null : List.copyOf(distributed);
            routeKinds = routeKinds == null ? null : List.copyOf(routeKinds);
        }
    }

    /** Quotas (absent: no cap). Memory in MB, reserved memory counts {@code memoryMb + scratchMb} per replica. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quotas(Long apps, Long reservedMemoryMb, Long replicasPerApp, Long memoryMbPerReplica, Long scratchMbPerReplica, Long distributedQuotaMbPerApp,
                         Long defaultDistributedQuotaMb, Long storageMb, Long domains) {
        public static final Quotas NONE = new Quotas(null, null, null, null, null, null, null, null, null);

        /** Key by key: a value of {@code o} replaces this one. */
        public Quotas merge(Quotas o) {
            if (o == null) return this;
            return new Quotas(o.apps != null ? o.apps : apps, o.reservedMemoryMb != null ? o.reservedMemoryMb : reservedMemoryMb,
                    o.replicasPerApp != null ? o.replicasPerApp : replicasPerApp, o.memoryMbPerReplica != null ? o.memoryMbPerReplica : memoryMbPerReplica,
                    o.scratchMbPerReplica != null ? o.scratchMbPerReplica : scratchMbPerReplica,
                    o.distributedQuotaMbPerApp != null ? o.distributedQuotaMbPerApp : distributedQuotaMbPerApp,
                    o.defaultDistributedQuotaMb != null ? o.defaultDistributedQuotaMb : defaultDistributedQuotaMb, o.storageMb != null ? o.storageMb : storageMb,
                    o.domains != null ? o.domains : domains);
        }
    }

    /**
     * Spread: the first {@code min(replicas, spreadNodes, eligible nodes)} replicas on pairwise distinct nodes.
     * {@code antiAffinity} : {@value #PREFERRED} (falls back to a node that already has a replica) or {@value #REQUIRED} (replica left unplaced rather than sharing a failure).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ha(Integer spreadNodes, String antiAffinity) {
        public static final String PREFERRED = "preferred";
        public static final String REQUIRED = "required";

        /** Wanted number of distinct nodes (default 1). */
        public int spread() { return spreadNodes == null ? 1 : spreadNodes; }

        public boolean required() { return REQUIRED.equals(antiAffinity); }

        /** Field by field, this one wins over {@code base}. */
        public Ha over(Ha base) {
            if (base == null) return this;
            return new Ha(spreadNodes != null ? spreadNodes : base.spreadNodes, antiAffinity != null ? antiAffinity : base.antiAffinity);
        }
    }

    /**
     * Override for a named application. {@code memoryMbPerReplica}, {@code replicasPerApp} and {@code ha} may widen the contract; the others can only tighten it.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppOverride(Long memoryMbPerReplica, Long replicasPerApp, Long scratchMbPerReplica, Long distributedQuotaMbPerApp, Ha ha) {}

    /** Pricing: ISO 4217 currency, pinned price list, fixed part, discount, minimum, overrides. Amounts as decimal strings. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingTerms(String currency, PriceListRef priceList, Subscription subscription, String discountPct, String minimumMonthly, List<PriceOverride> overrides) {
        public PricingTerms {
            overrides = overrides == null ? null : List.copyOf(overrides);
        }
    }

    /** Price list: pinned {@code version}, or {@code follow: true} (the list's effective version each hour). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceListRef(String id, Integer version, Boolean follow) {}

    /** Fixed monthly part: per organization, per application, per spread application (>= 2 nodes). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subscription(String monthly, String perApp, String perHaApp) {}

    /**
     * Override of a price line: price ({@code price}) or tiers ({@code tiers}), quantity included per month ({@code included}, in the unit {@code per}),
     * own discount ({@code discountPct}), restricted to dimensions ({@code dims}) and to an environment ({@code env}). {@code per}: absent, that of the list line.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceOverride(String unit, Map<String, String> dims, String env, String per, String price, Long included, List<Tier> tiers, String discountPct) {
        public PriceOverride {
            dims = dims == null ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
            tiers = tiers == null ? null : List.copyOf(tiers);
        }
    }

    /** Tier: up to {@code upTo} (cumulative billed quantity, absent for the last one) at price {@code price}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tier(Long upTo, String price) {}

    /** Monthly budget and alert thresholds (in % of the budget, 1 to 500, at most 5); {@code forecast}: linear end-of-month forecast. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(String monthly, List<Integer> thresholdsPct, Boolean forecast) {
        public Budget {
            thresholdsPct = thresholdsPct == null ? null : List.copyOf(thresholdsPct);
        }
    }
}
