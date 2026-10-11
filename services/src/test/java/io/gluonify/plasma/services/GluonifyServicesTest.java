package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GluonifyServicesTest {
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

    /** Connecte le poste à la zone test.lan du faux cluster (ce que fait gluonify login). */
    DeveloperTokens.Session login(final long expiresInSeconds) {
        DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", null)).write(home);
        final DeveloperTokens.Session s = new DeveloperTokens.Session("test.lan", FakeCluster.CLIENT_ID, URI.create(cluster.issuer() + "/token"),
                cluster.jwt(cluster.audiences), clock.instant().getEpochSecond() + expiresInSeconds, cluster.newRefreshToken(), 0, cluster.audiences, "ada", null);
        DeveloperTokens.write(home, s);
        return s;
    }

    // ------------------------------------------------------------------------------------------------ 1. surcharge

    @Test
    void surchargeParProprieteSysteme() {
        props.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://127.0.0.1:9999/base/");
        props.put("GLUONIFY_CRYSTALLON_TOKEN", " t1 ");
        env.put("GLUONIFY_ENVIRONMENT", "PRD"); // même sur la plateforme, la propriété système l'emporte
        env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://10.0.0.1:8502");
        final Endpoint e = services().resolve("crystallon");
        assertEquals(Endpoint.Source.OVERRIDE, e.source());
        assertEquals("http://127.0.0.1:9999/base", e.url().toString());
        assertEquals("t1", e.token().orElseThrow());
        assertEquals(URI.create("http://127.0.0.1:9999/base/v1/render"), e.resolve("/v1/render"));
    }

    @Test
    void surchargeParVariableHorsPlateforme() throws Exception {
        env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "https://crystallon.example");
        final Path file = home.resolve("t.token");
        env.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString());
        final Endpoint e = services().resolve("crystallon");
        assertEquals(Endpoint.Source.OVERRIDE, e.source());
        assertTrue(e.token().isEmpty(), "fichier pas encore là");
        Files.writeString(file, "abc\n");
        assertEquals("abc", e.token().orElseThrow());
    }

    @Test
    void surchargeSansJeton() {
        props.put("GLUONIFY_SERVICE_MY_SVC_URL", "http://localhost:1");
        final Endpoint e = services().resolve("my-svc");
        assertTrue(e.token().isEmpty());
        assertEquals("GLUONIFY_SERVICE_MY_SVC_URL", GluonifyServices.urlKey("my-svc"));
        assertEquals("GLUONIFY_MY_SVC_TOKEN_FILE", GluonifyServices.tokenFileKey("my-svc"));
    }

    @Test
    void adresseInvalide() {
        props.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "ftp://x");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("GLUONIFY_SERVICE_CRYSTALLON_URL"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> services().resolve("../etc"));
    }

    // ------------------------------------------------------------------------------------------------ 2. plateforme

    @Test
    void plateformeAdresseInjecteeEtJetonRelu() throws Exception {
        env.put("GLUONIFY_ENVIRONMENT", "DEV");
        env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://10.200.0.5:8502");
        final Path file = home.resolve("crystallon.token");
        env.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString());
        final Endpoint e = services().resolve("crystallon");
        assertEquals(Endpoint.Source.PLATFORM, e.source());
        assertEquals("http://10.200.0.5:8502", e.url().toString());
        assertTrue(e.token().isEmpty());
        Files.writeString(file, "app-1");
        assertEquals("app-1", e.token().orElseThrow());
        Files.writeString(file, "app-2");
        assertEquals("app-2", e.token().orElseThrow(), "relu à chaque appel (renouvelé par l'agent)");
    }

    @Test
    void plateformeServiceAbsentDeUses() {
        env.put("GLUONIFY_ENVIRONMENT", "DEV");
        login(3600); // ignoré sur la plateforme
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("\"crystallon\"") && e.getMessage().contains("uses") && e.getMessage().contains("gluonify.json"), e.getMessage());
        assertTrue(services().resolveOptional("crystallon").isEmpty());
        assertEquals(0, cluster.discoveryHits.get());
    }

    // ------------------------------------------------------------------------------------------------ 3. poste du développeur

    @Test
    void developpeurDecouverteEtJeton() {
        final DeveloperTokens.Session s = login(3600);
        final Endpoint e = services().resolve("crystallon");
        assertEquals(Endpoint.Source.DEVELOPER, e.source());
        assertEquals(cluster.base() + "/crystallon", e.url().toString());
        assertEquals("gluonify-crystallon", e.audience());
        assertEquals("test.lan", e.zone());
        assertEquals(s.accessToken(), e.token().orElseThrow());
        assertEquals(0, cluster.refreshes.get());
        assertFalse(e.toString().contains(s.accessToken()));
    }

    @Test
    void decouverteGardeeCinqMinutes() {
        login(3600);
        final GluonifyServices services = services();
        services.resolve("crystallon");
        services.resolve("fermion");
        assertEquals(1, cluster.discoveryHits.get());
        clock.advance(Duration.ofMinutes(4));
        services.resolve("crystallon");
        assertEquals(1, cluster.discoveryHits.get());
        clock.advance(Duration.ofMinutes(2));
        services.resolve("crystallon");
        assertEquals(2, cluster.discoveryHits.get());
        // cluster momentanément en panne : la dernière copie sert
        cluster.discoveryStatus = 503;
        clock.advance(Duration.ofMinutes(6));
        assertEquals(cluster.base() + "/crystallon", services.resolve("crystallon").url().toString());
        services.invalidate();
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services.resolve("crystallon"));
        assertTrue(e.getMessage().contains("HTTP 503") && e.getMessage().contains("test.lan"), e.getMessage());
    }

    @Test
    void jetonExpireRenouvele() throws Exception {
        final DeveloperTokens.Session old = login(30); // moins que la marge d'une minute
        final Endpoint e = services().resolve("crystallon");
        final String t = e.token().orElseThrow();
        assertNotEquals(old.accessToken(), t);
        assertEquals(1, cluster.refreshes.get());
        assertEquals("refresh_token", cluster.lastToken.get("grant_type"));
        final DeveloperTokens.Session now = DeveloperTokens.read(home, "test.lan").orElseThrow();
        assertEquals(t, now.accessToken());
        assertNotEquals(old.refreshToken(), now.refreshToken(), "Charm fait tourner le jeton de rafraîchissement");
        assertEquals(clock.instant().getEpochSecond() + 3600, now.expiresAt());
        assertEquals(t, e.token().orElseThrow());
        assertEquals(1, cluster.refreshes.get(), "jeton frais : pas de second renouvellement");
        final Path file = DeveloperTokens.file(home, "test.lan");
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.getParent()));
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(home.resolve("config.json")));
        }
        clock.advance(Duration.ofMinutes(60));
        assertNotEquals(t, e.token().orElseThrow());
        assertEquals(2, cluster.refreshes.get());
    }

    @Test
    void renouvellementUniqueEntreFils() throws Exception {
        login(0);
        final Endpoint e = services().resolve("crystallon");
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<String>> calls = new ArrayList<>();
            for (int k = 0; k < 16; k++) {
                calls.add(() -> e.token().orElseThrow());
            }
            final Set<String> seen = new java.util.HashSet<>();
            for (final Future<String> f : pool.invokeAll(calls)) {
                seen.add(f.get());
            }
            assertEquals(1, seen.size());
            assertEquals(1, cluster.refreshes.get(), "un seul renouvellement : le jeton de rafraîchissement est à usage unique");
        }
        finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sessionExpiree() throws Exception {
        final DeveloperTokens.Session s = login(0);
        // jeton de rafraîchissement déjà utilisé (révoqué chez Charm)
        DeveloperTokens.write(home, new DeveloperTokens.Session(s.zone(), s.clientId(), s.tokenEndpoint(), s.accessToken(), s.expiresAt(), "rt-unknown", 0,
                s.audiences(), s.subject(), null));
        final Endpoint e = services().resolve("crystallon");
        final GluonifyServicesException ex = assertThrows(GluonifyServicesException.class, e::token);
        assertTrue(ex.getMessage().contains("expired") && ex.getMessage().contains("gluonify login test.lan"), ex.getMessage());
        assertFalse(ex.getMessage().contains("rt-unknown"));
        // jeton de rafraîchissement expiré : pas d'appel à Charm
        DeveloperTokens.write(home, new DeveloperTokens.Session(s.zone(), s.clientId(), s.tokenEndpoint(), s.accessToken(), s.expiresAt(), "rt-x",
                clock.instant().getEpochSecond() - 1, s.audiences(), s.subject(), null));
        assertThrows(GluonifyServicesException.class, e::token);
        assertEquals(0, cluster.refreshes.get());
    }

    @Test
    void pasConnecte() {
        DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), null, null)).write(home);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertEquals("not logged in to zone test.lan: run gluonify login test.lan", e.getMessage());
    }

    @Test
    void serviceNonPublie() {
        login(3600);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("interfero"));
        assertTrue(e.getMessage().contains("does not publish the service interfero") && e.getMessage().contains("crystallon"), e.getMessage());
        assertTrue(services().resolveOptional("interfero").isEmpty());
        assertTrue(services().resolveOptional("crystallon").isPresent());
    }

    @Test
    void audienceAbsenteDuJeton() {
        cluster.audiences = List.of("gluonify-fermion");
        login(3600);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("gluonify-crystallon") && e.getMessage().contains("gluonify login test.lan"), e.getMessage());
        assertEquals(Endpoint.Source.DEVELOPER, services().resolve("fermion").source());
    }

    @Test
    void zoneChoisieParVariable() {
        login(3600);
        env.put("GLUONIFY_ZONE", "other.lan");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("other.lan") && e.getMessage().contains("gluonify login other.lan"), e.getMessage());
        env.put("GLUONIFY_ZONE", "test.lan");
        assertEquals("test.lan", services().resolve("crystallon").zone());
    }

    @Test
    void decouverteInjoignable() {
        DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", URI.create("http://127.0.0.1:1/.well-known/gluonify"), null, null)).write(home);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("is the cluster running"), e.getMessage());
    }

    @Test
    void decouverteEnClairRefusee() {
        DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", URI.create("http://cluster.example/.well-known/gluonify"), null, null)).write(home);
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        assertTrue(e.getMessage().contains("must be https"), e.getMessage());
    }

    // ------------------------------------------------------------------------------------------------ 4. rien

    @Test
    void rienDeConnu() {
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> services().resolve("crystallon"));
        final String m = e.getMessage();
        assertTrue(m.contains("not the Gluonify platform") && m.contains("no cluster is known") && m.contains("gluonify login gluonify.lan")
                && m.contains("GLUONIFY_SERVICE_CRYSTALLON_URL") && m.contains("GluonifyTestServices"), m);
        assertTrue(services().resolveOptional("crystallon").isEmpty());
    }

    @Test
    void dossierParVariable() {
        assertEquals(Path.of("/x/y"), DeveloperConfig.home(Map.of("GLUONIFY_HOME", "/x/y")::get, k -> "/home/me"));
        assertEquals(Path.of("/home/me/.gluonify"), DeveloperConfig.home(k -> null, k -> "/home/me"));
    }
}
