package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.contracts.ContractTerms.AppOverride;
import io.gluonify.plasma.contracts.ContractTerms.Ha;
import io.gluonify.plasma.contracts.ContractTerms.Quotas;
import io.gluonify.plasma.contracts.ContractTerms.Services;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Contract versions in time, plans, merging of plan and contract, canonical form and fingerprint, quotas of an application, organizations and violations. */
class ContractsGapsTest {

    static ContractTerms none() {
        return new ContractTerms(null, null, null, null, null, null, null, null, null, null);
    }

    static ContractVersion version(final int n, final Long from, final boolean pending, final Long cancelledAt, final Long approvedAt) {
        return new ContractVersion(n, from, 0L, "author", "note", "sha256:x", none(), cancelledAt, cancelledAt == null ? null : "canceller", pending, approvedAt, approvedAt == null ? null : "approver");
    }

    static ContractVersion plain(final int n, final Long from) {
        return version(n, from, false, null, null);
    }

    // --- contract versions in time ---

    @Test
    void theEffectiveVersionIsTheLatestOneThatStartedAndIsNeitherCancelledNorPending() {
        final List<ContractVersion> versions = List.of(plain(1, 100L), plain(2, 200L), version(3, 250L, false, 240L, null), version(4, 260L, true, null, null),
                version(5, 270L, true, null, 255L), plain(6, 200L), plain(7, null), plain(8, 900L));
        assertNull(Contracts.effective(versions, 50));
        assertEquals(1, Contracts.effective(versions, 150).version());
        assertEquals(6, Contracts.effective(versions, 250).version(), "same date: the greater number wins");
        assertEquals(5, Contracts.effective(versions, 300).version(), "an approved pending version counts");
        assertNull(Contracts.effective(null, 300));
        assertNull(Contracts.effective(List.of(), 300));
    }

    @Test
    void theStateOfAVersionIsComputedFromTheOthersAndTheClock() {
        final ContractVersion old = plain(1, 100L);
        final ContractVersion current = plain(2, 200L);
        final ContractVersion future = plain(3, 900L);
        final ContractVersion cancelled = version(4, 300L, false, 10L, null);
        final ContractVersion pending = version(5, 300L, true, null, null);
        final List<ContractVersion> all = List.of(old, current, future, cancelled, pending);
        assertEquals(Contracts.ACTIVE, Contracts.state(current, all, 500));
        assertEquals(Contracts.SUPERSEDED, Contracts.state(old, all, 500));
        assertEquals(Contracts.SCHEDULED, Contracts.state(future, all, 500));
        assertEquals(Contracts.CANCELLED, Contracts.state(cancelled, all, 500));
        assertEquals(Contracts.PENDING, Contracts.state(pending, all, 500));
        assertEquals(Contracts.SCHEDULED, Contracts.state(current, all, 50), "nothing active yet");
    }

    @Test
    void theNextVersionIsTheNearestUpcomingOneThatCanTakeEffect() {
        final List<ContractVersion> versions = List.of(plain(1, 100L), plain(2, 900L), plain(3, 500L), version(4, 300L, false, 10L, null), version(5, 400L, true, null, null), plain(6, 700L));
        assertEquals(3, Contracts.next(versions, 200).version());
        assertEquals(6, Contracts.next(versions, 600).version());
        assertNull(Contracts.next(versions, 1000));
        assertNull(Contracts.next(List.of(), 0));
    }

    @Test
    void versionsAreCancelledAndApprovedWithoutChangingTheRest() {
        final ContractVersion v = plain(1, 100L);
        assertFalse(v.cancelled());
        final ContractVersion cancelled = v.cancel(5L, "ada");
        assertTrue(cancelled.cancelled());
        assertEquals("ada", cancelled.cancelledBy());
        assertEquals(v.fingerprint(), cancelled.fingerprint());
        final ContractVersion pending = version(2, 100L, true, null, null);
        assertTrue(pending.awaitingApproval());
        final ContractVersion approved = pending.approve(9L, "bob");
        assertFalse(approved.awaitingApproval());
        assertEquals("bob", approved.approvedBy());
        assertEquals(Boolean.FALSE, new ContractVersion(1, 1L, 1L, "a", "n", "f", none(), null, null, null, null, null).pending(), "a missing flag is false");
    }

    // --- plans and contracts ---

