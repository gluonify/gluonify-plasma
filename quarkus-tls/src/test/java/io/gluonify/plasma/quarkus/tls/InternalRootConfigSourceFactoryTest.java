package io.gluonify.plasma.quarkus.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Trust of the internal root for the OIDC tenants: nothing without a readable root, the named configuration with it, and the tenants that reach Charm's service address
 * over HTTPS pointed at it, whether their address is written with Charm's variable or comes expanded.
 *
 * @author David CABILLIC
 */
class InternalRootConfigSourceFactoryTest {

    private static final String CHARM = "https://192.168.1.4:31002";

    private static final String DEFAULT_TENANT = "quarkus.oidc.tls.tls-configuration-name";

    private static final String PORTAL_TENANT = "quarkus.oidc.portal.tls.tls-configuration-name";

    @TempDir
    Path dir;

    private Path root() throws IOException {

        final Path file = dir.resolve("root.pem");
        Files.writeString(file, "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
        return file;
    }

    @Test
    void withoutARootNothingChanges() {

        assertTrue(InternalRootConfigSourceFactory.properties(name -> null, Set.of()).isEmpty());
        assertTrue(InternalRootConfigSourceFactory.properties(name -> "GLUONIFY_CA_FILE".equals(name) ? dir.resolve("absent.pem").toString() : null, Set.of()).isEmpty(),
                "an unreadable root is no root");
    }

    @Test
    void theRootAloneDefinesTheConfigurationButTouchesNoTenantWhenCharmIsPlain() throws IOException {

        final String file = root().toString();
        final Map<String, String> settings = Map.of("GLUONIFY_CA_FILE", file, InternalRootConfigSourceFactory.CHARM_URL, "http://192.168.1.4:31002",
                "quarkus.oidc.auth-server-url", "http://192.168.1.4:31002/realms/gluonify");
        assertEquals(Map.of(InternalRootConfigSourceFactory.TRUST_PROPERTY, file), InternalRootConfigSourceFactory.properties(settings::get, Set.of()));
    }

    @Test
    void theTenantsTowardsCharmTrustTheRoot() throws IOException {

        final String file = root().toString();
        final Map<String, String> settings = new HashMap<>(Map.of("GLUONIFY_GDOWN_CA_FILE", file, InternalRootConfigSourceFactory.CHARM_URL, CHARM + "/",
                "quarkus.oidc.auth-server-url", CHARM + "/realms/gluonify",
                "quarkus.oidc.portal.auth-server-url", "${GLUONIFY_SERVICE_CHARM_URL:http://localhost:8180}/realms/gluonify",
                "quarkus.oidc.google.auth-server-url", "https://accounts.google.com",
                "quarkus.oidc.own.auth-server-url", CHARM + "/realms/gluonify",
                "quarkus.oidc.own.tls.tls-configuration-name", "mine"));
        final Map<String, String> out = InternalRootConfigSourceFactory.properties(settings::get, Set.of("quarkus.oidc.portal", "quarkus.oidc.google", "quarkus.oidc.own"));
        assertEquals(Map.of(InternalRootConfigSourceFactory.TRUST_PROPERTY, file, DEFAULT_TENANT, InternalRootConfigSourceFactory.CONFIGURATION,
                PORTAL_TENANT, InternalRootConfigSourceFactory.CONFIGURATION), out, "a public provider and a tenant with its own configuration are left alone");
    }

    @Test
    void charmsAddressIsRecognisedOnlyAsAPrefixOfAPath() {

        assertTrue(InternalRootConfigSourceFactory.towardsCharm(CHARM, CHARM));
        assertTrue(InternalRootConfigSourceFactory.towardsCharm(" ${GLUONIFY_SERVICE_CHARM_URL}/realms/gluonify", CHARM));
        assertFalse(InternalRootConfigSourceFactory.towardsCharm(CHARM + "0/realms/gluonify", CHARM), "another port is another server");
        assertFalse(InternalRootConfigSourceFactory.towardsCharm(null, CHARM));
    }

    @Test
    void theTenantsAreReadFromTheDottedNames() {

        assertEquals(Set.of("quarkus.oidc", "quarkus.oidc.portal", "quarkus.oidc-client", "quarkus.oidc-client.mail"),
                InternalRootConfigSourceFactory.names(List.of("quarkus.oidc.auth-server-url", "quarkus.oidc.portal.auth-server-url", "quarkus.oidc-client.auth-server-url",
                        "quarkus.oidc-client.mail.auth-server-url", "quarkus.oidc.portal.client-id", "QUARKUS_OIDC_AUTH_SERVER_URL").iterator()));
    }

    @Test
    void theFactoryWorksInsideARealConfiguration() throws IOException {

        final String file = root().toString();
        final SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(Map.of("GLUONIFY_CA_FILE", file, InternalRootConfigSourceFactory.CHARM_URL, CHARM), "environment", 300))
                .withSources(new PropertiesConfigSource(Map.of("quarkus.oidc.auth-server-url", "${GLUONIFY_SERVICE_CHARM_URL:http://localhost:8180}/realms/gluonify",
                        "quarkus.oidc.on-behalf-of.auth-server-url", "${quarkus.oidc.auth-server-url}"), "application.properties", 250))
                .withSources(new InternalRootConfigSourceFactory()).build();
        assertEquals(file, config.getConfigValue(InternalRootConfigSourceFactory.TRUST_PROPERTY).getValue());
        assertEquals(InternalRootConfigSourceFactory.CONFIGURATION, config.getConfigValue(DEFAULT_TENANT).getValue());
        assertEquals(InternalRootConfigSourceFactory.CONFIGURATION, config.getConfigValue("quarkus.oidc.on-behalf-of.tls.tls-configuration-name").getValue(),
                "a tenant that copies the default tenant's address is recognised once expanded");
    }

    @Test
    void theFactoryAddsNoSourceWithoutARoot() {

        final SmallRyeConfig config = new SmallRyeConfigBuilder().addDefaultInterceptors().withSources(new InternalRootConfigSourceFactory()).build();
        assertTrue(config.getOptionalValue(InternalRootConfigSourceFactory.TRUST_PROPERTY, String.class).isEmpty());
    }
}
