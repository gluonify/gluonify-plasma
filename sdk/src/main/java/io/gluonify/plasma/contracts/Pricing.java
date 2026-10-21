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
 * Pricing of a month's usage ({@code contracts.md} § 6.1): pure function, replayed identically by the calorimeter and Prism thanks to the shared test vectors
 * {@code sdk/src/test/resources/contracts/pricing-vectors.json}.
 *
 * <p>Calculation rules (all in {@link BigDecimal}, never {@code double}):
 * <ol>
 *   <li>usage is grouped by (unit, dimensions, environment); an indicative or internal unit is not priced; a unit not in the catalog is refused;</li>
 *   <li>price: the most specific contract override (dimensions contained in those of the usage, then environment), otherwise the most specific list line;
 *       an override without price or tiers takes the list price; times (1 - the override's {@code discountPct}). Without a price: line "not priced", amount 0;</li>
 *   <li>quantity converted into the unit {@code per} (override, otherwise list, otherwise base unit), rounded to 9 decimal places (half up);</li>
 *   <li>billed quantity = max(0, quantity - {@code included} x share); tier bounds are also multiplied by the share (share = fraction of the month covered
 *       by the section, rounded to 9 decimal places);</li>
 *   <li>line amount = sum of tiers (quantity in the tier x price), rounded to the minor unit of the currency (ISO 4217), half up;</li>
 *   <li>section: usage = sum of rounded lines; times (1 - the contract's {@code discountPct}), rounded; fixed part = {@code monthly} x active days / days of the month
 *       + {@code perApp} x application-days / days + {@code perHaApp} x spread application-days / days, rounded;</li>
 *   <li>total = max({@code minimumMonthly} of the last section, sum of sections (discounted usage + fixed part)).</li>
 * </ol>
 */
public final class Pricing {
    private Pricing() {}

    /** Usage quantity of the month, in the catalog's base unit (decimal string). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(String unit, Map<String, String> dims, String env, String quantity) {
        public Usage {
            dims = dims == null || dims.isEmpty() ? Map.of() : java.util.Collections.unmodifiableMap(new TreeMap<>(dims));
        }
    }

    /**
     * A section: the part of the month covered by a contract version, with its pricing terms, the lines of the price list used, the usage of the
     * section and the days of the fixed part.
     *
     * @param share     fraction of the month (0 to 1): prorates {@code included} and the tiers; absent: 1
     * @param pricing   pricing terms of the version; absent: list only, no fixed part
     * @param list      lines of the price list version; absent: none
     * @param usage     quantities of the section
     * @param activeDays active days of the organization in the section (fixed part {@code monthly}); absent: 0
     * @param appDays   applications x days ({@code perApp} part); absent: 0
     * @param haAppDays applications spread over at least 2 nodes x days ({@code perHaApp} part); absent: 0
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

    /** Priced line ({@code priced} false: unit not priced, shown with a zero amount). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Line(String unit, Map<String, String> dims, String env, String quantity, String per, String included, List<TierAmount> tiers, String amount, Boolean priced) {
        /** Key of a line: {@code unit} followed by the sorted dimensions {@code {k=v}} and by {@code @env}. */
        public String key() { return Pricing.key(unit, dims, env); }
    }

    /** Result of a section. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SectionResult(Integer contractVersion, List<Line> lines, String usage, String usageDiscounted, String fixed) {}

    /** Priced statement of the month. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Statement(String currency, List<SectionResult> sections, String usage, String fixed, String minimum, String total) {}

    /** Key of a usage line (grouping and comparison of the test vectors). */
    public static String key(final String unit, final Map<String, String> dims, final String env) {
        return unit + (dims == null || dims.isEmpty() ? "" : new TreeMap<>(dims).toString()) + (env == null ? "" : "@" + env);
    }

    /** Prices a month ({@code daysInMonth}: 28 to 31) in the currency {@code currency}. */
    public static Statement value(final String currency, final int daysInMonth, final List<Section> sections) {
        if (daysInMonth < 28 || daysInMonth > 31) throw new IllegalArgumentException("daysInMonth: 28 to 31");
        if (currency == null || !Decimals.CURRENCY.matcher(currency).matches()) throw new IllegalArgumentException("devise ISO 4217 attendue");
        List<SectionResult> out = new ArrayList<>();
        BigDecimal usage = BigDecimal.ZERO;
        BigDecimal fixed = BigDecimal.ZERO;
        BigDecimal minimum = BigDecimal.ZERO;
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

    private static SectionResult section(final String currency, final int days, final Section s) {
        BigDecimal share = s.share() == null ? BigDecimal.ONE : Decimals.quantity(new BigDecimal(s.share()));
        if (share.signum() < 0 || share.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("share: from 0 to 1");
        ContractTerms.PricingTerms pr = s.pricing();
        // grouping of the usage, in order of first appearance
        Map<String, Usage> grouped = new java.util.LinkedHashMap<>();
        for (Usage u : s.usage()) {
            UsageUnit unit = UsageCatalog.get(u.unit());
            if (unit == null) throw new IllegalArgumentException("unit not in the catalog: " + u.unit());
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

    private static BigDecimal part(final String price, final String count, final BigDecimal days) {
        if (price == null || count == null) return BigDecimal.ZERO;
        return new BigDecimal(price).multiply(new BigDecimal(count)).divide(days, Decimals.QUANTITY_SCALE, RoundingMode.HALF_UP);
    }

    private static Line line(final String currency, final BigDecimal share, final Usage u, final ContractTerms.PricingTerms pr, final List<PriceLine> list) {
        UsageUnit unit = UsageCatalog.get(u.unit());
        ContractTerms.PriceOverride o = pr == null || pr.overrides() == null ? null : bestOverride(pr.overrides(), u);
        PriceLine l = bestLine(list, u);
        String per = perOf(o, l, unit);
        BigDecimal q = Decimals.quantity(unit.quantity().convert(Decimals.of(u.quantity()), per));
        BigDecimal included = includedOf(o, share);
        BigDecimal billed = q.subtract(included).max(BigDecimal.ZERO);
        BigDecimal keep = Decimals.keep(o == null ? null : o.discountPct());
        List<TierAmount> tiers = new ArrayList<>();
        BigDecimal amount = BigDecimal.ZERO;
        boolean priced;
        if (o != null && o.tiers() != null) {
            priced = true;
            amount = tieredAmount(o.tiers(), billed, share, keep, tiers);
        } else {
            String base = baseOf(o, l);
            priced = base != null;
            if (priced) amount = flatAmount(base, billed, keep, tiers);
        }
        return new Line(u.unit(), u.dims().isEmpty() ? null : u.dims(), u.env(), Decimals.plain(q), per, included.signum() == 0 ? null : Decimals.plain(included),
                tiers.isEmpty() ? null : tiers, money(amount, currency), priced);
    }

    private static String perOf(final ContractTerms.PriceOverride o, final PriceLine l, final UsageUnit unit) {
        if (o != null && o.per() != null) return o.per();
        return l != null ? l.per() : unit.quantity().base();
    }

    private static BigDecimal includedOf(final ContractTerms.PriceOverride o, final BigDecimal share) {
        if (o == null || o.included() == null) return BigDecimal.ZERO;
        return Decimals.quantity(BigDecimal.valueOf(o.included()).multiply(share));
    }

    private static String baseOf(final ContractTerms.PriceOverride o, final PriceLine l) {
        if (o != null && o.price() != null) return o.price();
        return l != null ? l.price() : null;
    }

    /** The amount of a tiered price: each tier prices the part of the billed quantity that falls in it; the tiers are appended to {@code out}. */
    private static BigDecimal tieredAmount(final List<ContractTerms.Tier> tierDefs, final BigDecimal billed, final BigDecimal share, final BigDecimal keep, final List<TierAmount> out) {
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal low = BigDecimal.ZERO;
        for (ContractTerms.Tier t : tierDefs) {
            BigDecimal high = t.upTo() == null ? null : Decimals.quantity(BigDecimal.valueOf(t.upTo()).multiply(share));
            BigDecimal inTier = (high == null ? billed : billed.min(high)).subtract(low).max(BigDecimal.ZERO);
            BigDecimal price = new BigDecimal(t.price()).multiply(keep);
            BigDecimal a = inTier.multiply(price);
            out.add(new TierAmount(Decimals.plain(inTier), Decimals.plain(price), Decimals.plain(a)));
            amount = amount.add(a);
            if (high == null) break;
            low = high;
        }
        return amount;
    }

    /** The amount of a flat price; the single tier is appended to {@code out}. */
    private static BigDecimal flatAmount(final String base, final BigDecimal billed, final BigDecimal keep, final List<TierAmount> out) {
        BigDecimal price = new BigDecimal(base).multiply(keep);
        BigDecimal amount = billed.multiply(price);
        out.add(new TierAmount(Decimals.plain(billed), Decimals.plain(price), Decimals.plain(amount)));
        return amount;
    }

    /** Most specific override: dimensions contained in those of the usage, environment equal or absent; on a tie, the first. */
    static ContractTerms.PriceOverride bestOverride(final List<ContractTerms.PriceOverride> overrides, final Usage u) {
        ContractTerms.PriceOverride best = null;
        int bestScore = -1;
        for (ContractTerms.PriceOverride o : overrides) {
            if (!o.unit().equals(u.unit()) || !within(o.dims(), u.dims()) || o.env() != null && !o.env().equals(u.env())) continue;
            int score = (o.dims() == null ? 0 : o.dims().size() * 2) + (o.env() == null ? 0 : 1);
            if (score > bestScore) {
                best = o;
                bestScore = score;
            }
        }
        return best;
    }

    /** Most specific list line. */
    static PriceLine bestLine(final List<PriceLine> lines, final Usage u) {
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

    private static boolean within(final Map<String, String> dims, final Map<String, String> of) {
        if (dims == null) return true;
        for (var e : dims.entrySet()) if (!e.getValue().equals(of.get(e.getKey()))) return false;
        return true;
    }

    private static String money(final BigDecimal v, final String currency) {
        return Decimals.money(v, currency).toPlainString();
    }
}