    @Test
    void aPlanContractMergesKeyByKeyAndKeepsItsOwnPlanReference() {
        final ContractTerms plan = new ContractTerms(new ContractTerms.Validity("2026-01-01", null), "warn", List.of("SBX"), new Services(List.of("crystallon"), true, null, null, null, null, null, null, null, List.of("crystallon")),
                new Quotas(5L, null, 2L, 1024L, null, null, null, null, null), Map.of("PRD", new Quotas(null, null, 4L, null, null, null, null, null, null)), new Ha(2, "preferred"),
                Map.of("shop", new AppOverride(null, 3L, null, null, null)), null, new ContractTerms.Budget("100", null, null), null);
        final ContractTerms contract = new ContractTerms(null, "enforce", null, new Services(null, false, List.of("std"), null, null, null, null, null, null, null), new Quotas(10L, null, null, 2048L, null, null, null, null, null),
                Map.of("PRD", new Quotas(null, null, null, 8192L, null, null, null, null, null), "QUA", new Quotas(1L, null, null, null, null, null, null, null, null)), null, null, null, null, new ContractTerms.PlanRef("developer", 2));
        final ContractTerms resolved = Contracts.resolve(contract, plan);
        assertEquals("enforce", resolved.enforcement());
        assertEquals(List.of("SBX"), resolved.environments());
        assertEquals("2026-01-01", resolved.validity().from());
        assertEquals(10L, resolved.quotas().apps());
        assertEquals(2L, resolved.quotas().replicasPerApp(), "the plan fills what the contract leaves absent");
        assertEquals(2048L, resolved.quotas().memoryMbPerReplica());
        assertEquals(List.of("crystallon"), resolved.services().uses());
        assertEquals(Boolean.FALSE, resolved.services().gdownDatabase());
        assertEquals(List.of("std"), resolved.services().distributed());
        assertEquals(List.of("crystallon"), resolved.services().consoles());
        assertEquals(4L, resolved.quotasByEnv().get("PRD").replicasPerApp());
        assertEquals(8192L, resolved.quotasByEnv().get("PRD").memoryMbPerReplica());
        assertEquals(1L, resolved.quotasByEnv().get("QUA").apps());
        assertEquals(2, resolved.ha().spread());
        assertEquals("100", resolved.budget().monthly());
        assertEquals("developer", resolved.plan().id());
        assertEquals(3L, resolved.apps().get("shop").replicasPerApp());
    }

    @Test
    void withoutAPlanOrContractTheOtherIsReturnedAsIs() {
        final ContractTerms c = none();
        assertSame(c, Contracts.resolve(c, null));
        assertNull(Contracts.resolve(null, c));
        final ContractTerms onlyServices = new ContractTerms(null, null, null, new Services(List.of("a"), null, null, null, null, null, null, null, null), null, null, null, null, null, null);
        assertSame(onlyServices.services(), Contracts.resolve(none(), onlyServices).services(), "the plan's services when the contract has none");
        assertSame(onlyServices.services(), Contracts.resolve(onlyServices, none()).services(), "the contract's services when the plan has none");
    }

    @Test
    void aContractQuotaOverridesEnvironmentQuotasOfThePlanOnlyForTheSameEnvironment() {
        final ContractTerms plan = new ContractTerms(null, null, null, null, null, Map.of("PRD", new Quotas(1L, null, null, null, null, null, null, null, null)), null, null, null, null, null);
        final ContractTerms contract = new ContractTerms(null, null, null, null, null, Map.of("PRD", new Quotas(9L, null, null, null, null, null, null, null, null)), null, null, null, null, null);
        assertEquals(9L, Contracts.resolve(contract, plan).quotasByEnv().get("PRD").apps());
        assertEquals(1L, Contracts.resolve(none(), plan).quotasByEnv().get("PRD").apps());
        assertEquals(9L, Contracts.resolve(contract, none()).quotasByEnv().get("PRD").apps());
    }

    @Test
    void consolesAreRestrictedToTheServicesUsed() {
        assertEquals(List.of(), Contracts.consoles(null));
        assertEquals(List.of(), Contracts.consoles(none()));
        assertEquals(List.of(), Contracts.consoles(new ContractTerms(null, null, null, new Services(List.of("a"), null, null, null, null, null, null, null, null), null, null, null, null, null, null)));
        assertEquals(List.of("a", "b"), Contracts.consoles(new ContractTerms(null, null, null, new Services(null, null, null, null, null, null, null, null, null, List.of("b", "a")), null, null, null, null, null, null)));
        assertEquals(List.of("a"), Contracts.consoles(new ContractTerms(null, null, null, new Services(List.of("a", "c"), null, null, null, null, null, null, null, null, List.of("b", "a")), null, null, null, null, null, null)));
    }

