package io.gluonify.plasma.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.crystallon.client.CrystallonClient;
import io.gluonify.plasma.crystallon.client.RenderedPdf;
import io.gluonify.plasma.services.GluonifyServices;
import io.gluonify.plasma.services.GluonifyServicesException;
import io.gluonify.plasma.testing.FakeCrystallon;
import io.gluonify.plasma.testing.GluonifyTestServices;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The application writes nothing: the injected {@code CrystallonClient} comes from the default producer and talks to the fake services. */
@QuarkusTest
class GluonifyProducersTest {
    static GluonifyTestServices services;

    @BeforeAll
    static void start() {
        services = GluonifyTestServices.start();
    }

    @AfterAll
    static void stop() {
        services.close();
    }

    @Inject
    Instance<CrystallonClient> crystallon;

    @Test
    void clientInjecteSansConfiguration() {
        assertTrue(crystallon.isResolvable());
        final CrystallonClient client = crystallon.get();
        assertSame(client, crystallon.get(), "a single client for the application");
        final RenderedPdf pdf = client.byName("Gluonify invoice").render(Map.of("date", LocalDate.of(2026, 10, 11)));
        assertArrayEquals(FakeCrystallon.pdf(), pdf.bytes());
        // the application's mapper (Quarkus: ISO 8601 dates) writes the payload
        assertTrue(services.crystallon().lastRender().body().contains("\"2026-10-11\""), services.crystallon().lastRender().body());
    }

    @Test
    void sansServiceLeMessageDitQuoiFaire(@TempDir final Path home) {
        final GluonifyServices none = GluonifyServices.builder().environment(k -> null).properties(k -> null).home(home).build();
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> GluonifyProducers.crystallon(none, null));
        assertTrue(e.getMessage().contains("gluonify login"), e.getMessage());
    }
}
