package io.gluonify.plasma.services.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** The module's HTTP calls (discovery, Charm): bounded JSON, timeouts, extra certificate authority. Not a public API. */
public final class Http {
    /** Maximum size of a response read (discovery, tokens). */
    public static final int MAX_BODY = 1024 * 1024;
    public static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private Http() {
    }

    /** A response: status and JSON body (object), or {@code null} if the body is not a JSON object. */
    public record Response(int status, Map<String, Object> json) {
        public boolean ok() {
            return status / 100 == 2 && json != null;
        }
    }

    /**
     * A new HTTP client (never in a static field: native image). {@code caFile}: a PEM file of authorities accepted IN ADDITION to those of the
     * JDK (development cluster signed by a local authority), or {@code null}.
     */
    public static HttpClient client(final Path caFile) {
        final HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER);
        if (caFile != null) {
            b.sslContext(sslContext(caFile));
        }
        return b.build();
    }

    static SSLContext sslContext(final Path caFile) {
        try {
            final List<X509Certificate> extra = new ArrayList<>();
            try (InputStream in = Files.newInputStream(caFile)) {
                for (final Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) {
                    extra.add((X509Certificate) c);
                }
            }
            if (extra.isEmpty()) {
                throw new IllegalArgumentException("no certificate in " + caFile);
            }
            final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            for (int k = 0; k < extra.size(); k++) {
                ks.setCertificateEntry("gluonify-" + k, extra.get(k));
            }
            final X509TrustManager custom = trustManager(ks);
            final X509TrustManager system = trustManager(null);
            final X509TrustManager both = new X509TrustManager() {
                @Override
                public void checkClientTrusted(final X509Certificate[] chain, final String auth) throws CertificateException {
                    system.checkClientTrusted(chain, auth);
                }

                @Override
                public void checkServerTrusted(final X509Certificate[] chain, final String auth) throws CertificateException {
                    try {
                        system.checkServerTrusted(chain, auth);
                    }
                    catch (final CertificateException e) {
                        custom.checkServerTrusted(chain, auth);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    final X509Certificate[] a = system.getAcceptedIssuers();
                    final X509Certificate[] b = custom.getAcceptedIssuers();
                    final X509Certificate[] out = new X509Certificate[a.length + b.length];
                    System.arraycopy(a, 0, out, 0, a.length);
                    System.arraycopy(b, 0, out, a.length, b.length);
                    return out;
                }
            };
            final SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[] {both}, null);
            return ctx;
        }
        catch (final IOException | GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("cannot read the certificate authority file " + caFile + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static X509TrustManager trustManager(final KeyStore ks) throws GeneralSecurityException {
        final TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (final TrustManager t : f.getTrustManagers()) {
            if (t instanceof X509TrustManager) {
                return (X509TrustManager) t;
            }
        }
        throw new GeneralSecurityException("no X509TrustManager");
    }

    /** True for an {@code https} address, or {@code http} to the loopback interface ({@code localhost}, {@code 127.x}, {@code ::1}) only. */
    public static boolean secure(final URI uri) {
        if (uri == null || uri.getScheme() == null || uri.getHost() == null) {
            return false;
        }
        final String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("https")) {
            return true;
        }
        return scheme.equals("http") && loopback(uri.getHost());
    }

    public static boolean loopback(final String host) {
        final String h = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return h.equals("localhost") || h.endsWith(".localhost") || h.equals("::1") || h.matches("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
    }

    /** {@code GET} of a JSON document. @throws IOException network, timeout, body too large */
    public static Response getJson(final HttpClient http, final URI uri) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json").GET().build();
        return send(http, req);
    }

    /** {@code POST} of a form ({@code application/x-www-form-urlencoded}), JSON response. */
    public static Response postForm(final HttpClient http, final URI uri, final Map<String, String> form) throws IOException, InterruptedException {
        final HttpRequest req = HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT).header("Accept", "application/json")
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(form))).build();
        return send(http, req);
    }

    public static String form(final Map<String, String> form) {
        final StringJoiner j = new StringJoiner("&");
        for (final Map.Entry<String, String> e : form.entrySet()) {
            if (e.getValue() != null) {
                j.add(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
            }
        }
        return j.toString();
    }

    private static Response send(final HttpClient http, final HttpRequest req) throws IOException, InterruptedException {
        final HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        final byte[] body;
        try (InputStream in = r.body()) {
            body = readBounded(in);
        }
        Map<String, Object> json = null;
        try {
            json = Json.object(new String(body, StandardCharsets.UTF_8));
        }
        catch (final IllegalArgumentException e) {
            // not JSON (proxy page...): the status alone
        }
        return new Response(r.statusCode(), json);
    }

    private static byte[] readBounded(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            if (out.size() + n > MAX_BODY) {
                throw new IOException("response larger than " + MAX_BODY + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