    // --- quotas and spread of an application ---

    @Test
    void theQuotasOfAnApplicationStackContractEnvironmentAndOverride() {
        final ContractTerms t = new ContractTerms(null, null, null, null, new Quotas(10L, 1000L, 2L, 512L, 64L, 256L, null, null, null),
                Map.of("PRD", new Quotas(null, 2000L, 4L, null, null, null, null, null, null)), new Ha(2, "required"),
                Map.of("shop", new AppOverride(1024L, 8L, 32L, 128L, new Ha(null, "preferred")), "noha", new AppOverride(null, null, null, null, null)), null, null);
        assertEquals(2L, t.quotasFor(null, null).replicasPerApp());
        assertEquals(4L, t.quotasFor("PRD", null).replicasPerApp());
        assertEquals(2000L, t.quotasFor("PRD", null).reservedMemoryMb());
        assertEquals(2L, t.quotasFor("QUA", "other").replicasPerApp(), "an unknown environment or application changes nothing");
        final Quotas shop = t.quotasFor("PRD", "shop");
        assertEquals(8L, shop.replicasPerApp());
        assertEquals(1024L, shop.memoryMbPerReplica());
        assertEquals(32L, shop.scratchMbPerReplica());
        assertEquals(128L, shop.distributedQuotaMbPerApp());
        assertEquals(10L, shop.apps());
        assertEquals(Quotas.NONE, new ContractTerms(null, null, null, null, null, null, null, null, null, null).quotasFor("PRD", "shop"));
    }

    @Test
    void theSpreadOfAnApplicationIsItsOverridesOverTheContractsAndDefaultsToOne() {
        final ContractTerms t = new ContractTerms(null, null, null, null, null, null, new Ha(3, "required"),
                Map.of("shop", new AppOverride(null, null, null, null, new Ha(null, "preferred")), "plain", new AppOverride(null, null, null, null, null)), null, null);
        assertEquals(3, t.haFor("shop").spread());
        assertEquals("preferred", t.haFor("shop").antiAffinity());
        assertFalse(t.haFor("shop").required());
        assertEquals(t.ha(), t.haFor("plain"));
        assertEquals(t.ha(), t.haFor(null));
        assertEquals(t.ha(), t.haFor("unknown"));
        assertNull(none().haFor("x"));
        assertEquals(1, new Ha(null, null).spread());
        assertTrue(new Ha(2, "required").required());
        assertEquals(new Ha(2, "x"), new Ha(2, "x").over(null));
        assertEquals(new Ha(4, "preferred"), new Ha(null, null).over(new Ha(4, "preferred")));
        assertEquals(new Ha(2, "required"), new Ha(2, "required").over(new Ha(4, "preferred")));
    }

    @Test
    void enforcementCopiesAndPlanReference() {
        final ContractTerms t = none();
        assertTrue(t.enforcing());
        assertTrue(t.withEnforcement("enforce").enforcing());
        assertFalse(t.withEnforcement(ContractTerms.WARN).enforcing());
        assertEquals(Map.of("a", new AppOverride(null, null, null, null, null)), t.withApps(Map.of("a", new AppOverride(null, null, null, null, null))).apps());
        assertEquals("p", t.withPlan(new ContractTerms.PlanRef("p", 1)).plan().id());
        assertNull(t.environments());
        assertEquals(Quotas.NONE, Quotas.NONE.merge(null));
    }

    // --- canonical form and fingerprint ---

    enum Colour {
        RED
    }

    record Sample(String text, Map<String, Object> map, List<Object> list) {
    }

    @Test
    void canonicalFormSortsKeysDropsNullsAndWritesEveryKnownType() {
        final Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("z", 1L);
        m.put("a", null);
        m.put("b", true);
        m.put("c", Colour.RED);
        m.put("d", new BigDecimal("1.50"));
        m.put("e", (short) 2);
        m.put("f", (byte) 3);
        assertEquals("{\"b\":true,\"c\":\"RED\",\"d\":1.50,\"e\":2,\"f\":3,\"z\":1}", Canonical.json(m));
        assertEquals("null", Canonical.json(null));
        assertEquals("\"a\\\"b\\\\c\\u0001\"", Canonical.json("a\"b\\c\u0001"));
        assertEquals("[1,\"x\",null]", Canonical.json(java.util.Arrays.asList(1, "x", null)));
        assertEquals("{\"list\":[1],\"map\":{\"k\":2},\"text\":\"t\"}", Canonical.json(new Sample("t", Map.of("k", 2), List.of(1))));
        assertEquals("{}", Canonical.json(Map.of()));
        assertEquals("[]", Canonical.json(List.of()));
    }

