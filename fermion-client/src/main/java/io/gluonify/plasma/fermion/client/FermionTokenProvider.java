package io.gluonify.plasma.fermion.client;

import io.gluonify.plasma.services.GluonifyServices;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

/**
 * Provides the Charm token (audience {@code gluonify-fermion}) sent in the first {@code auth} frame. Called on every (re)connection:
 * the implementation returns a valid token, renewed if necessary. Without an application bean, the client uses {@link #gluonify()} (
 * {@code @Reserve} producer of this module): nothing to write.
 */
@FunctionalInterface
public interface FermionTokenProvider {

    /** Environment variable: path of the application token file dropped by the platform (application declaring {@code "uses": ["fermion"]}). */
    String ENV_TOKEN_FILE = "GLUONIFY_FERMION_TOKEN_FILE";

    String token();

    /**
     * The token resolved by {@link GluonifyServices} for the {@code fermion} service, on every (re)connection: on the platform, the application token
     * ({@code GLUONIFY_FERMION_TOKEN_FILE}); on the developer workstation, the {@code gluonify login} token (renewed); in tests, the
     * {@code GLUONIFY_FERMION_TOKEN} override. This is the default provider when the application does not produce one.
     *
     * @throws io.gluonify.plasma.services.GluonifyServicesException on the call to {@link #token()}, if fermion cannot be reached
     */
    static FermionTokenProvider gluonify() {
        return () -> GluonifyServices.endpoint("fermion").token().orElse(null);
    }

    /** Like {@link #gluonify()} with a supplied resolution (tests). */
    static FermionTokenProvider gluonify(final GluonifyServices services) {
        return () -> services.resolve("fermion").token().orElse(null);
    }

    /**
     * The application token that the platform provides to an application declaring {@code "uses": ["fermion"]}: the file from
     * {@code GLUONIFY_FERMION_TOKEN_FILE}, dropped by the node agent, re-read on every (re)connection and renewed before it expires
     * (audience {@code gluonify-fermion}, role {@code gluonify:fermion:user}). File missing or empty: no token ({@code null}).
     * To produce as a bean: {@code @Produces FermionTokenProvider tokens() { return FermionTokenProvider.platform(); }}.
     *
     * @throws IllegalStateException if the variable is absent (no {@code fermion} in {@code uses}, or a platform without service tokens)
     */
    static FermionTokenProvider platform() {
        return platform(System::getenv);
    }

    /** Like {@link #platform()} with a supplied environment (tests). */
    static FermionTokenProvider platform(final UnaryOperator<String> environment) {
        final String file = environment.apply(ENV_TOKEN_FILE);
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(ENV_TOKEN_FILE + " is not set: the platform did not grant the service \"fermion\" (build the application with Bup with this module "
                    + "on its runtime classpath, or add \"fermion\" to \"uses\" in gluonify.json)");
        }
        final Path path = Path.of(file.strip());
        return () -> {
            try {
                final String value = Files.readString(path, StandardCharsets.UTF_8).strip();
                return value.isEmpty() ? null : value;
            }
            catch (final NoSuchFileException _) {
                return null;
            }
            catch (final IOException e) {
                throw new UncheckedIOException("cannot read the Fermion token file " + path, e);
            }
        };
    }
}
