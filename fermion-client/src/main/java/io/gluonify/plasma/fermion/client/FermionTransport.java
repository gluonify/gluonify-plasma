package io.gluonify.plasma.fermion.client;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which fermion URLs a node may connect to: the token of the first frame never travels in clear outside the platform's internal network.
 * {@code https} and {@code wss} are always accepted; {@code http} and {@code ws} only toward a loopback, private or link-local address (the
 * internal service URLs the platform injects, a laboratory, the tests) or {@code localhost}. The twin of {@code gluonify.fermion.ws_url} in
 * gluonify-plasma-python.
 *
 * @author David CABILLIC
 */
public final class FermionTransport {

    private static final Set<String> SECURE = Set.of("https", "wss");
    private static final Set<String> PLAIN = Set.of("http", "ws");
    private static final String LOCALHOST = "localhost";
    private static final int ULA_MASK = 0xfe;
    private static final int ULA_PREFIX = 0xfc;

    private FermionTransport() {

    }

    /**
     * Whether a node may connect to this fermion URL.
     *
     * @param url Base URL of fermion
     * @return True for https/wss, or http/ws toward the internal network; false otherwise (another scheme, a public host, unparsable)
     */
    public static boolean acceptable(final String url) {

        final URI uri;
        try {
            uri = URI.create(url.strip());
        }
        catch (final IllegalArgumentException _) {
            return false;
        }
        final String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (SECURE.contains(scheme)) {
            return true;
        }
        return PLAIN.contains(scheme) && internal(uri.getHost());
    }

    /**
     * Whether a host is {@code localhost} or a loopback, private or link-local address literal (never a DNS lookup).
     *
     * @param host Host of the URL, IPv6 literals in brackets
     * @return True for the internal network
     */
    static boolean internal(final String host) {

        if (host == null || host.isEmpty()) {
            return false;
        }
        final String name = host.toLowerCase(Locale.ROOT);
        if (LOCALHOST.equals(name) || name.endsWith("." + LOCALHOST)) {
            return true;
        }
        final String literal = name.startsWith("[") && name.endsWith("]") ? name.substring(1, name.length() - 1) : name;
        final InetAddress address;
        try {
            address = InetAddress.ofLiteral(literal);
        }
        catch (final IllegalArgumentException _) {
            return false;
        }
        return address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isLinkLocalAddress()
                || address instanceof final Inet6Address v6 && (v6.getAddress()[0] & ULA_MASK) == ULA_PREFIX;
    }
}
