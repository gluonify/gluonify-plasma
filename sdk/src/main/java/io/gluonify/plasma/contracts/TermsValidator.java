package io.gluonify.plasma.contracts;

import io.gluonify.plasma.usage.UsageCatalog;
import io.gluonify.plasma.usage.UsageUnit;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validation des termes d'un contrat et des lignes d'une liste de prix ({@code contracts.md} § 2.2 et § 2.4). Rend toutes les erreurs (phrases en français,
 * pour une réponse 400), jamais la première seule. Ne lit que le catalogue du SDK ({@link UsageCatalog}) : aucun appel réseau.
 */
public final class TermsValidator {
    private TermsValidator() {}

    public static final int MAX_APPS = 64;
    public static final int MAX_PRICE_LINES = 200;
    public static final int MAX_DIMS = 3;
    public static final int MAX_THRESHOLDS = 5;
    public static final int MAX_SPREAD = 8;
    /** Environnement : {@code gluonify.higgs.env-name} (SBX, QUA, ACP, PRD…). */
    public static final Pattern ENV = Pattern.compile("[A-Z][A-Z0-9_-]{0,15}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    public static final Set<String> DISTRIBUTED = Set.of("std", "tmp", "gdown");
    public static final Set<String> ROUTE_KINDS = Set.of("app", "gdown", "upstream");
    /** Service jamais permis à un client (jeton de l'API pour les applications de la plateforme). */
    public static final String HIGGS = "higgs";

    /** Erreurs des termes (liste vide : valides). */
    public static List<String> validate(ContractTerms t) {
        List<String> e = new ArrayList<>();
        if (t == null) {
            e.add("terms obligatoire");
            return e;
        }
        if (t.enforcement() != null && !t.enforcement().equals(ContractTerms.ENFORCE) && !t.enforcement().equals(ContractTerms.WARN))
            e.add("enforcement : enforce ou warn");
        if (t.validity() != null) {
            LocalDate from = date(t.validity().from(), "validity.from", e), until = date(t.validity().until(), "validity.until", e);
            if (from != null && until != null && until.isBefore(from)) e.add("validity : until précède from");
        }
        if (t.environments() != null) for (String env : t.environments()) if (env == null || !ENV.matcher(env).matches()) e.add("environments : nom invalide (" + env + ")");
        if (t.services() != null) services(t.services(), e);
        if (t.quotas() != null) quotas(t.quotas(), "quotas", e);
        if (t.quotasByEnv() != null) t.quotasByEnv().forEach((env, q) -> {
            if (!ENV.matcher(env).matches()) e.add("quotasByEnv : environnement invalide (" + env + ")");
            if (q != null) quotas(q, "quotasByEnv." + env, e);
        });
        if (t.ha() != null) ha(t.ha(), "ha", e);
        if (t.apps() != null) {
            if (t.apps().size() > MAX_APPS) e.add("apps : " + MAX_APPS + " surcharges au plus");
            ContractTerms.Quotas base = t.quotas() == null ? ContractTerms.Quotas.NONE : t.quotas();
            t.apps().forEach((name, o) -> {
                String p = "apps." + name;
                if (!NAME.matcher(name).matches()) e.add(p + " : nom d'application invalide");
                if (o == null) return;
                positive(o.memoryMbPerReplica(), p + ".memoryMbPerReplica", e);
                positive(o.replicasPerApp(), p + ".replicasPerApp", e);
                nonNegative(o.scratchMbPerReplica(), p + ".scratchMbPerReplica", e);
                positive(o.distributedQuotaMbPerApp(), p + ".distributedQuotaMbPerApp", e);
                // seules memoryMbPerReplica, replicasPerApp et ha peuvent élargir le contrat
                if (o.scratchMbPerReplica() != null && base.scratchMbPerReplica() != null && o.scratchMbPerReplica() > base.scratchMbPerReplica())
                    e.add(p + ".scratchMbPerReplica : une surcharge ne peut que resserrer le contrat (" + base.scratchMbPerReplica() + ")");
                if (o.distributedQuotaMbPerApp() != null && base.distributedQuotaMbPerApp() != null && o.distributedQuotaMbPerApp() > base.distributedQuotaMbPerApp())
                    e.add(p + ".distributedQuotaMbPerApp : une surcharge ne peut que resserrer le contrat (" + base.distributedQuotaMbPerApp() + ")");
                if (o.ha() != null) ha(o.ha(), p + ".ha", e);
            });
        }
        if (t.pricing() != null) pricing(t.pricing(), e);
        if (t.budget() != null) {
            if (t.budget().monthly() != null && !Decimals.valid(t.budget().monthly())) e.add("budget.monthly : décimal positif attendu (9 décimales au plus)");
            if (t.budget().thresholdsPct() != null) {
                if (t.budget().thresholdsPct().size() > MAX_THRESHOLDS) e.add("budget.thresholdsPct : " + MAX_THRESHOLDS + " seuils au plus");
                for (Integer th : t.budget().thresholdsPct()) if (th == null || th < 1 || th > 500) e.add("budget.thresholdsPct : de 1 à 500 %");
            }
        }
        return e;
    }

    /** Erreurs des lignes d'une liste de prix. */
    public static List<String> validateLines(List<PriceLine> lines) {
        List<String> e = new ArrayList<>();
        if (lines == null) {
            e.add("lines obligatoire");
            return e;
        }
        if (lines.size() > MAX_PRICE_LINES) e.add("lines : " + MAX_PRICE_LINES + " lignes au plus");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            PriceLine l = lines.get(i);
            String p = "lines[" + i + "]";
            if (l == null) {
                e.add(p + " : ligne vide");
                continue;
            }
            UsageUnit u = unit(l.unit(), p, e);
            if (u != null) {
                dims(u, l.dims(), p, e);
                if (l.per() == null || !u.quantity().accepts(l.per())) e.add(p + ".per : " + u.quantity().perUnits().keySet() + " pour " + u.name());
            }
            if (!Decimals.valid(l.price())) e.add(p + ".price : décimal positif attendu (9 décimales au plus)");
            if (!seen.add(l.unit() + "|" + (l.dims() == null ? "" : l.dims().toString()))) e.add(p + " : ligne en double (même unité et mêmes dimensions)");
        }
        return e;
    }

