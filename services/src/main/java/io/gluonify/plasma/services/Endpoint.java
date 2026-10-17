package io.gluonify.plasma.services;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
 */
public record Endpoint(String service, URI url, Supplier<String> tokens, Source source, String audience, String zone) {

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

    /** The current token, empty if there is none (the service will answer 401 if it requires one). */
    public Optional<String> token() {
        final String t = tokens.get();
        return t == null || t.isBlank() ? Optional.empty() : Optional.of(t.strip());
    }

    /** {@code url} followed by {@code path} ({@code /v1/...}). */
    public URI resolve(final String path) {
        final String base = url.toString().replaceAll("/+$", "");
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    @Override
    public String toString() { // never a token
        return "Endpoint[service=" + service + ", url=" + url + ", source=" + source + (audience == null ? "" : ", audience=" + audience)
                + (zone == null ? "" : ", zone=" + zone) + "]";
    }
}
