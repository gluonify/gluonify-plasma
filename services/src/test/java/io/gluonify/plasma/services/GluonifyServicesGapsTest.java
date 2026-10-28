package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Service resolution corners: where tokens come from, malformed addresses, optional services, the process-wide instance, the builder. */
class GluonifyServicesGapsTest {
    @TempDir
    Path home;

    final Map<String, String> env = new HashMap<>();
    final Map<String, String> props = new HashMap<>();
    final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    FakeCluster cluster;

    @BeforeEach
    void start() throws Exception {
        cluster = new FakeCluster();
    }

    @AfterEach
    void stop() {
        cluster.close();
    }

    GluonifyServices services() {
        return GluonifyServices.builder().environment(env::get).properties(props::get).home(home).clock(clock).build();
    }

    /**
     * The refusal of {@code resolve(service)}, the services being built outside the asserted call.
     *
     * @param type expected exception
     * @param service name given to {@code resolve}
     * @param why message of a failed assertion, or null
     * @param <T> type of the exception
     * @return the exception thrown
     */
    <T extends Throwable> T refused(final Class<T> type, final String service, final String why) {
        final GluonifyServices s = services();
        return assertThrows(type, () -> s.resolve(service), why);
    }

    void login(final DeveloperConfig.Zone zone, final List<String> audiences) {
        DeveloperConfig.empty().with(zone).write(home);
        DeveloperTokens.write(home, new DeveloperTokens.Session(zone.name(), FakeCluster.CLIENT_ID, URI.create(cluster.issuer() + "/token"), cluster.jwt(audiences),
                clock.instant().getEpochSecond() + 3600, cluster.newRefreshToken(), 0, audiences, "ada", null));
    }

