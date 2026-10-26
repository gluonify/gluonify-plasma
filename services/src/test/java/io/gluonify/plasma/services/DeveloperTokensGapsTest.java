package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The developer's token files and their renewal at Charm: states of a session, damaged files, every way a renewal can fail. */
class DeveloperTokensGapsTest {
    static final String ZONE = "test.lan";
    @TempDir
    Path home;

    final MutableClock clock = new MutableClock(Instant.parse("2026-10-11T10:00:00Z"));
    FakeCluster cluster;
    HttpServer slow;

    @BeforeEach
    void start() throws Exception {
        cluster = new FakeCluster();
    }

    @AfterEach
    void stop() {
        cluster.close();
        if (slow != null) {
            slow.stop(0);
        }
    }

    Supplier<HttpClient> http() {
        final HttpClient client = HttpClient.newHttpClient();
        return () -> client;
    }

    long now() {
        return clock.instant().getEpochSecond();
    }

    /** A session of the fake cluster; {@code ttl} seconds of access token left (negative: expired). */
    DeveloperTokens.Session session(final long ttl, final String refresh, final long refreshExpiresAt) {
        return new DeveloperTokens.Session(ZONE, FakeCluster.CLIENT_ID, URI.create(cluster.issuer() + "/token"), cluster.jwt(cluster.audiences), now() + ttl, refresh, refreshExpiresAt,
                cluster.audiences, "ada", URI.create(cluster.issuer() + "/revoke"));
    }

    // --- the session record ---

    @Test
    void aSessionPrintsNeverItsTokens() {
        final DeveloperTokens.Session s = new DeveloperTokens.Session(ZONE, "c", null, "ACCESS-SECRET", 0, "REFRESH-SECRET", 0, null, "ada", null);
        assertFalse(s.toString().contains("SECRET"), s.toString());
        assertTrue(s.toString().contains("refreshable=true"), s.toString());
        assertTrue(s.toString().contains("subject=ada"), s.toString());
        assertEquals(List.of(), s.audiences(), "no audiences: an empty list, not null");
    }

    @Test
    void freshnessAndRenewalOfASession() {
        final Clock c = clock;
        final DeveloperTokens.Session valid = new DeveloperTokens.Session(ZONE, "c", null, "a", now() + 100, "r", 0, null, null, null);
        assertTrue(valid.fresh(c, Duration.ZERO));
        assertTrue(valid.fresh(c, Duration.ofSeconds(99)));
        assertFalse(valid.fresh(c, Duration.ofSeconds(100)), "the margin is taken off the remaining time");
        assertFalse(new DeveloperTokens.Session(ZONE, "c", null, null, now() + 100, "r", 0, null, null, null).fresh(c, Duration.ZERO), "no access token");
        assertTrue(valid.renewable(c), "unknown refresh expiry: we try");
        assertTrue(new DeveloperTokens.Session(ZONE, "c", null, "a", 0, "r", now() + 1, null, null, null).renewable(c));
        assertFalse(new DeveloperTokens.Session(ZONE, "c", null, "a", 0, "r", now(), null, null, null).renewable(c), "expired refresh token");
        assertFalse(new DeveloperTokens.Session(ZONE, "c", null, "a", 0, null, 0, null, null, null).renewable(c), "no refresh token");
    }

    // --- files ---

    @Test
    void aZoneNameCannotEscapeTheTokensFolder() {
        assertThrows(IllegalArgumentException.class, () -> DeveloperTokens.file(home, "../etc"));
        assertThrows(IllegalArgumentException.class, () -> DeveloperTokens.read(home, "A/B"));
        assertEquals(home.resolve("tokens").resolve("a.lan.json"), DeveloperTokens.file(home, "a.lan"));
    }

    @Test
    void tokensAreWrittenAndReadBackWithEveryField() {
        final DeveloperTokens.Session s = session(3600, "rt", now() + 86400);
        DeveloperTokens.write(home, s);
        assertEquals(s, DeveloperTokens.read(home, ZONE).orElseThrow());
        assertTrue(DeveloperTokens.read(home, "other.lan").isEmpty());
    }

