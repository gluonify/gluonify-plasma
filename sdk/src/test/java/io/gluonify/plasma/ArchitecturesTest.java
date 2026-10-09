package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ArchitecturesTest {
    @Test
    void developmentHostsHaveTheirOwnArchitectureKeys() {
        for (String a : new String[] {"amd64", "arm64", "darwin-amd64", "darwin-arm64", "windows-amd64", "windows-arm64"})
            assertTrue(AppSpec.ARCHITECTURES.contains(a), a);
        assertFalse(AppSpec.ARCHITECTURES.contains("darwin"));
        assertFalse(AppSpec.ARCHITECTURES.contains("linux-amd64"));
    }
}
