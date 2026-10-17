package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Validation of the terms and price lists (contracts.md § 2.2, § 2.4): all the errors, never only the first. */
class TermsValidatorTest {
    private final JsonMapper json = JsonMapper.builder().build();

    List<String> errors(String termsJson) {
        return TermsValidator.validate(json.readValue(termsJson, ContractTerms.class));
    }

    static boolean has(List<String> e, String part) {
        return e.stream().anyMatch(s -> s.contains(part));
    }

    @Test
    void emptyTermsValid() {
        assertEquals(List.of(), errors("{}"));
        assertEquals(List.of("terms required"), TermsValidator.validate(null));
    }

    @Test
    void allErrorsAreReturned() {
        List<String> e = errors("""
                { "enforcement": "maybe", "environments": ["prd"], "validity": { "from": "2027-01-01", "until": "2026-01-01" },
                  "services": { "uses": ["higgs", "Bad"], "distributed": ["nfs"], "routeKinds": ["ftp"] },
                  "quotas": { "apps": -1, "replicasPerApp": 0, "scratchMbPerReplica": 64, "distributedQuotaMbPerApp": 100, "defaultDistributedQuotaMb": 200 },
                  "quotasByEnv": { "sbx": {} },
                  "ha": { "spreadNodes": 9, "antiAffinity": "sometimes" },
                  "apps": { "shop": { "scratchMbPerReplica": 128, "distributedQuotaMbPerApp": 1000, "ha": { "spreadNodes": 0 } }, "Bad_Name": {} },
                  "budget": { "monthly": "-5", "thresholdsPct": [0, 50, 80, 100, 200, 600] } }""");
        for (String p : List.of("enforcement", "environments", "validity", "higgs is never allowed", "services.uses : invalid name", "services.distributed", "services.routeKinds",
                "quotas.apps", "quotas.replicasPerApp", "defaultDistributedQuotaMb : exceeds", "quotasByEnv : invalid environment", "ha.spreadNodes", "ha.antiAffinity",
                "apps.shop.scratchMbPerReplica : an override can only tighten", "apps.shop.distributedQuotaMbPerApp : an override", "apps.shop.ha.spreadNodes",
                "apps.Bad_Name", "budget.monthly", "budget.thresholdsPct : 5 thresholds", "budget.thresholdsPct : 1 to 500"))
            assertTrue(has(e, p), p + " in " + e);
    }

    @Test
    void aPerApplicationOverrideCanWidenMemoryReplicasAndHa() {
        assertEquals(List.of(), errors("""
                { "quotas": { "memoryMbPerReplica": 1024, "replicasPerApp": 2 }, "ha": { "spreadNodes": 2 },
                  "apps": { "shop": { "memoryMbPerReplica": 4096, "replicasPerApp": 6, "ha": { "spreadNodes": 3, "antiAffinity": "required" } } } }"""));
    }

    @Test
    void pricingValidated() {
        List<String> e = errors("""
                { "pricing": { "currency": "chf", "priceList": { "id": "Std", "version": 2, "follow": true }, "discountPct": "120", "minimumMonthly": "1.0000000001",
                  "subscription": { "monthly": "x" },
                  "overrides": [
                    { "unit": "nope.unit", "price": "1" },
                    { "unit": "strange.instance.memory_used", "price": "1" },
                    { "unit": "bup.jobs.seconds", "price": "1" },
                    { "unit": "strange.instance.memory_reserved", "dims": { "state": "sleepy", "zone": "a" }, "per": "GB", "price": "1" },
                    { "unit": "strange.instance.cpu", "price": "1", "tiers": [ { "upTo": 10, "price": "1" } ] },
                    { "unit": "strange.instance.cpu" },
                    { "unit": "crystallon.render.pages", "tiers": [ { "upTo": 100, "price": "1" }, { "upTo": 50, "price": "1" }, { "price": "1" } ] },
                    { "unit": "crystallon.render.pages", "env": "sbx", "included": -1, "discountPct": "101" }
                  ] } }""");
        for (String p : List.of("pricing.currency", "pricing.priceList.id", "version and follow", "pricing.discountPct", "pricing.minimumMonthly", "subscription.monthly",
                "overrides[0].unit : unit not in the catalogue", "overrides[1].unit : strange.instance.memory_used is not billable (indicative)",
                "overrides[2].unit : bup.jobs.seconds is not billable (internal)", "overrides[3].dims.state", "overrides[3].dims : zone is not a dimension",
                "overrides[3].per", "overrides[4] : price and tiers", "overrides[4].tiers[0].upTo : the last tier", "overrides[5] : price, tiers, included",
                "overrides[6].tiers[1].upTo : strictly increasing bounds", "overrides[7].env", "overrides[7].included", "overrides[7].discountPct"))
            assertTrue(has(e, p), p + " in " + e);
        assertTrue(has(errors("{ \"pricing\": { \"priceList\": { \"id\": \"std\" } } }"), "version : required without follow"));
        assertEquals(List.of(), errors("{ \"pricing\": { \"priceList\": { \"id\": \"std\", \"follow\": true } } }"));
    }

    @Test
    void listLines() {
        assertEquals(List.of(), TermsValidator.validateLines(List.of(new PriceLine("strange.instance.cpu", null, "hour", "0.02"),
                new PriceLine("strange.instance.memory_reserved", Map.of("state", "frozen"), "GB-hour", "0.002"),
                new PriceLine("strange.instance.memory_reserved", Map.of("state", "active"), "GB-hour", "0.008"))));
        List<String> e = TermsValidator.validateLines(List.of(new PriceLine("strange.instance.cpu", null, "GB", "0.02"), new PriceLine("strange.instance.cpu", null, "hour", "abc"),
                new PriceLine("x.y", null, "unit", "1")));
        assertTrue(has(e, "lines[0].per"), e.toString());
        assertTrue(has(e, "lines[1].price"), e.toString());
        assertTrue(has(e, "lines[1] : duplicate line"), e.toString());
        assertTrue(has(e, "lines[2].unit"), e.toString());
        assertEquals(List.of("lines required"), TermsValidator.validateLines(null));
    }

    @Test
    void contractListBinding() {
        ContractTerms t = json.readValue("{ \"pricing\": { \"currency\": \"EUR\", \"priceList\": { \"id\": \"standard-chf\", \"version\": 3 } } }", ContractTerms.class);
        PriceList chf = new PriceList("standard-chf", "Tariff", "CHF", 1L, "ops", List.of(new PriceListVersion(1, 1L, 1L, "ops", "x", List.of())));
        List<String> e = TermsValidator.validateLink(t, chf);
        assertTrue(has(e, "pricing.currency : EUR is not the currency"), e.toString());
        assertTrue(has(e, "unknown version (3)"), e.toString());
        assertTrue(has(TermsValidator.validateLink(t, null), "unknown list"));
        assertEquals(List.of(), TermsValidator.validateLink(new ContractTerms(null, null, null, null, null, null, null, null, null, null), null));
    }
}
