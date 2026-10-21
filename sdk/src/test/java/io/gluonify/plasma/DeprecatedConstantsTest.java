package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.gluonify.plasma.analysis.Finding;
import io.gluonify.plasma.contracts.Organization;
import io.gluonify.plasma.contracts.Plan;
import io.gluonify.plasma.contracts.PriceList;
import io.gluonify.plasma.release.KeyRollover;
import io.gluonify.plasma.release.ReleaseManifest;
import org.junit.jupiter.api.Test;

/** The deprecated constant names stay aliases of the renamed ones until their removal. */
@SuppressWarnings("removal")
class DeprecatedConstantsTest {

    @Test
    void deprecatedAliasesPointToTheRenamedConstants() {
        assertEquals(Finding.CURRENT_VERSION, Finding.VERSION);
        assertSame(Finding.RULE_PATTERN, Finding.RULE);
        assertSame(PriceList.ID_PATTERN, PriceList.ID);
        assertSame(Plan.ID_PATTERN, Plan.ID);
        assertEquals(Organization.STATUS_SUSPENDED, Organization.SUSPENDED);
        assertEquals(Organization.STATUS_ARCHIVED, Organization.ARCHIVED);
        assertEquals(ReleaseManifest.SCHEMA_VERSION, ReleaseManifest.SCHEMA);
        assertSame(ReleaseManifest.VERSION_PATTERN, ReleaseManifest.VERSION);
        assertEquals(KeyRollover.SCHEMA_VERSION, KeyRollover.SCHEMA);
    }
}