    /** Liaison contrat → liste : la liste existe, la version épinglée aussi, et la devise est la même. Erreurs pour un refus 422. */
    public static List<String> validateLink(ContractTerms t, PriceList list) {
        List<String> e = new ArrayList<>();
        if (t == null || t.pricing() == null || t.pricing().priceList() == null) return e;
        ContractTerms.PriceListRef ref = t.pricing().priceList();
        if (list == null) {
            e.add("pricing.priceList : liste inconnue (" + ref.id() + ")");
            return e;
        }
        if (t.pricing().currency() != null && !t.pricing().currency().equals(list.currency()))
            e.add("pricing.currency : " + t.pricing().currency() + " n'est pas la devise de la liste " + list.id() + " (" + list.currency() + ")");
        if (ref.version() != null && list.version(ref.version()) == null) e.add("pricing.priceList.version : version inconnue (" + ref.version() + ")");
        return e;
    }

    private static void services(ContractTerms.Services s, List<String> e) {
        if (s.uses() != null) for (String u : s.uses()) {
            if (u == null || !NAME.matcher(u).matches()) e.add("services.uses : nom invalide (" + u + ")");
            else if (u.equals(HIGGS)) e.add("services.uses : higgs n'est jamais permis à un client");
        }
        if (s.distributed() != null) for (String d : s.distributed()) if (!DISTRIBUTED.contains(d)) e.add("services.distributed : std, tmp ou gdown (" + d + ")");
        if (s.routeKinds() != null) for (String k : s.routeKinds()) if (!ROUTE_KINDS.contains(k)) e.add("services.routeKinds : app, gdown ou upstream (" + k + ")");
    }

    private static void quotas(ContractTerms.Quotas q, String p, List<String> e) {
        nonNegative(q.apps(), p + ".apps", e);
        nonNegative(q.reservedMemoryMb(), p + ".reservedMemoryMb", e);
        positive(q.replicasPerApp(), p + ".replicasPerApp", e);
        positive(q.memoryMbPerReplica(), p + ".memoryMbPerReplica", e);
        nonNegative(q.scratchMbPerReplica(), p + ".scratchMbPerReplica", e);
        positive(q.distributedQuotaMbPerApp(), p + ".distributedQuotaMbPerApp", e);
        positive(q.defaultDistributedQuotaMb(), p + ".defaultDistributedQuotaMb", e);
        nonNegative(q.storageMb(), p + ".storageMb", e);
        nonNegative(q.domains(), p + ".domains", e);
        if (q.defaultDistributedQuotaMb() != null && q.distributedQuotaMbPerApp() != null && q.defaultDistributedQuotaMb() > q.distributedQuotaMbPerApp())
            e.add(p + ".defaultDistributedQuotaMb : dépasse distributedQuotaMbPerApp");
    }

    private static void ha(ContractTerms.Ha h, String p, List<String> e) {
        if (h.spreadNodes() != null && (h.spreadNodes() < 1 || h.spreadNodes() > MAX_SPREAD)) e.add(p + ".spreadNodes : de 1 à " + MAX_SPREAD);
        if (h.antiAffinity() != null && !h.antiAffinity().equals(ContractTerms.Ha.PREFERRED) && !h.antiAffinity().equals(ContractTerms.Ha.REQUIRED))
            e.add(p + ".antiAffinity : preferred ou required");
    }

