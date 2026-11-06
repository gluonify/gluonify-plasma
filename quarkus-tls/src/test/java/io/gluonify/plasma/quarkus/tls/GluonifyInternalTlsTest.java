package io.gluonify.plasma.quarkus.tls;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.tls.TlsConfigurationRegistry;
import java.lang.reflect.Proxy;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * The configuration given to a client, and no name without a root.
 *
 * @author David CABILLIC
 */
class GluonifyInternalTlsTest {

    private static final X509TrustManager MANAGER = new X509TrustManager() {

        @Override
        public void checkClientTrusted(final X509Certificate[] chain, final String auth) {

            throw new UnsupportedOperationException("not called");
        }

        @Override
        public void checkServerTrusted(final X509Certificate[] chain, final String auth) {

            throw new UnsupportedOperationException("not called");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {

            return new X509Certificate[0];
        }
    };

    @Test
    void noRootNoName() {

        final GluonifyInternalTls tls = new GluonifyInternalTls();
        tls.onStart(null);
        assertTrue(tls.name().isEmpty());
    }

    @Test
    void aRootIsRegisteredOnce() {

        final List<String> names = new ArrayList<>();
        final GluonifyInternalTls tls = new GluonifyInternalTls();
        tls.registry = (TlsConfigurationRegistry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {TlsConfigurationRegistry.class}, (p, m, a) -> {
            names.add((String) a[0]);
            return null;
        });
        tls.roots = () -> Optional.of(MANAGER);
        assertEquals(GluonifyInternalTls.CONFIGURATION, tls.name().orElseThrow());
        assertEquals(GluonifyInternalTls.CONFIGURATION, tls.name().orElseThrow());
        assertEquals(List.of(GluonifyInternalTls.CONFIGURATION), names);
    }

    @Test
    void theHostNameIsVerified() {

        final var configuration = GluonifyInternalTls.clientConfiguration(MANAGER);
        assertNotNull(configuration.getTrustStoreOptions());
        assertEquals("HTTPS", configuration.getHostnameVerificationAlgorithm().orElseThrow());
        assertEquals("HTTPS", configuration.getClientSSLOptions().getHostnameVerificationAlgorithm());
    }
}
