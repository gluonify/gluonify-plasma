package io.gluonify.plasma.services;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Comment joindre un service de Gluonify : son adresse, la source de ses jetons, et d'où vient la résolution.
 *
 * @param service  nom du service, tel qu'écrit dans {@code uses} ({@code crystallon}, {@code fermion}…)
 * @param url      adresse de base du service, sans barre finale (interne sur la plateforme, publique sur le poste du développeur)
 * @param tokens   appelé à chaque requête : le jeton à présenter en {@code Authorization: Bearer}, ou {@code null} pour n'en présenter aucun ; peut
 *                 relire un fichier ou renouveler le jeton du développeur, et lever une {@link GluonifyServicesException} qui dit quoi faire
 * @param source   d'où vient la résolution
 * @param audience audience attendue par le service ({@code gluonify-crystallon}), si elle est connue ; sinon {@code null}
 * @param zone     zone du cluster pour {@link Source#DEVELOPER}, sinon {@code null}
 */
public record Endpoint(String service, URI url, Supplier<String> tokens, Source source, String audience, String zone) {

    /** D'où vient la résolution d'un service. */
    public enum Source {
        /** Surcharge explicite ({@code GLUONIFY_SERVICE_<APP>_URL} en propriété système, ou en variable hors de la plateforme) : tests, cas particuliers. */
        OVERRIDE,
        /** Sur la plateforme : adresse injectée pour un service de {@code uses}, jeton d'application déposé par l'agent du nœud. */
        PLATFORM,
        /** Sur le poste du développeur : document de découverte de la zone et jeton du développeur ({@code gluonify login}). */
        DEVELOPER
    }

    public Endpoint {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(tokens, "tokens");
        Objects.requireNonNull(source, "source");
    }

    /** Le jeton du moment, vide s'il n'y en a pas (le service répondra 401 s'il en exige un). */
    public Optional<String> token() {
        final String t = tokens.get();
        return t == null || t.isBlank() ? Optional.empty() : Optional.of(t.strip());
    }

    /** {@code url} suivie de {@code path} ({@code /v1/...}). */
    public URI resolve(final String path) {
        final String base = url.toString().replaceAll("/+$", "");
        return URI.create(base + (path.startsWith("/") ? path : "/" + path));
    }

    @Override
    public String toString() { // jamais de jeton
        return "Endpoint[service=" + service + ", url=" + url + ", source=" + source + (audience == null ? "" : ", audience=" + audience)
                + (zone == null ? "" : ", zone=" + zone) + "]";
    }
}
