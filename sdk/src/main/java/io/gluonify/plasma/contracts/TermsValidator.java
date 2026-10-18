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
 * Validation of the terms of a contract and of the lines of a price list ({@code contracts.md} § 2.2 and § 2.4). Returns all the errors (English sentences,
 * for a 400 response), never only the first one. Reads only the SDK catalog ({@link UsageCatalog}): no network call.
 */
public final class TermsValidator {
    private TermsValidator() {}

    public static final int MAX_APPS = 64;
    public static final int MAX_PRICE_LINES = 200;
    public static final int MAX_DIMS = 3;
    public static final int MAX_THRESHOLDS = 5;
    public static final int MAX_SPREAD = 8;
    /** Environment: {@code gluonify.higgs.env-name} (SBX, QUA, ACP, PRD…). */
    public static final Pattern ENV = Pattern.compile("[A-Z][A-Z0-9_-]{0,15}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    public static final Set<String> DISTRIBUTED = Set.of("std", "tmp", "gdown");
    public static final Set<String> ROUTE_KINDS = Set.of("app", "gdown", "upstream");
    /** Service never allowed to a customer (API token for the platform's applications). */
    public static final String HIGGS = "higgs";

    /** Errors of the terms (empty list: valid). */
    public static List<String> validate(ContractTerms t) {
        List<String> e = new ArrayList<>();
        if (t == null) {
            e.add("terms required");
            return e;
        }
        if (t.enforcement() != null && !t.enforcement().equals(ContractTerms.ENFORCE) && !t.enforcement().equals(ContractTerms.WARN))
            e.add("enforcement : enforce or warn");
        if (t.validity() != null) {
            LocalDate from = date(t.validity().from(), "validity.from", e), until = date(t.validity().until(), "validity.until", e);
            if (from != null && until != null && until.isBefore(from)) e.add("validity : until precedes from");
        }
        if (t.environments() != null) for (String env : t.environments()) if (env == null || !ENV.matcher(env).matches()) e.add("environments : invalid name (" + env + ")");
        if (t.services() != null) services(t.services(), e);
        planRef(t.plan(), e);
        if (t.quotas() != null) quotas(t.quotas(), "quotas", e);
        if (t.quotasByEnv() != null) t.quotasByEnv().forEach((env, q) -> {
            if (!ENV.matcher(env).matches()) e.add("quotasByEnv : invalid environment (" + env + ")");
            if (q != null) quotas(q, "quotasByEnv." + env, e);
        });
        if (t.ha() != null) ha(t.ha(), "ha", e);
        if (t.apps() != null) {
            if (t.apps().size() > MAX_APPS) e.add("apps : at most " + MAX_APPS + " overrides");
            ContractTerms.Quotas base = t.quotas() == null ? ContractTerms.Quotas.NONE : t.quotas();
            t.apps().forEach((name, o) -> {
                String p = "apps." + name;
                if (!NAME.matcher(name).matches()) e.add(p + " : invalid application name");
                if (o == null) return;
                positive(o.memoryMbPerReplica(), p + ".memoryMbPerReplica", e);
                positive(o.replicasPerApp(), p + ".replicasPerApp", e);
                nonNegative(o.scratchMbPerReplica(), p + ".scratchMbPerReplica", e);
                positive(o.distributedQuotaMbPerApp(), p + ".distributedQuotaMbPerApp", e);
                // only memoryMbPerReplica, replicasPerApp and ha may widen the contract
                if (o.scratchMbPerReplica() != null && base.scratchMbPerReplica() != null && o.scratchMbPerReplica() > base.scratchMbPerReplica())
                    e.add(p + ".scratchMbPerReplica : an override can only tighten the contract (" + base.scratchMbPerReplica() + ")");
                if (o.distributedQuotaMbPerApp() != null && base.distributedQuotaMbPerApp() != null && o.distributedQuotaMbPerApp() > base.distributedQuotaMbPerApp())
                    e.add(p + ".distributedQuotaMbPerApp : an override can only tighten the contract (" + base.distributedQuotaMbPerApp() + ")");
                if (o.ha() != null) ha(o.ha(), p + ".ha", e);
            });
        }
        if (t.pricing() != null) pricing(t.pricing(), e);
        if (t.budget() != null) {
            if (t.budget().monthly() != null && !Decimals.valid(t.budget().monthly())) e.add("budget.monthly : positive decimal expected (at most 9 decimal places)");
            if (t.budget().thresholdsPct() != null) {
                if (t.budget().thresholdsPct().size() > MAX_THRESHOLDS) e.add("budget.thresholdsPct : " + MAX_THRESHOLDS + " thresholds at most");
                for (Integer th : t.budget().thresholdsPct()) if (th == null || th < 1 || th > 500) e.add("budget.thresholdsPct : 1 to 500 %");
            }
        }
        return e;
    }

    /** Errors of the lines of a price list. */
    public static List<String> validateLines(List<PriceLine> lines) {
        List<String> e = new ArrayList<>();
        if (lines == null) {
            e.add("lines required");
            return e;
        }
        if (lines.size() > MAX_PRICE_LINES) e.add("lines : at most " + MAX_PRICE_LINES + " lines");
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            PriceLine l = lines.get(i);
            String p = "lines[" + i + "]";
            if (l == null) {
                e.add(p + " : empty line");
                continue;
            }
            UsageUnit u = unit(l.unit(), p, e);
            if (u != null) {
                dims(u, l.dims(), p, e);
                if (l.per() == null || !u.quantity().accepts(l.per())) e.add(p + ".per : " + u.quantity().perUnits().keySet() + " for " + u.name());
            }
            if (!Decimals.valid(l.price())) e.add(p + ".price : positive decimal expected (at most 9 decimal places)");
            if (!seen.add(l.unit() + "|" + (l.dims() == null ? "" : l.dims().toString()))) e.add(p + " : duplicate line (same unit and same dimensions)");
        }
        return e;
    }

