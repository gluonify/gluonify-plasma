package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

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
        assertNull(p.token(), "fichier pas encore déposé");
        Files.writeString(file, " a\n");
        assertEquals("a", p.token());
        Files.writeString(file, "b");
        assertEquals("b", p.token());
        Files.writeString(file, "  ");
        assertNull(p.token(), "fichier vide : pas de jeton");
    }

    @Test
    void platformSansLaVariable() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> FermionTokenProvider.platform(Map.<String, String>of()::get));
        assertTrue(e.getMessage().contains("GLUONIFY_FERMION_TOKEN_FILE") && e.getMessage().contains("uses"), e.getMessage());
    }
}