    @Test
    void aTypeThatHasNoCanonicalFormIsRefused() {
        final Object plain = new Object();
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Canonical.json(plain));
        assertTrue(e.getMessage().startsWith("non-canonical type: java.lang.Object"), e.getMessage());
        final List<Double> floating = List.of(1.5);
        assertThrows(IllegalArgumentException.class, () -> Canonical.json(floating), "a floating point number has no canonical form");
        final Map<String, int[]> array = Map.of("k", new int[0]);
        assertThrows(IllegalArgumentException.class, () -> Canonical.json(array));
    }

    @Test
    void theFingerprintIsStableAndSensitiveToTheTerms() {
        final ContractTerms a = new ContractTerms(null, "warn", null, null, null, null, null, null, null, null);
        final String fp = Contracts.fingerprint(a);
        assertTrue(fp.matches("sha256:[0-9a-f]{64}"), fp);
        assertEquals(fp, Contracts.fingerprint(new ContractTerms(null, "warn", null, null, null, null, null, null, null, null)));
        assertNotEquals(fp, Contracts.fingerprint(a.withEnforcement("enforce")));
        assertEquals(Canonical.json(a), Contracts.canonical(a));
        assertEquals("{\"enforcement\":\"warn\"}", Contracts.canonical(a));
    }

    // --- price lists, plans, organizations ---

    @Test
    void priceListVersionsAreNumberedAndTheEffectiveOneFollowsTheDate() {
        final PriceListVersion v1 = new PriceListVersion(1, 100L, 0L, "x", "n", List.of());
        final PriceListVersion v2 = new PriceListVersion(2, 200L, 0L, "x", "n", List.of());
        final PriceListVersion v3 = new PriceListVersion(3, 200L, 0L, "x", "n", List.of());
        PriceList list = new PriceList("list", "List", "CHF", 0L, "x", null);
        assertEquals(1, list.nextVersion());
        assertNull(list.effective(1000));
        list = list.withVersion(v1).withVersion(v2).withVersion(v3);
        assertEquals(4, list.nextVersion());
        assertSame(v2, list.version(2));
        assertNull(list.version(9));
        assertNull(list.effective(50));
        assertSame(v1, list.effective(150));
        assertSame(v3, list.effective(200), "same date: the greater number");
        assertEquals("Renamed", list.withName("Renamed").name());
        assertEquals(3, list.withName("Renamed").versions().size());
    }

    @Test
    void planVersionsAndNames() {
        final PlanVersion v1 = new PlanVersion(1, 100L, 0L, "x", "n", PlanVersion.BILLING_NONE, none());
        final PlanVersion v2 = new PlanVersion(2, 100L, 0L, "x", "n", PlanVersion.BILLING_USAGE, none());
        Plan plan = new Plan("developer", null, 0L, "x", null);
        assertEquals(1, plan.nextVersion());
        assertNull(plan.effective(500));
        assertNull(plan.version(1));
        plan = plan.withVersion(v1).withVersion(v2).withName(Map.of("en", "Developer", "fr", "Développeur"));
        assertEquals(3, plan.nextVersion());
        assertSame(v1, plan.version(1));
        assertNull(plan.version(5));
        assertNull(plan.effective(50));
        assertSame(v2, plan.effective(100), "same date: the greater number");
        assertTrue(v1.free());
        assertFalse(v2.free());
        assertEquals("Développeur", plan.name().get("fr"));
    }

    @Test
    void planNamesNeedEnglishAndWellFormedLanguageCodes() {
        assertTrue(Plan.validName(Map.of("en", "Developer")));
        assertTrue(Plan.validName(Map.of("en", "Developer", "de", "Entwickler")));
        assertFalse(Plan.validName(null));
        assertFalse(Plan.validName(Map.of()));
        assertFalse(Plan.validName(Map.of("fr", "Seulement")), "English is required");
        assertFalse(Plan.validName(Map.of("en", "Developer", "FR", "x")), "language codes are two lower-case letters");
        assertFalse(Plan.validName(Map.of("en", "Developer", "fr", " ")));
        assertFalse(Plan.validName(Map.of("en", "x".repeat(121))));
        assertFalse(Plan.validName(Map.of("en", "bad\u0001")));
    }

    @Test
    void organizationsKeepVersionsStatusAndNames() {
        Organization org = new Organization("acme", "Acme", null, null, 0L, "x", null);
        assertEquals(Organization.ACTIVE, org.status(), "a missing status is active");
        assertEquals(1, org.nextVersion());
        assertNull(org.version(1));
        org = org.withVersion(plain(1, 100L)).withVersion(plain(2, 200L));
        assertEquals(3, org.nextVersion());
        assertEquals(2, org.versions().size());
        final ContractVersion replacement = plain(1, 999L);
        org = org.withVersion(replacement);
        assertEquals(2, org.versions().size(), "same number: replaced");
        assertSame(replacement, org.version(1));
        assertNull(org.version(7));
        assertFalse(org.suspended());
        assertFalse(org.archived());
        assertTrue(org.withStatus(Organization.STATUS_SUSPENDED).suspended());
        assertTrue(org.withStatus(Organization.STATUS_ARCHIVED).archived());
        final Organization renamed = org.withName("Acme SA", "crm-1");
        assertEquals("Acme SA", renamed.name());
        assertEquals("crm-1", renamed.externalRef());
        assertEquals(2, renamed.versions().size());
        assertTrue(Organization.validId("acme"));
        assertFalse(Organization.validId(Organization.DEFAULT));
        assertTrue(Organization.validName("Acme"));
        assertFalse(Organization.validName(null));
        assertFalse(Organization.validName(" "));
        assertFalse(Organization.validName("x".repeat(121)));
        assertFalse(Organization.validName("a\u007fb"));
    }

    // --- violations, decimals ---

    @Test
    void aViolationIsACapOrARefusedValueWithItsSource() {
        final Violation.Cap cap = new Violation.Cap(Violation.REPLICAS, "replicas", 3, 2L, 5);
        final Violation over = Violation.over(cap, Violation.SOURCE_TOP, "too many");
        assertEquals(3L, over.limit());
        assertEquals(2L, over.used());
        assertEquals(5L, over.requested());
        assertNull(over.value());
        assertEquals("top", over.source());
        final Violation refused = Violation.refused(Violation.SERVICE_NOT_ALLOWED, "uses", "crystallon", "no");
        assertEquals("crystallon", refused.value());
        assertEquals(Violation.SOURCE_ORGANIZATION, refused.source());
        assertNull(refused.limit());
        assertFalse(refused.organizationRule());
        assertTrue(Violation.over(new Violation.Cap(Violation.APPS, "apps", 1, 1L, 2), Violation.SOURCE_ORGANIZATION, "m").organizationRule());
        assertTrue(Violation.CODES.containsAll(Violation.ORGANIZATION_RULES));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedLongFormOfACapStillWorks() {
        final Violation v = Violation.over(Violation.STORAGE, "storage", 10, null, 20, Violation.SOURCE_ORGANIZATION, "full");
        assertEquals(Violation.STORAGE, v.code());
        assertNull(v.used());
        assertEquals(20L, v.requested());
    }

    @Test
    void decimalsAreStrictAndRoundedPerCurrency() {
        assertTrue(Decimals.valid("0"));
        assertTrue(Decimals.valid("12.345678901".substring(0, 12)));
        assertFalse(Decimals.valid(null));
        assertFalse(Decimals.valid("01"));
        assertFalse(Decimals.valid("-1"));
        assertFalse(Decimals.valid("1e5"));
        assertFalse(Decimals.valid("1.1234567890"));
        assertTrue(Decimals.validPct("100"));
        assertTrue(Decimals.validPct("99.5"));
        assertFalse(Decimals.validPct("100.1"));
        assertFalse(Decimals.validPct(null));
        assertFalse(Decimals.validPct("abc"));
        assertEquals(BigDecimal.ZERO, Decimals.of(null));
        assertEquals(new BigDecimal("2.5"), Decimals.of("2.5"));
        assertEquals(0, Decimals.minor("JPY"));
        assertEquals(3, Decimals.minor("KWD"));
        assertEquals(2, Decimals.minor("CHF"));
        assertEquals("1.01", Decimals.money(new BigDecimal("1.005"), "CHF").toPlainString());
        assertEquals("2", Decimals.money(new BigDecimal("1.5"), "JPY").toPlainString());
        assertEquals("0.333333333", Decimals.quantity(new BigDecimal("0.3333333333")).toPlainString());
        assertEquals("100", Decimals.plain(new BigDecimal("1E+2")));
        assertEquals("1.5", Decimals.plain(new BigDecimal("1.500")));
        assertEquals(new BigDecimal("0.75"), Decimals.keep("25"));
        assertEquals(BigDecimal.ONE, Decimals.keep(null));
    }
}