    /** Contract to list link: the list exists, so does the pinned version, and the currency is the same. Errors for a 422 refusal. */
    public static List<String> validateLink(ContractTerms t, PriceList list) {
        List<String> e = new ArrayList<>();
        if (t == null || t.pricing() == null || t.pricing().priceList() == null) return e;
        ContractTerms.PriceListRef ref = t.pricing().priceList();
        if (list == null) {
            e.add("pricing.priceList : unknown list (" + ref.id() + ")");
            return e;
        }
        if (t.pricing().currency() != null && !t.pricing().currency().equals(list.currency()))
            e.add("pricing.currency : " + t.pricing().currency() + " is not the currency of list " + list.id() + " (" + list.currency() + ")");
        if (ref.version() != null && list.version(ref.version()) == null) e.add("pricing.priceList.version : unknown version (" + ref.version() + ")");
        return e;
    }

    private static void services(ContractTerms.Services s, List<String> e) {
        if (s.uses() != null) for (String u : s.uses()) {
            if (u == null || !NAME.matcher(u).matches()) e.add("services.uses : invalid name (" + u + ")");
            else if (u.equals(HIGGS)) e.add("services.uses : higgs is never allowed to a customer");
        }
        if (s.distributed() != null) for (String d : s.distributed()) if (!DISTRIBUTED.contains(d)) e.add("services.distributed : std, tmp or gdown (" + d + ")");
        if (s.routeKinds() != null) for (String k : s.routeKinds()) if (!ROUTE_KINDS.contains(k)) e.add("services.routeKinds : app, gdown or upstream (" + k + ")");
        if (s.consoles() != null) for (String c : s.consoles()) {
            if (c == null || !NAME.matcher(c).matches()) e.add("services.consoles : invalid name (" + c + ")");
            else if (s.uses() != null && !s.uses().contains(c)) e.add("services.consoles : " + c + " is not in services.uses (a console is never broader than uses)");
        }
    }

    /** Errors of a plan reference: identifier and version >= 1 (null: valid, no plan). */
    static void planRef(ContractTerms.PlanRef r, List<String> e) {
        if (r == null) return;
        if (r.id() == null || !Plan.ID.matcher(r.id()).matches()) e.add("plan.id : invalid identifier (" + r.id() + ")");
        if (r.version() == null || r.version() < 1) e.add("plan.version : integer >= 1 required");
    }

    /** Errors of a plan version's terms: the terms of a contract, without a plan of their own; {@code billing} none or usage. */
    public static List<String> validatePlan(String billing, ContractTerms t) {
        List<String> e = validate(t);
        if (t != null && t.plan() != null) e.add("plan : the terms of a plan follow no plan");
        if (!PlanVersion.BILLING_NONE.equals(billing) && !PlanVersion.BILLING_USAGE.equals(billing)) e.add("billing : none or usage");
        if (PlanVersion.BILLING_NONE.equals(billing) && t != null && t.pricing() != null) e.add("pricing : a plan with billing none has no pricing");
        return e;
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
            e.add(p + ".defaultDistributedQuotaMb : exceeds distributedQuotaMbPerApp");
    }

    private static void ha(ContractTerms.Ha h, String p, List<String> e) {
        if (h.spreadNodes() != null && (h.spreadNodes() < 1 || h.spreadNodes() > MAX_SPREAD)) e.add(p + ".spreadNodes : from 1 to " + MAX_SPREAD);
        if (h.antiAffinity() != null && !h.antiAffinity().equals(ContractTerms.Ha.PREFERRED) && !h.antiAffinity().equals(ContractTerms.Ha.REQUIRED))
            e.add(p + ".antiAffinity : preferred or required");
    }

