package io.gluonify.plasma.crystallon.client;

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
 * Marqueur de service ({@code META-INF/gluonify/services/<service>}, voir le README de gluonify-plasma) : le client de Crystallon déclare le service {@code crystallon}. Le builder (Bup) lit ces marqueurs
 * dans les jars du chemin de classes d'exécution de l'application et en déduit {@code uses}.
 */
class ServiceMarkerTest {
    static final String DIR = "META-INF/gluonify/services/";

    @Test
    void marqueursDuModule() throws Exception {
        assertEquals(Set.of("crystallon"), markers(CrystallonClient.class));
    }

    /** Marqueurs livrés par le module (dossier {@code target/classes} ou jar) qui contient {@code owner}, et lui seul. */
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
            for (final Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements();) {
                final String n = e.nextElement().getName();
                if (n.startsWith(DIR) && n.length() > DIR.length() && n.indexOf('/', DIR.length()) < 0) {
                    out.add(n.substring(DIR.length()));
                }
            }
        }
        return out;
    }
}
