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
 * Le document de découverte d'une zone, publié par Higgs sur {@code https://<zone>/.well-known/gluonify} (public, sans secret, désactivé en PRD par
 * défaut). Format :
 *
 * <pre>{@code
 * {
 *   "zone": "gluonify.lan",
 *   "environment": "SBX",
 *   "issuer": "https://gluonify.lan/realms/gluonify",
 *   "openidConfiguration": "https://gluonify.lan/realms/gluonify/.well-known/openid-configuration",   // facultatif : défaut issuer + /.well-known/openid-configuration
 *   "cli": {"clientId": "gluonify-cli"},                                                              // facultatif : défaut gluonify-cli
 *   "services": {
 *     "crystallon": {"url": "https://adm.gluonify.lan/crystallon", "audience": "gluonify-crystallon"},
 *     "fermion":    {"url": "https://adm.gluonify.lan/fermion",    "audience": "gluonify-fermion"}
 *   }
 * }
 * }</pre>
 *
 * Une entrée de service dont l'adresse n'est ni {@code https} ni la boucle locale est ignorée.
 *
 * @param zone                zone annoncée (peut être {@code null})
 * @param environment         environnement de la zone ({@code SBX}, {@code DEV}…), ou {@code null}
 * @param issuer              émetteur des jetons Charm, ou {@code null}
 * @param openidConfiguration adresse de la configuration OpenID de Charm, ou {@code null}
 * @param clientId            client public de la CLI ({@code gluonify-cli} par défaut)
 * @param services            services ayant une adresse publique, par nom
 */
public record Discovery(String zone, String environment, String issuer, URI openidConfiguration, String clientId, Map<String, Service> services) {

    /** Client public de la CLI chez Charm. */
    public static final String DEFAULT_CLIENT_ID = "gluonify-cli";

    /** Un service et son adresse publique. @param audience audience attendue par le service, ou {@code null} */
    public record Service(String name, URI url, String audience) {
    }

    public Discovery {
        services = Collections.unmodifiableMap(new LinkedHashMap<>(services));
    }

    /** L'adresse habituelle du document : {@code https://<zone>/.well-known/gluonify}. */
    public static URI defaultUrl(final String zone) {
        return URI.create("https://" + zone + "/.well-known/gluonify");
    }

    public Optional<Service> service(final String name) {
        return Optional.ofNullable(services.get(name));
    }

    /** L'adresse de la configuration OpenID de Charm (celle du document, sinon déduite de l'émetteur), ou vide si aucun émetteur n'est connu. */
    public Optional<URI> openidConfigurationUrl() {
        if (openidConfiguration != null) {
            return Optional.of(openidConfiguration);
        }
        if (issuer == null) {
            return Optional.empty();
        }
        return Optional.of(URI.create(issuer.replaceAll("/+$", "") + "/.well-known/openid-configuration"));
    }

    /** Lit le document. @throws IllegalArgumentException si le texte n'est pas un objet JSON */
    public static Discovery parse(final String json) {
        return of(Json.object(json));
    }

    /** Le document déjà lu en JSON (objet). */
    public static Discovery of(final Map<String, Object> m) {
        final Map<String, Service> services = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : Json.obj(m, "services").entrySet()) {
            if (!(e.getValue() instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> s = (Map<String, Object>) e.getValue();
            final URI url = uri(Json.str(s, "url"));
            if (url == null || !Http.secure(url)) {
                continue;
            }
            final String name = e.getKey().toLowerCase(Locale.ROOT);
            services.put(name, new Service(name, url, Json.str(s, "audience")));
        }
        final URI oidc = uri(Json.str(m, "openidConfiguration"));
        final String clientId = Json.str(Json.obj(m, "cli"), "clientId");
        return new Discovery(Json.str(m, "zone"), Json.str(m, "environment"), Json.str(m, "issuer"), oidc != null && Http.secure(oidc) ? oidc : null,
                clientId == null ? DEFAULT_CLIENT_ID : clientId, services);
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
