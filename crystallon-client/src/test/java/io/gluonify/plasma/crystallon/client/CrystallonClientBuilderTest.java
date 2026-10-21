package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Client construction: mandatory address, platform environment, rejected values. */
class CrystallonClientBuilderTest {
    @TempDir
    Path dir;

    @Test
    void theBaseUrlIsRequired() {
        final var subject = CrystallonClient.builder();
        final IllegalStateException e = assertThrows(IllegalStateException.class, subject::build);
        assertTrue(e.getMessage().contains("base URL"));
    }

    @Test
    void theBaseUrlMustBeAnAbsoluteHttpUrl() {
        final var subject5 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject5.baseUrl("crystallon:8080"));
        final var subject4 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject4.baseUrl("ftp://crystallon"));
        final var subject3 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject3.baseUrl("/relative"));
        final var subject2 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject2.baseUrl("http://crystallon?x=1"));
        final var subject = CrystallonClient.builder();
        assertThrows(NullPointerException.class, () -> subject.baseUrl((String) null));
        CrystallonClient.builder().baseUrl(URI.create("https://crystallon.example")).build();
    }

    @Test
    void valuesAreChecked() {
        final var subject3 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject3.timeout(Duration.ZERO));
        final var input = Duration.ofSeconds(-1);
        final var subject2 = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject2.timeout(input));
        final var subject = CrystallonClient.builder();
        assertThrows(IllegalArgumentException.class, () -> subject.maxPdfBytes(0));
        final CrystallonClient client = CrystallonClient.builder().baseUrl("http://crystallon").build();
        assertThrows(IllegalArgumentException.class, () -> client.byId(" "));
        assertThrows(IllegalArgumentException.class, () -> client.byName(null));
    }

    @Test
    void fromEnvironmentReadsTheUrlAndTheToken() throws Exception {
        final Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, "file-token\n");
        try (FakeCrystallon fake = new FakeCrystallon()) {
            final Map<String, String> env = new HashMap<>();
            env.put("GLUONIFY_SERVICE_CRYSTALLON_URL", " " + fake.url() + "/ ");
            env.put("GLUONIFY_CRYSTALLON_TOKEN_FILE", tokenFile.toString());

            final RenderedPdf pdf = CrystallonClient.fromEnvironment(env::get).byName("Gluonify invoice").render(Map.of());

            assertEquals(FakeCrystallon.PDF.length, pdf.bytes().length);
            assertEquals("/v1/templates/by-name/Gluonify%20invoice/render", fake.last().rawPath());
            assertEquals("Bearer file-token", fake.last().header("Authorization"));

            env.put("GLUONIFY_CRYSTALLON_TOKEN", "env-token");
            CrystallonClient.fromEnvironment(env::get).byId("t1").render(Map.of());
            assertEquals("Bearer env-token", fake.last().header("Authorization"));
        }
    }

    @Test
    void fromEnvironmentWithoutTheUrlExplainsWhatToDo() {
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> CrystallonClient.fromEnvironment(Map.<String, String>of()::get));
        assertTrue(e.getMessage().contains("GLUONIFY_SERVICE_CRYSTALLON_URL"));
        assertTrue(e.getMessage().contains("uses"));
        final var blankEnv = Map.of("GLUONIFY_SERVICE_CRYSTALLON_URL", "  ");
        final IllegalStateException blank = assertThrows(IllegalStateException.class,
                () -> CrystallonClient.fromEnvironment(blankEnv::get));
        assertTrue(blank.getMessage().contains("GLUONIFY_SERVICE_CRYSTALLON_URL"));
    }

    @Test
    void theRealEnvironmentIsUsedByDefault() {
        assumeTrue(System.getenv("GLUONIFY_SERVICE_CRYSTALLON_URL") == null);
        assertThrows(IllegalStateException.class, CrystallonClient::fromEnvironment);
    }

    @Test
    void theNameSegmentIsEncodedByteByByte() {
        assertEquals("Gluonify%20invoice", CrystallonClient.segment("Gluonify invoice"));
        assertEquals("a%2Fb", CrystallonClient.segment("a/b"));
        assertEquals("%C3%A9%E2%82%AC", CrystallonClient.segment("é€"));
        assertEquals("A-z_0.9~", CrystallonClient.segment("A-z_0.9~"));
        assertEquals("%2E", CrystallonClient.segment("."));
        assertEquals("%3F%23%25%2B", CrystallonClient.segment("?#%+"));
    }
}
