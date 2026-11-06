package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The root's variables are set by the platform, never by an application.
 *
 * @author David CABILLIC
 */
class GdownBoltReservedTest {

    @Test
    void theRootVariablesAreReserved() {

        assertTrue(GdownBolt.RESERVED_ENV.contains("GLUONIFY_CA_FILE"));
        assertTrue(GdownBolt.RESERVED_ENV.contains("GLUONIFY_GDOWN_CA_FILE"));
    }
}
