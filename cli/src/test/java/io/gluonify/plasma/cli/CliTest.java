package io.gluonify.plasma.cli;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.services.DeveloperConfig;
import io.gluonify.plasma.services.DeveloperTokens;
import io.gluonify.plasma.services.FakeCluster;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI against a fake Charm: end-to-end PKCE sign-in (the "browser" follows the redirect), status, token, logout. */
class CliTest {
    @TempDir
    Path home;

    FakeCluster cluster;
    final Map<String, String> env = new HashMap<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final HttpClient browserHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    volatile String browserPage;

    @BeforeEach
    void start() throws Exception {
        cluster = new FakeCluster();
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    /** The fake browser: opens the /authorize address, follows the redirect to the CLI's local server. */
    final Consumer<URI> browser = uri -> CompletableFuture.runAsync(() -> {
        try {
            browserPage = browserHttp.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
        }
        catch (final Exception e) {
            browserPage = "failed: " + e;
        }
    });

    int run(final Consumer<URI> opener, final String... args) {
        out.reset();
        err.reset();
        final Cli cli = new Cli(env::get, k -> null, home, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8),
                opener, Clock.systemUTC(), ZoneOffset.UTC);
        return cli.run(args);
    }

    String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    int login() {
        return run(browser, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20");
    }

    @Test
    void connexionDeBoutEnBout() throws Exception {
        assertEquals(0, login(), err());
        assertTrue(out().contains("Logged in to test.lan as ada"), out());
        assertTrue(out().contains("crystallon"), out());
        assertTrue(browserPage.contains("Logged in to test.lan"), browserPage);
        // what Charm received: PKCE S256, public client, redirect to the local loopback
        final Map<String, String> a = cluster.lastAuthorize;
        assertEquals("gluonify-cli", a.get("client_id"));
        assertEquals("S256", a.get("code_challenge_method"));
        assertTrue(a.get("redirect_uri").matches("http://127\\.0\\.0\\.1:\\d+/callback"), a.get("redirect_uri"));
        assertEquals(FakeCluster.s256(cluster.lastToken.get("code_verifier")), a.get("code_challenge"));
        assertEquals(1, cluster.codeExchanges.get());

        final DeveloperTokens.Session s = DeveloperTokens.read(home, "test.lan").orElseThrow();
        assertEquals("ada", s.subject());
        assertEquals(List.of("gluonify-crystallon", "gluonify-fermion"), s.audiences());
        assertNotNull(s.refreshToken());
        assertFalse(out().contains(s.accessToken()), "the token is never printed");
        final Path file = DeveloperTokens.file(home, "test.lan");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
        }
        final DeveloperConfig config = DeveloperConfig.read(home);
        assertEquals("test.lan", config.current().orElseThrow().name());
        assertEquals("SBX", config.current().orElseThrow().environment());
        assertEquals(cluster.discoveryUrl(), config.current().orElseThrow().discovery());

        // status: never a token
        assertEquals(0, run(browser, "status"));
        assertTrue(out().contains("* test.lan (SBX), ada, token valid until"), out());
        assertFalse(out().contains(s.accessToken()));
        assertFalse(out().contains(s.refreshToken()));

        // token: checks without printing, then --print
        assertEquals(0, run(browser, "token", "crystallon"));
        assertTrue(out().startsWith("ok, crystallon at " + cluster.base() + "/crystallon (DEVELOPER), expires at "), out());
        assertFalse(out().contains(s.accessToken()));
        assertEquals(0, run(browser, "token", "crystallon", "--print"));
        assertEquals(s.accessToken(), out().strip());
        assertEquals(1, run(browser, "token", "interfero"));
        assertTrue(err().contains("does not publish the service interfero"), err());

        // logout
        assertEquals(0, run(browser, "logout"));
        assertTrue(out().contains("Logged out of test.lan"), out());
        assertTrue(DeveloperTokens.read(home, "test.lan").isEmpty());
        assertTrue(DeveloperConfig.read(home).isEmpty());
        assertEquals(0, run(browser, "status"));
        assertTrue(out().contains("Not logged in"), out());
        assertEquals(1, run(browser, "token", "crystallon"));
        assertTrue(err().contains("gluonify login"), err());
    }

    @Test
    void reponseDUnAutreLogin() {
        // a "browser" that returns a code with another state: refused
        final Consumer<URI> forged = uri -> CompletableFuture.runAsync(() -> {
            try {
                final String redirect = io.gluonify.plasma.services.FakeCluster.form(uri.getRawQuery()).get("redirect_uri");
                browserHttp.send(HttpRequest.newBuilder(URI.create(redirect + "?code=x&state=other")).GET().build(), HttpResponse.BodyHandlers.ofString());
            }
            catch (final Exception e) {
                // does not matter
            }
        });
        assertEquals(1, run(forged, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"));
        assertTrue(err().contains("does not match this login"), err());
        assertEquals(0, cluster.codeExchanges.get());
        assertTrue(DeveloperConfig.read(home).isEmpty(), "nothing written");
    }

    @Test
    void erreurRenvoyeeParCharm() {
        final Consumer<URI> denied = uri -> CompletableFuture.runAsync(() -> {
            try {
                final Map<String, String> q = FakeCluster.form(uri.getRawQuery());
                browserHttp.send(HttpRequest.newBuilder(URI.create(q.get("redirect_uri") + "?error=access_denied&state=" + q.get("state"))).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
            }
            catch (final Exception e) {
                // does not matter
            }
        });
        assertEquals(1, run(denied, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"));
        assertTrue(err().contains("Charm refused the login: access_denied"), err());
    }

    @Test
    void sansReponseDuNavigateur() {
        assertEquals(1, run(uri -> { }, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "1"));
        assertTrue(err().contains("no answer from the browser within 1 s"), err());
        assertTrue(out().contains(cluster.issuer() + "/authorize?"), "the address is printed so it can be opened by hand");
    }

    @Test
    void decouverteIndisponible() {
        cluster.discoveryStatus = 404;
        assertEquals(1, login());
        assertTrue(err().contains("HTTP 404"), err());
        assertEquals(1, run(browser, "login", "test.lan", "--discovery", "http://cluster.example/.well-known/gluonify"));
        assertTrue(err().contains("must be https"), err());
    }

    @Test
    void usage() {
        assertEquals(2, run(browser));
        assertEquals(2, run(browser, "frobnicate"));
        assertEquals(2, run(browser, "login"));
        assertEquals(2, run(browser, "login", "a.lan", "--bogus"));
        assertEquals(1, run(browser, "login", "https://a.lan"));
        assertTrue(err().contains("not a zone name"), err());
        assertEquals(0, run(browser, "--help"));
        assertTrue(out().contains("gluonify login"), out());
    }

    @Test
    void browserCommandDependsOnTheOperatingSystem() {
        final URI uri = URI.create("https://example.org/login");
        assertEquals(List.of("open", "https://example.org/login"), Cli.browserCommand("mac os x", uri));
        assertEquals(List.of("rundll32", "url.dll,FileProtocolHandler", "https://example.org/login"), Cli.browserCommand("windows 11", uri));
        assertEquals(List.of("xdg-open", "https://example.org/login"), Cli.browserCommand("linux", uri));
    }
}
