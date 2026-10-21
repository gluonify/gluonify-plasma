package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequiresTest {
    @Test
    void sortsDeduplicatesAndSelectsByDistribution() {
        var r = new Requires(Map.of("debian13", List.of("libx11-6", "libfreetype6", "libx11-6"), "ubuntu24.04", List.of("libfreetype6")));
        assertEquals(List.of("libfreetype6", "libx11-6"), r.forDistro("debian13"));
        assertEquals(List.of(), r.forDistro("debian12"));
        assertFalse(r.isEmpty());
        assertTrue(Requires.NONE.isEmpty());
    }

    @Test
    void rejectsAnythingThatCouldBeReadAsAnOptionOrShellSyntax() {
        for (String bad : List.of("-y", "--force-yes", "a b", "a;b", "$(id)", "UPPER", "x", "", "a/b", "a".repeat(65))) {
            final var input3 = Map.of("debian13", List.of(bad));
            assertThrows(IllegalArgumentException.class, () -> new Requires(input3), bad);
        }
        final var input2 = Map.of("Debian 13", List.of("libc6"));
        assertThrows(IllegalArgumentException.class, () -> new Requires(input2));
        final var input = Map.of("../x", List.of("libc6"));
        assertThrows(IllegalArgumentException.class, () -> new Requires(input));
    }

    @Test
    void boundsTheNumberOfPackages() {
        var many = new java.util.ArrayList<String>();
        for (int i = 0; i < Requires.MAX_PACKAGES + 1; i++) many.add("pkg" + String.format("%03d", i));
        final Map<String, List<String>> input = Map.of("debian13", many);
        assertThrows(IllegalArgumentException.class, () -> new Requires(input));
    }

    @Test
    void buildsTheDistributionIdFromOsRelease() {
        assertEquals("debian13", Requires.distroId("debian", "13"));
        assertEquals("ubuntu24.04", Requires.distroId("Ubuntu", "24.04"));
    }
}
