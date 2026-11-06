package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The cluster's root as seen by {@link GluonifyTls}: the variable and its alias, the JDK's default without a usable file, and a real TLS exchange with a
 * private root that only a client holding it accepts.
 *
 * @author David CABILLIC
 */
class GluonifyTlsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @TempDir
    Path dir;

    private static UnaryOperator<String> env(final String key, final String value) {

        return Map.of(key, value)::get;
    }

    @Test
    void withoutTheVariableTheJdkDefaultApplies() throws Exception {

        final UnaryOperator<String> none = k -> null;
        assertTrue(GluonifyTls.caFile(none).isEmpty());
        assertTrue(GluonifyTls.trustManager(none).isEmpty());
        assertSame(SSLContext.getDefault(), GluonifyTls.sslContext(none));
        assertTrue(GluonifyTls.caFile(env(GluonifyTls.CA_FILE_ENV, " ")).isEmpty(), "blank is absent");
    }

    @Test
    void anUnreadableOrInvalidFileIsNeverFatal() throws Exception {

        final UnaryOperator<String> missing = env(GluonifyTls.CA_FILE_ENV, dir.resolve("missing.pem").toString());
        assertTrue(GluonifyTls.caFile(missing).isEmpty());
        final UnaryOperator<String> junk = env(GluonifyTls.CA_FILE_ENV, Files.writeString(dir.resolve("junk.pem"), "not a certificate").toString());
        assertTrue(GluonifyTls.caFile(junk).isPresent());
        assertTrue(GluonifyTls.trustManager(junk).isEmpty());
        assertSame(SSLContext.getDefault(), GluonifyTls.sslContext(junk));
        assertTrue(GluonifyTls.builder(TIMEOUT, HttpClient.Redirect.NEVER, junk).build().sslContext() == SSLContext.getDefault());
    }

    @Test
    void theAliasIsReadWhenTheVariableIsAbsent() throws Exception {

        final Path pem = Files.writeString(dir.resolve("root.pem"), "x");
        assertEquals(pem, GluonifyTls.caFile(env(GluonifyTls.CA_FILE_ALIAS_ENV, pem.toString())).orElseThrow());
        final UnaryOperator<String> both = Map.of(GluonifyTls.CA_FILE_ENV, pem.toString(), GluonifyTls.CA_FILE_ALIAS_ENV, "/nowhere")::get;
        assertEquals(pem, GluonifyTls.caFile(both).orElseThrow(), "the variable wins");
    }

    @Test
    void theProcessEnvironmentHasNoRootInTests() {

        assertTrue(GluonifyTls.caFile().isEmpty());
        assertTrue(GluonifyTls.trustManager().isEmpty());
        assertEquals(GluonifyTls.sslContext(k -> null), GluonifyTls.sslContext());
        assertEquals(HttpClient.Redirect.ALWAYS, GluonifyTls.httpClient(TIMEOUT, HttpClient.Redirect.ALWAYS).followRedirects());
    }

    @Test
    void onlyAClientWithThePrivateRootReachesTheServer() throws Exception {

        final String password = UUID.randomUUID().toString();
        final Path keystore = dir.resolve("ks.p12");
        final Path pem = dir.resolve("root.pem");
        final String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2",
                "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", password);
        run(keytool, "-exportcert", "-rfc", "-alias", "t", "-keystore", keystore.toString(), "-storepass", password, "-file", pem.toString());
        final HttpsServer server = server(keystore, password.toCharArray());
        try {
            final URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
            final HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).build();
            final HttpClient trusting = GluonifyTls.builder(TIMEOUT, HttpClient.Redirect.NEVER, env(GluonifyTls.CA_FILE_ENV, pem.toString())).build();
            assertEquals(204, trusting.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
            final HttpClient jdk = GluonifyTls.builder(TIMEOUT, HttpClient.Redirect.NEVER, k -> null).build();
            assertThrows(SSLHandshakeException.class, () -> jdk.send(request, HttpResponse.BodyHandlers.discarding()));
            assertTrue(GluonifyTls.trustManager(env(GluonifyTls.CA_FILE_ENV, pem.toString())).orElseThrow().getAcceptedIssuers().length > 1, "system roots kept");
        }
        finally {
            server.stop(0);
        }
    }

    @Test
    void thePlatformEndpointCarriesTheRootAndSurvivesABadOne() throws Exception {

        final Path junk = Files.writeString(dir.resolve("junk.pem"), "not a certificate");
        final Endpoint e = new Endpoint("charm", URI.create("http://10.0.0.1:1"), () -> null, Endpoint.Source.PLATFORM, null, null, junk);
        assertTrue(e.sslContext().isEmpty());
    }

    @Test
    void thePlatformResolutionSetsTheRoot() throws Exception {

        final Path pem = Files.writeString(dir.resolve("root.pem"), "x");
        final Map<String, String> vars = Map.of(GluonifyServices.ENV_ENVIRONMENT, "DEV", "GLUONIFY_SERVICE_CHARM_URL", "http://10.0.0.1:1", GluonifyTls.CA_FILE_ENV, pem.toString());
        final Endpoint e = GluonifyServices.builder().environment((UnaryOperator<String>) vars::get).properties((UnaryOperator<String>) k -> null).home(dir).build().resolve("charm");
        assertEquals(pem, e.caFile());
    }

    private static HttpsServer server(final Path keystore, final char[] password) throws Exception {

        final KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore)) {
            ks.load(in, password);
        }
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        final SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        final HttpsServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(ctx));
        server.createContext("/", x -> {
            x.sendResponseHeaders(204, -1);
            x.close();
        });
        server.start();
        return server;
    }

    private static void run(final String... command) throws Exception {

        final Process p = new ProcessBuilder(List.of(command)).redirectErrorStream(true).start();
        final String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(0, p.waitFor(), output);
    }
}
