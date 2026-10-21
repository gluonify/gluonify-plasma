package io.gluonify.plasma.services.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Removal of trailing slashes.
 *
 * @author David CABILLIC
 */
class HttpSlashesTest {

    @Test
    void trailingSlashesAreRemoved() {

        assertEquals("https://a/b", Http.stripTrailingSlashes("https://a/b///"));
    }

    @Test
    void slashesInsideAreKept() {

        assertEquals("a//b", Http.stripTrailingSlashes("a//b"));
    }

    @Test
    void onlySlashesGiveAnEmptyString() {

        assertEquals("", Http.stripTrailingSlashes("////"));
    }

    @Test
    void aLongRunOfSlashesIsLinear() {

        assertEquals("x", Http.stripTrailingSlashes("x" + "/".repeat(200_000)));
    }
}
