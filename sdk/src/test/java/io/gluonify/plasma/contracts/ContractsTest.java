package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.usage.Quantity;
import io.gluonify.plasma.usage.UsageCatalog;
import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Contrats (contracts.md § 2) : JSON relu à l'identique, version effective, état calculé, empreinte canonique, catalogue, réflexion native. */
class ContractsTest {
    private final JsonMapper json = JsonMapper.builder().build();
    static final long T = 1_790_000_000_000L;

    static final String SPEC_TERMS = """
            {
              "validity": { "from": "2026-11-01", "until": "2027-10-31" },
              "enforcement": "enforce",
              "environments": ["SBX", "QUA", "PRD"],
              "services": { "uses": ["crystallon", "fermion", "interfero", "photon", "field", "calorimeter"], "gdownDatabase": true, "distributed": ["std", "tmp", "gdown"],
                "distributedLocks": false, "sleep": true, "extraPorts": false, "nas": false, "routeKinds": ["app", "gdown"] },
              "quotas": { "apps": 20, "reservedMemoryMb": 16384, "replicasPerApp": 4, "memoryMbPerReplica": 2048, "scratchMbPerReplica": 512,
                "distributedQuotaMbPerApp": 10240, "defaultDistributedQuotaMb": 1024, "storageMb": 102400, "domains": 10 },
              "quotasByEnv": { "SBX": { "apps": 40, "reservedMemoryMb": 8192 } },
              "ha": { "spreadNodes": 2, "antiAffinity": "preferred" },
              "apps": { "shop": { "memoryMbPerReplica": 4096, "replicasPerApp": 6, "ha": { "spreadNodes": 3, "antiAffinity": "required" } } },
              "pricing": {
                "currency": "CHF",
                "priceList": { "id": "standard-chf", "version": 2, "follow": false },
                "subscription": { "monthly": "490.00", "perApp": "0.00", "perHaApp": "20.00" },
                "discountPct": "10",
                "minimumMonthly": "0.00",
                "overrides": [
                  { "unit": "strange.instance.memory_reserved", "dims": { "state": "frozen" }, "price": "0.0010" },
                  { "unit": "crystallon.render.pages", "included": 10000, "tiers": [ { "upTo": 100000, "price": "0.0020" }, { "price": "0.0010" } ] },
                  { "unit": "higgs.ingress.requests", "discountPct": "50" },
                  { "unit": "bup.analysis.runs", "dims": { "kind": "sonar" }, "env": "SBX", "price": "0" }
                ]
              },
              "budget": { "monthly": "1500.00", "thresholdsPct": [50, 80, 100], "forecast": true },
              "unknownLaterField": 1
            }""";

    ContractTerms terms() {
        return json.readValue(SPEC_TERMS, ContractTerms.class);
    }

    static ContractVersion v(int n, long from) {
        return new ContractVersion(n, from, T, "ops", "n" + n, null, null, null, null, null, null, null);
    }

    @Test
    void lesTermesDeLaSpecificationSontValidesEtRelusALIdentique() {
        ContractTerms t = terms();
        assertEquals(List.of(), TermsValidator.validate(t));
        String s = json.writeValueAsString(t);
        assertEquals(t, json.readValue(s, ContractTerms.class));
        assertFalse(s.contains("unknownLaterField"), "propriété inconnue ignorée");
        assertTrue(t.enforcing());
        assertEquals(2, t.haFor("other").spread());
        assertEquals(3, t.haFor("shop").spread());
        assertTrue(t.haFor("shop").required());
    }

    @Test
    void quotasEffectifsParEnvironnementEtParApplication() {
        ContractTerms t = terms();
        ContractTerms.Quotas prd = t.quotasFor("PRD", "web");
        assertEquals(20, prd.apps());
        assertEquals(16384, prd.reservedMemoryMb());
        ContractTerms.Quotas sbx = t.quotasFor("SBX", "web");
        assertEquals(40, sbx.apps());
        assertEquals(8192, sbx.reservedMemoryMb());
        assertEquals(4, sbx.replicasPerApp(), "les clés absentes de quotasByEnv viennent de quotas");
        ContractTerms.Quotas shop = t.quotasFor("PRD", "shop");
        assertEquals(4096, shop.memoryMbPerReplica(), "surcharge par application qui élargit");
        assertEquals(6, shop.replicasPerApp());
        assertEquals(512, shop.scratchMbPerReplica());
        ContractTerms empty = new ContractTerms(null, null, null, null, null, null, null, null, null, null);
        assertEquals(ContractTerms.Quotas.NONE, empty.quotasFor("PRD", "x"));
        assertNull(empty.haFor("x"));
        assertTrue(empty.enforcing(), "enforcement absent : enforce");
    }

