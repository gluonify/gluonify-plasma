package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Which dimensions a unit accepts: enumerated values, free values, the cap.
 *
 * @author David CABILLIC
 */
class UsageUnitTest {

    private static final UsageUnit UNIT = new UsageUnit("x.runs", "x", Quantity.COUNT, false, false, Map.of("engine", List.of("edge", "claude")), Set.of("agent", "ticket"), Set.of("ticket"));

    @Test
    void enumeratedAndFreeDimensionsAreAccepted() {

        assertTrue(UNIT.acceptsDims(null));
        assertTrue(UNIT.acceptsDims(Map.of()));
        assertTrue(UNIT.acceptsDims(Map.of("engine", "edge", "agent", "claude-1", "ticket", "SUP-12")));
    }

    @Test
    void wrongValuesUnknownDimensionsAndNullsAreRefused() {

        assertFalse(UNIT.acceptsDims(Map.of("engine", "gpt")));
        assertFalse(UNIT.acceptsDims(Map.of("ticket", "has space")));
        assertFalse(UNIT.acceptsDims(Map.of("ticket", "x".repeat(65))));
        assertFalse(UNIT.acceptsDims(Map.of("colour", "red")));
        assertFalse(UNIT.acceptsDims(new java.util.HashMap<>(java.util.Collections.singletonMap("ticket", null))));
    }

    @Test
    void moreThanSixDimensionsAreRefused() {

        final var many = new UsageUnit("x.many", "x", Quantity.COUNT, false, false, Map.of(), Set.of("a", "b", "c", "d", "e", "f", "g"), Set.of());

        assertTrue(many.acceptsDims(Map.of("a", "1", "b", "1", "c", "1", "d", "1", "e", "1", "f", "1")));
        assertFalse(many.acceptsDims(Map.of("a", "1", "b", "1", "c", "1", "d", "1", "e", "1", "f", "1", "g", "1")));
    }

    @Test
    void aHighCardinalityDimensionMustBeFreeAndAllDimsListBoth() {

        assertThrows(IllegalArgumentException.class, () -> new UsageUnit("x.bad", "x", Quantity.COUNT, false, false, Map.of(), Set.of(), Set.of("ticket")));
        assertEquals(Set.of("engine", "agent", "ticket"), UNIT.allDims());
        assertEquals(Set.of("ticket"), UNIT.highCardinality());
        assertFalse(UNIT.priceable());
    }
}
