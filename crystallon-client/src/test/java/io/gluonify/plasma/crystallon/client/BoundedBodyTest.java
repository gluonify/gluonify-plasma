package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Value semantics of {@link BoundedBody.Body}: bodies are compared by the content of their bytes.
 *
 * @author David CABILLIC
 */
class BoundedBodyTest {

    @Test
    void bodiesWithTheSameBytesAreEqual() {

        final BoundedBody.Body a = new BoundedBody.Body(new byte[] {1, 2}, false);
        final BoundedBody.Body b = new BoundedBody.Body(new byte[] {1, 2}, false);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void bodiesDifferByBytesOrLimitFlag() {

        final BoundedBody.Body a = new BoundedBody.Body(new byte[] {1, 2}, false);
        assertNotEquals(a, new BoundedBody.Body(new byte[] {1, 3}, false));
        assertNotEquals(a, new BoundedBody.Body(new byte[] {1, 2}, true));
        assertNotEquals(a, "body");
    }

    @Test
    void toStringGivesTheSizeNotTheContent() {

        assertTrue(new BoundedBody.Body(new byte[] {7, 7, 7}, true).toString().contains("bytes=3 bytes, tooLarge=true"));
    }
}
