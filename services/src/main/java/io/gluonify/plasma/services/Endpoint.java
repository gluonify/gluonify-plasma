package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;

/**
 * How to reach a Gluonify service: its address, the source of its tokens, and where the resolution comes from.
 *
 * @param service  name of the service, as written in {@code uses} ({@code crystallon}, {@code fermion}...)
 * @param url      base address of the service, without a trailing slash (internal on the platform, public on the developer workstation)
 * @param tokens   called on every request: the token to present as {@code Authorization: Bearer}, or {@code null} to present none; may
 *                 reread a file or renew the developer's token, and throw a {@link GluonifyServicesException} that says what to do
 * @param source   where the resolution comes from
 * @param audience audience expected by the service ({@code gluonify-crystallon}), if known; otherwise {@code null}
 * @param zone     zone of the cluster for {@link Source#DEVELOPER}, otherwise {@code null}
 * @param caFile   certificate authority of the zone accepted in addition to the JDK's ({@code caFile} of the zone in
 *                 {@code ~/.gluonify/config.json}: a development cluster signed by a local authority), otherwise {@code null}
 */
public record Endpoint(String service, URI url, Supplier<String> tokens, Source source, String audience, String zone, Path caFile) {

    /** Where the resolution of a service comes from. */
    public enum Source {
        /** Explicit override ({@code GLUONIFY_SERVICE_<APP>_URL} as a system property, or as a variable outside the platform): tests, special cases. */
        OVERRIDE,
        /** On the platform: address injected for a service in {@code uses}, application token dropped by the node agent. */
        PLATFORM,
        /** On the developer workstation: the zone's discovery document and the developer's token ({@code gluonify login}). */
        DEVELOPER
    }

    public Endpoint {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(source, "source");
    }

    /**
     * An endpoint whose certificate is checked against the JDK's authorities only.
     *
     * @param service  name of the service
     * @param url      base address of the service
     * @param tokens   source of the token presented on every request
     * @param source   where the resolution comes from
     * @param audience audience expected by the service, or {@code null}
     * @param zone     zone of the cluster for {@link Source#DEVELOPER}, otherwise {@code null}
     */
    public Endpoint(final String service, final URI url, final Supplier<String> tokens, final Source source, final String audience, final String zone) {
        this(service, url, tokens, source, audience, zone, null);
    }

    /**
     * The TLS context to reach the service: the JDK's authorities plus the zone's {@link #caFile()}, built on each call (read the file once
     * per HTTP client). Empty when the zone has no authority of its own: the JDK's default context applies. Never a context that trusts
     * every certificate.
     *
     * @return The context, or empty without {@code caFile}
     * @throws GluonifyServicesException If {@code caFile} cannot be read or holds no certificate
     */
    public Optional<SSLContext> sslContext() {
        if (caFile == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Http.sslContext(caFile));
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException(e.getMessage() + ": fix caFile of zone " + zone + " in ~/.gluonify/" + DeveloperConfig.FILE, e);
        }
    }

    /** The current token, empty if there is none (the service will answer 401 if it requires one). */
    public Optional<String> token() {
        final String t = tokens.get();
        return t == null || t.isBlank() ? Optional.empty() : Optional.of(t.strip());
    }

    /** {@code url} followed by {@code path} ({@code /v1/...}). */
    public URI resolve(final String path) {
        final String base = Http.stripTrailingSlashes(url.toString());
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    @Override
    public String toString() { // never a token
        return "Endpoint[service=" + service + ", url=" + url + ", source=" + source + (audience == null ? "" : ", audience=" + audience)
                + (zone == null ? "" : ", zone=" + zone) + (caFile == null ? "" : ", caFile=" + caFile) + "]";
    }
}
