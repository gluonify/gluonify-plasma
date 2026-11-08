package io.gluonify.plasma.quarkus.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.PemKeyCertOptions;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The server TLS of an application: nothing without the pair, the pair registered once at startup, PEM or its base64, and the Quarkus properties that move the port to
 * TLS only.
 *
 * @author David CABILLIC
 */
class GluonifyServerTlsTest {

    private static final String CERTIFICATE = "-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n";

    private static final String KEY = "-----BEGIN PRIVATE KEY-----\nMIIE\n-----END PRIVATE KEY-----\n";

    private static final String PORT = "quarkus.http.port";

    private final List<String> names = new ArrayList<>();

    private GluonifyServerTls tls(final String certificate, final String key) {

        final GluonifyServerTls tls = new GluonifyServerTls();
        tls.certificate = Optional.ofNullable(certificate);
        tls.privateKey = Optional.ofNullable(key);
        tls.registry = (TlsConfigurationRegistry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TlsConfigurationRegistry.class}, (p, m, a) -> {
            names.add((String) a[0]);
            return null;
        });
        return tls;
    }

    @Test
    void withoutThePairNothingIsRegistered() {

        tls(null, " ").onStart(null);
        assertTrue(names.isEmpty());
    }

    @Test
    void thePairIsRegisteredAtStartup() {

        tls(CERTIFICATE, KEY).onStart(null);
        assertEquals(List.of(GluonifyServerTls.CONFIGURATION), names);
    }

    @Test
    void halfAPairStopsTheStartup() {

        final GluonifyServerTls tls = tls(CERTIFICATE, null);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> tls.onStart(null));
        assertEquals("gluonify.tls.certificate-pem and gluonify.tls.private-key-pem go together", e.getMessage());
    }

    @Test
    void theConfigurationCarriesThePairAsPem() {

        final String base64 = Base64.getEncoder().encodeToString(KEY.getBytes(StandardCharsets.US_ASCII));
        final var configuration = GluonifyServerTls.serverConfiguration(CERTIFICATE, base64);
        final PemKeyCertOptions options = assertInstanceOf(PemKeyCertOptions.class, configuration.getKeyStoreOptions());
        assertEquals(CERTIFICATE, options.getCertValue().toString());
        assertEquals(KEY, options.getKeyValue().toString(), "the base64 of the PEM is decoded");
        assertNotNull(configuration.getServerSSLOptions());
    }

    @Test
    void aValueThatIsNotPemIsRefused() {

        assertEquals("gluonify.tls.private-key-pem: a PEM PRIVATE KEY block expected",
                assertThrows(IllegalArgumentException.class, () -> GluonifyServerTls.serverConfiguration(CERTIFICATE, CERTIFICATE)).getMessage());
        assertEquals("gluonify.tls.certificate-pem: neither PEM nor base64",
                assertThrows(IllegalArgumentException.class, () -> GluonifyServerTls.serverConfiguration("QQ=Q", KEY)).getMessage());
    }

    @Test
    void thePortMovesToTlsOnlyWithTheCertificate() {

        assertTrue(ServerTlsConfigSourceFactory.properties(name -> null).isEmpty());
        assertTrue(ServerTlsConfigSourceFactory.properties(name -> GluonifyServerTls.CERTIFICATE_PROPERTY.equals(name) ? "" : null).isEmpty());
        final Map<String, String> sameDefault = ServerTlsConfigSourceFactory.properties(name -> GluonifyServerTls.CERTIFICATE_PROPERTY.equals(name) ? CERTIFICATE : null);
        assertEquals(Map.of("quarkus.http.tls-configuration-name", GluonifyServerTls.CONFIGURATION, "quarkus.http.insecure-requests", "disabled",
                "quarkus.http.ssl-port", "8080"), sameDefault);
        final Map<String, String> instancePort = ServerTlsConfigSourceFactory.properties(name -> switch (name) {
            case GluonifyServerTls.CERTIFICATE_PROPERTY -> CERTIFICATE;
            case PORT -> "31002";
            default -> null;
        });
        assertEquals("31002", instancePort.get("quarkus.http.ssl-port"), "the TLS listener takes the instance's port");
        assertEquals("8080", ServerTlsConfigSourceFactory.properties(name -> PORT.equals(name) ? " " : CERTIFICATE).get("quarkus.http.ssl-port"));
        assertFalse(new ServerTlsConfigSourceFactory().getConfigSources(new MapContext(Map.of())).iterator().hasNext());
        final var source = new ServerTlsConfigSourceFactory().getConfigSources(new MapContext(Map.of(GluonifyServerTls.CERTIFICATE_PROPERTY, CERTIFICATE))).iterator().next();
        assertEquals("disabled", source.getValue("quarkus.http.insecure-requests"));
    }

    /** A configuration context over fixed values. */
    private record MapContext(Map<String, String> values) implements io.smallrye.config.ConfigSourceContext {

        @Override
        public io.smallrye.config.ConfigValue getValue(final String name) {

            final String value = values.get(name);
            return value == null ? null : io.smallrye.config.ConfigValue.builder().withName(name).withValue(value).build();
        }

        @Override
        public List<String> getProfiles() {

            return List.of();
        }

        @Override
        public java.util.Iterator<String> iterateNames() {

            return java.util.Collections.emptyIterator();
        }
    }
}
