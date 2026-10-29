package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServicesException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Crystallon of a development cluster signed by a local authority (Pebble): the client built from an endpoint that carries the zone's
 * {@code caFile} reaches it; the same address without the file is refused by the JDK's authorities (no option trusts everything).
 *
 * @author David CABILLIC
 */
class CrystallonZoneTlsTest {

    private static final byte[] PDF = "%PDF-1.7 minimal".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    private HttpsServer tls;

    private Path pem;

    @BeforeEach
    void start() throws Exception {

        assumeTrue(Files.isExecutable(keytool()), "keytool is part of the JDK");
        final Path keystore = dir.resolve("ks.p12");
        pem = dir.resolve("ca.pem");
        run(keytool().toString(), "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext",
                "san=dns:localhost,ip:127.0.0.1", "-validity", "2", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        run(keytool().toString(), "-exportcert", "-rfc", "-alias", "t", "-keystore", keystore.toString(), "-storepass", "changeit", "-file", pem.toString());
        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, "changeit".toCharArray());
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        final SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        tls = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tls.setHttpsConfigurator(new HttpsConfigurator(ctx));
        tls.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.sendResponseHeaders(200, PDF.length);
            ex.getResponseBody().write(PDF);
            ex.close();
        });
        tls.start();
    }

    @AfterEach
    void stop() {

        if (tls != null) {
            tls.stop(0);
        }
    }

    @Test
    void theZoneAuthorityOfTheEndpointIsTrusted() throws Exception {

        final CrystallonClient client = CrystallonClient.builder().endpoint(endpoint(pem)).build();
        assertArrayEquals(PDF, client.byId("t").render(Map.of()).bytes());
    }

    @Test
    void withoutTheZoneAuthorityTheCertificateIsRefused() {

        final CrystallonClient client = CrystallonClient.builder().endpoint(endpoint(null)).build();
        final CrystallonException e = assertThrows(CrystallonException.class, () -> client.byId("t").render(Map.of()));
        assertEquals(0, e.status(), "a transport failure, not an HTTP status: " + e.getMessage());
    }

    @Test
    void anUnreadableZoneAuthorityIsExplainedWhenTheClientIsBuilt() {

        final Path absent = dir.resolve("absent.pem");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> CrystallonClient.builder().endpoint(endpoint(absent)));
        assertTrue(e.getMessage().contains("fix caFile of zone gluonify.lan"), e.getMessage());
    }

    private Endpoint endpoint(final Path caFile) {

        return new Endpoint("crystallon", URI.create("https://localhost:" + tls.getAddress().getPort()), () -> "developer-token", Endpoint.Source.DEVELOPER,
                "gluonify-crystallon", "gluonify.lan", caFile);
    }

    private static Path keytool() {

        return Path.of(System.getProperty("java.home"), "bin", "keytool");
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
