package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    private static final List<String> ROLES = List.of("planning", "architecture", "security-review", "security-testing", "code-analysis", "code-writing",
            "test-writing", "code-review", "documentation", "translation", "prompt");
    private static final List<String> TIERS = List.of("local", "standard", "strong", "top");
    private static final String RUNS = "superposition.runs";
    private static final String OUTCOME = "outcome";
    private static final String AGENT = "agent";
    private static final String TICKET = "ticket";
    private static final String ENGINE = "engine";
    private static final String MODEL = "model";

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
    void theirDimensionsAreEnumeratedOrFree() {

        final List<String> engines = List.of("edge", "claude");

        assertEquals(Map.of("model", MODELS, "kind", List.of("input", "output", "cache_read", "cache_write"), "engine", engines), UsageCatalog.get("superposition.tokens").dims());
        assertEquals(Map.of("level", List.of("L0", "L1", "L2", "L3"), "role", ROLES, "tier", TIERS, "engine", engines, "model", MODELS,
                        OUTCOME, List.of("success", "failure", "interrupted", "timeout")),
                UsageCatalog.get(RUNS).dims());
        assertEquals(Map.of("model", MODELS, "engine", engines), UsageCatalog.get("superposition.cost.usd").dims());
        for (final String name : List.of("superposition.tokens", "superposition.runs", "superposition.cost.usd")) {
            assertEquals(java.util.Set.of("agent", "ticket"), UsageCatalog.get(name).free(), name);
            assertEquals(java.util.Set.of("ticket"), UsageCatalog.get(name).highCardinality(), name);
        }
    }

    @Test
    void aRunRecordWithRoleAndTierIsAcceptedWithEveryOtherDimension() {

        final UsageUnit runs = UsageCatalog.get(RUNS);

        // what superposition's Metering sends once the catalogue knows the roles: seven dimensions
        assertTrue(runs.acceptsDims(Map.of("role", "code-writing", "tier", "local", ENGINE, "edge", MODEL, "qwen3-coder", OUTCOME, "success", AGENT, "edge-1",
                TICKET, "GLU-12")));
        assertTrue(runs.acceptsDims(Map.of("role", "prompt", "tier", "strong", ENGINE, "claude", OUTCOME, "failure")), "a run without a role");
        assertTrue(runs.acceptsDims(Map.of("role", "prompt", ENGINE, "claude", OUTCOME, "failure")), "a run without a tier");
        for (final String role : ROLES) {
            assertTrue(runs.acceptsDims(Map.of("role", role)), role);
        }
        for (final String tier : TIERS) {
            assertTrue(runs.acceptsDims(Map.of("tier", tier)), tier);
        }
    }

    @Test
    void anOlderEmitterWithTheLevelIsStillAccepted() {

        assertTrue(UsageCatalog.get(RUNS).acceptsDims(Map.of("level", "L2", ENGINE, "claude", MODEL, "opus", OUTCOME, "success", AGENT, "claude-1", TICKET, "GLU-7")));
    }

    @Test
    void anUnknownRoleOrTierIsRefused() {

        final UsageUnit runs = UsageCatalog.get(RUNS);

        assertFalse(runs.acceptsDims(Map.of("role", "judging")));
        assertFalse(runs.acceptsDims(Map.of("role", "Code-Writing")));
        assertFalse(runs.acceptsDims(Map.of("tier", "L1")), "a level is not a tier");
        assertFalse(runs.acceptsDims(Map.of("tier", "premium")));
        assertFalse(UsageCatalog.get("superposition.tokens").acceptsDims(Map.of("role", "planning")), "only the runs carry the role");
    }
}
