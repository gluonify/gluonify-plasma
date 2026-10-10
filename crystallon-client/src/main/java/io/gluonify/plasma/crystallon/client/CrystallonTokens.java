package io.gluonify.plasma.crystallon.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * D'où vient le jeton Charm présenté à Crystallon ({@code Authorization: Bearer ...}, audience {@code gluonify-crystallon}, rôle
 * {@code gluonify:crystallon:render} pour le rendu). {@link #token()} est appelé à chaque requête : une source peut donc changer en cours de
 * vie (renouvellement d'un fichier, jeton de la personne connectée). Le jeton n'est jamais journalisé par ce module.
 */
public interface CrystallonTokens {

    /** Variable d'environnement : le jeton lui-même. */
    String ENV_TOKEN = "GLUONIFY_CRYSTALLON_TOKEN";
    /** Variable d'environnement : le chemin d'un fichier qui contient le jeton (relu à chaque appel). */
    String ENV_TOKEN_FILE = "GLUONIFY_CRYSTALLON_TOKEN_FILE";

    /** @return le jeton à présenter, ou vide pour appeler sans en-tête {@code Authorization} */
    Optional<String> token();

    /** Aucun jeton : pas d'en-tête {@code Authorization} (Crystallon répondra 401 s'il en exige un). */
    static CrystallonTokens none() {
        return Optional::empty;
    }

    /** Un jeton fixe (espaces de début et de fin retirés). @throws IllegalArgumentException si le jeton est vide */
    static CrystallonTokens fixed(final String token) {
        Objects.requireNonNull(token, "token");
        final String value = token.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the Crystallon token is blank");
        }
        return () -> Optional.of(value);
    }

    /**
     * Le jeton est lu dans ce fichier <b>à chaque appel</b> (un renouvellement externe est pris en compte), espaces de début et de fin retirés.
     * Fichier absent ou vide : pas de jeton. Fichier illisible pour une autre raison : {@link CrystallonException} (statut 0).
     */
    static CrystallonTokens file(final Path path) {
        Objects.requireNonNull(path, "path");
        return () -> {
            try {
                final String value = Files.readString(path, StandardCharsets.UTF_8).strip();
                return value.isEmpty() ? Optional.empty() : Optional.of(value);
            }
            catch (final NoSuchFileException e) {
                return Optional.empty();
            }
            catch (final IOException | RuntimeException e) {
                throw new CrystallonException(0, null, "cannot read the Crystallon token file " + path + " (" + e.getClass().getSimpleName() + ")", null, e);
            }
        };
    }

    /**
     * Le jeton de la personne connectée, fourni à chaque appel par l'application (par exemple le jeton de la requête en cours), pour que
     * Crystallon voie la personne et non l'application. Une valeur {@code null} ou vide donne pas de jeton.
     */
    static CrystallonTokens forwarding(final Supplier<String> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return () -> {
            final String value = supplier.get();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
        };
    }

    /** {@code GLUONIFY_CRYSTALLON_TOKEN} s'il est défini, sinon le fichier de {@code GLUONIFY_CRYSTALLON_TOKEN_FILE}, sinon {@link #none()}. */
    static CrystallonTokens fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    /** Comme {@link #fromEnvironment()} avec un environnement fourni (tests). */
    static CrystallonTokens fromEnvironment(final Function<String, String> environment) {
        final String token = environment.apply(ENV_TOKEN);
        if (token != null && !token.isBlank()) {
            return fixed(token);
        }
        final String file = environment.apply(ENV_TOKEN_FILE);
        if (file != null && !file.isBlank()) {
            return file(Path.of(file.strip()));
        }
        return none();
    }
}
