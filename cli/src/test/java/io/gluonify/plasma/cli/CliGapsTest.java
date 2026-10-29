package io.gluonify.plasma.cli;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.gluonify.plasma.services.DeveloperConfig;
import io.gluonify.plasma.services.DeveloperTokens;
import io.gluonify.plasma.services.FakeCluster;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The CLI's refusals and corners: options, discovery and OpenID documents that disagree, the browser's odd answers, logout, status of every session state. */
class CliGapsTest {
    @TempDir
    Path home;

    FakeCluster cluster;
    final Map<String, String> env = new HashMap<>();
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final ByteArrayOutputStream err = new ByteArrayOutputStream();
    final HttpClient browserHttp = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    @BeforeEach
    void start() throws Exception {
        cluster = new FakeCluster();
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    /** The browser that follows the authorization address, as a person would. */
    final Consumer<URI> browser = uri -> CompletableFuture.runAsync(() -> get(uri));

    void get(final URI uri) {
        try {
            browserHttp.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (final Exception e) {
            // the CLI may already have stopped listening
        }
    }

    /** A browser that sends the callback this function builds from the authorization parameters. */
    Consumer<URI> answering(final Function<Map<String, String>, String> callbackUrl) {
        return uri -> CompletableFuture.runAsync(() -> get(URI.create(callbackUrl.apply(FakeCluster.form(uri.getRawQuery())))));
    }

    int run(final Clock clock, final Consumer<URI> opener, final String... args) {
        out.reset();
        err.reset();
        final Cli cli = new Cli(new Cli.Settings(env::get, k -> null, home),
                new Cli.Streams(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)), opener, clock, ZoneOffset.UTC);
        return cli.run(args);
    }

    int run(final Consumer<URI> opener, final String... args) {
        return run(Clock.systemUTC(), opener, args);
    }

    static String time(final Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", java.util.Locale.ROOT).format(instant.atZone(ZoneOffset.UTC));
    }

    String out() {
        return out.toString(StandardCharsets.UTF_8);
    }

    String err() {
        return err.toString(StandardCharsets.UTF_8);
    }

    int login(final String... extra) {
        final String[] base = {"login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"};
        final String[] all = java.util.stream.Stream.concat(java.util.Arrays.stream(base), java.util.Arrays.stream(extra)).toArray(String[]::new);
        return run(browser, all);
    }

    // --- command line ---

    @Test
    void helpPrintsTheUsageAndSucceeds() {
        assertEquals(0, run(browser, "help"));
        assertTrue(out().contains("Usage: gluonify"), out());
        assertEquals(0, run(browser, "-h"));
    }

    @Test
    void optionsAreCheckedBeforeAnythingIsDone() {
        assertEquals(2, run(browser, "login", "a.lan", "--port"));
        assertTrue(err().contains("--port needs a value"), err());
        assertEquals(2, run(browser, "login", "a.lan", "--port", "x"));
        assertTrue(err().contains("--port must be a number"), err());
        assertEquals(2, run(browser, "login", "a.lan", "--port", "70000"));
        assertTrue(err().contains("--port must be between 0 and 65535"), err());
        assertEquals(2, run(browser, "login", "a.lan", "--timeout", "0"));
        assertEquals(2, run(browser, "login", "a.lan", "b.lan"));
        assertEquals(2, run(browser, "logout", "a.lan", "b.lan"));
        assertTrue(err().contains("logout takes at most one zone"), err());
        assertEquals(2, run(browser, "status", "extra"));
        assertTrue(err().contains("status takes no argument"), err());
        assertEquals(2, run(browser, "token"));
        assertEquals(2, run(browser, "token", "a", "b"));
        assertTrue(err().contains("token needs exactly one service"), err());
    }

    @Test
    void aMissingAuthorityFileIsAnErrorNotACrash() {
        assertEquals(1, login("--ca", home.resolve("absent.pem").toString()));
        assertFalse(err().isBlank());
        assertTrue(DeveloperConfig.read(home).isEmpty());
    }

    // --- login: the browser ---

    @Test
    void withoutBrowserTheAddressIsPrintedAndNothingIsOpened() {
        final int[] opened = {0};
        assertEquals(1, run(uri -> opened[0]++, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "1", "--no-browser"));
        assertEquals(0, opened[0]);
        assertTrue(out().contains("Log in to test.lan in your browser:"), out());
        assertFalse(out().contains("(opening it now)"), out());
        assertTrue(err().contains("no answer from the browser within 1 s"), err());
    }

    @Test
    void aBrowserThatCannotBeOpenedLeavesTheAddressToOpenByHand() {
        assertEquals(1, run(uri -> {
            throw new IllegalStateException("no display");
        }, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "1"));
        assertTrue(out().contains("(the browser could not be opened: open the address above)"), out());
    }

    @Test
    void aChosenPortIsUsedForTheCallback() throws Exception {
        final int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = s.getLocalPort();
        }
        assertEquals(0, login("--port", Integer.toString(port)), err());
        assertEquals("http://127.0.0.1:" + port + "/callback", cluster.lastAuthorize.get("redirect_uri"));
    }

