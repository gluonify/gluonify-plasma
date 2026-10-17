package io.gluonify.plasma.cli;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.gluonify.plasma.services.DeveloperConfig;
import io.gluonify.plasma.services.DeveloperTokens;
import io.gluonify.plasma.services.Discovery;
import io.gluonify.plasma.services.GluonifyServicesException;
import io.gluonify.plasma.services.internal.Http;
import io.gluonify.plasma.services.internal.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@code gluonify login <zone>}: OAuth 2.0 for native applications (RFC 8252) with PKCE S256 (RFC 7636). The browser opens on
 * Charm's {@code /authorize}; Charm sends the code back to a single-use local HTTP server, bound to {@code 127.0.0.1} on a free port
 * ({@code http://127.0.0.1:<port>/callback}); the code is exchanged for the tokens with the PKCE verifier. The {@code state} parameter and, if
 * present, {@code iss} (RFC 9207) are checked. The CLI never sees a password.
 */
final class Login {
    static final String DEFAULT_SCOPE = "openid";
    static final String CALLBACK = "/callback";

    /** Login options. {@code port} 0: a free port. */
    record Options(String zone, URI discovery, Path ca, int port, String scope, Duration timeout, boolean noBrowser) {
    }

    private final Cli cli;

    Login(final Cli cli) {
        this.cli = cli;
    }

    DeveloperTokens.Session run(final Options o) {
        final URI discoveryUrl = o.discovery() != null ? o.discovery() : Discovery.defaultUrl(o.zone());
        if (!Http.secure(discoveryUrl)) {
            throw new GluonifyServicesException("the discovery address must be https: " + discoveryUrl);
        }
        final HttpClient http;
        try {
            http = cli.http(o.ca());
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException(e.getMessage());
        }
        final Discovery d = Discovery.of(get(http, discoveryUrl, "the discovery document of " + o.zone()));
        final URI oidcUrl = d.openidConfigurationUrl().orElseThrow(() -> new GluonifyServicesException("the discovery document of " + o.zone()
                + " gives no issuer (Charm): is the cluster up to date?"));
        if (!Http.secure(oidcUrl)) {
            throw new GluonifyServicesException("the OpenID configuration of " + o.zone() + " must be https: " + oidcUrl);
        }
        final Map<String, Object> oidc = get(http, oidcUrl, "the OpenID configuration of Charm");
        final String issuer = Json.str(oidc, "issuer") != null ? Json.str(oidc, "issuer") : d.issuer();
        if (d.issuer() != null && issuer != null && !d.issuer().equals(issuer)) {
            throw new GluonifyServicesException("the issuer of Charm (" + issuer + ") is not the one of the discovery document (" + d.issuer() + ")");
        }
        final URI authorize = endpoint(oidc, "authorization_endpoint", o.zone());
        final URI token = endpoint(oidc, "token_endpoint", o.zone());
        final URI revocation = Json.str(oidc, "revocation_endpoint") == null ? null : endpoint(oidc, "revocation_endpoint", o.zone());

        final SecureRandom random = new SecureRandom();
        final String verifier = random(random, 48);
        final String state = random(random, 24);
        final CompletableFuture<String> code = new CompletableFuture<>();
        final HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), o.port()), 0);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot listen on 127.0.0.1:" + o.port() + " for the login callback (" + e.getClass().getSimpleName() + ")", e);
        }
        final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            final Thread t = new Thread(r, "gluonify-login-callback");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", ex -> callback(ex, state, issuer, o.zone(), code));
        server.start();
        try {
            final String redirect = "http://127.0.0.1:" + server.getAddress().getPort() + CALLBACK;
            final Map<String, String> q = new LinkedHashMap<>();
            q.put("response_type", "code");
            q.put("client_id", d.clientId());
            q.put("redirect_uri", redirect);
            q.put("scope", o.scope());
            q.put("state", state);
            q.put("code_challenge", s256(verifier));
            q.put("code_challenge_method", "S256");
            final URI url = URI.create(authorize + (authorize.getRawQuery() == null ? "?" : "&") + Http.form(q));
            cli.out.println("Log in to " + o.zone() + " in your browser" + (o.noBrowser() ? "" : " (opening it now)") + ":");
            cli.out.println("  " + url);
            if (!o.noBrowser()) {
                try {
                    cli.browser.accept(url);
                }
                catch (final RuntimeException e) {
                    cli.out.println("(the browser could not be opened: open the address above)");
                }
            }
            final String authorizationCode;
            try {
                authorizationCode = code.get(o.timeout().toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (final TimeoutException e) {
                throw new GluonifyServicesException("no answer from the browser within " + o.timeout().toSeconds() + " s: run gluonify login " + o.zone() + " again");
            }
            catch (final ExecutionException e) {
                throw new GluonifyServicesException(e.getCause().getMessage(), e.getCause());
            }
            catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new GluonifyServicesException("the login was interrupted", e);
            }
            final Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", authorizationCode);
            form.put("redirect_uri", redirect);
            form.put("client_id", d.clientId());
            form.put("code_verifier", verifier);
            final Http.Response r = post(http, token, form);
            if (!r.ok()) {
                final String error = Json.str(r.json(), "error");
                throw new GluonifyServicesException("Charm refused the login (HTTP " + r.status() + (error == null ? "" : ", " + error) + ")");
            }
            final DeveloperTokens.Session s = DeveloperTokens.fromTokenResponse(o.zone(), d.clientId(), token, revocation, r.json(), cli.clock, null);
            DeveloperTokens.write(cli.home, s);
            DeveloperConfig.read(cli.home).with(new DeveloperConfig.Zone(o.zone(), discoveryUrl, d.environment(), o.ca())).write(cli.home);
            if (!d.services().isEmpty()) {
                cli.out.println("Services of " + o.zone() + (d.environment() == null ? "" : " (" + d.environment() + ")") + ": " + String.join(", ", d.services().keySet()));
            }
            return s;
        }
        finally {
            server.stop(0);
            executor.shutdownNow();
        }
    }

    /** The browser's return: a single valid call ends the login; other paths (favicon...) get 404. */
    private static void callback(final HttpExchange ex, final String state, final String issuer, final String zone, final CompletableFuture<String> code)
            throws IOException {
        try {
            ex.getRequestBody().readAllBytes();
            if (!"GET".equals(ex.getRequestMethod()) || !CALLBACK.equals(ex.getRequestURI().getRawPath()) || code.isDone()) {
                page(ex, 404, "Not found.");
                return;
            }
            final Map<String, String> p = query(ex.getRequestURI().getRawQuery());
            if (!state.equals(p.get("state"))) {
                page(ex, 400, "This login answer does not match the login started by gluonify login. Run it again.");
                code.completeExceptionally(new GluonifyServicesException("the login answer does not match this login (state): run gluonify login " + zone + " again"));
                return;
            }
            if (p.containsKey("error")) {
                page(ex, 400, "The login failed: " + p.get("error") + ". You can close this window.");
                code.completeExceptionally(new GluonifyServicesException("Charm refused the login: " + p.get("error")
                        + (p.get("error_description") == null ? "" : " (" + p.get("error_description") + ")")));
                return;
            }
            if (p.get("iss") != null && issuer != null && !issuer.equals(p.get("iss"))) {
                page(ex, 400, "The login answer comes from another issuer. Run gluonify login again.");
                code.completeExceptionally(new GluonifyServicesException("the login answer comes from another issuer (" + p.get("iss") + ")"));
                return;
            }
            final String c = p.get("code");
            if (c == null || c.isBlank()) {
                page(ex, 400, "The login answer has no code. Run gluonify login again.");
                code.completeExceptionally(new GluonifyServicesException("the login answer has no code"));
                return;
            }
            page(ex, 200, "Logged in to " + zone + ". You can close this window and go back to the terminal.");
            code.complete(c);
        }
        finally {
            ex.close();
        }
    }

    private static void page(final HttpExchange ex, final int status, final String text) throws IOException {
        final String html = "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><title>Gluonify</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:4rem auto;max-width:36rem;padding:0 1rem;color:#1d1d1f;background:#fff}"
                + "@media (prefers-color-scheme:dark){body{color:#f5f5f7;background:#1d1d1f}}</style></head><body><h1>Gluonify</h1><p>"
                + escape(text) + "</p></body></html>";
        final byte[] b = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("Referrer-Policy", "no-referrer");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static String escape(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private Map<String, Object> get(final HttpClient http, final URI url, final String what) {
        final Http.Response r;
        try {
            r = Http.getJson(http, url);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot read " + what + " at " + url + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "): is the cluster running? For a cluster signed by a local authority, add --ca <pem>", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GluonifyServicesException("the login was interrupted", e);
        }
        if (!r.ok()) {
            throw new GluonifyServicesException("cannot read " + what + " at " + url + " (HTTP " + r.status() + ")");
        }
        return r.json();
    }

    private static Http.Response post(final HttpClient http, final URI url, final Map<String, String> form) {
        try {
            return Http.postForm(http, url, form);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("Charm is unreachable at " + url + " (" + e.getClass().getSimpleName() + ")", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GluonifyServicesException("the login was interrupted", e);
        }
    }

    private static URI endpoint(final Map<String, Object> oidc, final String key, final String zone) {
        final String v = Json.str(oidc, key);
        if (v == null) {
            throw new GluonifyServicesException("Charm of " + zone + " gives no " + key + ": is the login of Charm enabled (gluonify.charm.auth.enabled)?");
        }
        final URI u = URI.create(v);
        if (!Http.secure(u)) {
            throw new GluonifyServicesException("the " + key + " of Charm must be https: " + u);
        }
        return u;
    }

    static String s256(final String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String random(final SecureRandom random, final int bytes) {
        final byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static Map<String, String> query(final String raw) {
        final Map<String, String> m = new LinkedHashMap<>();
        if (raw == null) {
            return m;
        }
        for (final String kv : raw.split("&")) {
            final int eq = kv.indexOf('=');
            final String k = URLDecoder.decode(eq < 0 ? kv : kv.substring(0, eq), StandardCharsets.UTF_8);
            m.putIfAbsent(k, eq < 0 ? "" : URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return m;
    }
}
