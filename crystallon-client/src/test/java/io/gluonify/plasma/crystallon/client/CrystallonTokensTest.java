package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Les sources de jeton, seules puis dans un appel réel (l'en-tête {@code Authorization}). */
class CrystallonTokensTest {
    private FakeCrystallon fake;

    @TempDir
    Path dir;

    @BeforeEach
    void start() {
        fake = new FakeCrystallon();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    private void callWith(final CrystallonTokens tokens) {
        fake.client().tokens(tokens).build().byId("t1").render(Map.of());
    }

    @Test
    void noneSendsNoAuthorizationHeader() {
        callWith(CrystallonTokens.none());
        assertNull(fake.last().header("Authorization"));
        assertEquals(Optional.empty(), CrystallonTokens.none().token());
    }

    @Test
    void theBuilderDefaultIsNone() {
        fake.client().build().byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"));
    }

    @Test
    void fixedSendsABearerToken() {
        callWith(CrystallonTokens.fixed("  abc.def.ghi \n"));
        assertEquals("Bearer abc.def.ghi", fake.last().header("Authorization"));
        assertThrows(IllegalArgumentException.class, () -> CrystallonTokens.fixed("  "));
        assertThrows(NullPointerException.class, () -> CrystallonTokens.fixed(null));
    }

    @Test
    void aFileIsReadAgainAtEachCallAndTrimmed() throws IOException {
        final Path file = dir.resolve("crystallon.token");
        final CrystallonClient client = fake.client().tokens(CrystallonTokens.file(file)).build();

        client.byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"), "a missing file means no token");

        Files.writeString(file, "first-token\n");
        client.byId("t1").render(Map.of());
        assertEquals("Bearer first-token", fake.last().header("Authorization"));

        Files.writeString(file, "  second-token  \r\n\r\n");
        client.byId("t1").render(Map.of());
        assertEquals("Bearer second-token", fake.last().header("Authorization"));

        Files.writeString(file, " \n");
        client.byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"), "an empty file means no token");

        Files.writeString(file, "third-token");
        Files.delete(file);
        client.byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"));
    }

    @Test
    void aFileThatCannotBeReadIsAnError() {
        final CrystallonException e = assertThrows(CrystallonException.class, () -> CrystallonTokens.file(dir).token());
        assertEquals(0, e.status());
        assertTrue(e.getMessage().contains(dir.toString()));
    }

    @Test
    void forwardingAsksTheSupplierAtEachCall() {
        final AtomicReference<String> current = new AtomicReference<>("person-1");
        final CrystallonClient client = fake.client().tokens(CrystallonTokens.forwarding(current::get)).build();

        client.byId("t1").render(Map.of());
        assertEquals("Bearer person-1", fake.last().header("Authorization"));

        current.set("person-2");
        client.byId("t1").render(Map.of());
        assertEquals("Bearer person-2", fake.last().header("Authorization"));

        current.set(null);
        client.byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"));

        current.set("");
        client.byId("t1").render(Map.of());
        assertNull(fake.last().header("Authorization"));
    }

    @Test
    void aTokenThatIsNotAValidHeaderValueIsRefusedWithoutBeingEchoed() {
        final CrystallonException e = assertThrows(CrystallonException.class, () -> callWith(CrystallonTokens.fixed("line1\nline2")));
        assertEquals(0, e.status());
        assertFalse(e.getMessage().contains("line1"));
        assertTrue(fake.calls().isEmpty());
    }

    @Test
    void fromEnvironmentPrefersTheTokenThenTheFileThenNone() throws IOException {
        final Path file = dir.resolve("t.token");
        Files.writeString(file, "from-file\n");

        assertEquals(Optional.of("from-env"), CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN", " from-env ",
                "GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token());
        assertEquals(Optional.of("from-file"), CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN", " ",
                "GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token());
        assertEquals(Optional.of("from-file"), CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token());
        assertEquals(Optional.empty(), CrystallonTokens.fromEnvironment(Map.<String, String>of()::get).token());
    }
}
