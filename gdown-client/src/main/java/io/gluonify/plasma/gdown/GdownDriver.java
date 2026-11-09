package io.gluonify.plasma.gdown;

import io.gluonify.plasma.GdownBolt;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyTls;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.neo4j.driver.AuthTokenManager;
import org.neo4j.driver.AuthTokenManagers;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Config;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * A Neo4j driver connected to the platform's gDown without configuration: the Bolt address the platform injects ({@code GLUONIFY_GDOWN_BOLT_URL}, else port 7687 of the
 * service address), the application's token re-read before it expires (the gDown Bolt accepts it as a bearer token), and the platform's internal root
 * ({@code GLUONIFY_CA_FILE}) added to the certificates the driver trusts. Outside the platform the resolution of {@code gluonify-plasma-services} applies: an override
 * ({@code GLUONIFY_SERVICE_GDOWN_URL}, {@code GLUONIFY_GDOWN_TOKEN}) or the developer's zone after {@code gluonify login}.
 *
 * <p>TLS is mandatory towards the platform: a plain {@code bolt://} or {@code neo4j://} address is accepted only towards a loopback, private or link-local host (a
 * gDown of the developer's machine), refused otherwise. A {@code +s} or {@code +ssc} scheme means encryption with the platform's root when it is present, else the
 * system's authorities; a certificate is never trusted blindly.
 *
 * @author David CABILLIC
 */
public final class GdownDriver {

    /** The service name in {@code uses} and in the resolution. */
    public static final String SERVICE = "gdown";

    /** How long a token handed to the driver is used before it is read again. */
    static final Duration TOKEN_LIFETIME = Duration.ofMinutes(5);

    private static final Set<String> PLAIN_SCHEMES = Set.of("bolt", "neo4j");

    private static final Set<String> ENCRYPTED_SCHEMES = Set.of("bolt+s", "bolt+ssc", "neo4j+s", "neo4j+ssc");

    private GdownDriver() {
    }

    /**
     * The driver of the process: platform, override or developer workstation. It connects lazily, as every Neo4j driver.
     *
     * @return The driver; the caller closes it
     * @throws io.gluonify.plasma.services.GluonifyServicesException When no gDown can be resolved
     * @throws IllegalArgumentException When the Bolt address is plain towards a public host, or has an unknown scheme
     */
    public static Driver create() {

        return create(GluonifyServices.defaults(), System::getenv);
    }

    /**
     * A driver for a given resolution and environment (tests, embedded uses).
     *
     * @param services    The service resolution
     * @param environment Reads a variable
     * @return The driver; the caller closes it
     * @throws io.gluonify.plasma.services.GluonifyServicesException When no gDown can be resolved
     * @throws IllegalArgumentException When the Bolt address is plain towards a public host, or has an unknown scheme
     */
    public static Driver create(final GluonifyServices services, final UnaryOperator<String> environment) {

        final Settings s = settings(services, environment);
        final Config config = config(s.encrypted(), s.caFile());
        final String first = s.tokens().get();
        if (first == null || first.isBlank()) {
            return GraphDatabase.driver(s.uri(), AuthTokens.none(), config);
        }
        return GraphDatabase.driver(s.uri(), auth(s.tokens()), config);
    }

    /**
     * What a driver is built from: the Bolt address without its security suffix, whether the connection is encrypted, the root to trust and the token source.
     *
     * @param uri       The address, scheme {@code bolt} or {@code neo4j}
     * @param encrypted True for TLS
     * @param caFile    The platform's root, or empty
     * @param tokens    The token source (the resolution's), {@code null} or blank for none
     */
    record Settings(URI uri, boolean encrypted, Optional<Path> caFile, Supplier<String> tokens) {
    }

    /**
     * The settings of the process environment.
     *
     * @param services    The service resolution
     * @param environment Reads a variable
     * @return The settings
     */
    static Settings settings(final GluonifyServices services, final UnaryOperator<String> environment) {

        final Endpoint endpoint = services.resolve(SERVICE);
        final String bolt = environment.apply(GdownBolt.URL_ENV);
        final URI given = bolt == null || bolt.isBlank() ? boltOf(endpoint.url()) : URI.create(bolt.strip());
        final String scheme = given.getScheme() == null ? "" : given.getScheme().toLowerCase(Locale.ROOT);
        final boolean encrypted;
        if (ENCRYPTED_SCHEMES.contains(scheme)) {
            encrypted = true;
        } else if (PLAIN_SCHEMES.contains(scheme)) {
            if (!local(given.getHost())) {
                throw new IllegalArgumentException("A plain " + scheme + ":// address towards " + given.getHost() + " is refused: gDown Bolt is TLS only outside the local machine (use "
                        + scheme + "+s://)");
            }
            encrypted = false;
        } else {
            throw new IllegalArgumentException("The Bolt address " + given + " has no known scheme (bolt, bolt+s, neo4j, neo4j+s)");
        }
        final URI uri = URI.create(scheme.substring(0, scheme.indexOf('+') < 0 ? scheme.length() : scheme.indexOf('+')) + given.toString().substring(scheme.length()));
        final Optional<Path> ca = endpoint.caFile() != null ? Optional.of(endpoint.caFile()) : GluonifyTls.caFile(environment);
        return new Settings(uri, encrypted, ca, endpoint.tokens());
    }

    /** The Bolt address of a service address: its host, the Bolt port, encrypted. */
    private static URI boltOf(final URI service) {

        return URI.create("bolt+s://" + service.getHost() + ":" + GdownBolt.PORT);
    }

    /**
     * Whether a host is the local machine, or on a private or link-local network: a plain connection may go there.
     *
     * @param host The host, {@code null} for none
     * @return True when the host resolves to such an address
     */
    static boolean local(final String host) {

        if (host == null || host.isBlank()) {
            return false;
        }
        try {
            final InetAddress a = InetAddress.getByName(host);
            return a.isLoopbackAddress() || a.isSiteLocalAddress() || a.isLinkLocalAddress();
        } catch (final UnknownHostException e) {
            return false;
        }
    }

    /**
     * The driver configuration: encrypted with the platform's root (the system's authorities otherwise), or plain.
     *
     * @param encrypted True for TLS
     * @param caFile    The platform's root, or empty
     * @return The configuration
     */
    static Config config(final boolean encrypted, final Optional<Path> caFile) {

        final Config.ConfigBuilder b = Config.builder();
        if (!encrypted) {
            return b.withoutEncryption().build();
        }
        b.withEncryption();
        if (caFile.isPresent()) {
            b.withTrustStrategy(Config.TrustStrategy.trustCustomCertificateSignedBy(caFile.get().toFile()).withHostnameVerification());
        } else {
            b.withTrustStrategy(Config.TrustStrategy.trustSystemCertificates().withHostnameVerification());
        }
        return b.build();
    }

    /**
     * The token manager: the token of the resolution, as a bearer token, read again every {@link #TOKEN_LIFETIME} (the node agent renews the token file).
     *
     * @param tokens The token source
     * @return The manager
     */
    static AuthTokenManager auth(final Supplier<String> tokens) {

        return AuthTokenManagers.bearer(() -> {
            final String token = tokens.get();
            final String bearer = token == null ? "" : token.strip();
            return AuthTokens.bearer(bearer).expiringAt(System.currentTimeMillis() + TOKEN_LIFETIME.toMillis());
        });
    }
}