    private static void pricing(ContractTerms.PricingTerms pr, List<String> e) {
        if (pr.currency() != null && !Decimals.CURRENCY.matcher(pr.currency()).matches()) e.add("pricing.currency : ISO 4217 code (three uppercase letters)");
        if (pr.priceList() != null) {
            if (pr.priceList().id() == null || !PriceList.ID.matcher(pr.priceList().id()).matches()) e.add("pricing.priceList.id : invalid identifier");
            boolean follow = Boolean.TRUE.equals(pr.priceList().follow());
            if (follow && pr.priceList().version() != null) e.add("pricing.priceList : version and follow are incompatible");
            if (!follow && pr.priceList().version() == null) e.add("pricing.priceList.version : required without follow (a pinned version)");
        }
        if (pr.subscription() != null) {
            decimal(pr.subscription().monthly(), "pricing.subscription.monthly", e);
            decimal(pr.subscription().perApp(), "pricing.subscription.perApp", e);
            decimal(pr.subscription().perHaApp(), "pricing.subscription.perHaApp", e);
        }
        if (pr.discountPct() != null && !Decimals.validPct(pr.discountPct())) e.add("pricing.discountPct : percentage from 0 to 100");
        decimal(pr.minimumMonthly(), "pricing.minimumMonthly", e);
        if (pr.overrides() == null) return;
        if (pr.overrides().size() > MAX_PRICE_LINES) e.add("pricing.overrides : at most " + MAX_PRICE_LINES + " lines");
        for (int i = 0; i < pr.overrides().size(); i++) {
            ContractTerms.PriceOverride o = pr.overrides().get(i);
            String p = "pricing.overrides[" + i + "]";
            if (o == null) {
                e.add(p + " : empty line");
                continue;
            }
            UsageUnit u = unit(o.unit(), p, e);
            if (u != null) {
                dims(u, o.dims(), p, e);
                if (o.per() != null && !u.quantity().accepts(o.per())) e.add(p + ".per : " + u.quantity().perUnits().keySet() + " for " + u.name());
            }
            if (o.env() != null && !ENV.matcher(o.env()).matches()) e.add(p + ".env : invalid environment");
            decimal(o.price(), p + ".price", e);
            if (o.discountPct() != null && !Decimals.validPct(o.discountPct())) e.add(p + ".discountPct : percentage from 0 to 100");
            if (o.included() != null && o.included() < 0) e.add(p + ".included : positive or zero");
            if (o.price() != null && o.tiers() != null) e.add(p + " : price and tiers are incompatible");
            if (o.price() == null && o.tiers() == null && o.included() == null && o.discountPct() == null) e.add(p + " : price, tiers, included or discountPct expected");
            if (o.tiers() != null) {
                if (o.tiers().isEmpty()) e.add(p + ".tiers : at least one tier");
                long prev = -1;
                for (int j = 0; j < o.tiers().size(); j++) {
                    ContractTerms.Tier t = o.tiers().get(j);
                    String q = p + ".tiers[" + j + "]";
                    if (t == null) {
                        e.add(q + " : empty tier");
                        continue;
                    }
                    if (!Decimals.valid(t.price())) e.add(q + ".price : positive decimal expected (at most 9 decimal places)");
                    boolean last = j == o.tiers().size() - 1;
                    if (last && t.upTo() != null) e.add(q + ".upTo : the last tier has no bound");
                    if (!last && (t.upTo() == null || t.upTo() <= prev)) e.add(q + ".upTo : strictly increasing bounds");
                    if (t.upTo() != null) prev = t.upTo();
                }
            }
        }
    }

    private static UsageUnit unit(String name, String p, List<String> e) {
        UsageUnit u = UsageCatalog.get(name);
        if (u == null) {
            e.add(p + ".unit : unit not in the catalogue (" + name + ")");
            return null;
        }
        if (!u.priceable()) {
            e.add(p + ".unit : " + name + " is not billable (" + (u.internal() ? "internal" : "indicative") + ")");
            return null;
        }
        return u;
    }

    private static void dims(UsageUnit u, Map<String, String> dims, String p, List<String> e) {
        if (dims == null) return;
        if (dims.size() > MAX_DIMS) e.add(p + ".dims : at most " + MAX_DIMS + " dimensions");
        dims.forEach((k, v) -> {
            List<String> allowed = u.dims().get(k);
            if (allowed == null) e.add(p + ".dims : " + k + " is not a dimension of " + u.name());
            else if (!allowed.contains(v)) e.add(p + ".dims." + k + " : " + allowed + " (" + v + ")");
        });
    }

    private static void decimal(String v, String p, List<String> e) {
        if (v != null && !Decimals.valid(v)) e.add(p + " : positive decimal expected (at most 9 decimal places)");
    }

    private static void positive(Long v, String p, List<String> e) {
        if (v != null && v < 1) e.add(p + " : strictly positive");
    }

    private static void nonNegative(Long v, String p, List<String> e) {
        if (v != null && v < 0) e.add(p + " : positive or zero");
    }

    private static LocalDate date(String s, String p, List<String> e) {
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException x) {
            e.add(p + " : date yyyy-mm-dd expected");
            return null;
        }
    }
}
