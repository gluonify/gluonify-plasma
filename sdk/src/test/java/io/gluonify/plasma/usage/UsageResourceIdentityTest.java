package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

/**
 * The identity of the usage resource read from the configuration.
 *
 * @author David CABILLIC
 */
class UsageResourceIdentityTest {

    private static Config config(final Map<String, String> values) {

        return (Config) Proxy.newProxyInstance(Config.class.getClassLoader(), new Class<?>[] {Config.class},
                (proxy, method, args) -> Optional.ofNullable(values.get((String) args[0])));
    }

    @Test
    void theComponentIsTheApplicationNameWithoutTheBrandPrefix() {

        final UsageReport.Identity id = UsageResource.identity(config(Map.of("quarkus.application.name", "gluonify-rup", "quarkus.application.version", "1.2")));

        assertEquals(new UsageReport.Identity("rup", "1.2", null, null), id);
    }

    @Test
    void theConfiguredValuesWin() {

        final UsageReport.Identity id = UsageResource.identity(config(Map.of("gluonify.usage.component", "c", "gluonify.usage.instance", "i-1", "gluonify.usage.node", "n1")));

        assertEquals(new UsageReport.Identity("c", "unknown", "i-1", "n1"), id);
    }

    @Test
    void withoutAnyConfigurationTheNameIsUnknown() {

        final UsageReport.Identity id = UsageResource.identity(config(Map.of()));

        assertEquals("unknown", id.component());
        assertNull(id.instance());
    }
}
