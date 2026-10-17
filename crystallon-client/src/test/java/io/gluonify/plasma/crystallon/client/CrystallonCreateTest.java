package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyServicesException;
import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link CrystallonClient#create()}: the address and the token come from the gluonify-plasma-services resolution. */
class CrystallonCreateTest {
    @TempDir
    Path home;

    private FakeCrystallon fake;
    private final Map<String, String> env = new HashMap<>();
    private final Map<String, String> props = new HashMap<>();

    @BeforeEach
    void start() {
        fake = new FakeCrystallon();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private GluonifyServices services() {
        return GluonifyServices.builder().environment(env::get).properties(props::get).home(home).build();
    }

    @Test
    void createParSurcharge() {
        props.put("GLUONIFY_SERVICE_CRYSTALLON_URL", fake.url() + "/prefix/");
        props.put("GLUONIFY_CRYSTALLON_TOKEN", "test-token");
        fake.reply(FakeCrystallon.Reply.pdf());
        final RenderedPdf pdf = CrystallonClient.create(services()).byName("Gluonify invoice").render(Map.of("n", 1));
        assertArrayEquals(FakeCrystallon.PDF, pdf.bytes());
        assertEquals("/prefix/v1/templates/by-name/Gluonify%20invoice/render", fake.last().rawPath());
        assertEquals("Bearer test-token", fake.last().header("Authorization"));
    }

    @Test
    void createSurLaPlateforme() throws Exception {
        env.put("GLUONIFY_ENVIRONMENT", "DEV");
        env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", fake.url());
        final Path file = home.resolve("crystallon.token");
        java.nio.file.Files.writeString(file, "app-token\n");
        env.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString());
        fake.reply(FakeCrystallon.Reply.pdf());
        CrystallonClient.create(services()).byId("t1").render(Map.of());
        assertEquals("Bearer app-token", fake.last().header("Authorization"));
    }

    @Test
    void createSansRienDeConnu() {
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> CrystallonClient.create(services()));
        assertTrue(e.getMessage().contains("gluonify login") && e.getMessage().contains("GluonifyTestServices"), e.getMessage());
        env.put("GLUONIFY_ENVIRONMENT", "DEV");
        final GluonifyServicesException p = assertThrows(GluonifyServicesException.class, () -> CrystallonClient.create(services()));
        assertTrue(p.getMessage().contains("\"crystallon\"") && p.getMessage().contains("uses"), p.getMessage());
    }

    @Test
    void echecDuJetonDevientCrystallonException() {
        final Endpoint e = new Endpoint("crystallon", URI.create(fake.url()), () -> {
            throw new GluonifyServicesException("the developer session of zone x.lan has expired or was revoked: run gluonify login x.lan");
        }, Endpoint.Source.DEVELOPER, "gluonify-crystallon", "x.lan");
        final CrystallonClient client = CrystallonClient.builder().endpoint(e).build();
        final CrystallonException ex = assertThrows(CrystallonException.class, () -> client.byId("t").render(Map.of()));
        assertEquals(0, ex.status());
        assertTrue(ex.getMessage().contains("gluonify login x.lan"), ex.getMessage());
        assertTrue(fake.calls().isEmpty(), "no call without a valid token");
    }
}
