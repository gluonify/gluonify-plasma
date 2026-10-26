package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.contracts.ContractTerms.AppOverride;
import io.gluonify.plasma.contracts.ContractTerms.Budget;
import io.gluonify.plasma.contracts.ContractTerms.Ha;
import io.gluonify.plasma.contracts.ContractTerms.PlanRef;
import io.gluonify.plasma.contracts.ContractTerms.PriceListRef;
import io.gluonify.plasma.contracts.ContractTerms.PriceOverride;
import io.gluonify.plasma.contracts.ContractTerms.PricingTerms;
import io.gluonify.plasma.contracts.ContractTerms.Quotas;
import io.gluonify.plasma.contracts.ContractTerms.Services;
import io.gluonify.plasma.contracts.ContractTerms.Subscription;
import io.gluonify.plasma.contracts.ContractTerms.Tier;
import io.gluonify.plasma.contracts.ContractTerms.Validity;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Every rule of the contract validator, one refusal each: what a customer's terms, a price list's lines or a plan may not say. */
class TermsValidatorGapsTest {

    static ContractTerms terms(final Validity validity, final String enforcement, final List<String> environments, final Services services, final Quotas quotas,
            final Map<String, Quotas> quotasByEnv, final Ha ha, final Map<String, AppOverride> apps, final PricingTerms pricing, final Budget budget, final PlanRef plan) {
        return new ContractTerms(validity, enforcement, environments, services, quotas, quotasByEnv, ha, apps, pricing, budget, plan);
    }

    static ContractTerms withQuotas(final Quotas q) {
        return terms(null, null, null, null, q, null, null, null, null, null, null);
    }

    static ContractTerms withApps(final Quotas base, final Map<String, AppOverride> apps) {
        return terms(null, null, null, null, base, null, null, apps, null, null, null);
    }

    static ContractTerms withPricing(final PricingTerms p) {
        return terms(null, null, null, null, null, null, null, null, p, null, null);
    }

    static ContractTerms withBudget(final Budget b) {
        return terms(null, null, null, null, null, null, null, null, null, b, null);
    }

    static ContractTerms withServices(final Services s) {
        return terms(null, null, null, s, null, null, null, null, null, null, null);
    }

    static boolean has(final List<String> errors, final String fragment) {
        return errors.stream().anyMatch(e -> e.contains(fragment));
    }

    static void assertRefuses(final ContractTerms t, final String fragment) {
        final List<String> errors = TermsValidator.validate(t);
        assertTrue(has(errors, fragment), fragment + " expected in " + errors);
    }

    static Quotas quotas(final Long apps, final Long reserved, final Long replicas, final Long memory, final Long scratch, final Long distributed, final Long defaultDistributed, final Long storage, final Long domains) {
        return new Quotas(apps, reserved, replicas, memory, scratch, distributed, defaultDistributed, storage, domains);
    }

    static PriceOverride override(final String unit, final Map<String, String> dims, final String env, final String per, final String price, final Long included, final List<Tier> tiers, final String discount) {
        return new PriceOverride(unit, dims, env, per, price, included, tiers, discount);
    }

    // --- terms ---

    @Test
    void anAbsentTermsObjectIsRefused() {
        assertEquals(List.of("terms required"), TermsValidator.validate(null));
        assertEquals(List.of(), TermsValidator.validate(withQuotas(null)));
    }