    @Test
    void versionEffectiveEtEtatCalcule() {
        ContractVersion v1 = v(1, T), v2 = v(2, T + 1000), v3 = v(3, T + 5000).cancel(T + 10, "ops"), v4 = v(4, T + 9000);
        List<ContractVersion> all = List.of(v1, v2, v3, v4);
        assertNull(Contracts.effective(all, T - 1));
        assertEquals(1, Contracts.effective(all, T).version());
        assertEquals(2, Contracts.effective(all, T + 1000).version());
        assertEquals(2, Contracts.effective(all, T + 6000).version(), "une version annulée ne s'applique jamais");
        assertEquals(4, Contracts.effective(all, T + 9000).version());
        assertEquals(Contracts.SUPERSEDED, Contracts.state(v1, all, T + 2000));
        assertEquals(Contracts.ACTIVE, Contracts.state(v2, all, T + 2000));
        assertEquals(Contracts.CANCELLED, Contracts.state(v3, all, T + 2000));
        assertEquals(Contracts.SCHEDULED, Contracts.state(v4, all, T + 2000));
        assertEquals(4, Contracts.next(all, T + 2000).version());
        assertNull(Contracts.next(all, T + 9000));
        // à date égale, le plus grand numéro
        assertEquals(5, Contracts.effective(List.of(v(5, T), v1), T).version());
        // quatre yeux : en attente, jamais effective avant l'approbation
        ContractVersion p = new ContractVersion(6, T, T, "alice", "x", null, null, null, null, true, null, null);
        assertEquals(1, Contracts.effective(List.of(v1, p), T + 1).version());
        assertEquals(Contracts.PENDING, Contracts.state(p, List.of(v1, p), T + 1));
        assertEquals(6, Contracts.effective(List.of(v1, p.approve(T + 2, "bob")), T + 3).version());
    }

    @Test
    void empreinteCanoniqueStableEtSensibleAuxTermesSeulement() {
        ContractTerms a = terms();
        ContractTerms b = json.readValue(json.writeValueAsString(a), ContractTerms.class);
        assertEquals(Contracts.fingerprint(a), Contracts.fingerprint(b));
        assertTrue(Contracts.fingerprint(a).matches("sha256:[0-9a-f]{64}"));
        assertNotEquals(Contracts.fingerprint(a), Contracts.fingerprint(a.withEnforcement("warn")));
        // l'ordre des clés d'entrée ne compte pas
        Map<String, ContractTerms.Quotas> m1 = new LinkedHashMap<>(), m2 = new LinkedHashMap<>();
        m1.put("SBX", new ContractTerms.Quotas(1L, null, null, null, null, null, null, null, null));
        m1.put("PRD", new ContractTerms.Quotas(2L, null, null, null, null, null, null, null, null));
        m2.put("PRD", m1.get("PRD"));
        m2.put("SBX", m1.get("SBX"));
        ContractTerms x = new ContractTerms(null, null, null, null, null, m1, null, null, null, null), y = new ContractTerms(null, null, null, null, null, m2, null, null, null, null);
        assertEquals(Contracts.fingerprint(x), Contracts.fingerprint(y));
        assertEquals("{\"quotasByEnv\":{\"PRD\":{\"apps\":2},\"SBX\":{\"apps\":1}}}", Contracts.canonical(x));
        // une liste vide (rien n'est permis) n'est pas une liste absente (tout est permis)
        ContractTerms none = new ContractTerms(null, null, null, new ContractTerms.Services(List.of(), null, null, null, null, null, null, null, null), null, null, null, null, null, null);
        ContractTerms all = new ContractTerms(null, null, null, new ContractTerms.Services(null, null, null, null, null, null, null, null, null), null, null, null, null, null, null);
        assertNotEquals(Contracts.fingerprint(none), Contracts.fingerprint(all));
        assertEquals("{\"a\\\"b\\\\c\\u0001\":\"é\"}", Canonical.json(Map.of("a\"b\\c\u0001", "é")));
    }

    @Test
    void organisationEtVersionsRelues() {
        ContractVersion cv = new ContractVersion(1, T, T, "ops", "premier", Contracts.fingerprint(terms()), terms(), null, null, null, null, null);
        Organization o = new Organization("acme", "Acme SA", null, "CRM-1", T, "ops", List.of(cv));
        assertEquals(Organization.ACTIVE, o.status());
        String s = json.writeValueAsString(o);
        assertEquals(o, json.readValue(s, Organization.class));
        JsonNode n = json.readTree(s);
        assertEquals(false, n.get("versions").get(0).get("pending").asBoolean());
        // un ancien document sans versions ni statut se relit
        Organization old = json.readValue("{\"id\":\"acme\",\"name\":\"Acme\"}", Organization.class);
        assertEquals(List.of(), old.versions());
        assertEquals(Organization.ACTIVE, old.status());
        assertEquals(2, o.nextVersion());
        assertEquals(cv, o.version(1));
        assertNull(o.version(2));
        Organization o2 = o.withVersion(cv.cancel(T + 1, "ops"));
        assertEquals(1, o2.versions().size());
        assertTrue(o2.version(1).cancelled());
        assertTrue(Organization.validId("acme"));
        assertFalse(Organization.validId(Organization.DEFAULT), "_default n'est pas un identifiant d'organisation");
        assertFalse(Organization.validName(""));
        assertFalse(Organization.validName("a\nb"));
        assertTrue(Organization.validName("Acme SA"));
    }

