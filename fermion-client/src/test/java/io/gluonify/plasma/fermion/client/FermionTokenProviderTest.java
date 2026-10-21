package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.services.DeveloperConfig;
import io.gluonify.plasma.services.DeveloperTokens;
import io.gluonify.plasma.services.FakeCluster;
import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyServicesException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FermionTokenProviderTest {
    @TempDir
    Path dir;

    @Test
    void platformLitLeFichierAChaqueConnexion() throws Exception {
        Path file = dir.resolve("fermion.token");
        FermionTokenProvider p = FermionTokenProvider.platform(Map.of("GLUONIFY_FERMION_TOKEN_FILE", file.toString())::get);
        assertNull(p.token(), "file not dropped yet");
        Files.writeString(file, " a\n");
        assertEquals("a", p.token());
        Files.writeString(file, "b");
        assertEquals("b", p.token());
        Files.writeString(file, "  ");
        assertNull(p.token(), "empty file: no token");
    }

    @Test
    void platformSansLaVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FermionTokenProvider.platform(Map.<String, String>of()::get));
        assertTrue(e.getMessage().contains("GLUONIFY_FERMION_TOKEN_FILE") && e.getMessage().contains("uses"), e.getMessage());
    }

    @Test
    void gluonifySurLaPlateforme() throws Exception {
        Path file = dir.resolve("fermion.token");
        Files.writeString(file, "app-token");
        GluonifyServices services = GluonifyServices.builder().home(dir).properties(k -> null)
                .environment(Map.of("GLUONIFY_ENVIRONMENT", "DEV", "GLUONIFY_SERVICE_FERMION_URL", "http://10.0.0.7:8090", "GLUONIFY_FERMION_TOKEN_FILE", file.toString())::get).build();
        assertEquals("app-token", FermionTokenProvider.gluonify(services).token());
    }

    @Test
    void gluonifySurLePosteDuDeveloppeur() throws Exception {
        try (FakeCluster cluster = new FakeCluster()) {
            DeveloperConfig.empty().with(new DeveloperConfig.Zone("test.lan", cluster.discoveryUrl(), "SBX", null)).write(dir);
            String jwt = cluster.jwt(cluster.audiences);
            DeveloperTokens.write(dir, new DeveloperTokens.Session("test.lan", FakeCluster.CLIENT_ID, URI.create(cluster.issuer() + "/token"), jwt,
                    System.currentTimeMillis() / 1000 + 3600, cluster.newRefreshToken(), 0, cluster.audiences, "ada", null));
            GluonifyServices services = GluonifyServices.builder().home(dir).properties(k -> null).environment(k -> null).build();
            assertEquals(jwt, FermionTokenProvider.gluonify(services).token());
            assertEquals(cluster.base() + "/fermion", services.resolve("fermion").url().toString());
        }
    }

    @Test
    void gluonifySansRienDeConnu() {
        GluonifyServices services = GluonifyServices.builder().home(dir).properties(k -> null).environment(k -> null).build();
        final var subject = FermionTokenProvider.gluonify(services);
        GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> subject.token());
        assertTrue(e.getMessage().contains("gluonify login"), e.getMessage());
    }
}
