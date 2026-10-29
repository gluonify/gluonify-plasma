package io.gluonify.plasma.logging;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logmanager.ExtHandler;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

/**
 * With the module on the classpath and no configuration of the application, Quarkus sets the filter on the handlers: the default comes
 * from the module's {@code META-INF/microprofile-config.properties}.
 *
 * @author David CABILLIC
 */
@QuarkusTest
class JwsRedactionDefaultTest {

    @Test
    void theHandlersFilterIsOnByDefault() {

        assertEquals(JwsRedactionFilter.NAME, ConfigProvider.getConfig().getValue("quarkus.log.console.filter", String.class));
        assertEquals(JwsRedactionFilter.NAME, ConfigProvider.getConfig().getValue("quarkus.log.file.filter", String.class));
        assertEquals(JwsRedactionFilter.NAME, ConfigProvider.getConfig().getValue("quarkus.log.syslog.filter", String.class));
    }

    @Test
    void theConsoleHandlerRedacts() {

        final List<Filter> filters = new ArrayList<>();
        for (final Handler handler : Logger.getLogger("").getHandlers()) {
            collect(handler, filters);
        }
        final ExtLogRecord logRecord = new ExtLogRecord(org.jboss.logmanager.Level.WARN, "Token verification has failed: %s", ExtLogRecord.FormatStyle.PRINTF,
                JwsRedactionDefaultTest.class.getName());
        logRecord.setLoggerName("io.quarkus.oidc.runtime.OidcProvider");
        logRecord.setParameters(new Object[] {JwsRedactionFilterTest.sampleJws()});
        // the handlers' filter is a composite (Quarkus' cleanup filter and the named ones): it is judged by what it does to a record
        filters.forEach(f -> f.isLoggable(logRecord));
        assertEquals("Token verification has failed: " + JwsRedactionFilter.REDACTED, logRecord.getFormattedMessage(), "filters of the handlers: " + filters);
    }

    // the console handler sits behind Quarkus' wrappers (delayed and async handlers): their children are walked
    private static void collect(final Handler handler, final List<Filter> into) {

        if (handler.getFilter() != null) {
            into.add(handler.getFilter());
        }
        if (handler instanceof ExtHandler ext) {
            for (final Handler child : ext.getHandlers()) {
                collect(child, into);
            }
        }
    }
}
