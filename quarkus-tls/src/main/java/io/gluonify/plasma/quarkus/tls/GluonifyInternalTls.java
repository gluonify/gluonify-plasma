package io.gluonify.plasma.quarkus.tls;

import io.gluonify.plasma.services.GluonifyTls;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.BaseTlsConfiguration;
import io.quarkus.tls.TlsConfigurationRegistry;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.TrustOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.X509TrustManager;

/**
 * Registers the TLS configuration {@value #CONFIGURATION} (the system's roots plus the cluster's internal authority, {@code GLUONIFY_CA_FILE}) in the Quarkus TLS
 * registry, once, at startup or at the first use. Registered only when the root is there: a name that does not exist must not be passed to a client, so a
 * client asks {@link #name()}.
 *
 * @author David CABILLIC
 */
@ApplicationScoped
public class GluonifyInternalTls {

    /** Name of the TLS configuration. */
    public static final String CONFIGURATION = "gluonify-internal";

    private static final String HOSTNAME_VERIFICATION = "HTTPS";

    @Inject
    TlsConfigurationRegistry registry;

    /** Where the root comes from: the process environment; replaced by the tests. */
    Supplier<Optional<X509TrustManager>> roots = GluonifyTls::trustManager;

    private boolean registered;

    void onStart(@Observes final StartupEvent event) {

        name();
    }

    /**
     * Name of the TLS configuration to give a client.
     *
     * @return {@value #CONFIGURATION} when the root is there, empty otherwise (the JDK's authorities)
     */
    public synchronized Optional<String> name() {

        if (registered) {
            return Optional.of(CONFIGURATION);
        }
        final Optional<X509TrustManager> manager = roots.get();
        manager.ifPresent(m -> {
            registry.register(CONFIGURATION, clientConfiguration(m));
            registered = true;
        });
        return manager.map(m -> CONFIGURATION);
    }

    /**
     * The configuration of a client: the given trust and the host name verified.
     *
     * @param manager The trust manager
     * @return The configuration
     */
    static BaseTlsConfiguration clientConfiguration(final X509TrustManager manager) {

        final TrustOptions trust = TrustOptions.wrap(manager);
        return new BaseTlsConfiguration() {

            @Override
            public TrustOptions getTrustStoreOptions() {

                return trust;
            }

            @Override
            public ClientSSLOptions getClientSSLOptions() {

                return new ClientSSLOptions().setTrustOptions(trust).setHostnameVerificationAlgorithm(HOSTNAME_VERIFICATION);
            }

            @Override
            public Optional<String> getHostnameVerificationAlgorithm() {

                return Optional.of(HOSTNAME_VERIFICATION);
            }
        };
    }
}
