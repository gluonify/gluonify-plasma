package io.gluonify.plasma.quarkus.tls;

import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.common.MapBackedConfigSource;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Moves the application's port to TLS only when the platform hands over the PEM pair of {@link GluonifyServerTls}: an instance has one port ({@code quarkus.http.port}),
 * so the TLS listener takes it and the plain listener is not started. An explicit {@code QUARKUS_HTTP_*} of the environment still wins (this source sits between it and
 * {@code application.properties}).
 *
 * @author David CABILLIC
 */
public final class ServerTlsConfigSourceFactory implements ConfigSourceFactory {

    private static final int ORDINAL = 275;

    private static final String DEFAULT_PORT = "8080";

    @Override
    public Iterable<ConfigSource> getConfigSources(final ConfigSourceContext context) {

        final Map<String, String> properties = properties(name -> value(context.getValue(name)));
        return properties.isEmpty() ? List.of() : List.of(new MapBackedConfigSource("gluonify-server-tls", properties, ORDINAL) {

            private static final long serialVersionUID = 1L;
        });
    }

    /**
     * The Quarkus properties implied by the settings.
     *
     * @param settings Reads a setting, null when absent
     * @return The properties; empty without the certificate
     */
    static Map<String, String> properties(final UnaryOperator<String> settings) {

        final String certificate = settings.apply(GluonifyServerTls.CERTIFICATE_PROPERTY);
        if (certificate == null || certificate.isBlank()) {
            return Map.of();
        }
        final String port = settings.apply("quarkus.http.port");
        return Map.of("quarkus.http.tls-configuration-name", GluonifyServerTls.CONFIGURATION,
                "quarkus.http.insecure-requests", "disabled",
                "quarkus.http.ssl-port", port == null || port.isBlank() ? DEFAULT_PORT : port);
    }

    private static String value(final ConfigValue value) {

        return value == null ? null : value.getValue();
    }
}