    @Test
    void aPortAlreadyInUseIsReported() throws Exception {
        try (ServerSocket busy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            assertEquals(1, login("--port", Integer.toString(busy.getLocalPort())));
            assertTrue(err().contains("cannot listen on 127.0.0.1:" + busy.getLocalPort()), err());
        }
    }

    @Test
    void otherRequestsToTheCallbackServerAreIgnoredUntilTheRealAnswer() {
        final Consumer<URI> noisy = uri -> CompletableFuture.runAsync(() -> {
            final String redirect = FakeCluster.form(uri.getRawQuery()).get("redirect_uri");
            final String root = redirect.substring(0, redirect.length() - "/callback".length());
            try {
                final HttpResponse<String> favicon = browserHttp.send(HttpRequest.newBuilder(URI.create(root + "/favicon.ico")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, favicon.statusCode());
                final HttpResponse<String> post = browserHttp.send(HttpRequest.newBuilder(URI.create(redirect)).POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, post.statusCode());
            }
            catch (final Exception e) {
                throw new IllegalStateException(e);
            }
            get(uri);
        });
        assertEquals(0, run(noisy, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"), err());
    }

    @Test
    void anErrorAnswerCarriesItsDescription() {
        final Consumer<URI> denied = answering(q -> q.get("redirect_uri") + "?error=access_denied&error_description=" + java.net.URLEncoder.encode("not allowed here", StandardCharsets.UTF_8) + "&state=" + q.get("state"));
        assertEquals(1, run(denied, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"));
        assertTrue(err().contains("Charm refused the login: access_denied (not allowed here)"), err());
    }

    @Test
    void anAnswerFromAnotherIssuerIsRefused() {
        final Consumer<URI> other = answering(q -> q.get("redirect_uri") + "?code=x&state=" + q.get("state") + "&iss=" + java.net.URLEncoder.encode("https://evil.example/realms/x", StandardCharsets.UTF_8));
        assertEquals(1, run(other, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"));
        assertTrue(err().contains("comes from another issuer (https://evil.example/realms/x)"), err());
        assertEquals(0, cluster.codeExchanges.get());
    }

    @Test
    void anAnswerWithoutACodeIsRefused() {
        for (final String code : new String[]{"", "&code=", "&code=%20"}) {
            final Consumer<URI> empty = answering(q -> q.get("redirect_uri") + "?state=" + q.get("state") + code);
            assertEquals(1, run(empty, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"), code);
            assertTrue(err().contains("the login answer has no code"), code + err());
        }
    }

    @Test
    void anAnswerWithoutIssuerIsAcceptedWhenTheCodeIsValid() {
        final Consumer<URI> noIss = uri -> CompletableFuture.runAsync(() -> {
            try {
                final HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(302, r.statusCode());
                final URI location = URI.create(r.headers().firstValue("Location").orElseThrow());
                final String query = location.getRawQuery().replaceAll("&iss=[^&]*", "");
                get(URI.create(location.getScheme() + "://" + location.getAuthority() + location.getRawPath() + "?" + query));
            }
            catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(0, run(noIss, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"), err());
    }

    @Test
    void aCallbackWithoutQueryDoesNotMatchTheLogin() {
        final Consumer<URI> bare = answering(q -> q.get("redirect_uri"));
        assertEquals(1, run(bare, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"));
        assertTrue(err().contains("does not match this login"), err());
    }

    @Test
    void parametersWithoutValueAndRepeatedOnesAreTolerated() {
        final Consumer<URI> odd = uri -> CompletableFuture.runAsync(() -> {
            try {
                final HttpResponse<String> r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
                final URI location = URI.create(r.headers().firstValue("Location").orElseThrow());
                get(URI.create(location.getScheme() + "://" + location.getAuthority() + location.getRawPath() + "?flag&" + location.getRawQuery() + "&code=ignored-second"));
            }
            catch (final Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertEquals(0, run(odd, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20"), err());
    }

    @Test
    void withoutDiscoveryOptionTheZonesOwnAddressIsUsed() {
        assertEquals(1, run(browser, "login", "localhost", "--timeout", "1"));
        assertTrue(err().contains("cannot read the discovery document of localhost at https://localhost"), err());
    }

    // --- login: discovery, OpenID configuration, token endpoint ---

    @Test
    void aDiscoveryDocumentWithoutIssuerCannotLogIn() {
        cluster.discoveryTweak = m -> {
            m.remove("issuer");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("gives no issuer"), err());
    }

    @Test
    void anInsecureOpenIdConfigurationAddressIsRefused() {
        cluster.discoveryTweak = m -> {
            m.put("issuer", "http://cluster.example/realms/gluonify");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("the OpenID configuration of test.lan must be https"), err());
    }

    @Test
    void anIssuerThatDiffersFromTheDiscoveryDocumentIsRefused() {
        cluster.openidTweak = m -> {
            m.put("issuer", cluster.base() + "/realms/other");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("is not the one of the discovery document"), err());
    }

    @Test
    void anOpenIdConfigurationWithoutIssuerFallsBackOnTheDiscoveryOne() {
        cluster.openidTweak = m -> {
            m.remove("issuer");
            return m;
        };
        assertEquals(0, login(), err());
    }

    @Test
    void aMissingOrInsecureEndpointIsExplained() {
        cluster.openidTweak = m -> {
            m.remove("authorization_endpoint");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("gives no authorization_endpoint"), err());
        cluster.openidTweak = m -> {
            m.put("token_endpoint", "http://cluster.example/token");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("the token_endpoint of Charm must be https"), err());
    }

    @Test
    void anUnreadableOpenIdConfigurationIsReportedWithItsStatusOrCause() {
        cluster.discoveryTweak = m -> {
            m.put("issuer", "http://127.0.0.1:1/realms/gluonify");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("cannot read the OpenID configuration of Charm"), err());
        assertTrue(err().contains("is the cluster running?"), err());
    }

    @Test
    void anUnreachableDiscoveryAddressIsReportedWithAHint() {
        assertEquals(1, run(browser, "login", "test.lan", "--discovery", "http://127.0.0.1:1/.well-known/gluonify", "--timeout", "1"));
        assertTrue(err().contains("cannot read the discovery document of test.lan"), err());
        assertTrue(err().contains("--ca <pem>"), err());
    }

    @Test
    void aRegistrationWithoutRevocationEndpointOrServicesStillLogsIn() {
        cluster.openidTweak = m -> {
            m.remove("revocation_endpoint");
            return m;
        };
        cluster.discoveryTweak = m -> {
            m.remove("environment");
            m.put("services", Map.of());
            return m;
        };
        assertEquals(0, login(), err());
        assertFalse(out().contains("Services of"), out());
        assertNull(DeveloperTokens.read(home, "test.lan").orElseThrow().revocationEndpoint());
        assertNull(DeveloperConfig.read(home).current().orElseThrow().environment());
        assertEquals(0, run(browser, "logout"));
        assertEquals(0, cluster.revocations.get(), "nothing to revoke without an endpoint");
    }

    @Test
    void aServicesLineWithoutEnvironmentOmitsTheParentheses() {
        cluster.discoveryTweak = m -> {
            m.remove("environment");
            return m;
        };
        assertEquals(0, login(), err());
        assertTrue(out().contains("Services of test.lan: "), out());
    }

    @Test
    void charmRefusingTheCodeIsReportedWithOrWithoutItsErrorCode() {
        cluster.tokenFailureStatus = 400;
        cluster.tokenFailureBody = "{\"error\":\"invalid_grant\"}";
        assertEquals(1, login());
        assertTrue(err().contains("Charm refused the login (HTTP 400, invalid_grant)"), err());
        cluster.tokenFailureBody = "{}";
        assertEquals(1, login());
        assertTrue(err().contains("Charm refused the login (HTTP 400)"), err());
        assertTrue(DeveloperConfig.read(home).isEmpty());
    }

    @Test
    void anUnreachableTokenEndpointIsReported() {
        cluster.openidTweak = m -> {
            m.put("token_endpoint", "http://127.0.0.1:1/token");
            return m;
        };
        assertEquals(1, login());
        assertTrue(err().contains("Charm is unreachable at http://127.0.0.1:1/token"), err());
    }

    // --- logout ---

    @Test
    void logoutWhenNothingIsKnown() {
        assertEquals(0, run(browser, "logout"));
        assertEquals("Not logged in.", out().strip());
        assertEquals(0, run(browser, "logout", "other.lan"));
        assertEquals("Not logged in to other.lan.", out().strip());
        assertEquals(1, run(browser, "logout", "https://a.lan/x"));
        assertTrue(err().contains("not a zone name"), err());
    }

    @Test
    void logoutOfANamedZoneRevokesAndForgetsIt() {
        assertEquals(0, login(), err());
        assertEquals(0, run(browser, "logout", "TEST.LAN"));
        assertEquals("Logged out of test.lan.", out().strip());
        assertEquals(1, cluster.revocations.get());
        assertTrue(DeveloperTokens.read(home, "test.lan").isEmpty());
    }

    @Test
    void aFailedRevocationIsAWarningAndTheTokensAreDeletedAnyway() {
        assertEquals(0, login(), err());
        cluster.close();
        assertEquals(0, run(browser, "logout"));
        assertTrue(err().contains("warning: the token could not be revoked at Charm"), err());
        assertTrue(out().contains("Logged out of test.lan."), out());
        assertTrue(DeveloperTokens.read(home, "test.lan").isEmpty());
    }

    @Test
    void aZoneWithoutTokensIsForgottenFromTheConfiguration() {
        assertEquals(0, login(), err());
        assertTrue(DeveloperTokens.delete(home, "test.lan"));
        assertEquals(0, run(browser, "logout"));
        assertEquals("Logged out of test.lan.", out().strip());
        assertTrue(DeveloperConfig.read(home).isEmpty());
    }

    @Test
    void aSessionWithoutRefreshTokenIsNotRevoked() {
        assertEquals(0, login(), err());
        final DeveloperTokens.Session s = DeveloperTokens.read(home, "test.lan").orElseThrow();
        DeveloperTokens.write(home, new DeveloperTokens.Session(s.zone(), s.clientId(), s.tokenEndpoint(), s.accessToken(), s.expiresAt(), null, 0, s.audiences(), s.subject(), s.revocationEndpoint()));
        assertEquals(0, run(browser, "logout"));
        assertEquals(0, cluster.revocations.get());
    }

    // --- status ---

    @Test
    void statusWhenNothingIsKnown() {
        assertEquals(0, run(browser, "status"));
        assertTrue(out().contains("Not logged in. Run: gluonify login <zone>"), out());
    }

    @Test
    void statusSaysWhenTheEnvironmentOverridesTheZones() {
        env.put("GLUONIFY_ENVIRONMENT", "PRD");
        assertEquals(0, run(browser, "status"));
        assertTrue(out().contains("GLUONIFY_ENVIRONMENT is set"), out());
    }

    @Test
    void statusShowsEveryStateOfASession() {
        assertEquals(0, login(), err());
        final DeveloperTokens.Session s = DeveloperTokens.read(home, "test.lan").orElseThrow();
        final URI discovery = cluster.discoveryUrl();
        final DeveloperConfig known = DeveloperConfig.read(home);
        known.with(new DeveloperConfig.Zone("third.lan", discovery, "DEV", null)).with(new DeveloperConfig.Zone("other.lan", discovery, null, null)).with(known.zone("test.lan").orElseThrow()).write(home);

        DeveloperTokens.write(home, new DeveloperTokens.Session("third.lan", s.clientId(), s.tokenEndpoint(), s.accessToken(), s.expiresAt(), null, 0, List.of(), null, null));
        DeveloperTokens.write(home, new DeveloperTokens.Session("other.lan", s.clientId(), s.tokenEndpoint(), s.accessToken(), s.expiresAt(), "rt", 0, s.audiences(), "bob", null));
        assertEquals(0, run(browser, "status"));
        final String shown = out();
        assertTrue(shown.contains("* test.lan (SBX), ada, token valid until"), shown);
        assertTrue(shown.contains(", renewed automatically until "), shown);
        assertTrue(shown.contains(", audiences gluonify-crystallon gluonify-fermion"), shown);
        assertTrue(shown.contains("  other.lan, bob, token valid until"), shown);
        assertTrue(shown.contains("other.lan, bob, token valid until " + time(s.expiry()) + ", renewed automatically, audiences"), shown);
        assertTrue(shown.contains("  third.lan (DEV), token valid until"), shown);
        assertTrue(shown.contains("third.lan (DEV), token valid until " + time(s.expiry()) + ", no refresh token"), shown);
        assertFalse(shown.contains(s.accessToken()));

        assertEquals(0, run(Clock.fixed(Instant.now().plus(Duration.ofDays(3)), ZoneOffset.UTC), browser, "status"));
        assertTrue(out().contains("test.lan (SBX), ada, token expired, session expired: gluonify login test.lan"), out());

        DeveloperTokens.delete(home, "other.lan");
        assertEquals(0, run(browser, "status"));
        assertTrue(out().contains("  other.lan: not logged in"), out());
    }

    // --- token ---

    @Test
    void aServiceWithoutATokenIsCheckedOrRefusedForPrinting() {
        env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://127.0.0.1:1");
        assertEquals(0, run(browser, "token", "crystallon"));
        assertEquals("ok, crystallon at http://127.0.0.1:1 (OVERRIDE), without a token", out().strip());
        assertEquals(1, run(browser, "token", "crystallon", "--print"));
        assertTrue(err().contains("error: no token for crystallon (OVERRIDE)"), err());
    }

    // --- process wiring ---

    @Test
    void theSystemCliIsWiredToTheRealEnvironment() {
        final Cli cli = Cli.system();
        assertNotNull(cli.home);
        assertSame(System.out, cli.out);
        assertSame(System.err, cli.err);
    }

    @Test
    void openingTheBrowserNeverFailsTheLogin() {
        final String saved = System.getProperty("os.name");
        assumeTrue(Files.isExecutable(Path.of("/usr/bin/xdg-open")) == false && Files.isExecutable(Path.of("/usr/local/bin/xdg-open")) == false, "a real xdg-open would open a window");
        try {
            System.setProperty("os.name", "TestOS");
            final URI login = URI.create("https://example.invalid/login");
            assertDoesNotThrow(() -> Cli.openBrowser(login), "no xdg-open here: the failure is swallowed, the address was printed");
        }
        finally {
            System.setProperty("os.name", saved);
        }
    }

    // --- interruption: the thread is interrupted while it waits on the network or on the browser ---

    @Test
    void aLoginInterruptedBeforeTheDiscoveryStopsAndKeepsTheFlag() {
        Thread.currentThread().interrupt();
        final int code = login();
        assertTrue(Thread.interrupted(), "the interrupt flag is restored for the caller");
        assertEquals(1, code);
        assertTrue(err().contains("the login was interrupted"), err());
    }

    @Test
    void aLoginInterruptedWhileWaitingForTheBrowserStops() {
        final int code = run(uri -> Thread.currentThread().interrupt(), "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20");
        Thread.interrupted(); // the callback server's shutdown may have consumed the flag already: cleared for the next tests
        assertEquals(1, code);
        assertTrue(err().contains("the login was interrupted"), err());
    }

    @Test
    void aLoginInterruptedBeforeTheCodeExchangeStops() {
        final int code = run(uri -> {
            get(uri); // the browser answers first: the code is there, then the thread is interrupted before the exchange
            Thread.currentThread().interrupt();
        }, "login", "test.lan", "--discovery", cluster.discoveryUrl().toString(), "--timeout", "20");
        Thread.interrupted(); // the callback server's shutdown may have consumed the flag already: cleared for the next tests
        assertEquals(1, code);
        assertTrue(err().contains("the login was interrupted"), err());
    }

}
