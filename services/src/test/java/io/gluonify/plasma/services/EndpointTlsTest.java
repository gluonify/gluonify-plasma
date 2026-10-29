package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The zone's TLS context carried by an {@link Endpoint}: none without {@code caFile}, the zone's authority on top of the JDK's with one, an
 * explained failure for a file that cannot be used.
 *
 * @author David CABILLIC
 */
class EndpointTlsTest {

    private static final URI URL = URI.create("https://gluonify.lan/crystallon");

    @TempDir
    Path dir;

    @Test
    void withoutCaFileTheJdkContextApplies() {

        final Endpoint e = new Endpoint("crystallon", URL, () -> null, Endpoint.Source.PLATFORM, null, null);
        assertNull(e.caFile(), "the six-argument constructor carries no authority");
        assertTrue(e.sslContext().isEmpty());
    }

    @Test
    void aZoneAuthorityGivesAContext() throws Exception {

        final Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        assumeTrue(Files.isExecutable(keytool), "keytool is part of the JDK");
        final Path keystore = dir.resolve("ks.p12");
        final Path pem = dir.resolve("ca.pem");
        run(keytool.toString(), "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        run(keytool.toString(), "-exportcert", "-rfc", "-alias", "t", "-keystore", keystore.toString(), "-storepass", "changeit", "-file", pem.toString());
        final Endpoint e = new Endpoint("crystallon", URL, () -> null, Endpoint.Source.DEVELOPER, null, "gluonify.lan", pem);
        assertEquals("TLS", e.sslContext().orElseThrow().getProtocol());
        assertTrue(e.toString().contains("caFile=" + pem), e.toString());
    }

    @Test
    void anUnusableAuthorityIsExplained() throws Exception {

        final Path junk = Files.writeString(dir.resolve("junk.pem"), "not a certificate");
        final Endpoint e = new Endpoint("crystallon", URL, () -> null, Endpoint.Source.DEVELOPER, null, "gluonify.lan", junk);
        final GluonifyServicesException x = assertThrows(GluonifyServicesException.class, e::sslContext);
        assertTrue(x.getMessage().contains("cannot read the certificate authority file " + junk), x.getMessage());
        assertTrue(x.getMessage().endsWith("fix caFile of zone gluonify.lan in ~/.gluonify/config.json"), x.getMessage());
    }

    private static void run(final String... command) throws Exception {

        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), output);
    }
}
