package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The units gDown emits per database and per member (metering.md § 3.1 and § 3.3), as gluonify-calorimeter's copy of the catalogue declares them.
 *
 * @author David CABILLIC
 */
class GdownUnitsTest {

    private static final String DB_CPU = "gdown.db.cpu";
    private static final String PROCESS_CPU = "gdown.process.cpu";
    private static final String MEMORY = "gdown.db.memory_reserved";

    @Test
    void theCpuOfADatabaseIsABillingBasisInSeconds() {

        final UsageUnit unit = UsageCatalog.get(DB_CPU);
        assertEquals("gdown", unit.service());
        assertEquals(Quantity.SECONDS, unit.quantity());
        assertTrue(unit.priceable(), "billable and not internal: a contract may price it");
        assertEquals(Map.of(), unit.dims());
    }

    @Test
    void theCpuOfAMemberIsAnInternalTotalNeverPriced() {

        final UsageUnit unit = UsageCatalog.get(PROCESS_CPU);
        assertEquals("gdown", unit.service());
        assertEquals(Quantity.SECONDS, unit.quantity());
        assertFalse(unit.billable(), "indicative: the reconciliation total, not a customer's share");
        assertTrue(unit.internal(), "a platform cost");
        assertFalse(unit.priceable());
    }

    @Test
    void theMemoryOfADatabaseIsBillableSinceTheEngineMeasuresIt() {

        final UsageUnit unit = UsageCatalog.get(MEMORY);
        assertEquals(Quantity.MB_SECONDS, unit.quantity());
        assertTrue(unit.priceable(), "the engine's measure is a billing basis (Q2.2)");
    }

    @Test
    void theUnitsOfGdownKeepTheirCatalogueOrder() {

        final List<String> names = UsageCatalog.names();
        assertEquals(names.indexOf(MEMORY) + 1, names.indexOf(DB_CPU));
        assertEquals(names.indexOf(DB_CPU) + 1, names.indexOf(PROCESS_CPU));
        assertTrue(UsageCatalog.get(DB_CPU).acceptsDims(Map.of()));
        assertFalse(UsageCatalog.get(DB_CPU).acceptsDims(Map.of("kind", "read")), "no dimension");
    }
}
