package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The units of gluonify-superposition (architecture § 11): indicative (never priceable), counted, with enumerated dimensions.
 *
 * @author David CABILLIC
 */
class SuperpositionUnitsTest {

    private static final List<String> MODELS = List.of("qwen3-coder", "haiku", "sonnet", "opus", "fable");

    @Test
    void theThreeUnitsAreIndicativeCountsOfSuperposition() {

        for (final String name : List.of("superposition.tokens", "superposition.runs", "superposition.cost.usd")) {
            final UsageUnit unit = UsageCatalog.get(name);
            assertEquals("superposition", unit.service(), name);
            assertEquals(Quantity.COUNT, unit.quantity(), name);
            assertFalse(unit.billable(), name + " is indicative");
            assertFalse(unit.priceable(), name + " is never a billing basis");
        }
    }

    @Test
    void theirDimensionsAreEnumerated() {

        assertEquals(Map.of("model", MODELS, "kind", List.of("input", "output", "cache_read", "cache_write")),
                UsageCatalog.get("superposition.tokens").dims());
        assertEquals(Map.of("level", List.of("L0", "L1", "L2", "L3"), "engine", List.of("edge", "claude"),
                "outcome", List.of("success", "failure", "interrupted", "timeout")), UsageCatalog.get("superposition.runs").dims());
        assertEquals(Map.of("model", MODELS), UsageCatalog.get("superposition.cost.usd").dims());
    }
}
