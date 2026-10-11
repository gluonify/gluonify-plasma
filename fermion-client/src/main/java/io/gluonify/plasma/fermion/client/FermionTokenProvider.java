package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.services.GluonifyServices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * Fournit le jeton Charm (audience {@code gluonify-fermion}) envoyé dans la première trame {@code auth}. Appelé à chaque (re)connexion :
 * l'implémentation renvoie un jeton valide, au besoin renouvelé. Sans bean de l'application, le client utilise {@link #gluonify()} (producteur
 * {@code @DefaultBean} de ce module) : rien à écrire.
 */
@FunctionalInterface
public interface FermionTokenProvider {

    /** Variable d'environnement : chemin du fichier du jeton d'application déposé par la plateforme (application qui déclare {@code "uses": ["fermion"]}). */
    String ENV_TOKEN_FILE = "GLUONIFY_FERMION_TOKEN_FILE";

    String token();

    /**
     * Le jeton résolu par {@link GluonifyServices} pour le service {@code fermion}, à chaque (re)connexion : sur la plateforme, le jeton d'application
     * ({@code GLUONIFY_FERMION_TOKEN_FILE}) ; sur le poste du développeur, le jeton de {@code gluonify login} (renouvelé) ; en test, la surcharge
     * {@code GLUONIFY_FERMION_TOKEN}. C'est le fournisseur par défaut quand l'application n'en produit pas.
     *
     * @throws io.gluonify.plasma.services.GluonifyServicesException à l'appel de {@link #token()}, si fermion ne peut pas être joint
     */
    static FermionTokenProvider gluonify() {
        return () -> GluonifyServices.endpoint("fermion").token().orElse(null);
    }

    /** Comme {@link #gluonify()} avec une résolution fournie (tests). */
    static FermionTokenProvider gluonify(final GluonifyServices services) {
        return () -> services.resolve("fermion").token().orElse(null);
    }

    /**
     * Le jeton d'application que la plateforme fournit à une application qui déclare {@code "uses": ["fermion"]} : le fichier de
     * {@code GLUONIFY_FERMION_TOKEN_FILE}, déposé par l'agent du nœud, relu à chaque (re)connexion et renouvelé avant son expiration
     * (audience {@code gluonify-fermion}, rôle {@code gluonify:fermion:user}). Fichier absent ou vide : pas de jeton ({@code null}).
     * À produire en bean : {@code @Produces FermionTokenProvider tokens() { return FermionTokenProvider.platform(); }}.
     *
     * @throws IllegalStateException si la variable est absente (pas de {@code fermion} dans {@code uses}, ou plateforme sans jetons de service)
     */
    static FermionTokenProvider platform() {
        return platform(System::getenv);
    }

    /** Comme {@link #platform()} avec un environnement fourni (tests). */
    static FermionTokenProvider platform(final Function<String, String> environment) {
        final String file = environment.apply(ENV_TOKEN_FILE);
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(ENV_TOKEN_FILE + " is not set: declare \"uses\": [\"fermion\"] in the application spec, the platform then provides the application token");
        }
        final Path path = Path.of(file.strip());
        return () -> {
            try {
                final String value = Files.readString(path, StandardCharsets.UTF_8).strip();
                return value.isEmpty() ? null : value;
            }
            catch (final NoSuchFileException e) {
                return null;
            }
            catch (final IOException e) {
                throw new UncheckedIOException("cannot read the Fermion token file " + path, e);
            }
        };
    }
}
