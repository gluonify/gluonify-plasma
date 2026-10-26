package io.gluonify.plasma.services.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The HTTP helpers: which addresses are secure, form encoding, the body bound, non-JSON answers, and a cluster signed by a local authority (real TLS). */
class HttpGapsTest {
    @TempDir
    Path dir;

    HttpServer plain;
    HttpsServer tls;

    @AfterEach
    void stop() {
        if (plain != null) {
            plain.stop(0);
        }
        if (tls != null) {
            tls.stop(0);
        }
    }

    HttpServer serve(final int status, final byte[] body) throws IOException {
        plain = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plain.createContext("/", ex -> {
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        plain.start();
        return plain;
    }

    URI plainUri() {
        return URI.create("http://127.0.0.1:" + plain.getAddress().getPort() + "/");
    }

    @Test
    void onlyHttpsOrLoopbackHttpIsSecure() {
        assertTrue(Http.secure(URI.create("https://cluster.example/x")));
        assertTrue(Http.secure(URI.create("HTTPS://cluster.example")));
        assertTrue(Http.secure(URI.create("http://localhost:8080")));
        assertTrue(Http.secure(URI.create("http://127.0.0.1:1")));
        assertTrue(Http.secure(URI.create("http://[::1]:8080")));
        assertTrue(Http.secure(URI.create("http://app.localhost")));
        assertFalse(Http.secure(URI.create("http://cluster.example")));
        assertFalse(Http.secure(URI.create("ftp://localhost")));
        assertFalse(Http.secure(URI.create("/relative")));
        assertFalse(Http.secure(URI.create("https:///nohost")));
        assertFalse(Http.secure(null));
    }

    @Test
    void loopbackNamesAreRecognized() {
        assertTrue(Http.loopback("LOCALHOST"));
        assertTrue(Http.loopback("127.10.20.30"));
        assertFalse(Http.loopback("127.0.0.1.example.com"));
        assertFalse(Http.loopback("10.0.0.1"));
    }

    @Test
    void aFormIsEncodedAndNullValuesAreDropped() {
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("a b", "c&d=e");
        form.put("skipped", null);
        form.put("z", "é");
        assertEquals("a+b=c%26d%3De&z=%C3%A9", Http.form(form));
        assertEquals("", Http.form(Map.of()));
    }

    @Test
    void aJsonObjectAnswerIsParsedAndAnotherBodyKeepsOnlyItsStatus() throws Exception {
        serve(200, "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        final Http.Response ok = Http.getJson(Http.client(null), plainUri());
        assertTrue(ok.ok());
        assertEquals(Map.of("a", 1L), ok.json());
        plain.stop(0);

        serve(502, "<html>bad gateway</html>".getBytes(StandardCharsets.UTF_8));
        final Http.Response page = Http.getJson(Http.client(null), plainUri());
        assertEquals(502, page.status());
        assertNull(page.json());
        assertFalse(page.ok());
        plain.stop(0);

        serve(204, new byte[0]);
        final Http.Response empty = Http.postForm(Http.client(null), plainUri(), Map.of("k", "v"));
        assertEquals(204, empty.status());
        assertFalse(empty.ok(), "no JSON body: not ok even with a 2xx status");
        assertFalse(new Http.Response(500, Map.of()).ok());
        assertTrue(new Http.Response(201, Map.of()).ok());
    }

    @Test
    void aResponseLargerThanTheBoundIsRefused() throws Exception {
        serve(200, new byte[Http.MAX_BODY + 1]);
        final IOException e = assertThrows(IOException.class, () -> Http.getJson(Http.client(null), plainUri()));
        assertTrue(e.getMessage().contains("response larger than"), e.getMessage());
    }

    // --- extra certificate authority ---

    static boolean keytoolAvailable() {
        return Files.isExecutable(Path.of(System.getProperty("java.home"), "bin", "keytool"));
    }

    /** A self-signed certificate for localhost: the PKCS#12 keystore and its PEM export (the "local authority" file). */
    void certificate(final Path keystore, final Path pem) throws Exception {
        final String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        run(keytool, "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "san=dns:localhost,ip:127.0.0.1",
                "-validity", "2", "-keystore", keystore.toString(), "-storetype", "PKCS12", "-storepass", "changeit");
        run(keytool, "-exportcert", "-rfc", "-alias", "t", "-keystore", keystore.toString(), "-storepass", "changeit", "-file", pem.toString());
    }

    static void run(final String... command) throws Exception {
        final Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output;
        try (InputStream in = p.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), output);
    }

    HttpsServer https(final Path keystore) throws Exception {
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
            final byte[] b = "{\"tls\":true}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        tls.start();
        return tls;
    }

    @Test
    void aClusterSignedByALocalAuthorityIsTrustedOnlyWithItsFile() throws Exception {
        assumeTrue(keytoolAvailable(), "keytool is part of the JDK");
        final Path keystore = dir.resolve("ks.p12");
        final Path pem = dir.resolve("ca.pem");
        certificate(keystore, pem);
        https(keystore);
        final URI uri = URI.create("https://localhost:" + tls.getAddress().getPort() + "/");

        final Http.Response r = Http.getJson(Http.client(pem), uri);
        assertEquals(Map.of("tls", Boolean.TRUE), r.json());
        assertThrows(IOException.class, () -> Http.getJson(Http.client(null), uri), "the JDK's authorities do not know it");
    }

    @Test
    void aCertificateAuthorityFileThatCannotBeUsedIsExplained() throws Exception {
        final Path empty = Files.writeString(dir.resolve("empty.pem"), "");
        final IllegalArgumentException none = assertThrows(IllegalArgumentException.class, () -> Http.client(empty));
        assertTrue(none.getMessage().contains("cannot read the certificate authority file " + empty), none.getMessage());
        assertTrue(none.getCause().getMessage().contains("no certificate in " + empty), "the reason is kept as the cause");

        final Path junk = Files.writeString(dir.resolve("junk.pem"), "-----BEGIN CERTIFICATE-----\nnot a certificate\n-----END CERTIFICATE-----\n");
        assertThrows(IllegalArgumentException.class, () -> Http.client(junk));

        final IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> Http.client(dir.resolve("absent.pem")));
        assertTrue(missing.getMessage().contains("cannot read the certificate authority file"), missing.getMessage());
    }

    @Test
    void theClientNeverFollowsRedirects() throws Exception {
        plain = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        plain.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/elsewhere");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        plain.start();
        final HttpClient client = Http.client(null);
        assertEquals(HttpClient.Redirect.NEVER, client.followRedirects());
        assertEquals(302, Http.getJson(client, plainUri()).status());
    }
}