    void login() {
        login(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", null), cluster.audiences);
    }

    // --- tokens of an explicit override ---

    @Test
    void anOverrideTokenComesFromAPropertyAFileOrTheEnvironmentInThatOrder() throws Exception {
        props.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://127.0.0.1:1");
        assertTrue(services().resolve("crystallon").token().isEmpty());

        final Path file = Files.writeString(home.resolve("token-file"), "  from-file \n");
        env.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString());
        assertEquals("from-file", services().resolve("crystallon").token().orElseThrow(), "environment file");
        env.put("GLUONIFY_CRYSTALLON_TOKEN", " from-env ");
        assertEquals("from-env", services().resolve("crystallon").token().orElseThrow(), "environment token before the environment file");
        props.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString());
        assertEquals("from-file", services().resolve("crystallon").token().orElseThrow(), "property file before the environment");
        props.put("GLUONIFY_CRYSTALLON_TOKEN", "from-property");
        assertEquals("from-property", services().resolve("crystallon").token().orElseThrow(), "property token first");
    }

    @Test
    void aTokenFileIsRereadAndMayBeAbsentOrEmpty() throws Exception {
        final Path file = home.resolve("token-file");
        env.put("GLUONIFY_ENVIRONMENT", "SBX");
        env.put("GLUONIFY_SERVICE_FERMION_URL", "https://fermion.sbx");
        env.put("GLUONIFY_FERMION_TOKEN_FILE", file.toString());
        final Endpoint e = services().resolve("fermion");
        assertEquals(Endpoint.Source.PLATFORM, e.source());
        assertTrue(e.token().isEmpty(), "not dropped yet");
        Files.writeString(file, "  \n");
        assertTrue(e.token().isEmpty(), "empty");
        Files.writeString(file, "first");
        assertEquals("first", e.token().orElseThrow());
        Files.writeString(file, "second");
        assertEquals("second", e.token().orElseThrow(), "reread on every call");
    }

    @Test
    void aTokenFileThatCannotBeReadIsReported() {
        env.put("GLUONIFY_ENVIRONMENT", "SBX");
        env.put("GLUONIFY_SERVICE_FERMION_URL", "https://fermion.sbx");
        env.put("GLUONIFY_FERMION_TOKEN_FILE", home.toString());
        final Endpoint e = services().resolve("fermion");
        final GluonifyServicesException ex = assertThrows(GluonifyServicesException.class, e::token);
        assertTrue(ex.getMessage().contains("cannot read the token file of fermion"), ex.getMessage());
    }

    @Test
    void onThePlatformATokenMayComeFromTheEnvironmentOrBeAbsent() {
        env.put("GLUONIFY_ENVIRONMENT", "SBX");
        env.put("GLUONIFY_SERVICE_FERMION_URL", "https://fermion.sbx/");
        assertTrue(services().resolve("fermion").token().isEmpty());
        env.put("GLUONIFY_FERMION_TOKEN", "  platform-token ");
        final Endpoint e = services().resolve("fermion");
        assertEquals("platform-token", e.token().orElseThrow());
        assertEquals(URI.create("https://fermion.sbx"), e.url(), "the trailing slash is dropped");
    }

    // --- addresses and names ---

    @Test
    void anAddressMustBeAnAbsoluteHttpOrWebSocketUrl() {
        for (final String bad : new String[]{"http://exa mple", "ftp://host/x", "/relative", "http:///nohost", "just text"}) {
            props.put("GLUONIFY_SERVICE_FERMION_URL", bad);
            refused(GluonifyServicesException.class, "fermion", bad);
        }
        props.put("GLUONIFY_SERVICE_FERMION_URL", "http://exa mple");
        assertTrue(refused(GluonifyServicesException.class, "fermion", null).getMessage().endsWith("is not a valid URL"));
        props.put("GLUONIFY_SERVICE_FERMION_URL", "ftp://host/x");
        assertTrue(refused(GluonifyServicesException.class, "fermion", null).getMessage().endsWith("must be an absolute http(s) URL"));
        for (final String ok : new String[]{"ws://host/x", "wss://host/x", "HTTPS://host/x"}) {
            props.put("GLUONIFY_SERVICE_FERMION_URL", ok);
            assertEquals(Endpoint.Source.OVERRIDE, services().resolve("fermion").source(), ok);
        }
    }

    @Test
    void aServiceNameIsCheckedAndNormalized() {
        props.put("GLUONIFY_SERVICE_MY_SVC_URL", "https://my.svc");
        assertEquals("my-svc", services().resolve("  My-Svc ").service());
        refused(IllegalArgumentException.class, "not a name", null);
        refused(IllegalArgumentException.class, "", null);
        refused(NullPointerException.class, null, null);
        assertEquals("GLUONIFY_SERVICE_MY_SVC_URL", GluonifyServices.urlKey("my-svc"));
        assertEquals("GLUONIFY_MY_SVC_TOKEN", GluonifyServices.tokenKey("my-svc"));
        assertEquals("GLUONIFY_MY_SVC_TOKEN_FILE", GluonifyServices.tokenFileKey("my-svc"));
    }

    // --- optional services ---

    @Test
    void anOptionalServiceFollowsTheSameThreeSources() {
        props.put("GLUONIFY_SERVICE_A_URL", "https://a.example");
        assertEquals(Endpoint.Source.OVERRIDE, services().resolveOptional("a").orElseThrow().source());

        env.put("GLUONIFY_ENVIRONMENT", "SBX");
        assertTrue(services().onPlatform());
        assertTrue(services().resolveOptional("b").isEmpty(), "absent from uses on the platform");
        env.put("GLUONIFY_SERVICE_B_URL", "https://b.example");
        assertEquals(Endpoint.Source.PLATFORM, services().resolveOptional("b").orElseThrow().source());
        env.put("GLUONIFY_SERVICE_C_URL", " ");
        assertTrue(services().resolveOptional("c").isEmpty(), "a blank address is no address");
        refused(GluonifyServicesException.class, "c", "required on the platform: refused");

        env.clear();
        assertFalse(services().onPlatform());
        assertTrue(services().resolveOptional("d").isEmpty(), "no cluster known on the workstation");
    }

    @Test
    void aZoneThatPublishesNoServiceSaysSo() {
        cluster.services.clear();
        login();
        final GluonifyServicesException e = refused(GluonifyServicesException.class, "crystallon", null);
        assertTrue(e.getMessage().contains("(it publishes no service)"), e.getMessage());
    }

    @Test
    void aConfigurationWithoutCurrentZoneIsExplained() {
        DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), null, null)).write(home);
        final Path file = home.resolve(DeveloperConfig.FILE);
        try {
            Files.writeString(file, Files.readString(file).replaceAll("\"current\":[^,\\n]*,?", ""));
        }
        catch (final java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        final GluonifyServicesException e = refused(GluonifyServicesException.class, "crystallon", null);
        assertTrue(e.getMessage().contains("no current zone in"), e.getMessage());
    }

    @Test
    void theAudienceCheckIsSkippedWhenEitherSideDoesNotKnowIt() {
        login(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", null), List.of());
        assertEquals(Endpoint.Source.DEVELOPER, services().resolve("crystallon").source(), "a token without audiences cannot be checked");
        cluster.discoveryTweak = m -> {
            final Map<String, Object> svc = new java.util.LinkedHashMap<>();
            svc.put("crystallon", Map.of("url", cluster.base() + "/crystallon"));
            m.put("services", svc);
            return m;
        };
        cluster.audiences = List.of("some-other-audience");
        login(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", null), List.of("some-other-audience"));
        assertNull(services().resolve("crystallon").audience(), "a service without audience accepts any token");
    }

    // --- HTTP client and builder ---

    @Test
    void aConfiguredHttpClientIsUsedForDiscovery() {
        login();
        final HttpClient client = HttpClient.newHttpClient();
        final GluonifyServices s = GluonifyServices.builder().environment(env::get).properties(props::get).home(home).clock(clock).httpClient(client).build();
        assertEquals(Endpoint.Source.DEVELOPER, s.resolve("crystallon").source());
        assertSame(client, s.http(null));
        assertSame(client, s.http(home.resolve("any.pem")));
    }

    @Test
    void aCertificateAuthorityFileThatCannotBeUsedPointsToTheConfigurationFile() {
        login(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", home.resolve("absent-ca.pem")), cluster.audiences);
        final GluonifyServicesException e = refused(GluonifyServicesException.class, "crystallon", null);
        assertTrue(e.getMessage().contains("fix caFile in " + home.resolve(DeveloperConfig.FILE)), e.getMessage());
    }

    @Test
    void clientsAreSharedPerAuthorityFile() {
        final GluonifyServices s = services();
        assertSame(s.http(null), s.http(null));
        assertEquals(home, s.home());
    }

    @Test
    void theDiscoveryIsReadAgainAfterItsLifetime() {
        login();
        final GluonifyServices s = GluonifyServices.builder().environment(env::get).properties(props::get).home(home).clock(clock).discoveryTtl(Duration.ofSeconds(10)).build();
        s.resolve("crystallon");
        s.resolve("crystallon");
        assertEquals(1, cluster.discoveryHits.get());
        clock.advance(Duration.ofSeconds(11));
        s.resolve("crystallon");
        assertEquals(2, cluster.discoveryHits.get());
        s.invalidate();
        s.resolve("crystallon");
        assertEquals(3, cluster.discoveryHits.get());
    }

    @Test
    void builderArgumentsMayNotBeNull() {
        final GluonifyServices.Builder b = GluonifyServices.builder();
        assertThrows(NullPointerException.class, () -> b.environment((java.util.function.UnaryOperator<String>) null));
        assertThrows(NullPointerException.class, () -> b.properties((java.util.function.UnaryOperator<String>) null));
        assertThrows(NullPointerException.class, () -> b.home(null));
        assertThrows(NullPointerException.class, () -> b.clock(null));
        assertThrows(NullPointerException.class, () -> b.discoveryTtl(null));
        assertThrows(NullPointerException.class, () -> b.httpClient(null));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedFunctionFormsOfTheBuilderStillWork() {
        props.put("GLUONIFY_SERVICE_A_URL", "https://a.example");
        final java.util.function.Function<String, String> environment = env::get;
        final java.util.function.Function<String, String> properties = props::get;
        final GluonifyServices s = GluonifyServices.builder().environment(environment).properties(properties).home(home).build();
        assertEquals(Endpoint.Source.OVERRIDE, s.resolve("a").source());
    }

    @Test
    void theHomeDefaultsToTheUsersFolderWhenNotGiven() {
        final Map<String, String> userHome = Map.of("user.home", "/home/ada");
        final GluonifyServices s = GluonifyServices.builder().environment(env::get).properties(userHome::get).build();
        assertEquals(Path.of("/home/ada/.gluonify"), s.home());
    }

    // --- the process-wide instance ---

    @Test
    void theProcessInstanceIsSharedAndResolvesThroughTheStaticShortcuts() {
        final String key = "GLUONIFY_SERVICE_COVERAGEPROBE_URL";
        System.setProperty(key, "https://probe.example/");
        try {
            final GluonifyServices first = GluonifyServices.defaults();
            assertSame(first, GluonifyServices.defaults());
            assertEquals(URI.create("https://probe.example"), GluonifyServices.endpoint("coverageprobe").url());
            assertEquals(Endpoint.Source.OVERRIDE, GluonifyServices.optional("coverageprobe").orElseThrow().source());
            assertNotNull(first.home());
        }
        finally {
            System.clearProperty(key);
        }
    }
}
