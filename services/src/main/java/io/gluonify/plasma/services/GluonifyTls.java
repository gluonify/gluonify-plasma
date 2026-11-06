package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import java.lang.System.Logger;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;

/**
 * Trust of the cluster's internal authority for the application's own HTTP clients: the JDK's system roots plus the PEM root the platform delivers in
 * {@value #CA_FILE_ENV} (alias {@value #CA_FILE_ALIAS_ENV}). Without the variable, or with a file that cannot be used, the JDK's default applies: logged
 * once, never fatal. Never a context that trusts everything; built at run time from the file, so it holds in a native image.
 *
 * @author David CABILLIC
 */
public final class GluonifyTls {

    /** Path of the PEM root of the cluster's internal authority, set by the platform on every instance. */
    public static final String CA_FILE_ENV = "GLUONIFY_CA_FILE";

    /** Former name of {@link #CA_FILE_ENV}, kept as an alias. */
    public static final String CA_FILE_ALIAS_ENV = "GLUONIFY_GDOWN_CA_FILE";

    // A flag, safe in a static field whatever the image's initialization time: its value is only set at run time.
    private static final AtomicBoolean WARNED = new AtomicBoolean();

    private GluonifyTls() {
    }

    /**
     * The readable root file of the process environment.
     *
     * @return The file, empty when the platform set none or it cannot be read
     */
    public static Optional<Path> caFile() {

        return caFile(System::getenv);
    }

    /**
     * The readable root file named by an environment.
     *
     * @param environment Reads a variable
     * @return The file, empty when the environment names none or it cannot be read
     */
    public static Optional<Path> caFile(final UnaryOperator<String> environment) {

        String value = environment.apply(CA_FILE_ENV);
        if (value == null || value.isBlank()) {
            value = environment.apply(CA_FILE_ALIAS_ENV);
        }
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        final Path file = Path.of(value.strip());
        if (!Files.isReadable(file)) {
            warnOnce(file, null);
            return Optional.empty();
        }
        return Optional.of(file);
    }

    /**
     * The trust manager of the process environment: the system's roots, or else the cluster's.
     *
     * @return The trust manager, empty when the JDK's default applies
     */
    public static Optional<X509TrustManager> trustManager() {

        return trustManager(System::getenv);
    }

    /**
     * The trust manager of an environment: the system's roots, or else the cluster's.
     *
     * @param environment Reads a variable
     * @return The trust manager, empty when the JDK's default applies
     */
    public static Optional<X509TrustManager> trustManager(final UnaryOperator<String> environment) {

        final Optional<Path> file = caFile(environment);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Http.trustManager(file.get()));
        }
        catch (final IllegalArgumentException e) {
            warnOnce(file.get(), e);
            return Optional.empty();
        }
    }

    /**
     * The TLS context of the process: the system's roots plus the cluster's.
     *
     * @return The context; the JDK's default without a usable root
     * @throws IllegalStateException If the JDK has no default context
     */
    public static SSLContext sslContext() {

        return sslContext(System::getenv);
    }

    /**
     * The TLS context of an environment: the system's roots plus the cluster's.
     *
     * @param environment Reads a variable
     * @return The context; the JDK's default without a usable root
     * @throws IllegalStateException If the JDK has no default context
     */
    public static SSLContext sslContext(final UnaryOperator<String> environment) {

        return internalContext(environment).orElseGet(GluonifyTls::jdkDefault);
    }

    /**
     * A new HTTP client (one per component, never in a static field: native image) that trusts the system's roots plus the cluster's.
     *
     * @param connectTimeout Connection timeout
     * @param redirect       Redirect policy
     * @return The client
     */
    public static HttpClient httpClient(final Duration connectTimeout, final HttpClient.Redirect redirect) {

        return builder(connectTimeout, redirect).build();
    }

    /**
     * The builder of {@link #httpClient(Duration, HttpClient.Redirect)}, for a client that also sets its version or its executor.
     *
     * @param connectTimeout Connection timeout
     * @param redirect       Redirect policy
     * @return The builder
     */
    public static HttpClient.Builder builder(final Duration connectTimeout, final HttpClient.Redirect redirect) {

        return builder(connectTimeout, redirect, System::getenv);
    }

    /**
     * The builder of {@link #httpClient(Duration, HttpClient.Redirect)} for an environment.
     *
     * @param connectTimeout Connection timeout
     * @param redirect       Redirect policy
     * @param environment    Reads a variable
     * @return The builder
     */
    public static HttpClient.Builder builder(final Duration connectTimeout, final HttpClient.Redirect redirect, final UnaryOperator<String> environment) {

        final HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout).followRedirects(redirect);
        internalContext(environment).ifPresent(builder::sslContext);
        return builder;
    }

    /**
     * Logs once that the cluster's root cannot be used. The file's path is not a secret; the cause's message says why.
     *
     * @param file  The root file
     * @param cause Why it cannot be used, or {@code null} when it cannot be read
     */
    static void warnOnce(final Path file, final IllegalArgumentException cause) {

        if (WARNED.compareAndSet(false, true)) {
            System.getLogger(GluonifyTls.class.getName()).log(Logger.Level.WARNING,
                    "the cluster's root " + file + " cannot be used, the JDK's authorities apply" + (cause == null ? "" : ": " + cause.getMessage()));
        }
    }

    private static Optional<SSLContext> internalContext(final UnaryOperator<String> environment) {

        final Optional<Path> file = caFile(environment);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Http.sslContext(file.get()));
        }
        catch (final IllegalArgumentException e) {
            warnOnce(file.get(), e);
            return Optional.empty();
        }
    }

    private static SSLContext jdkDefault() {

        try {
            return SSLContext.getDefault();
        }
        catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK has no default TLS context", e);
        }
    }
}