    @Test
    void optionalFieldsMayBeAbsent() {
        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, "c", null, "a", 5, null, 0, List.of("x"), null, null));
        final DeveloperTokens.Session back = DeveloperTokens.read(home, ZONE).orElseThrow();
        assertNull(back.tokenEndpoint());
        assertNull(back.revocationEndpoint());
        assertNull(back.subject());
        assertNull(back.refreshToken());
        assertEquals(List.of("x"), back.audiences());
    }

    @Test
    void aDamagedOrUnreadableFileIsExplained() throws Exception {
        final Path file = DeveloperTokens.file(home, ZONE);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{broken");
        final GluonifyServicesException broken = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.read(home, ZONE));
        assertTrue(broken.getMessage().contains("are not readable: run gluonify login " + ZONE + " again"), broken.getMessage());
        Files.write(file, new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xc3});
        final GluonifyServicesException unreadable = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.read(home, ZONE));
        assertTrue(unreadable.getMessage().contains("cannot read the developer tokens"), unreadable.getMessage());
    }

    @Test
    void writingWhereNothingCanBeWrittenIsReported() throws Exception {
        final Path blocked = Files.writeString(home.resolve("file-not-folder"), "x");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.write(blocked, session(60, "r", 0)));
        assertTrue(e.getMessage().contains("cannot write the developer tokens of zone " + ZONE), e.getMessage());
    }

    @Test
    void deleteReportsWhetherAFileExistedAndRemovesTheLock() throws Exception {
        assertFalse(DeveloperTokens.delete(home, ZONE));
        DeveloperTokens.write(home, session(60, "r", 0));
        final Path lock = DeveloperTokens.file(home, ZONE).resolveSibling(ZONE + ".json.lock");
        Files.writeString(lock, "");
        assertTrue(DeveloperTokens.delete(home, ZONE));
        assertFalse(Files.exists(lock));
        assertTrue(DeveloperTokens.read(home, ZONE).isEmpty());
    }

    @Test
    void aTokenFileThatCannotBeDeletedIsReported() throws Exception {
        final Path file = DeveloperTokens.file(home, ZONE);
        Files.createDirectories(file);
        Files.writeString(file.resolve("inside"), "x");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.delete(home, ZONE));
        assertTrue(e.getMessage().contains("cannot delete the developer tokens of zone " + ZONE), e.getMessage());
    }

    // --- token responses ---

    static String jwt(final Map<String, Object> claims) {
        final Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        return b.encodeToString("{}".getBytes(StandardCharsets.UTF_8)) + "." + b.encodeToString(io.gluonify.plasma.services.internal.Json.write(claims).getBytes(StandardCharsets.UTF_8)) + ".sig";
    }

    static final DeveloperTokens.Origin ORIGIN = new DeveloperTokens.Origin(ZONE, "c", URI.create("https://a.lan/token"), null);

    @Test
    void aResponseWithoutAccessTokenIsRefused() {
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.fromTokenResponse(ORIGIN, Map.of("expires_in", 5), clock, null));
        assertEquals("Charm answered without an access token", e.getMessage());
    }

    @Test
    void withoutANewRefreshTokenThePreviousOneIsKept() {
        final DeveloperTokens.Session previous = session(10, "old-refresh", now() + 777);
        final Map<String, Object> response = Map.of("access_token", jwt(Map.of("sub", "u-1", "aud", List.of("a1"))), "expires_in", 60);
        final DeveloperTokens.Session next = DeveloperTokens.fromTokenResponse(ORIGIN, response, clock, previous);
        assertEquals("old-refresh", next.refreshToken());
        assertEquals(now() + 777, next.refreshExpiresAt());
        assertEquals(now() + 60, next.expiresAt());
        assertEquals("u-1", next.subject(), "no preferred_username: the subject claim");
        assertEquals(List.of("a1"), next.audiences());
        final DeveloperTokens.Session without = DeveloperTokens.fromTokenResponse(ORIGIN, response, clock, null);
        assertNull(without.refreshToken());
        assertEquals(0, without.refreshExpiresAt());
    }

    @Test
    void theRefreshLifetimeAndTheDefaultAccessLifetimeAreRead() {
        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("access_token", jwt(Map.of("preferred_username", "ada", "sub", "u")));
        response.put("refresh_token", "r2");
        response.put("refresh_expires_in", 100);
        final DeveloperTokens.Session s = DeveloperTokens.fromTokenResponse(ORIGIN, response, clock, null);
        assertEquals("ada", s.subject());
        assertEquals(now() + 3600, s.expiresAt(), "expires_in defaults to an hour");
        assertEquals(now() + 100, s.refreshExpiresAt());
        assertEquals("r2", s.refreshToken());
    }

    @Test
    void anAccessTokenThatIsNotAJwtHasNoClaims() {
        for (final String token : new String[]{"opaque-token", "a.!!!.c", "a." + Base64.getUrlEncoder().withoutPadding().encodeToString("not json".getBytes(StandardCharsets.UTF_8)) + ".c"}) {
            final DeveloperTokens.Session s = DeveloperTokens.fromTokenResponse(ORIGIN, Map.of("access_token", token), clock, null);
            assertNull(s.subject(), token);
            assertEquals(List.of(), s.audiences(), token);
        }
    }

    @SuppressWarnings("removal")
    @Test
    void theDeprecatedLongFormBuildsTheSameSession() {
        final Map<String, Object> response = Map.of("access_token", jwt(Map.of("sub", "u")), "refresh_token", "r");
        final DeveloperTokens.Session viaOrigin = DeveloperTokens.fromTokenResponse(ORIGIN, response, clock, null);
        final DeveloperTokens.Session viaArguments = DeveloperTokens.fromTokenResponse(ZONE, "c", URI.create("https://a.lan/token"), null, response, clock, null);
        assertEquals(viaOrigin, viaArguments);
    }

    // --- access token and renewal ---

    @Test
    void withoutASessionThereIsNoToken() {
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.accessToken(home, ZONE, http(), clock));
        assertEquals("not logged in to zone test.lan: run gluonify login test.lan", e.getMessage());
        assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
    }

    @Test
    void aFreshTokenIsReturnedWithoutAnyCall() {
        final DeveloperTokens.Session s = session(3600, "rt", 0);
        DeveloperTokens.write(home, s);
        final Supplier<HttpClient> never = () -> {
            throw new AssertionError("no HTTP call for a fresh token");
        };
        assertEquals(s.accessToken(), DeveloperTokens.accessToken(home, ZONE, never, clock));
    }

    @Test
    void anExpiredTokenIsRenewedAndTheNewSessionIsStored() {
        DeveloperTokens.write(home, session(-10, cluster.newRefreshToken(), 0));
        final String renewed = DeveloperTokens.accessToken(home, ZONE, http(), clock);
        assertEquals(1, cluster.refreshes.get());
        assertEquals(renewed, DeveloperTokens.read(home, ZONE).orElseThrow().accessToken());
    }

    @Test
    void aSessionWithoutClientIdRenewsAsTheDefaultClient() {
        final DeveloperTokens.Session s = session(-10, cluster.newRefreshToken(), 0);
        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, null, s.tokenEndpoint(), s.accessToken(), s.expiresAt(), s.refreshToken(), 0, s.audiences(), "ada", null));
        DeveloperTokens.accessToken(home, ZONE, http(), clock);
        assertEquals(Discovery.DEFAULT_CLIENT_ID, cluster.lastToken.get("client_id"));
    }

    @Test
    void forcingRenewsAFreshTokenAndNotForcingDoesNot() {
        DeveloperTokens.write(home, session(3600, cluster.newRefreshToken(), 0));
        DeveloperTokens.refreshed(home, ZONE, http(), clock, false);
        assertEquals(0, cluster.refreshes.get());
        DeveloperTokens.refreshed(home, ZONE, http(), clock, true);
        assertEquals(1, cluster.refreshes.get());
    }

    @Test
    void aSessionThatCannotBeRenewedMustLogInAgain() {
        final DeveloperTokens.Session s = session(-10, "rt", now() - 1);
        DeveloperTokens.write(home, s);
        final GluonifyServicesException expired = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.accessToken(home, ZONE, http(), clock));
        assertTrue(expired.getMessage().contains("has expired or was revoked: run gluonify login " + ZONE), expired.getMessage());
        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, "c", null, s.accessToken(), s.expiresAt(), "rt", 0, null, null, null));
        assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.accessToken(home, ZONE, http(), clock), "no token endpoint to renew at");
    }

    @Test
    void aRefreshTokenAlreadyUsedOrRefusedMeansTheSessionIsOver() {
        final String once = cluster.newRefreshToken();
        DeveloperTokens.write(home, session(-10, once, 0));
        DeveloperTokens.refreshed(home, ZONE, http(), clock, true);
        DeveloperTokens.write(home, session(-10, once, 0));
        final GluonifyServicesException used = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
        assertTrue(used.getMessage().contains("has expired or was revoked"), used.getMessage());

        cluster.tokenFailureStatus = 401;
        DeveloperTokens.write(home, session(-10, cluster.newRefreshToken(), 0));
        final GluonifyServicesException denied = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
        assertTrue(denied.getMessage().contains("has expired or was revoked"), denied.getMessage());
    }

    @Test
    void otherAnswersOfCharmAreReportedWithTheirStatusAndError() {
        DeveloperTokens.write(home, session(-10, cluster.newRefreshToken(), 0));
        cluster.tokenFailureStatus = 500;
        cluster.tokenFailureBody = "{\"error\":\"boom\"}";
        final GluonifyServicesException withError = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
        assertTrue(withError.getMessage().contains("Charm answered HTTP 500 (boom)"), withError.getMessage());
        cluster.tokenFailureStatus = 503;
        cluster.tokenFailureBody = "<html>unavailable</html>";
        final GluonifyServicesException withoutError = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
        assertTrue(withoutError.getMessage().endsWith("Charm answered HTTP 503"), withoutError.getMessage());
    }

    @Test
    void anInsecureOrUnreachableTokenEndpointIsReported() {
        final DeveloperTokens.Session s = session(-10, "rt", 0);
        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, s.clientId(), URI.create("http://cluster.example/token"), s.accessToken(), s.expiresAt(), "rt", 0, null, null, null));
        final GluonifyServicesException insecure = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.accessToken(home, ZONE, http(), clock));
        assertTrue(insecure.getMessage().contains("is not https: run gluonify login " + ZONE), insecure.getMessage());

        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, s.clientId(), URI.create("http://127.0.0.1:1/token"), s.accessToken(), s.expiresAt(), "rt", 0, null, null, null));
        final GluonifyServicesException down = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.accessToken(home, ZONE, http(), clock));
        assertTrue(down.getMessage().contains("Charm is unreachable at http://127.0.0.1:1/token"), down.getMessage());
    }

    @Test
    void aFolderThatCannotHoldTheLockIsReported() throws Exception {
        Files.writeString(home.resolve("tokens"), "a file where the folder should be");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
        assertTrue(e.getMessage().contains("cannot lock the developer tokens of zone " + ZONE), e.getMessage());
    }

    @Test
    void aRenewalInterruptedWhileWaitingForCharmKeepsTheInterruption() throws Exception {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slow.createContext("/token", ex -> {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ex.sendResponseHeaders(500, -1);
            ex.close();
        });
        slow.start();
        final DeveloperTokens.Session s = session(-10, "rt", 0);
        DeveloperTokens.write(home, new DeveloperTokens.Session(ZONE, s.clientId(), URI.create("http://127.0.0.1:" + slow.getAddress().getPort() + "/token"), s.accessToken(), s.expiresAt(), "rt", 0, null, null, null));
        final Thread caller = Thread.currentThread();
        final Thread interrupter = new Thread(() -> {
            try {
                entered.await(30, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e) {
                return;
            }
            caller.interrupt();
        });
        interrupter.start();
        try {
            final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperTokens.refreshed(home, ZONE, http(), clock, true));
            assertTrue(e.getMessage().contains("was interrupted"), e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted(), "the interruption is restored for the caller");
        }
        finally {
            Thread.interrupted();
            release.countDown();
            interrupter.join();
        }
    }
}