    @Test
    void listeDePrixVersionEffective() {
        PriceListVersion a = new PriceListVersion(1, T, T, "ops", "a", List.of(new PriceLine("strange.instance.cpu", null, "hour", "0.02")));
        PriceListVersion b = new PriceListVersion(2, T + 100, T, "ops", "b", List.of());
        PriceList pl = new PriceList("standard-chf", "Tarif", "CHF", T, "ops", List.of(a, b));
        assertNull(pl.effective(T - 1));
        assertEquals(1, pl.effective(T + 99).version());
        assertEquals(2, pl.effective(T + 100).version());
        assertEquals(3, pl.nextVersion());
        assertEquals(pl, json.readValue(json.writeValueAsString(pl), PriceList.class));
    }

    @Test
    void catalogueEtConversions() {
        assertNotNull(UsageCatalog.get("strange.instance.memory_reserved"));
        assertTrue(UsageCatalog.get("strange.instance.memory_reserved").priceable());
        assertFalse(UsageCatalog.get("strange.instance.memory_used").priceable(), "indicative");
        assertFalse(UsageCatalog.get("bup.jobs.seconds").priceable(), "interne");
        assertNull(UsageCatalog.get("nope.x"));
        assertEquals(new HashSet<>(UsageCatalog.names()).size(), UsageCatalog.names().size());
        for (String n : UsageCatalog.names()) assertTrue(n.matches("[a-z]+\\.[a-z_]+(\\.[a-z_]+)?"), n);
        assertEquals(0, new BigDecimal("720").compareTo(Quantity.MB_SECONDS.convert(new BigDecimal(1024L * 3600 * 720), "GB-hour")));
        assertEquals(0, BigDecimal.ONE.compareTo(Quantity.BYTES.convert(new BigDecimal(1L << 30), "GB")));
        assertEquals(0, new BigDecimal("2.5").compareTo(Quantity.COUNT.convert(new BigDecimal(2_500_000), "million")));
        assertEquals(0, new BigDecimal("1.5").compareTo(Quantity.SECONDS.convert(new BigDecimal(90), "minute")));
        assertThrows(IllegalArgumentException.class, () -> Quantity.SECONDS.convert(BigDecimal.ONE, "GB"));
    }

    @Test
    void decimaux() {
        assertTrue(Decimals.valid("0"));
        assertTrue(Decimals.valid("490.00"));
        assertTrue(Decimals.valid("0.000000001"));
        assertFalse(Decimals.valid("0.0000000001"), "10 décimales");
        assertFalse(Decimals.valid("-1"));
        assertFalse(Decimals.valid("1e3"));
        assertFalse(Decimals.valid("01"));
        assertFalse(Decimals.validPct("100.01"));
        assertEquals("0.01", Decimals.money(new BigDecimal("0.005"), "CHF").toPlainString());
        assertEquals("2", Decimals.money(new BigDecimal("1.5"), "JPY").toPlainString());
        assertEquals("1.235", Decimals.money(new BigDecimal("1.2345"), "KWD").toPlainString());
    }

    @Test
    void reflexionNativeDeclaree() throws Exception {
        JsonNode meta;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/native-image/io.gluonify.plasma/gluonify-plasma-sdk/reachability-metadata.json")) {
            meta = json.readTree(in);
        }
        Set<String> declared = new HashSet<>();
        meta.get("reflection").forEach(e -> declared.add(e.get("type").asString()));
        for (Class<?> c : List.of(ContractTerms.class, ContractVersion.class, Organization.class, PriceList.class, PriceListVersion.class, PriceLine.class, Violation.class,
                io.gluonify.plasma.usage.UsageUnit.class, Quantity.class)) assertTrue(declared.contains(c.getName()), c.getName());
        for (Class<?> outer : List.of(ContractTerms.class, Pricing.class))
            for (Class<?> c : outer.getDeclaredClasses()) if (Modifier.isPublic(c.getModifiers())) assertTrue(declared.contains(c.getName()), c.getName());
    }
}