    private static void pricing(ContractTerms.PricingTerms pr, List<String> e) {
        if (pr.currency() != null && !Decimals.CURRENCY.matcher(pr.currency()).matches()) e.add("pricing.currency : code ISO 4217 (trois lettres majuscules)");
        if (pr.priceList() != null) {
            if (pr.priceList().id() == null || !PriceList.ID.matcher(pr.priceList().id()).matches()) e.add("pricing.priceList.id : identifiant invalide");
            boolean follow = Boolean.TRUE.equals(pr.priceList().follow());
            if (follow && pr.priceList().version() != null) e.add("pricing.priceList : version et follow sont incompatibles");
            if (!follow && pr.priceList().version() == null) e.add("pricing.priceList.version : obligatoire sans follow (une version épinglée)");
        }
        if (pr.subscription() != null) {
            decimal(pr.subscription().monthly(), "pricing.subscription.monthly", e);
            decimal(pr.subscription().perApp(), "pricing.subscription.perApp", e);
            decimal(pr.subscription().perHaApp(), "pricing.subscription.perHaApp", e);
        }
        if (pr.discountPct() != null && !Decimals.validPct(pr.discountPct())) e.add("pricing.discountPct : pourcentage de 0 à 100");
        decimal(pr.minimumMonthly(), "pricing.minimumMonthly", e);
        if (pr.overrides() == null) return;
        if (pr.overrides().size() > MAX_PRICE_LINES) e.add("pricing.overrides : " + MAX_PRICE_LINES + " lignes au plus");
        for (int i = 0; i < pr.overrides().size(); i++) {
            ContractTerms.PriceOverride o = pr.overrides().get(i);
            String p = "pricing.overrides[" + i + "]";
            if (o == null) {
                e.add(p + " : ligne vide");
                continue;
            }
            UsageUnit u = unit(o.unit(), p, e);
            if (u != null) {
                dims(u, o.dims(), p, e);
                if (o.per() != null && !u.quantity().accepts(o.per())) e.add(p + ".per : " + u.quantity().perUnits().keySet() + " pour " + u.name());
            }
            if (o.env() != null && !ENV.matcher(o.env()).matches()) e.add(p + ".env : environnement invalide");
            decimal(o.price(), p + ".price", e);
            if (o.discountPct() != null && !Decimals.validPct(o.discountPct())) e.add(p + ".discountPct : pourcentage de 0 à 100");
            if (o.included() != null && o.included() < 0) e.add(p + ".included : positif ou nul");
            if (o.price() != null && o.tiers() != null) e.add(p + " : price et tiers sont incompatibles");
            if (o.price() == null && o.tiers() == null && o.included() == null && o.discountPct() == null) e.add(p + " : price, tiers, included ou discountPct attendu");
            if (o.tiers() != null) {
                if (o.tiers().isEmpty()) e.add(p + ".tiers : au moins un palier");
                long prev = -1;
                for (int j = 0; j < o.tiers().size(); j++) {
                    ContractTerms.Tier t = o.tiers().get(j);
                    String q = p + ".tiers[" + j + "]";
                    if (t == null) {
                        e.add(q + " : palier vide");
                        continue;
                    }
                    if (!Decimals.valid(t.price())) e.add(q + ".price : décimal positif attendu (9 décimales au plus)");
                    boolean last = j == o.tiers().size() - 1;
                    if (last && t.upTo() != null) e.add(q + ".upTo : le dernier palier n'a pas de borne");
                    if (!last && (t.upTo() == null || t.upTo() <= prev)) e.add(q + ".upTo : bornes strictement croissantes");
                    if (t.upTo() != null) prev = t.upTo();
                }
            }
        }
    }

    private static UsageUnit unit(String name, String p, List<String> e) {
        UsageUnit u = UsageCatalog.get(name);
        if (u == null) {
            e.add(p + ".unit : unité hors catalogue (" + name + ")");
            return null;
        }
        if (!u.priceable()) {
            e.add(p + ".unit : " + name + " n'est pas tarifable (" + (u.internal() ? "interne" : "indicative") + ")");
            return null;
        }
        return u;
    }

    private static void dims(UsageUnit u, Map<String, String> dims, String p, List<String> e) {
        if (dims == null) return;
        if (dims.size() > MAX_DIMS) e.add(p + ".dims : " + MAX_DIMS + " dimensions au plus");
        dims.forEach((k, v) -> {
            List<String> allowed = u.dims().get(k);
            if (allowed == null) e.add(p + ".dims : " + k + " n'est pas une dimension de " + u.name());
            else if (!allowed.contains(v)) e.add(p + ".dims." + k + " : " + allowed + " (" + v + ")");
        });
    }

    private static void decimal(String v, String p, List<String> e) {
        if (v != null && !Decimals.valid(v)) e.add(p + " : décimal positif attendu (9 décimales au plus)");
    }

    private static void positive(Long v, String p, List<String> e) {
        if (v != null && v < 1) e.add(p + " : strictement positif");
    }

    private static void nonNegative(Long v, String p, List<String> e) {
        if (v != null && v < 0) e.add(p + " : positif ou nul");
    }

    private static LocalDate date(String s, String p, List<String> e) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException x) {
            e.add(p + " : date aaaa-mm-jj attendue");
            return null;
        }
    }
}
