package io.gluonify.plasma.console;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.vertx.ext.web.RoutingContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * The filter without a container: a page without a head, and the dev mode where nothing is packaged.
 *
 * @author David CABILLIC
 */
class ConsoleIndexPlainTest {

    @Test
    void aPageWithoutAHeadIsLeftToTheBrowser() {

        assertNull(ConsoleIndex.split("<html><body>no head</body></html>"));
        final ConsoleIndex.Page page = ConsoleIndex.split("<html><head><title>x</title></head></html>");
        assertEquals("<html><head><base href=\"/top/\"><title>x</title></head></html>", page.with("/top/"));
        assertEquals("<html><head><base href=\"/top/\"><title>x</title></head></html>", ConsoleIndex.withBase("<html><head><title>x</title></head></html>", "/top"));
        assertEquals("<html><head><base href=\"/\"><title>x</title></head></html>", ConsoleIndex.withBase("<html><head><title>x</title></head></html>", "https://evil/"));
        assertEquals("<html><body>no head</body></html>", ConsoleIndex.withBase("<html><body>no head</body></html>", "/top"));
    }

    @Test
    void aPageThatIsAbsentOrUnreadableIsNone() {

        assertNull(ConsoleIndex.load(() -> null));
        assertNull(ConsoleIndex.load(() -> new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("broken");
            }
        }));
        assertEquals("x", ConsoleIndex.load(() -> new java.io.ByteArrayInputStream("<head>x".getBytes())).tail());
        assertNull(ConsoleIndex.load(() -> new java.io.ByteArrayInputStream("no head".getBytes())));
    }

    @Test
    void withoutAPackagedPageEveryRequestGoesThrough() {

        final AtomicBoolean next = new AtomicBoolean();
        final RoutingContext ctx = (RoutingContext) Proxy.newProxyInstance(ConsoleIndexPlainTest.class.getClassLoader(), new Class<?>[] {RoutingContext.class}, (p, m, a) -> {
            if ("next".equals(m.getName())) {
                next.set(true);
                return null;
            }
            throw new UnsupportedOperationException(m.getName()); // the request is not even looked at
        });
        new ConsoleIndex((ConsoleIndex.Page) null).serve(ctx);
        assertTrue(next.get());
    }
}
