package io.gluonify.plasma.quarkus.tls;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.KeyCertOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.ServerSSLOptions;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * HTTPS of the application's own port from the PEM pair the platform hands over when the application serves TLS (gluonify-codex/docs/internal-tls.md, stage 2:
 * {@code GLUONIFY_TLS_CERTIFICATE_PEM} and {@code GLUONIFY_TLS_PRIVATE_KEY_PEM}, issued by the cluster's internal authority, never written to disk). Registered in the Quarkus
 * TLS registry under {@value #CONFIGURATION} before the HTTP server starts; {@link ServerTlsConfigSourceFactory} then moves the port to TLS only. Without the pair nothing
 * changes. A half pair or a value that is not PEM stops the application at startup.
 *
 * @author David CABILLIC
 */
@ApplicationScoped
public class GluonifyServerTls {

    /** Name of the server TLS configuration. */
    public static final String CONFIGURATION = "gluonify-server";

    /** Property of the certificate chain (environment {@code GLUONIFY_TLS_CERTIFICATE_PEM}). */
    public static final String CERTIFICATE_PROPERTY = "gluonify.tls.certificate-pem";

    /** Property of the private key (environment {@code GLUONIFY_TLS_PRIVATE_KEY_PEM}). */
    public static final String PRIVATE_KEY_PROPERTY = "gluonify.tls.private-key-pem";

    private static final String PEM_HEADER = "-----BEGIN ";

    @Inject
    TlsConfigurationRegistry registry;

    @ConfigProperty(name = CERTIFICATE_PROPERTY)
    Optional<String> certificate;

    @ConfigProperty(name = PRIVATE_KEY_PROPERTY)
    Optional<String> privateKey;

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) final StartupEvent event) {

        if (enabled(certificate, privateKey)) {
            registry.register(CONFIGURATION, serverConfiguration(certificate.orElseThrow(), privateKey.orElseThrow()));
        }
    }

    /**
     * Whether the application serves TLS.
     *
     * @param certificate The certificate chain
     * @param privateKey  The private key
     * @return True when the pair is present
     * @throws IllegalArgumentException When only one value of the pair is present
     */
    static boolean enabled(final Optional<String> certificate, final Optional<String> privateKey) {

        final boolean present = present(certificate);
        if (present != present(privateKey)) {
            throw new IllegalArgumentException(CERTIFICATE_PROPERTY + " and " + PRIVATE_KEY_PROPERTY + " go together");
        }
        return present;
    }

    private static boolean present(final Optional<String> value) {

        return value.filter(v -> !v.isBlank()).isPresent();
    }

    /**
     * The server configuration of a PEM pair.
     *
     * @param certificate The certificate chain, PEM text or its base64
     * @param privateKey  The PKCS#8 private key, PEM text or its base64
     * @return The configuration
     * @throws IllegalArgumentException When a value is not PEM
     */
    static BaseTlsConfiguration serverConfiguration(final String certificate, final String privateKey) {

        final KeyCertOptions options = new PemKeyCertOptions()
                .setCertValue(Buffer.buffer(pem(certificate, "CERTIFICATE", CERTIFICATE_PROPERTY)))
                .setKeyValue(Buffer.buffer(pem(privateKey, "PRIVATE KEY", PRIVATE_KEY_PROPERTY)));
        return new BaseTlsConfiguration() {

            @Override
            public KeyCertOptions getKeyStoreOptions() {

                return options;
            }

            @Override
            public ServerSSLOptions getServerSSLOptions() {

                return new ServerSSLOptions();
            }
        };
    }

    /**
     * The PEM text of a value given as PEM or as the base64 of the PEM (a value without line breaks travels better in some environments).
     *
     * @param value    The value
     * @param kind     The PEM label expected ({@code CERTIFICATE}, {@code PRIVATE KEY})
     * @param property The property, for the error
     * @return The PEM text
     * @throws IllegalArgumentException When the value is not a PEM block of that kind
     */
    static String pem(final String value, final String kind, final String property) {

        final String text = value.contains(PEM_HEADER) ? value : decode(value, property);
        if (!text.contains(kind + "-----")) {
            throw new IllegalArgumentException(property + ": a PEM " + kind + " block expected");
        }
        return text;
    }

    private static String decode(final String value, final String property) {

        try {
            return new String(Base64.getMimeDecoder().decode(value.strip()), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(property + ": neither PEM nor base64", e);
        }
    }
}
