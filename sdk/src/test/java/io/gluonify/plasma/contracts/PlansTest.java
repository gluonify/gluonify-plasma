package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Plans (subscriptions.md § 2): the plan's terms apply for every key the contract leaves absent; consoles never broader than uses. */
class PlansTest {
    static final ContractTerms.Services DEV_SERVICES = new ContractTerms.Services(List.of("gdown", "crystallon", "fermion", "top", "charm"), true, null, null, null, null, null,
            null, true, List.of("gdown", "crystallon", "fermion", "top", "charm"));
    static final ContractTerms DEVELOPER = new ContractTerms(null, null, List.of("SBX"), DEV_SERVICES,
            new ContractTerms.Quotas(3L, 2048L, 1L, 512L, null, null, null, 1024L, 1L), null, null, null, null, null);

    @Test
    void aContractWithOnlyAPlanGetsThePlanTerms() {
        ContractTerms c = new ContractTerms(null, null, null, null, null, null, null, null, null, null, new ContractTerms.PlanRef("developer", 1));
        ContractTerms r = Contracts.resolve(c, DEVELOPER);
        assertEquals(List.of("SBX"), r.environments());
        assertEquals(DEV_SERVICES, r.services());
        assertEquals(3L, r.quotas().apps());
        assertEquals("developer", r.plan().id());
        assertEquals(List.of("charm", "crystallon", "fermion", "gdown", "top"), Contracts.consoles(r));
    }

    @Test
    void aKeyOfTheContractWinsFieldByField() {
        ContractTerms c = new ContractTerms(null, "warn", null,
                new ContractTerms.Services(List.of("gdown", "crystallon"), null, null, null, null, null, null, null, null, null),
                new ContractTerms.Quotas(5L, null, null, null, null, null, null, null, null), null, null, null, null, null, new ContractTerms.PlanRef("developer", 1));
        ContractTerms r = Contracts.resolve(c, DEVELOPER);
        assertEquals("warn", r.enforcement());
        assertEquals(List.of("gdown", "crystallon"), r.services().uses());
        assertEquals(Boolean.TRUE, r.services().gdownDatabase(), "the plan's value for an absent service key");
        assertEquals(5L, r.quotas().apps());
        assertEquals(2048L, r.quotas().reservedMemoryMb(), "the plan's value for an absent quota");
        assertEquals(List.of("crystallon", "gdown"), Contracts.consoles(r), "the plan's consoles, bounded by the contract's uses");
    }

    @Test
    void withoutPlanTermsTheContractIsUnchanged() {
        ContractTerms c = new ContractTerms(null, null, null, null, null, null, null, null, null, null, new ContractTerms.PlanRef("gone", 1));
        assertSame(c, Contracts.resolve(c, null));
        assertNull(Contracts.resolve(null, DEVELOPER));
        assertEquals(List.of(), Contracts.consoles(c), "absent consoles: none");
    }

    @Test
    void validation() {
        assertTrue(TermsValidator.validatePlan("none", DEVELOPER).isEmpty(), TermsValidator.validatePlan("none", DEVELOPER).toString());
        assertTrue(TermsValidator.validatePlan("free", DEVELOPER).contains("billing : none or usage"));
        ContractTerms withPlan = DEVELOPER.withPlan(new ContractTerms.PlanRef("developer", 1));
        assertTrue(TermsValidator.validatePlan("none", withPlan).contains("plan : the terms of a plan follow no plan"));
        ContractTerms broader = new ContractTerms(null, null, null,
                new ContractTerms.Services(List.of("gdown"), null, null, null, null, null, null, null, null, List.of("gdown", "crystallon")), null, null, null, null, null, null);
        assertTrue(TermsValidator.validate(broader).stream().anyMatch(e -> e.startsWith("services.consoles : crystallon is not in services.uses")));
        ContractTerms badRef = new ContractTerms(null, null, null, null, null, null, null, null, null, null, new ContractTerms.PlanRef("Dev!", 0));
        List<String> e = TermsValidator.validate(badRef);
        assertTrue(e.contains("plan.version : integer >= 1 required") && e.stream().anyMatch(x -> x.startsWith("plan.id")), e.toString());
    }

    @Test
    void effectiveVersionAndNames() {
        Plan p = new Plan("developer", Map.of("en", "Developer"), 1L, "op", List.of())
                .withVersion(new PlanVersion(1, 100L, 100L, "op", "first", "none", DEVELOPER))
                .withVersion(new PlanVersion(2, 300L, 200L, "op", "later", "none", DEVELOPER));
        assertNull(p.effective(50));
        assertEquals(1, p.effective(200).version());
        assertEquals(2, p.effective(300).version());
        assertEquals(3, p.nextVersion());
        assertTrue(p.effective(200).free());
        assertTrue(Plan.validName(Map.of("en", "Developer", "fr", "Developer")));
        assertTrue(!Plan.validName(Map.of("fr", "Developer")), "an English name is required");
        assertTrue(Plan.ID_PATTERN.matcher("developer").matches() && !Plan.ID_PATTERN.matcher("Developer").matches());
    }
}
