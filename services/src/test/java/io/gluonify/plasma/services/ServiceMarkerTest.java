package io.gluonify.plasma.services;

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
 * Service marker ({@code META-INF/gluonify/services/<service>}, see the README of gluonify-plasma): generic resolution declares none (it reaches no service by itself). The builder (Bup) reads these markers
 * in the jars of the application's runtime classpath and derives {@code uses} from them.
 */
class ServiceMarkerTest {
    static final String DIR = "META-INF/gluonify/services/";

    @Test
    void marqueursDuModule() throws Exception {
        assertEquals(Set.of(), markers(GluonifyServices.class));
    }

    /** Markers shipped by the module (folder {@code target/classes} or jar) that contains {@code owner}, and only that one. */
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
