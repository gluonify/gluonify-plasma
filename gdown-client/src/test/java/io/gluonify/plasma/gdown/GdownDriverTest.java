package io.gluonify.plasma.gdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyServicesException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.neo4j.driver.AuthToken;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;

/**
 * The driver factory: the settings read from the platform's variables, the schemes accepted and refused, the trust of the platform's root, the token manager, and a
 * driver built without connecting.
 */
class GdownDriverTest {

    private static final String SERVICE_URL = "GLUONIFY_SERVICE_GDOWN_URL";
    private static final String TOKEN = "GLUONIFY_GDOWN_TOKEN";
    private static final String BOLT_URL = "GLUONIFY_GDOWN_BOLT_URL";
    private static final String CA_FILE = "GLUONIFY_CA_FILE";
    private static final String HOST = "gdown.example.internal";

    @TempDir
    Path home;

    private Map<String, String> platform() {

        final Map<String, String> env = new HashMap<>();
        env.put("GLUONIFY_ENVIRONMENT", "DEV");
        env.put(SERVICE_URL, "https://" + HOST + ":8080");
        env.put(TOKEN, "t0ken-of-the-app");
        return env;
    }

    private GluonifyServices services(final Map<String, String> env) {

        return GluonifyServices.builder().environment(env::get).properties(k -> null).home(home).build();
    }

    private GdownDriver.Settings settings(final Map<String, String> env) {

        return GdownDriver.settings(services(env), env::get);
    }

    @Test
    void thePlatformAddressGivesAnEncryptedBoltOnTheServiceHost() {

        final Map<String, String> env = platform();
        final GdownDriver.Settings s = settings(env);
        assertEquals(URI.create("bolt://" + HOST + ":7687"), s.uri());
        assertTrue(s.encrypted());
        assertEquals(Optional.empty(), s.caFile(), "no root named");
        assertEquals("t0ken-of-the-app", s.tokens().get());
    }

    @Test
    void theInjectedBoltAddressWinsAndLosesItsSecuritySuffix() throws Exception {

        final Map<String, String> env = platform();
        env.put(BOLT_URL, "bolt+s://node-3:7687");
        final Path root = Files.writeString(home.resolve("root.pem"), "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n");
        env.put(CA_FILE, root.toString());
        final GdownDriver.Settings s = settings(env);
        assertEquals(URI.create("bolt://node-3:7687"), s.uri());
        assertTrue(s.encrypted());
        assertEquals(Optional.of(root), s.caFile(), "the platform's root is trusted");
        env.put(BOLT_URL, " neo4j+ssc://node-3:7687/?routing=x ");
        assertEquals(URI.create("neo4j://node-3:7687/?routing=x"), settings(env).uri(), "+ssc is read as +s: a certificate is never trusted blindly");
        assertTrue(settings(env).encrypted());
    }

    @Test
    void aPlainAddressIsAcceptedTowardsTheLocalMachineOnly() {

        final Map<String, String> env = platform();
        env.put(BOLT_URL, "bolt://127.0.0.1:7687");
        final GdownDriver.Settings local = settings(env);
        assertFalse(local.encrypted());
        assertEquals(URI.create("bolt://127.0.0.1:7687"), local.uri());
        env.put(BOLT_URL, "neo4j://10.1.2.3:7687");
        assertFalse(settings(env).encrypted(), "a private network too");
        env.put(BOLT_URL, "bolt://203.0.113.5:7687");
        final IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> settings(env));
        assertTrue(refused.getMessage().contains("TLS only") && refused.getMessage().contains("bolt+s://"), refused.getMessage());
        env.put(BOLT_URL, "http://" + HOST + ":7687");
        final IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class, () -> settings(env));
        assertTrue(scheme.getMessage().contains("no known scheme"), scheme.getMessage());
        env.put(BOLT_URL, "node-3:7687");
        assertThrows(IllegalArgumentException.class, () -> settings(env), "no scheme at all");
        assertFalse(GdownDriver.local(null));
        assertFalse(GdownDriver.local(" "));
        assertFalse(GdownDriver.local("no-such-host.invalid"));
        assertTrue(GdownDriver.local("localhost"));
        assertTrue(GdownDriver.local("169.254.1.1"), "link-local");
    }

    @Test
    void withoutAResolvableServiceTheResolutionSaysWhatToDo() {

        final Map<String, String> env = new HashMap<>();
        env.put("GLUONIFY_ENVIRONMENT", "DEV"); // the platform, without the service granted
        final GluonifyServices services = services(env);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> GdownDriver.settings(services, env::get));
        assertTrue(e.getMessage().contains("gdown"), e.getMessage());
    }

    @Test
    void theConfigurationTrustsThePlatformRootOrTheSystemAuthorities() throws Exception {

        final Config plain = GdownDriver.config(false, Optional.empty());
        assertFalse(plain.encrypted());
        final Config system = GdownDriver.config(true, Optional.empty());
        assertTrue(system.encrypted());
        assertEquals(Config.TrustStrategy.Strategy.TRUST_SYSTEM_CA_SIGNED_CERTIFICATES, system.trustStrategy().strategy());
        assertTrue(system.trustStrategy().isHostnameVerificationEnabled());
        final Path root = Files.writeString(home.resolve("ca.pem"), "x");
        final Config custom = GdownDriver.config(true, Optional.of(root));
        assertEquals(Config.TrustStrategy.Strategy.TRUST_CUSTOM_CA_SIGNED_CERTIFICATES, custom.trustStrategy().strategy());
        assertEquals(List.of(root.toFile()), custom.trustStrategy().certFiles());
        assertTrue(custom.trustStrategy().isHostnameVerificationEnabled());
    }

    @Test
    void theTokenManagerPresentsTheCurrentTokenAsABearerToken() throws Exception {

        final AuthToken a = GdownDriver.auth(() -> " first ").getToken().toCompletableFuture().get();
        assertEquals(AuthTokens.bearer("first"), a, "stripped");
        assertEquals(AuthTokens.bearer(""), GdownDriver.auth(() -> null).getToken().toCompletableFuture().get(), "no token: an empty bearer, the server answers");
        assertEquals(AuthTokens.bearer("t"), GdownDriver.auth(() -> "t").getToken().toCompletableFuture().get(), "the manager keeps a token until it expires, " + GdownDriver.TOKEN_LIFETIME);
    }

    @Test
    void aDriverIsBuiltWithoutConnecting() {

        final Map<String, String> env = platform();
        env.put(BOLT_URL, "bolt+s://" + HOST + ":7687");
        try (Driver d = GdownDriver.create(services(env), env::get)) {
            assertNotNull(d);
        }
        env.remove(TOKEN);
        try (Driver d = GdownDriver.create(services(env), env::get)) {
            assertNotNull(d, "without a token: no authentication, the server answers");
        }
        env.put(TOKEN, " ");
        try (Driver d = GdownDriver.create(services(env), env::get)) {
            assertNotNull(d, "a blank token is none");
        }
    }
}
