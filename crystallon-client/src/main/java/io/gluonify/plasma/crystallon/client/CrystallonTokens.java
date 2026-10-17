package io.gluonify.plasma.crystallon.client;

import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServicesException;
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
 * Where the Charm token presented to Crystallon comes from ({@code Authorization: Bearer ...}, audience {@code gluonify-crystallon}, role
 * {@code gluonify:crystallon:render} for rendering). {@link #token()} is called on every request: a source may therefore change during its
 * lifetime (file renewal, signed-in person's token). The token is never logged by this module.
 */
public interface CrystallonTokens {

    /** Environment variable: the token itself. */
    String ENV_TOKEN = "GLUONIFY_CRYSTALLON_TOKEN";
    /** Environment variable: the path of a file containing the token (re-read on every call). */
    String ENV_TOKEN_FILE = "GLUONIFY_CRYSTALLON_TOKEN_FILE";

    /** @return the token to present, or empty to call without an {@code Authorization} header */
    Optional<String> token();

    /** No token: no {@code Authorization} header (Crystallon will answer 401 if it requires one). */
    static CrystallonTokens none() {
        return Optional::empty;
    }

    /** A fixed token (leading and trailing whitespace stripped). @throws IllegalArgumentException if the token is empty */
    static CrystallonTokens fixed(final String token) {
        Objects.requireNonNull(token, "token");
        final String value = token.strip();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("the Crystallon token is blank");
        }
        return () -> Optional.of(value);
    }

    /**
     * The token is read from this file <b>on every call</b> (an external renewal is picked up), leading and trailing whitespace stripped.
     * File missing or empty: no token. File unreadable for another reason: {@link CrystallonException} (status 0).
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
     * The signed-in person's token, supplied on every call by the application (for example the token of the current request), so that
     * Crystallon sees the person and not the application. A {@code null} or empty value means no token.
     */
    static CrystallonTokens forwarding(final Supplier<String> supplier) {
        Objects.requireNonNull(supplier, "supplier");
        return () -> {
            final String value = supplier.get();
            return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.strip());
        };
    }

    /**
     * The application token that the platform provides to an application declaring {@code "uses": ["crystallon"]}: the file from
     * {@code GLUONIFY_CRYSTALLON_TOKEN_FILE}, dropped by the node agent, re-read on every call and renewed before it expires
     * (Charm token, audience {@code gluonify-crystallon}, role {@code gluonify:crystallon:render}, subject {@code app:<name>}).
     *
     * @throws IllegalStateException if the variable is absent (no {@code crystallon} in {@code uses}, or a platform without service tokens)
     */
    static CrystallonTokens platform() {
        return platform(System::getenv);
    }

    /** Like {@link #platform()} with a supplied environment (tests). */
    static CrystallonTokens platform(final Function<String, String> environment) {
        final String file = environment.apply(ENV_TOKEN_FILE);
        if (file == null || file.isBlank()) {
            throw new IllegalStateException(ENV_TOKEN_FILE + " is not set: the platform did not grant the service \"crystallon\" (build the application with Bup with this module "
                    + "on its runtime classpath, or add \"crystallon\" to \"uses\" in gluonify.json)");
        }
        return file(Path.of(file.strip()));
    }

    /**
     * The tokens of a service resolved by {@code GluonifyServices}: application token on the platform, developer token (renewed) on the
     * workstation, override in tests. A failure of the source (developer session expired, unreadable file) becomes a {@link CrystallonException}
     * (status 0) that keeps the message: it says what to do.
     */
    static CrystallonTokens of(final Endpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        return () -> {
            try {
                return endpoint.token();
            }
            catch (final GluonifyServicesException e) {
                throw new CrystallonException(0, null, e.getMessage(), null, e);
            }
        };
    }

    /** {@code GLUONIFY_CRYSTALLON_TOKEN} if defined, otherwise the file from {@code GLUONIFY_CRYSTALLON_TOKEN_FILE}, otherwise {@link #none()}. */
    static CrystallonTokens fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    /** Like {@link #fromEnvironment()} with a supplied environment (tests). */
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
