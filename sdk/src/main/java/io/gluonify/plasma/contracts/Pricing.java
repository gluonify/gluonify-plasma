package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.gluonify.plasma.usage.UsageCatalog;
import io.gluonify.plasma.usage.UsageUnit;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Valorisation de l'usage d'un mois ({@code contracts.md} § 6.1) : fonction pure, rejouée à l'identique par le calorimètre et Prism grâce au jeu d'essai commun
 * {@code sdk/src/test/resources/contracts/pricing-vectors.json}.
 *
 * <p>Règles de calcul (toutes en {@link BigDecimal}, jamais en {@code double}) :
 * <ol>
 *   <li>l'usage est regroupé par (unité, dimensions, environnement) ; une unité indicative ou interne n'est pas valorisée ; une unité hors catalogue est refusée ;</li>
 *   <li>prix : la surcharge du contrat la plus précise (dimensions comprises dans celles de l'usage, puis environnement), sinon la ligne de liste la plus précise ;
 *       une surcharge sans prix ni paliers reprend le prix de la liste ; × (1 − {@code discountPct} de la surcharge). Sans prix : ligne « non tarifée », montant 0 ;</li>
 *   <li>quantité convertie dans la grandeur {@code per} (surcharge, sinon liste, sinon grandeur de base), arrondie à 9 décimales (demi supérieur) ;</li>
 *   <li>quantité facturée = max(0, quantité − {@code included} × part) ; les bornes des paliers sont aussi multipliées par la part (part = fraction du mois couverte
 *       par la section, arrondie à 9 décimales) ;</li>
 *   <li>montant de la ligne = Σ paliers (quantité dans le palier × prix), arrondi à l'unité mineure de la devise (ISO 4217), demi supérieur ;</li>
 *   <li>section : usage = Σ lignes arrondies ; × (1 − {@code discountPct} du contrat), arrondi ; part fixe = {@code monthly} × jours actifs ÷ jours du mois
 *       + {@code perApp} × applications·jours ÷ jours + {@code perHaApp} × applications réparties·jours ÷ jours, arrondie ;</li>
 *   <li>total = max({@code minimumMonthly} de la dernière section, Σ sections (usage remisé + part fixe)).</li>
 * </ol>
 */
public final class Pricing {
    private Pricing() {}

    /** Quantité d'usage du mois, dans la grandeur de base du catalogue (chaîne décimale). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(String unit, Map<String, String> dims, String env, String quantity) {
        public Usage {
            dims = dims == null || dims.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
        }
    }

    /**
     * Une section : la part du mois couverte par une version de contrat, avec ses termes de tarification, les lignes de la liste de prix employée, l'usage de la
     * section et les jours de la part fixe.
     *
     * @param share     fraction du mois (0 à 1) : proratise {@code included} et les paliers ; absente : 1
     * @param pricing   termes de tarification de la version ; absents : liste seule, sans part fixe
     * @param list      lignes de la version de liste de prix ; absentes : aucune
     * @param usage     quantités de la section
     * @param activeDays jours actifs de l'organisation dans la section (part fixe {@code monthly}) ; absent : 0
     * @param appDays   applications × jours (part {@code perApp}) ; absent : 0
     * @param haAppDays applications réparties sur au moins 2 nœuds × jours (part {@code perHaApp}) ; absent : 0
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(Integer contractVersion, String share, ContractTerms.PricingTerms pricing, List<PriceLine> list, List<Usage> usage,
                         String activeDays, String appDays, String haAppDays) {
        public Section {
            list = list == null ? List.of() : List.copyOf(list);
            usage = usage == null ? List.of() : List.copyOf(usage);
        }
    }

    /** Montant d'un palier. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TierAmount(String quantity, String price, String amount) {}

    /** Ligne valorisée ({@code priced} faux : unité non tarifée, montrée avec un montant nul). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String unit, Map<String, String> dims, String env, String quantity, String per, String included, List<TierAmount> tiers, String amount, Boolean priced) {
        /** Clé d'une ligne : {@code unit} suivie des dimensions triées {@code {k=v}} et de {@code @env}. */
        public String key() { return Pricing.key(unit, dims, env); }
    }

    /** Résultat d'une section. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SectionResult(Integer contractVersion, List<Line> lines, String usage, String usageDiscounted, String fixed) {}

    /** Relevé valorisé du mois. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statement(String currency, List<SectionResult> sections, String usage, String fixed, String minimum, String total) {}

    /** Clé d'une ligne d'usage (regroupement et comparaison des jeux d'essai). */
    public static String key(String unit, Map<String, String> dims, String env) {
        return unit + (dims == null || dims.isEmpty() ? "" : new TreeMap<>(dims).toString()) + (env == null ? "" : "@" + env);
    }

    /** Valorise un mois ({@code daysInMonth} : 28 à 31) dans la devise {@code currency}. */
    public static Statement value(String currency, int daysInMonth, List<Section> sections) {
        if (daysInMonth < 28 || daysInMonth > 31) throw new IllegalArgumentException("daysInMonth : 28 à 31");
        if (currency == null || !Decimals.CURRENCY.matcher(currency).matches()) throw new IllegalArgumentException("devise ISO 4217 attendue");
        List<SectionResult> out = new ArrayList<>();
        BigDecimal usage = BigDecimal.ZERO, fixed = BigDecimal.ZERO, minimum = BigDecimal.ZERO;
        for (Section s : sections) {
            SectionResult r = section(currency, daysInMonth, s);
            out.add(r);
            usage = usage.add(new BigDecimal(r.usageDiscounted()));
            fixed = fixed.add(new BigDecimal(r.fixed()));
            if (s.pricing() != null && s.pricing().minimumMonthly() != null) minimum = new BigDecimal(s.pricing().minimumMonthly());
            else if (s.pricing() != null) minimum = BigDecimal.ZERO;
        }
        BigDecimal total = usage.add(fixed).max(minimum);
        return new Statement(currency, out, money(usage, currency), money(fixed, currency), money(minimum, currency), money(total, currency));
    }

    private static SectionResult section(String currency, int days, Section s) {
        BigDecimal share = s.share() == null ? BigDecimal.ONE : Decimals.quantity(new BigDecimal(s.share()));
        if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("share : de 0 à 1");
        ContractTerms.PricingTerms pr = s.pricing();
        // regroupement de l'usage, dans l'ordre de première apparition
        Map<String, Usage> grouped = new java.util.LinkedHashMap<>();
        for (Usage u : s.usage()) {
            UsageUnit unit = UsageCatalog.get(u.unit());
            if (unit == null) throw new IllegalArgumentException("unité hors catalogue : " + u.unit());
            if (!unit.priceable()) continue;
            String k = key(u.unit(), u.dims(), u.env());
            Usage prev = grouped.get(k);
            BigDecimal q = Decimals.of(u.quantity()).add(prev == null ? BigDecimal.ZERO : Decimals.of(prev.quantity()));
            grouped.put(k, new Usage(u.unit(), u.dims(), u.env(), q.toPlainString()));
        }
        List<Line> lines = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Usage u : grouped.values()) {
            Line l = line(currency, share, u, pr, s.list());
            lines.add(l);
            sum = sum.add(new BigDecimal(l.amount()));
        }
        BigDecimal discounted = sum.multiply(Decimals.keep(pr == null ? null : pr.discountPct()));
        BigDecimal fixed = BigDecimal.ZERO;
        if (pr != null && pr.subscription() != null) {
            BigDecimal d = BigDecimal.valueOf(days);
            fixed = fixed.add(part(pr.subscription().monthly(), s.activeDays(), d)).add(part(pr.subscription().perApp(), s.appDays(), d))
                    .add(part(pr.subscription().perHaApp(), s.haAppDays(), d));
        }
        return new SectionResult(s.contractVersion(), lines, money(sum, currency), money(discounted, currency), money(fixed, currency));
    }

    private static BigDecimal part(String price, String count, BigDecimal days) {
        if (price == null || count == null) return BigDecimal.ZERO;
        return new BigDecimal(price).multiply(new BigDecimal(count)).divide(days, Decimals.QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    private static Line line(String currency, BigDecimal share, Usage u, ContractTerms.PricingTerms pr, List<PriceLine> list) {
        UsageUnit unit = UsageCatalog.get(u.unit());
        ContractTerms.PriceOverride o = pr == null || pr.overrides() == null ? null : bestOverride(pr.overrides(), u);
        PriceLine l = bestLine(list, u);
        String per = o != null && o.per() != null ? o.per() : l != null ? l.per() : unit.quantity().base();
        BigDecimal q = Decimals.quantity(unit.quantity().convert(Decimals.of(u.quantity()), per));
        BigDecimal included = o == null || o.included() == null ? BigDecimal.ZERO : Decimals.quantity(BigDecimal.valueOf(o.included()).multiply(share));
        BigDecimal billed = q.subtract(included).max(BigDecimal.ZERO);
        BigDecimal keep = Decimals.keep(o == null ? null : o.discountPct());
        List<TierAmount> tiers = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        boolean priced;
        if (o != null && o.tiers() != null) {
            priced = true;
            BigDecimal low = BigDecimal.ZERO;
            for (ContractTerms.Tier t : o.tiers()) {
                BigDecimal high = t.upTo() == null ? null : Decimals.quantity(BigDecimal.valueOf(t.upTo()).multiply(share));
                BigDecimal inTier = (high == null ? billed : billed.min(high)).subtract(low).max(BigDecimal.ZERO);
                BigDecimal price = new BigDecimal(t.price()).multiply(keep);
                BigDecimal a = inTier.multiply(price);
                tiers.add(new TierAmount(Decimals.plain(inTier), Decimals.plain(price), Decimals.plain(a)));
                amount = amount.add(a);
                if (high == null) break;
                low = high;
            }
        } else {
            String base = o != null && o.price() != null ? o.price() : l != null ? l.price() : null;
            priced = base != null;
            if (priced) {
                BigDecimal price = new BigDecimal(base).multiply(keep);
                amount = billed.multiply(price);
                tiers.add(new TierAmount(Decimals.plain(billed), Decimals.plain(price), Decimals.plain(amount)));
            }
        }
        return new Line(u.unit(), u.dims().isEmpty() ? null : u.dims(), u.env(), Decimals.plain(q), per, included.signum() == 0 ? null : Decimals.plain(included),
                tiers.isEmpty() ? null : tiers, money(amount, currency), priced);
    }

    /** Surcharge la plus précise : dimensions comprises dans celles de l'usage, environnement égal ou absent ; à égalité, la première. */
    static ContractTerms.PriceOverride bestOverride(List<ContractTerms.PriceOverride> overrides, Usage u) {
        ContractTerms.PriceOverride best = null;
        int bestScore = -1;
        for (ContractTerms.PriceOverride o : overrides) {
            if (!o.unit().equals(u.unit()) || !within(o.dims(), u.dims())) continue;
            if (o.env() != null && !o.env().equals(u.env())) continue;
            int score = (o.dims() == null ? 0 : o.dims().size() * 2) + (o.env() == null ? 0 : 1);
            if (score > bestScore) {
                best = o;
                bestScore = score;
            }
        }
        return best;
    }

    /** Ligne de liste la plus précise. */
    static PriceLine bestLine(List<PriceLine> lines, Usage u) {
        PriceLine best = null;
        int bestScore = -1;
        for (PriceLine l : lines) {
            if (!l.unit().equals(u.unit()) || !within(l.dims(), u.dims())) continue;
            int score = l.dims() == null ? 0 : l.dims().size();
            if (score > bestScore) {
                best = l;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean within(Map<String, String> dims, Map<String, String> of) {
        if (dims == null) return true;
        for (var e : dims.entrySet()) if (!e.getValue().equals(of.get(e.getKey()))) return false;
        return true;
    }

    private static String money(BigDecimal v, String currency) {
        return Decimals.money(v, currency).toPlainString();
    }
}
