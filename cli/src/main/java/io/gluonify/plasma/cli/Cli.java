package io.gluonify.plasma.cli;

import io.gluonify.plasma.services.DeveloperConfig;
import io.gluonify.plasma.services.DeveloperTokens;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyServicesException;
import io.gluonify.plasma.services.internal.Http;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * The {@code gluonify} command for the developer's workstation:
 * <ul>
 *   <li>{@code login <zone>}: sign-in in the browser (Charm, PKCE S256, public client {@code gluonify-cli}, redirect to
 *       {@code http://127.0.0.1:<port>/callback}); writes {@code ~/.gluonify/config.json} and {@code ~/.gluonify/tokens/<zone>.json} (0600).
 *       No password goes through the command line;</li>
 *   <li>{@code logout [zone]}: revokes (best effort) and deletes the tokens of the zone;</li>
 *   <li>{@code status}: known zones, who is logged in, expiry times (never a token);</li>
 *   <li>{@code token <service> [--print]}: resolves the service like the SDK and checks the token (renewed if needed); prints the token only with
 *       {@code --print}.</li>
 * </ul>
 * Exit codes: 0 success, 1 failure, 2 usage.
 */
public final class Cli {
    private static final String OPT_CA = "--ca";
    private static final String OPT_TIMEOUT = "--timeout";
    private static final String OPT_PORT = "--port";
    private static final String OPT_DISCOVERY = "--discovery";
    static final String USAGE = String.join("\n",
            "Usage: gluonify <command>",
            "",
            "  login <zone> [--no-browser] [--port <n>] [--ca <pem>] [--discovery <url>] [--scope <scopes>] [--timeout <seconds>]",
            "                      log in to a Gluonify cluster in the browser (for example: gluonify login gluonify.lan)",
            "  logout [<zone>]     forget the tokens of a zone (the current one by default)",
            "  status              known zones, who is logged in, token expiry",
            "  token <service> [--print]",
            "                      check that the SDK can call a service (crystallon, fermion...); --print writes the token itself",
            "",
            "Files: ~/.gluonify/config.json and ~/.gluonify/tokens/<zone>.json (0600); GLUONIFY_HOME changes the folder.");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT);

    final UnaryOperator<String> environment;
    final UnaryOperator<String> properties;
    final Path home;
    final PrintStream out;
    final PrintStream err;
    final Consumer<URI> browser;
    final Clock clock;
    final ZoneId timeZone;

    /** Where the configuration comes from: environment variables, system properties and the folder of the files. */
    record Settings(UnaryOperator<String> environment, UnaryOperator<String> properties, Path home) {
    }

    /** The two output streams. */
    record Streams(PrintStream out, PrintStream err) {
    }

    Cli(final Settings settings, final Streams streams, final Consumer<URI> browser, final Clock clock, final ZoneId timeZone) {
        this.environment = settings.environment();
        this.properties = settings.properties();
        this.home = settings.home();
        this.out = streams.out();
        this.err = streams.err();
        this.browser = browser;
        this.clock = clock;
        this.timeZone = timeZone;
    }

    static Cli system() {
        return new Cli(new Settings(System::getenv, System::getProperty, DeveloperConfig.home(System::getenv, System::getProperty)),
                new Streams(System.out, System.err), Cli::openBrowser, Clock.systemUTC(), ZoneId.systemDefault());
    }

    /** Runs the command. @return the exit code */
    public int run(final String[] args) {
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            out.println(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        final List<String> rest = new ArrayList<>(List.of(args).subList(1, args.length));
        final Command command = Command.named(args[0]);
        if (command == null) {
            err.println("unknown command: " + args[0]);
            err.println(USAGE);
            return 2;
        }
        try {
            return switch (command) {
                case LOGIN -> {
                    login(rest);
                    yield 0;
                }
                case LOGOUT -> {
                    logout(rest);
                    yield 0;
                }
                case STATUS -> {
                    status(rest);
                    yield 0;
                }
                case TOKEN -> token(rest);
            };
        }
        catch (final UsageException e) {
            err.println(e.getMessage());
            err.println(USAGE);
            return 2;
        }
        catch (final GluonifyServicesException | IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    // ------------------------------------------------------------------------------------------------ login

    private void login(final List<String> args) {
        final Map<String, String> opts = options(args, List.of(OPT_PORT, OPT_CA, OPT_DISCOVERY, "--scope", OPT_TIMEOUT), List.of("--no-browser"));
        if (args.size() != 1) {
            throw new UsageException("login needs exactly one zone, for example: gluonify login gluonify.lan");
        }
        final String zone = args.get(0).toLowerCase(Locale.ROOT);
        if (!DeveloperConfig.validZone(zone)) {
            throw new IllegalArgumentException("not a zone name: " + args.get(0) + " (a host name such as gluonify.lan, without https:// or path)");
        }
        final Login.Options o = new Login.Options(zone,
                opts.containsKey(OPT_DISCOVERY) ? URI.create(opts.get(OPT_DISCOVERY)) : null,
                opts.containsKey(OPT_CA) ? Path.of(opts.get(OPT_CA)).toAbsolutePath() : null,
                opts.containsKey(OPT_PORT) ? integer(opts.get(OPT_PORT), OPT_PORT, 0, 65535) : 0,
                opts.getOrDefault("--scope", Login.DEFAULT_SCOPE),
                Duration.ofSeconds(opts.containsKey(OPT_TIMEOUT) ? integer(opts.get(OPT_TIMEOUT), OPT_TIMEOUT, 1, 3600) : 300),
                opts.containsKey("--no-browser"));
        final DeveloperTokens.Session s = new Login(this).run(o);
        out.println("Logged in to " + zone + (s.subject() == null ? "" : " as " + s.subject()) + "; the token is valid until " + time(s.expiry())
                + (s.refreshToken() == null ? " (no refresh token: log in again then)" : " and is renewed automatically") + ".");
    }

    // ------------------------------------------------------------------------------------------------ logout

    private void logout(final List<String> args) {
        options(args, List.of(), List.of());
        if (args.size() > 1) {
            throw new UsageException("logout takes at most one zone");
        }
        final DeveloperConfig config = DeveloperConfig.read(home);
        final String zone = args.isEmpty() ? config.current().map(DeveloperConfig.Zone::name).orElse(null) : args.get(0).toLowerCase(Locale.ROOT);
        if (zone == null) {
            out.println("Not logged in.");
            return;
        }
        if (!DeveloperConfig.validZone(zone)) {
            throw new IllegalArgumentException("not a zone name: " + zone);
        }
        final Optional<DeveloperTokens.Session> session = DeveloperTokens.read(home, zone);
        session.ifPresent(s -> revoke(s, config.zone(zone).map(DeveloperConfig.Zone::caFile).orElse(null)));
        final boolean had = DeveloperTokens.delete(home, zone);
        final boolean known = config.zone(zone).isPresent();
        if (known) {
            config.without(zone).write(home);
        }
        out.println(had || known ? "Logged out of " + zone + "." : "Not logged in to " + zone + ".");
    }

    /** Best-effort revocation of the refresh token: a failure does not prevent deleting the tokens from this computer. */
    private void revoke(final DeveloperTokens.Session s, final Path ca) {
        if (s.revocationEndpoint() == null || s.refreshToken() == null || !Http.secure(s.revocationEndpoint())) {
            return;
        }
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("token", s.refreshToken());
        form.put("token_type_hint", "refresh_token");
        form.put("client_id", s.clientId());
        try {
            Http.postForm(Http.client(ca), s.revocationEndpoint(), form);
        }
        catch (final IOException | RuntimeException e) {
            err.println("warning: the token could not be revoked at Charm (" + e.getClass().getSimpleName() + "); it is deleted from this computer anyway");
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------------------------------------ status

    private void status(final List<String> args) {
        options(args, List.of(), List.of());
        if (!args.isEmpty()) {
            throw new UsageException("status takes no argument");
        }
        if (environment.apply(GluonifyServices.ENV_ENVIRONMENT) != null) {
            out.println(GluonifyServices.ENV_ENVIRONMENT + " is set: applications started here resolve services from the platform, not from these zones.");
        }
        final DeveloperConfig config = DeveloperConfig.read(home);
        if (config.isEmpty()) {
            out.println("Not logged in. Run: gluonify login <zone> (for example gluonify login gluonify.lan)");
            return;
        }
        final String current = config.current().map(DeveloperConfig.Zone::name).orElse(null);
        for (final DeveloperConfig.Zone z : config.zones().values()) {
            final StringBuilder line = new StringBuilder(z.name().equals(current) ? "* " : "  ").append(z.name());
            if (z.environment() != null) {
                line.append(" (").append(z.environment()).append(')');
            }
            final Optional<DeveloperTokens.Session> s = DeveloperTokens.read(home, z.name());
            if (s.isEmpty()) {
                line.append(": not logged in");
            }
            else {
                appendSession(line, z.name(), s.get());
            }
            out.println(line);
        }
    }

    /** Who is logged in, the state of the token and of the session of a zone. */
    private void appendSession(final StringBuilder line, final String zone, final DeveloperTokens.Session t) {
        if (t.subject() != null) {
            line.append(", ").append(t.subject());
        }
        line.append(", token ").append(t.fresh(clock, Duration.ZERO) ? "valid until " + time(t.expiry()) : "expired");
        if (t.refreshToken() == null) {
            line.append(", no refresh token");
        }
        else if (!t.renewable(clock)) {
            line.append(", session expired: gluonify login ").append(zone);
        }
        else {
            line.append(", renewed automatically").append(t.refreshExpiresAt() == 0 ? "" : " until " + time(Instant.ofEpochSecond(t.refreshExpiresAt())));
        }
        if (!t.audiences().isEmpty()) {
            line.append(", audiences ").append(String.join(" ", t.audiences()));
        }
    }

    // ------------------------------------------------------------------------------------------------ token

    private int token(final List<String> args) {
        final Map<String, String> opts = options(args, List.of(), List.of("--print"));
        if (args.size() != 1) {
            throw new UsageException("token needs exactly one service, for example: gluonify token crystallon");
        }
        final GluonifyServices services = GluonifyServices.builder().environment(environment).properties(properties).home(home).clock(clock).build();
        final Endpoint e = services.resolve(args.get(0));
        final Optional<String> token = e.token();
        if (opts.containsKey("--print")) {
            if (token.isEmpty()) {
                err.println("error: no token for " + e.service() + " (" + e.source() + ")");
                return 1;
            }
            out.println(token.get());
            return 0;
        }
        if (token.isEmpty()) {
            out.println("ok, " + e.service() + " at " + e.url() + " (" + e.source() + "), without a token");
            return 0;
        }
        String expiry = "";
        if (e.source() == Endpoint.Source.DEVELOPER) {
            expiry = DeveloperTokens.read(home, e.zone()).map(s -> ", expires at " + time(s.expiry())).orElse("");
        }
        out.println("ok, " + e.service() + " at " + e.url() + " (" + e.source() + ")" + expiry);
        return 0;
    }

    // ------------------------------------------------------------------------------------------------ tools

    String time(final Instant instant) {
        return TIME_FORMAT.format(instant.atZone(timeZone));
    }

    /** Removes the options from {@code args} (which keeps the arguments) and returns them. */
    private static Map<String, String> options(final List<String> args, final List<String> withValue, final List<String> flags) {
        final Map<String, String> opts = new LinkedHashMap<>();
        int k = 0;
        while (k < args.size()) {
            final String a = args.get(k);
            if (!a.startsWith("--")) {
                k++;
                continue;
            }
            if (flags.contains(a)) {
                opts.put(a, "true");
                args.remove(k);
            }
            else if (withValue.contains(a)) {
                if (k + 1 >= args.size()) {
                    throw new UsageException(a + " needs a value");
                }
                opts.put(a, args.get(k + 1));
                args.remove(k);
                args.remove(k);
            }
            else {
                throw new UsageException("unknown option: " + a);
            }
        }
        return opts;
    }

    private static int integer(final String v, final String what, final int min, final int max) {
        try {
            final int n = Integer.parseInt(v);
            if (n < min || n > max) {
                throw new UsageException(what + " must be between " + min + " and " + max);
            }
            return n;
        }
        catch (final NumberFormatException e) {
            throw new UsageException(what + " must be a number");
        }
    }

    /** The command that opens an address in the browser of the given operating system (lower-case {@code os.name}). */
    static List<String> browserCommand(final String os, final URI uri) {
        if (os.contains("mac")) return List.of("open", uri.toString());
        if (os.contains("win")) return List.of("rundll32", "url.dll,FileProtocolHandler", uri.toString());
        return List.of("xdg-open", uri.toString());
    }

    /** Opens the address in the system browser; a failure is harmless (the address is printed). */
    static void openBrowser(final URI uri) {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final List<String> cmd = browserCommand(os, uri);
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        }
        catch (final IOException e) {
            // no browser: the address is printed
        }
    }

    /** The commands, by the word typed on the command line. */
    private enum Command {

        /** {@code login}. */
        LOGIN("login"),

        /** {@code logout}. */
        LOGOUT("logout"),

        /** {@code status}. */
        STATUS("status"),

        /** {@code token}. */
        TOKEN("token");

        private final String word;

        Command(final String word) {
            this.word = word;
        }

        /** The command typed as {@code word}, or {@code null} if there is none. */
        static Command named(final String word) {
            for (final Command c : values()) {
                if (c.word.equals(word)) {
                    return c;
                }
            }
            return null;
        }
    }

    static final class UsageException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        UsageException(final String message) {
            super(message);
        }
    }

    HttpClient http(final Path ca) {
        return Http.client(ca);
    }
}
