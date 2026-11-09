package io.gluonify.plasma.quarkus.tls;

import io.gluonify.plasma.services.GluonifyTls;
import io.smallrye.config.ConfigSourceContext;
import io.smallrye.config.ConfigSourceFactory;
import io.smallrye.config.ConfigValue;
import io.smallrye.config.common.MapBackedConfigSource;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Trust of the cluster's internal authority for the clients Quarkus configures itself (gluonify-codex/docs/internal-tls.md, stage 2): when the platform hands over the root
 * ({@code GLUONIFY_CA_FILE}, alias {@code GLUONIFY_GDOWN_CA_FILE}), the named TLS configuration {@value #CONFIGURATION} trusts that root, and every OIDC tenant or OIDC client
 * whose {@code auth-server-url} is Charm's service address over {@code https://} ({@code GLUONIFY_SERVICE_CHARM_URL}) uses it. A configuration defined here, not
 * registered by code, so that the OIDC tenants find it when they are created, whatever the start order. A tenant that names its own TLS configuration keeps it; a tenant
 * that reaches another server (a public identity provider) keeps the JDK's authorities.
 *
 * @author David CABILLIC
 */
public final class InternalRootConfigSourceFactory implements ConfigSourceFactory {

    /** Name of the TLS configuration that trusts the internal root alone. */
    public static final String CONFIGURATION = "gluonify-root";

    /** Variable of Charm's service address, as the platform hands it over. */
    static final String CHARM_URL = "GLUONIFY_SERVICE_CHARM_URL";

    /** Property of the root file in the TLS configuration. */
    static final String TRUST_PROPERTY = "quarkus.tls." + CONFIGURATION + ".trust-store.pem.certs";

    private static final int ORDINAL = 275;

    private static final String AUTH_SERVER_SUFFIX = ".auth-server-url";

    private static final String TLS_NAME_SUFFIX = ".tls.tls-configuration-name";

    private static final String HTTPS = "https://";

    /** The OIDC extension's default tenant and the OIDC client's default client, also given by the environment (no dotted name to iterate then). */
    private static final List<String> DEFAULT_TENANTS = List.of("quarkus.oidc", "quarkus.oidc-client");

    /** A tenant's or a client's server address: {@code quarkus.oidc[-client][.<name>].auth-server-url}. */
    private static final Pattern AUTH_SERVER = Pattern.compile("(quarkus\\.oidc(?:-client)?+(?:\\.[a-z0-9_-]++)?)\\.auth-server-url");

    @Override
    public Iterable<ConfigSource> getConfigSources(final ConfigSourceContext context) {

        final Map<String, String> properties = properties(name -> value(context.getValue(name)), names(context.iterateNames()));
        return properties.isEmpty() ? List.of() : List.of(new MapBackedConfigSource("gluonify-internal-root", properties, ORDINAL) {

            private static final long serialVersionUID = 1L;
        });
    }

    /**
     * The Quarkus properties implied by the settings.
     *
     * @param settings Reads a setting, null when absent
     * @param tenants  The OIDC tenants and clients the configuration names besides the defaults, as property prefixes ({@code quarkus.oidc.portal})
     * @return The properties; empty without a readable root
     */
    static Map<String, String> properties(final UnaryOperator<String> settings, final Set<String> tenants) {

        final Optional<Path> root = GluonifyTls.caFile(settings);
        if (root.isEmpty()) {
            return Map.of();
        }
        final Map<String, String> out = new TreeMap<>();
        out.put(TRUST_PROPERTY, root.get().toString());
        final String charm = settings.apply(CHARM_URL);
        if (charm == null || !charm.strip().startsWith(HTTPS)) {
            return out;
        }
        final Set<String> all = new TreeSet<>(DEFAULT_TENANTS);
        all.addAll(tenants);
        for (String tenant : all) {
            if (towardsCharm(settings.apply(tenant + AUTH_SERVER_SUFFIX), charm.strip()) && settings.apply(tenant + TLS_NAME_SUFFIX) == null) {
                out.put(tenant + TLS_NAME_SUFFIX, CONFIGURATION);
            }
        }
        return out;
    }

    /**
     * Whether a server address is Charm's service address.
     *
     * @param url   The tenant's address, expanded or not
     * @param charm Charm's service address
     * @return True when it starts with Charm's address or refers to its variable
     */
    static boolean towardsCharm(final String url, final String charm) {

        if (url == null) {
            return false;
        }
        final String value = url.strip();
        final String base = charm.endsWith("/") ? charm.substring(0, charm.length() - 1) : charm;
        return value.equals(base) || value.startsWith(base + "/") || value.startsWith("${" + CHARM_URL + "}") || value.startsWith("${" + CHARM_URL + ":");
    }

    /**
     * The tenants and clients named by dotted properties.
     *
     * @param names The configuration's property names
     * @return Their prefixes ({@code quarkus.oidc.portal}, {@code quarkus.oidc-client})
     */
    static Set<String> names(final Iterator<String> names) {

        final Set<String> out = new TreeSet<>();
        while (names.hasNext()) {
            final Matcher m = AUTH_SERVER.matcher(names.next());
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static String value(final ConfigValue value) {

        return value == null ? null : value.getValue();
    }
}
