package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import io.gluonify.plasma.services.internal.Json;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The discovery document of a zone, published by Higgs at {@code https://<zone>/.well-known/gluonify} (public, no secret, disabled in PRD by
 * default). Format:
 *
 * <pre>{@code
 * {
 *   "zone": "gluonify.lan",
 *   "environment": "SBX",
 *   "issuer": "https://gluonify.lan/realms/gluonify",
 *   "openidConfiguration": "https://gluonify.lan/realms/gluonify/.well-known/openid-configuration",   // optional: default issuer + /.well-known/openid-configuration
 *   "cli": {"clientId": "gluonify-cli"},                                                              // optional: default gluonify-cli
 *   "services": {
 *     "crystallon": {"url": "https://adm.gluonify.lan/crystallon", "audience": "gluonify-crystallon"},
 *     "fermion":    {"url": "https://adm.gluonify.lan/fermion",    "audience": "gluonify-fermion"}
 *   }
 * }
 * }</pre>
 *
 * A service entry whose address is neither {@code https} nor the loopback interface is ignored.
 *
 * @param zone                announced zone (may be {@code null})
 * @param environment         environment of the zone ({@code SBX}, {@code DEV}...), or {@code null}
 * @param issuer              issuer of the Charm tokens, or {@code null}
 * @param openidConfiguration address of Charm's OpenID configuration, or {@code null}
 * @param clientId            public client of the CLI ({@code gluonify-cli} by default)
 * @param services            services that have a public address, by name
 */
public record Discovery(String zone, String environment, String issuer, URI openidConfiguration, String clientId, Map<String, Service> services) {

    /** Public client of the CLI at Charm. */
    public static final String DEFAULT_CLIENT_ID = "gluonify-cli";

    /** A service and its public address. @param audience audience expected by the service, or {@code null} */
    public record Service(String name, URI url, String audience) {
    }

    public Discovery {
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /** The usual address of the document: {@code https://<zone>/.well-known/gluonify}. */
    public static URI defaultUrl(final String zone) {
        return URI.create("https://" + zone + "/.well-known/gluonify");
    }

    public Optional<Service> service(final String name) {
        return Optional.ofNullable(services.get(name));
    }

    /** The address of Charm's OpenID configuration (the document's own, otherwise derived from the issuer), or empty if no issuer is known. */
    public Optional<URI> openidConfigurationUrl() {
        if (openidConfiguration != null) {
            return Optional.of(openidConfiguration);
        }
        if (issuer == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(Http.stripTrailingSlashes(issuer) + "/.well-known/openid-configuration"));
    }

    /** Reads the document. @throws IllegalArgumentException if the text is not a JSON object */
    public static Discovery parse(final String json) {
        return of(Json.object(json));
    }

    /** The document already read as JSON (object). */
    public static Discovery of(final Map<String, Object> m) {
        final Map<String, Service> services = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : Json.obj(m, "services").entrySet()) {
            final Service service = service(e.getKey(), e.getValue());
            if (service != null) {
                services.put(service.name(), service);
            }
        }
        final URI oidc = uri(Json.str(m, "openidConfiguration"));
        final String clientId = Json.str(Json.obj(m, "cli"), "clientId");
        return new Discovery(Json.str(m, "zone"), Json.str(m, "environment"), Json.str(m, "issuer"), oidc != null && Http.secure(oidc) ? oidc : null,
                clientId == null ? DEFAULT_CLIENT_ID : clientId, services);
    }

    /** The service described by one entry of the document, or {@code null} if the entry is not an object or its address is missing or not secure. */
    private static Service service(final String key, final Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final Map<String, Object> s = (Map<String, Object>) value;
        final URI url = uri(Json.str(s, "url"));
        if (url == null || !Http.secure(url)) {
            return null;
        }
        final String name = key.toLowerCase(Locale.ROOT);
        return new Service(name, url, Json.str(s, "audience"));
    }

    private static URI uri(final String s) {
        if (s == null) {
            return null;
        }
        try {
            final URI u = URI.create(s);
            return u.getScheme() == null || u.getHost() == null ? null : u;
        }
        catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
