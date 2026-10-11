package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Termes d'une version de contrat d'organisation ({@code gluonify-codex/contracts.md} § 2.2) : tout ce qui est couvert par l'empreinte.
 * <strong>Un champ absent veut dire « pas de plafond »</strong> pour un quota et « tout est permis » pour un service ; une liste vide, « rien ».
 *
 * @param validity     durée commerciale (dates civiles, affichées ; n'arrête rien)
 * @param enforcement  {@value #ENFORCE} (refus 422) ou {@value #WARN} (accepté avec un avertissement) ; absent : {@value #ENFORCE}
 * @param environments environnements ({@code gluonify.higgs.env-name}) où l'organisation peut déployer ; absent : tous
 * @param services     services et options permis
 * @param quotas       quotas
 * @param quotasByEnv  surcharges des quotas, clé par clé, par environnement
 * @param ha           répartition des répliques ({@code contracts.md} § 4) ; absent : placement historique (au mieux)
 * @param apps         surcharges par nom d'application
 * @param pricing      tarification
 * @param budget       budget mensuel et seuils d'alerte
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

    /** Vrai si les violations sont refusées (422) ; {@code warn} les accepte. */
    public boolean enforcing() { return !WARN.equals(enforcement); }

    /** Copie avec un autre mode d'application. */
    public ContractTerms withEnforcement(String e) {
        return new ContractTerms(validity, e, environments, services, quotas, quotasByEnv, ha, apps, pricing, budget);
    }

    /** Copie avec d'autres surcharges par application. */
    public ContractTerms withApps(Map<String, AppOverride> a) {
        return new ContractTerms(validity, enforcement, environments, services, quotas, quotasByEnv, ha, a, pricing, budget);
    }

    /** Quotas effectifs d'une application {@code app} sur un cluster d'environnement {@code env} : {@code quotas} ⊕ {@code quotasByEnv[env]} ⊕ {@code apps[app]}. */
    public Quotas quotasFor(String env, String app) {
        Quotas q = quotas == null ? Quotas.NONE : quotas;
        if (env != null && quotasByEnv != null && quotasByEnv.get(env) != null) q = q.merge(quotasByEnv.get(env));
        AppOverride o = app == null || apps == null ? null : apps.get(app);
        if (o != null) q = q.merge(new Quotas(null, null, o.replicasPerApp(), o.memoryMbPerReplica(), o.scratchMbPerReplica(), o.distributedQuotaMbPerApp(), null, null, null));
        return q;
    }

    /** Répartition voulue pour {@code app} : celle de sa surcharge, sinon celle du contrat ; null : placement historique. */
    public Ha haFor(String app) {
        AppOverride o = app == null || apps == null ? null : apps.get(app);
        if (o != null && o.ha() != null) return o.ha().over(ha);
        return ha;
    }

    /** Dates civiles {@code aaaa-mm-jj}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Validity(String from, String until) {}

    /**
     * Services permis. {@code uses} : noms de {@code AppSpec.uses} (sans le « ? ») ; {@code distributed} : genres {@code std}, {@code tmp}, {@code gdown} ;
     * {@code routeKinds} : {@code app}, {@code gdown}, {@code upstream}. Un booléen absent : permis.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Services(List<String> uses, Boolean gdownDatabase, List<String> distributed, Boolean distributedLocks, Boolean sleep, Boolean extraPorts, Boolean nas,
                           List<String> routeKinds) {
        public Services {
            uses = uses == null ? null : List.copyOf(uses);
            distributed = distributed == null ? null : List.copyOf(distributed);
            routeKinds = routeKinds == null ? null : List.copyOf(routeKinds);
        }
    }

    /** Quotas (absent : pas de plafond). Mémoire en Mo, la mémoire réservée compte {@code memoryMb + scratchMb} par réplique. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quotas(Long apps, Long reservedMemoryMb, Long replicasPerApp, Long memoryMbPerReplica, Long scratchMbPerReplica, Long distributedQuotaMbPerApp,
                         Long defaultDistributedQuotaMb, Long storageMb, Long domains) {
        public static final Quotas NONE = new Quotas(null, null, null, null, null, null, null, null, null);

        /** Clé par clé : une valeur de {@code o} remplace celle-ci. */
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
     * Répartition : les {@code min(replicas, spreadNodes, nœuds éligibles)} premières répliques sur des nœuds deux à deux distincts.
     * {@code antiAffinity} : {@value #PREFERRED} (repli sur un nœud qui a déjà une réplique) ou {@value #REQUIRED} (réplique non placée plutôt que de partager une panne).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ha(Integer spreadNodes, String antiAffinity) {
        public static final String PREFERRED = "preferred";
        public static final String REQUIRED = "required";

        /** Nombre de nœuds distincts voulu (défaut 1). */
        public int spread() { return spreadNodes == null ? 1 : spreadNodes; }

        public boolean required() { return REQUIRED.equals(antiAffinity); }

        /** Champ par champ, celle-ci l'emporte sur {@code base}. */
        public Ha over(Ha base) {
            if (base == null) return this;
            return new Ha(spreadNodes != null ? spreadNodes : base.spreadNodes, antiAffinity != null ? antiAffinity : base.antiAffinity);
        }
    }

    /**
     * Surcharge pour une application nommée. {@code memoryMbPerReplica}, {@code replicasPerApp} et {@code ha} peuvent élargir le contrat ; les autres ne peuvent que le resserrer.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AppOverride(Long memoryMbPerReplica, Long replicasPerApp, Long scratchMbPerReplica, Long distributedQuotaMbPerApp, Ha ha) {}

    /** Tarification : devise ISO 4217, liste de prix épinglée, part fixe, remise, minimum, surcharges. Montants en chaînes décimales. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PricingTerms(String currency, PriceListRef priceList, Subscription subscription, String discountPct, String minimumMonthly, List<PriceOverride> overrides) {
        public PricingTerms {
            overrides = overrides == null ? null : List.copyOf(overrides);
        }
    }

    /** Liste de prix : {@code version} épinglée, ou {@code follow: true} (version effective de la liste à chaque heure). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceListRef(String id, Integer version, Boolean follow) {}

    /** Part fixe mensuelle : par organisation, par application, par application répartie (≥ 2 nœuds). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Subscription(String monthly, String perApp, String perHaApp) {}

    /**
     * Surcharge d'une ligne de prix : prix ({@code price}) ou paliers ({@code tiers}), quantité incluse par mois ({@code included}, dans la grandeur {@code per}),
     * remise propre ({@code discountPct}), restreinte à des dimensions ({@code dims}) et à un environnement ({@code env}). {@code per} : absent, celle de la ligne de liste.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceOverride(String unit, Map<String, String> dims, String env, String per, String price, Long included, List<Tier> tiers, String discountPct) {
        public PriceOverride {
            dims = dims == null ? null : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
            tiers = tiers == null ? null : List.copyOf(tiers);
        }
    }

    /** Palier : jusqu'à {@code upTo} (quantité facturée cumulée, absent pour le dernier) au prix {@code price}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tier(Long upTo, String price) {}

    /** Budget mensuel et seuils d'alerte (en % du budget, 1 à 500, 5 au plus) ; {@code forecast} : prévision linéaire de fin de mois. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Budget(String monthly, List<Integer> thresholdsPct, Boolean forecast) {
        public Budget {
            thresholdsPct = thresholdsPct == null ? null : List.copyOf(thresholdsPct);
        }
    }
}