    @Test
    void enforcementEnvironmentsAndValidity() {
        assertRefuses(terms(null, "sometimes", null, null, null, null, null, null, null, null, null), "enforcement : enforce or warn");
        assertEquals(List.of(), TermsValidator.validate(terms(null, "warn", null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of(), TermsValidator.validate(terms(null, "enforce", null, null, null, null, null, null, null, null, null)));
        assertRefuses(terms(null, null, List.of("SBX", "lower"), null, null, null, null, null, null, null, null), "environments : invalid name (lower)");
        assertRefuses(terms(null, null, List.of("lower"), null, null, null, null, null, null, null, null), "environments : invalid name (lower)");
        assertRefuses(terms(new Validity("2026-13-01", null), null, null, null, null, null, null, null, null, null, null), "validity.from : date yyyy-mm-dd expected");
        assertRefuses(terms(new Validity("2026-01-01", "garbage"), null, null, null, null, null, null, null, null, null, null), "validity.until : date yyyy-mm-dd expected");
        assertRefuses(terms(new Validity("2026-06-01", "2026-05-31"), null, null, null, null, null, null, null, null, null, null), "validity : until precedes from");
        assertEquals(List.of(), TermsValidator.validate(terms(new Validity("2026-06-01", "2026-06-01"), null, null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of(), TermsValidator.validate(terms(new Validity(null, null), null, null, null, null, null, null, null, null, null, null)));
        assertEquals(List.of(), TermsValidator.validate(terms(new Validity("2026-06-01", null), null, null, null, null, null, null, null, null, null, null)));
    }

    @Test
    void servicesAreCheckedByKind() {
        final List<String> errors = TermsValidator.validate(withServices(new Services(List.of("good", "Bad Name", "higgs"), null, List.of("std", "floppy"), null, null, null, null, List.of("app", "ftp"), null, List.of("good", "other", "BAD"))));
        assertTrue(has(errors, "services.uses : invalid name (Bad Name)"), errors.toString());
        assertTrue(has(errors, "higgs is never allowed to a customer"), errors.toString());
        assertTrue(has(errors, "services.distributed : std, tmp or gdown (floppy)"), errors.toString());
        assertTrue(has(errors, "services.routeKinds : app, gdown or upstream (ftp)"), errors.toString());
        assertTrue(has(errors, "services.consoles : other is not in services.uses"), errors.toString());
        assertTrue(has(errors, "services.consoles : invalid name (BAD)"), errors.toString());
        assertEquals(List.of(), TermsValidator.validate(withServices(new Services(null, null, null, null, null, null, null, null, null, List.of("anything")))), "consoles without uses: no limit to compare with");
    }

    @Test
    void quotasMustBePositiveOrNonNegativeAsEachFieldSays() {
        final Long bad = -1L;
        final Long zero = 0L;
        final List<String> errors = TermsValidator.validate(withQuotas(quotas(bad, bad, zero, zero, bad, zero, zero, bad, bad)));
        for (final String field : new String[]{"apps : positive or zero", "reservedMemoryMb : positive or zero", "replicasPerApp : strictly positive", "memoryMbPerReplica : strictly positive",
                "scratchMbPerReplica : positive or zero", "distributedQuotaMbPerApp : strictly positive", "defaultDistributedQuotaMb : strictly positive", "storageMb : positive or zero", "domains : positive or zero"}) {
            assertTrue(has(errors, "quotas." + field), field + " in " + errors);
        }
        assertEquals(List.of(), TermsValidator.validate(withQuotas(quotas(zero, zero, 1L, 1L, zero, 1L, 1L, zero, zero))));
        assertRefuses(withQuotas(quotas(null, null, null, null, null, 10L, 20L, null, null)), "defaultDistributedQuotaMb : exceeds distributedQuotaMbPerApp");
        assertEquals(List.of(), TermsValidator.validate(withQuotas(quotas(null, null, null, null, null, 20L, 10L, null, null))));
    }

    @Test
    void quotasPerEnvironmentAreCheckedToo() {
        final Map<String, Quotas> byEnv = new HashMap<>();
        byEnv.put("lower", null);
        byEnv.put("PRD", quotas(-1L, null, null, null, null, null, null, null, null));
        byEnv.put("QUA", null);
        final List<String> errors = TermsValidator.validate(terms(null, null, null, null, null, byEnv, null, null, null, null, null));
        assertTrue(has(errors, "quotasByEnv : invalid environment (lower)"), errors.toString());
        assertTrue(has(errors, "quotasByEnv.PRD.apps : positive or zero"), errors.toString());
        assertEquals(2, errors.size(), "a null quota set for an environment is no error: " + errors);
    }

    @Test
    void spreadAndAntiAffinity() {
        assertRefuses(terms(null, null, null, null, null, null, new Ha(0, null), null, null, null, null), "ha.spreadNodes : from 1 to 8");
        assertRefuses(terms(null, null, null, null, null, null, new Ha(9, null), null, null, null, null), "ha.spreadNodes : from 1 to 8");
        assertRefuses(terms(null, null, null, null, null, null, new Ha(2, "always"), null, null, null, null), "ha.antiAffinity : preferred or required");
        assertEquals(List.of(), TermsValidator.validate(terms(null, null, null, null, null, null, new Ha(8, "preferred"), null, null, null, null)));
        assertEquals(List.of(), TermsValidator.validate(terms(null, null, null, null, null, null, new Ha(null, "required"), null, null, null, null)));
        assertEquals(List.of(), TermsValidator.validate(terms(null, null, null, null, null, null, new Ha(1, null), null, null, null, null)));
    }

    @Test
    void applicationOverridesMayWidenOnlyWhatTheyAreAllowedTo() {
        final Quotas base = quotas(null, null, null, null, 100L, 1000L, null, null, null);
        final Map<String, AppOverride> apps = new java.util.LinkedHashMap<>();
        apps.put("Bad_Name", new AppOverride(null, null, null, null, null));
        apps.put("empty", null);
        apps.put("bad-numbers", new AppOverride(0L, 0L, -1L, 0L, new Ha(99, "x")));
        apps.put("widen", new AppOverride(8192L, 9L, 200L, 2000L, new Ha(2, null)));
        apps.put("tighten", new AppOverride(null, null, 50L, 500L, null));
        final List<String> errors = TermsValidator.validate(withApps(base, apps));
        assertTrue(has(errors, "apps.Bad_Name : invalid application name"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.memoryMbPerReplica : strictly positive"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.replicasPerApp : strictly positive"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.scratchMbPerReplica : positive or zero"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.distributedQuotaMbPerApp : strictly positive"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.ha.spreadNodes : from 1 to 8"), errors.toString());
        assertTrue(has(errors, "apps.bad-numbers.ha.antiAffinity : preferred or required"), errors.toString());
        assertTrue(has(errors, "apps.widen.scratchMbPerReplica : an override can only tighten the contract (100)"), errors.toString());
        assertTrue(has(errors, "apps.widen.distributedQuotaMbPerApp : an override can only tighten the contract (1000)"), errors.toString());
        assertFalse(has(errors, "apps.tighten"), errors.toString());
        assertFalse(has(errors, "apps.widen.memoryMbPerReplica"), "memory and replicas may widen: " + errors);
        assertFalse(has(errors, "apps.empty"), errors.toString());
    }

    @Test
    void anOverrideWithoutContractQuotasHasNothingToTighten() {
        final Map<String, AppOverride> apps = Map.of("shop", new AppOverride(null, null, 5000L, 5000L, null));
        assertEquals(List.of(), TermsValidator.validate(withApps(null, apps)));
    }

    @Test
    void tooManyOverridesAreRefused() {
        final Map<String, AppOverride> apps = new java.util.TreeMap<>();
        for (int i = 0; i <= TermsValidator.MAX_APPS; i++) {
            apps.put("app" + i, new AppOverride(null, null, null, null, null));
        }
        assertRefuses(withApps(null, apps), "apps : at most 64 overrides");
    }

    @Test
    void budgetAmountAndThresholds() {
        assertRefuses(withBudget(new Budget("-5", null, null)), "budget.monthly : positive decimal expected");
        assertEquals(List.of(), TermsValidator.validate(withBudget(new Budget("100.50", List.of(50, 100, 500), true))));
        assertEquals(List.of(), TermsValidator.validate(withBudget(new Budget(null, null, null))));
        assertRefuses(withBudget(new Budget(null, List.of(1, 2, 3, 4, 5, 6), null)), "budget.thresholdsPct : 5 thresholds at most");
        assertRefuses(withBudget(new Budget(null, List.of(0, 501, 50), null)), "budget.thresholdsPct : 1 to 500 %");
        assertEquals(2, TermsValidator.validate(withBudget(new Budget(null, List.of(0, 501, 50), null))).size());
    }

    // --- plan reference and plans ---

    @Test
    void aPlanReferenceNeedsAnIdentifierAndAVersion() {
        assertRefuses(terms(null, null, null, null, null, null, null, null, null, null, new PlanRef(null, 1)), "plan.id : invalid identifier (null)");
        assertRefuses(terms(null, null, null, null, null, null, null, null, null, null, new PlanRef("Bad Id", 1)), "plan.id : invalid identifier (Bad Id)");
        assertRefuses(terms(null, null, null, null, null, null, null, null, null, null, new PlanRef("developer", null)), "plan.version : integer >= 1 required");
        assertRefuses(terms(null, null, null, null, null, null, null, null, null, null, new PlanRef("developer", 0)), "plan.version : integer >= 1 required");
        assertEquals(List.of(), TermsValidator.validate(terms(null, null, null, null, null, null, null, null, null, null, new PlanRef("developer", 3))));
    }

    @Test
    void thePricingOfAPlanFollowsItsBillingMode() {
        final ContractTerms priced = withPricing(new PricingTerms("CHF", null, null, null, null, null));
        assertEquals(List.of(), TermsValidator.validatePlan("usage", priced));
        assertTrue(has(TermsValidator.validatePlan("none", priced), "a plan with billing none has no pricing"));
        assertEquals(List.of(), TermsValidator.validatePlan("none", withQuotas(null)));
        assertTrue(has(TermsValidator.validatePlan("monthly", withQuotas(null)), "billing : none or usage"));
        assertTrue(has(TermsValidator.validatePlan(null, withQuotas(null)), "billing : none or usage"));
        assertTrue(has(TermsValidator.validatePlan("usage", terms(null, null, null, null, null, null, null, null, null, null, new PlanRef("other", 1))), "plan : the terms of a plan follow no plan"));
        assertTrue(has(TermsValidator.validatePlan("usage", null), "terms required"));
        assertTrue(has(TermsValidator.validatePlan("none", null), "terms required"));
    }

    // --- pricing ---

    @Test
    void pricingCurrencyListSubscriptionAndDiscount() {
        assertRefuses(withPricing(new PricingTerms("chf", null, null, null, null, null)), "pricing.currency : ISO 4217 code");
        assertRefuses(withPricing(new PricingTerms(null, new PriceListRef(null, 1, null), null, null, null, null)), "pricing.priceList.id : invalid identifier");
        assertRefuses(withPricing(new PricingTerms(null, new PriceListRef("Bad", 1, null), null, null, null, null)), "pricing.priceList.id : invalid identifier");
        assertRefuses(withPricing(new PricingTerms(null, new PriceListRef("list", 2, true), null, null, null, null)), "version and follow are incompatible");
        assertRefuses(withPricing(new PricingTerms(null, new PriceListRef("list", null, null), null, null, null, null)), "pricing.priceList.version : required without follow");
        assertRefuses(withPricing(new PricingTerms(null, new PriceListRef("list", null, false), null, null, null, null)), "pricing.priceList.version : required without follow");
        assertEquals(List.of(), TermsValidator.validate(withPricing(new PricingTerms("CHF", new PriceListRef("list", null, true), null, null, null, null))));
        assertEquals(List.of(), TermsValidator.validate(withPricing(new PricingTerms("CHF", new PriceListRef("list", 3, false), null, null, null, null))));
        final List<String> amounts = TermsValidator.validate(withPricing(new PricingTerms(null, null, new Subscription("x", "-1", "1.1234567890"), "101", "abc", null)));
        assertTrue(has(amounts, "pricing.subscription.monthly : positive decimal"), amounts.toString());
        assertTrue(has(amounts, "pricing.subscription.perApp : positive decimal"), amounts.toString());
        assertTrue(has(amounts, "pricing.subscription.perHaApp : positive decimal"), amounts.toString());
        assertTrue(has(amounts, "pricing.discountPct : percentage from 0 to 100"), amounts.toString());
        assertTrue(has(amounts, "pricing.minimumMonthly : positive decimal"), amounts.toString());
        assertEquals(List.of(), TermsValidator.validate(withPricing(new PricingTerms(null, null, new Subscription(null, null, null), "100", "0", null))));
    }

    @Test
    void priceOverridesNameCatalogueUnitsDimensionsAndAmounts() {
        final List<PriceOverride> list = new ArrayList<>();
        list.add(override("no.such.unit", null, null, null, "1", null, null, null));
        list.add(override("strange.instance.memory_used", null, null, null, "1", null, null, null));
        list.add(override("bup.jobs.seconds", null, null, null, "1", null, null, null));
        list.add(override("strange.instance.cpu", Map.of("a", "1", "b", "2", "c", "3", "d", "4"), null, "decade", "1", null, null, null));
        list.add(override("strange.instance.memory_reserved", Map.of("state", "dormant", "colour", "red"), "lower", null, "1", null, null, null));
        list.add(override("strange.instance.cpu", null, null, null, "-1", -5L, List.of(new Tier(1L, "1")), "150"));
        list.add(override("strange.instance.cpu", null, null, null, null, null, null, null));
        final List<String> errors = TermsValidator.validate(withPricing(new PricingTerms(null, null, null, null, null, list)));
        assertTrue(has(errors, "pricing.overrides[0].unit : unit not in the catalogue (no.such.unit)"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[1].unit : strange.instance.memory_used is not billable (indicative)"), errors.toString());
        assertTrue(has(errors, "is not billable (internal)"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[3].dims : at most 3 dimensions"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[3].per : "), errors.toString());
        assertTrue(has(errors, "pricing.overrides[4].dims : colour is not a dimension of strange.instance.memory_reserved"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[4].dims.state : [active, frozen] (dormant)"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[4].env : invalid environment"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[5].price : positive decimal"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[5].discountPct : percentage from 0 to 100"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[5].included : positive or zero"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[5] : price and tiers are incompatible"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[5].tiers[0].upTo : the last tier has no bound"), errors.toString());
        assertTrue(has(errors, "pricing.overrides[6] : price, tiers, included or discountPct expected"), errors.toString());
    }

    @Test
    void tooManyPriceOverridesAreRefused() {
        final List<PriceOverride> list = new ArrayList<>();
        for (int i = 0; i <= TermsValidator.MAX_PRICE_LINES; i++) {
            list.add(override("strange.instance.cpu", null, null, null, "1", null, null, null));
        }
        assertRefuses(withPricing(new PricingTerms(null, null, null, null, null, list)), "pricing.overrides : at most 200 lines");
    }

    @Test
    void tiersMustGrowAndEndWithoutABound() {
        final List<String> empty = TermsValidator.validate(withPricing(new PricingTerms(null, null, null, null, null, List.of(override("strange.instance.cpu", null, null, null, null, null, List.of(), null)))));
        assertTrue(has(empty, "tiers : at least one tier"), empty.toString());
        final List<Tier> tiers = new ArrayList<>();
        tiers.add(new Tier(10L, "x"));
        tiers.add(new Tier(10L, "1"));
        tiers.add(new Tier(null, "1"));
        final List<String> errors = TermsValidator.validate(withPricing(new PricingTerms(null, null, null, null, null, List.of(override("strange.instance.cpu", null, null, null, null, null, tiers, null)))));
        assertTrue(has(errors, "tiers[0].price : positive decimal"), errors.toString());
        assertTrue(has(errors, "tiers[1].upTo : strictly increasing bounds"), errors.toString());
        assertFalse(has(errors, "tiers[2]"), errors.toString());
        final List<Tier> missingBound = List.of(new Tier(null, "1"), new Tier(null, "2"));
        assertTrue(has(TermsValidator.validate(withPricing(new PricingTerms(null, null, null, null, null, List.of(override("strange.instance.cpu", null, null, null, null, null, missingBound, null))))), "tiers[0].upTo : strictly increasing bounds"));
        final List<Tier> good = List.of(new Tier(10L, "1"), new Tier(20L, "0.5"), new Tier(null, "0.1"));
        assertEquals(List.of(), TermsValidator.validate(withPricing(new PricingTerms(null, null, null, null, null, List.of(override("strange.instance.cpu", Map.of(), "PRD", "hour", null, 5L, good, null))))));
    }

    // --- price lists ---

    @Test
    void theLinesOfAPriceListAreCheckedOneByOne() {
        assertEquals(List.of("lines required"), TermsValidator.validateLines(null));
        assertEquals(List.of(), TermsValidator.validateLines(List.of()));
        final List<PriceLine> lines = new ArrayList<>();
        lines.add(null);
        lines.add(new PriceLine("no.such.unit", null, "unit", "1"));
        lines.add(new PriceLine("strange.instance.cpu", null, "decade", "bad"));
        lines.add(new PriceLine("strange.instance.cpu", null, "hour", "1"));
        lines.add(new PriceLine("strange.instance.cpu", Map.of(), "hour", "2"));
        lines.add(new PriceLine("strange.instance.cpu", null, null, "1"));
        final List<String> errors = TermsValidator.validateLines(lines);
        assertTrue(has(errors, "lines[0] : empty line"), errors.toString());
        assertTrue(has(errors, "lines[1].unit : unit not in the catalogue"), errors.toString());
        assertTrue(has(errors, "lines[2].per : "), errors.toString());
        assertTrue(has(errors, "lines[2].price : positive decimal"), errors.toString());
        assertTrue(has(errors, "lines[4] : duplicate line (same unit and same dimensions)"), errors.toString());
        assertTrue(has(errors, "lines[5].per : "), errors.toString());
    }

    @Test
    void tooManyLinesAreRefused() {
        final List<PriceLine> lines = new ArrayList<>();
        for (int i = 0; i <= TermsValidator.MAX_PRICE_LINES; i++) {
            lines.add(new PriceLine("strange.instance.cpu", Map.of("n", Integer.toString(i)), "hour", "1"));
        }
        assertTrue(has(TermsValidator.validateLines(lines), "lines : at most 200 lines"));
    }

    @Test
    void aContractIsLinkedToAnExistingListInTheSameCurrency() {
        final PriceListVersion v1 = new PriceListVersion(1, 0L, 0L, "x", "n", List.of());
        final PriceList list = new PriceList("list", "List", "CHF", 0L, "x", List.of(v1));
        final ContractTerms none = withQuotas(null);
        assertEquals(List.of(), TermsValidator.validateLink(null, list));
        assertEquals(List.of(), TermsValidator.validateLink(none, list));
        assertEquals(List.of(), TermsValidator.validateLink(withPricing(new PricingTerms("CHF", null, null, null, null, null)), list), "no list referenced");
        final ContractTerms linked = withPricing(new PricingTerms("CHF", new PriceListRef("list", 1, null), null, null, null, null));
        assertEquals(List.of(), TermsValidator.validateLink(linked, list));
        assertTrue(has(TermsValidator.validateLink(linked, null), "pricing.priceList : unknown list (list)"));
        assertTrue(has(TermsValidator.validateLink(withPricing(new PricingTerms("EUR", new PriceListRef("list", 1, null), null, null, null, null)), list), "pricing.currency : EUR is not the currency of list list (CHF)"));
        assertTrue(has(TermsValidator.validateLink(withPricing(new PricingTerms("CHF", new PriceListRef("list", 7, null), null, null, null, null)), list), "pricing.priceList.version : unknown version (7)"));
        assertEquals(List.of(), TermsValidator.validateLink(withPricing(new PricingTerms(null, new PriceListRef("list", null, true), null, null, null, null)), list), "follow: no version to find, no currency to compare");
    }
}
