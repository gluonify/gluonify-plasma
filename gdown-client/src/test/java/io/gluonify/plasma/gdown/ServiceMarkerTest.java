package io.gluonify.plasma.gdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;

/**
 * Service marker ({@code META-INF/gluonify/services/<service>}, see the gluonify-plasma README): the gDown client declares the {@code gdown} service. The builder (Bup) reads
 * these markers in the jars of the application's run-time classpath and infers {@code uses} from them.
 */
class ServiceMarkerTest {

    static final String DIR = "META-INF/gluonify/services/";

    @Test
    void theModuleCarriesTheGdownMarker() throws Exception {

        assertEquals(Set.of("gdown"), markers(GdownDriver.class));
    }

    /** Markers shipped by the module (the {@code target/classes} folder or jar) that contains {@code owner}, and only it. */
    static Set<String> markers(final Class<?> owner) throws IOException, URISyntaxException {

        final Path location = Path.of(owner.getProtectionDomain().getCodeSource().getLocation().toURI());
        final Set<String> out = new TreeSet<>();
        if (Files.isDirectory(location)) {
            final Path dir = location.resolve(DIR);
            if (Files.isDirectory(dir)) {
                try (Stream<Path> s = Files.list(dir)) {
                    s.filter(Files::isRegularFile).forEach(p -> out.add(p.getFileName().toString()));
                }
            }
            return out;
        }
        try (ZipFile zip = new ZipFile(location.toFile())) {
            final Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                final ZipEntry e = entries.nextElement();
                if (e.getName().startsWith(DIR) && !e.isDirectory()) {
                    out.add(e.getName().substring(DIR.length()));
                }
            }
        }
        return out;
    }
}
