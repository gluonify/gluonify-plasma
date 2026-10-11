package io.gluonify.plasma.contracts;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Jeu d'essai commun de la valorisation (contracts.md § 6.2), rejoué par le calorimètre et Prism : chaque montant au centime. */
class PricingVectorsTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static JsonNode vectors() throws Exception {
        try (InputStream in = PricingVectorsTest.class.getResourceAsStream("/contracts/pricing-vectors.json")) {
            return JSON.readTree(in).get("vectors");
        }
    }

    @TestFactory
    List<DynamicTest> chaqueVecteur() throws Exception {
        List<DynamicTest> out = new ArrayList<>();
        for (JsonNode v : vectors()) out.add(DynamicTest.dynamicTest(v.get("name").asString(), () -> check(v)));
        return out;
    }

    static void check(JsonNode v) {
        List<Pricing.Section> sections = new ArrayList<>();
        for (JsonNode s : v.get("sections")) sections.add(JSON.treeToValue(s, Pricing.Section.class));
        Pricing.Statement st = Pricing.value(v.get("currency").asString(), v.get("daysInMonth").asInt(), sections);
        JsonNode e = v.get("expect");
        String name = v.get("name").asString();
        assertEquals(e.get("total").asString(), st.total(), name + " : total");
        if (e.has("usage")) assertEquals(e.get("usage").asString(), st.usage(), name + " : usage");
        if (e.has("fixed")) assertEquals(e.get("fixed").asString(), st.fixed(), name + " : part fixe");
        if (e.has("minimum")) assertEquals(e.get("minimum").asString(), st.minimum(), name + " : minimum");
        if (!e.has("sections")) return;
        for (int i = 0; i < e.get("sections").size(); i++) {
            JsonNode es = e.get("sections").get(i);
            Pricing.SectionResult r = st.sections().get(i);
            if (es.has("usage")) assertEquals(es.get("usage").asString(), r.usage(), name + " : usage de la section " + i);
            if (es.has("usageDiscounted")) assertEquals(es.get("usageDiscounted").asString(), r.usageDiscounted(), name + " : usage remisé " + i);
            if (es.has("fixed")) assertEquals(es.get("fixed").asString(), r.fixed(), name + " : part fixe " + i);
            if (es.has("lines")) es.get("lines").properties().forEach(l -> {
                Pricing.Line line = r.lines().stream().filter(x -> x.key().equals(l.getKey())).findFirst().orElse(null);
                assertNotNull(line, name + " : ligne " + l.getKey() + " parmi " + r.lines().stream().map(Pricing.Line::key).toList());
                assertEquals(l.getValue().asString(), line.amount(), name + " : " + l.getKey());
            });
            if (es.has("unpriced")) for (JsonNode k : es.get("unpriced")) {
                Pricing.Line line = r.lines().stream().filter(x -> x.key().equals(k.asString())).findFirst().orElseThrow();
                assertFalse(line.priced(), name + " : " + k.asString() + " non tarifée");
            }
        }
    }

    @Test
    void auMoinsDixVecteurs() throws Exception {
        assertTrue(vectors().size() >= 10);
    }

    @Test
    void lignesEtPaliersDetailles() {
        ContractTerms.PricingTerms pr = new ContractTerms.PricingTerms("CHF", null, null, null, null, List.of(new ContractTerms.PriceOverride("crystallon.render.pages", null, null, "unit", null, 10L,
                List.of(new ContractTerms.Tier(100L, "0.01"), new ContractTerms.Tier(null, "0.001")), null)));
        Pricing.Statement st = Pricing.value("CHF", 30, List.of(new Pricing.Section(1, null, pr, null, List.of(new Pricing.Usage("crystallon.render.pages", null, null, "1010")), null, null, null)));
        Pricing.Line l = st.sections().get(0).lines().get(0);
        assertEquals("1010", l.quantity());
        assertEquals("10", l.included());
        assertEquals("unit", l.per());
        assertEquals(2, l.tiers().size());
        assertEquals("100", l.tiers().get(0).quantity());
        assertEquals("900", l.tiers().get(1).quantity());
        assertEquals("1.90", l.amount());
        assertTrue(l.priced());
    }

    @Test
    void entreesInvalidesRefusees() {
        assertThrows(IllegalArgumentException.class, () -> Pricing.value("CHF", 27, List.of()));
        assertThrows(IllegalArgumentException.class, () -> Pricing.value("chf", 30, List.of()));
        assertThrows(IllegalArgumentException.class, () -> Pricing.value("CHF", 30, List.of(new Pricing.Section(null, null, null, null,
                List.of(new Pricing.Usage("nope.x", Map.of(), null, "1")), null, null, null))));
        assertThrows(IllegalArgumentException.class, () -> Pricing.value("CHF", 30, List.of(new Pricing.Section(null, "1.5", null, null, List.of(), null, null, null))));
        assertEquals("0.00", Pricing.value("CHF", 30, List.of()).total());
    }
}
