package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.mcp.McpContract;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The marker Bup reads to set {@code mcp: true} ({@code app-mcp.md} § 8 d2): {@code META-INF/gluonify/mcp} in this module, and no
 * {@code META-INF/gluonify/services/} entry (MCP is a capability, never a {@code uses} service).
 */
class McpMarkerTest {

    @Test
    void theModuleCarriesTheMcpMarkerAndNoServiceMarker() throws Exception {
        final Path classes = Path.of(McpResource.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        assertTrue(Files.isDirectory(classes), "run from target/classes");
        assertTrue(Files.isRegularFile(classes.resolve(McpContract.MARKER)));
        assertFalse(Files.exists(classes.resolve("META-INF/gluonify/services")));
    }
}
