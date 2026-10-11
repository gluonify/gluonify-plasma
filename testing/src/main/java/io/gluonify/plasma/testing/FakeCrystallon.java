package io.gluonify.plasma.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Un faux Crystallon en mémoire : répond aux routes du client du SDK ({@code POST /v1/templates/{id}/render}, {@code .../by-name/{nom}/render},
 * {@code POST /v1/render}, et leurs {@code /validate}) par un PDF minimal valide (une page) ou une validation réussie, et garde chaque demande
 * ({@link #renders()}). Le jeton attendu est {@link GluonifyTestServices#TOKEN} : un autre jeton reçoit 401, comme le vrai service. Le corps
 * (le JSON du payload) est gardé tel quel : l'application le relit avec sa propre bibliothèque JSON.
 */
public final class FakeCrystallon implements AutoCloseable {
    private static final byte[] PDF = minimalPdf();

    /** Le PDF rendu : un PDF minimal valide d'une page vierge (en-tête, catalogue, table des références croisées exacte). Une copie à chaque appel. */
    public static byte[] pdf() {
        return PDF.clone();
    }

    /**
     * Une demande reçue.
     *
     * @param kind     {@code render} ou {@code validate}
     * @param template le nom ou l'identifiant du modèle (décodé), ou {@code null} pour un modèle envoyé avec la demande ({@code inline})
     * @param byName   vrai si le modèle est désigné par son nom
     * @param query    la requête brute ({@code pdfa=false&version=2}), ou {@code null}
     * @param body     le corps JSON reçu ({@code {"data": ..., "params": ...}}, avec {@code template} pour {@code inline})
     */
    public record Render(String kind, String template, boolean byName, String query, String body) {
    }

    private record Failure(int status, String code, String message) {
    }

    private final String token;
    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Render> renders = new CopyOnWriteArrayList<>();
    private final List<Failure> failures = Collections.synchronizedList(new ArrayList<>());

    FakeCrystallon(final String token) {
        this.token = token;
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        }
        catch (final IOException e) {
            throw new UncheckedIOException("cannot start the fake Crystallon", e);
        }
        executor = Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "fake-crystallon");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Adresse de base du faux service ({@code http://127.0.0.1:<port>}). */
    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Les demandes reçues, dans l'ordre. */
    public List<Render> renders() {
        return List.copyOf(renders);
    }

    /** La dernière demande reçue. @throws IllegalStateException s'il n'y en a aucune */
    public Render lastRender() {
        final List<Render> r = renders();
        if (r.isEmpty()) {
            throw new IllegalStateException("the fake Crystallon received no request");
        }
        return r.get(r.size() - 1);
    }

    /** Oublie les demandes reçues et les échecs programmés. */
    public void reset() {
        renders.clear();
        failures.clear();
    }

    /** La prochaine demande échoue avec ce statut et ce corps d'erreur ({@code {"code", "message"}}), comme le vrai service. */
    public FakeCrystallon failNext(final int status, final String code, final String message) {
        failures.add(new Failure(status, code, message));
        return this;
    }

    private void handle(final HttpExchange ex) throws IOException {
        try {
            final byte[] body = ex.getRequestBody().readAllBytes(); // toujours lu jusqu'au bout
            final String path = ex.getRequestURI().getRawPath();
            final Render render = parse(ex.getRequestMethod(), path, ex.getRequestURI().getRawQuery(), new String(body, StandardCharsets.UTF_8));
            if (render == null) {
                json(ex, 404, error("CRYSTALLON-0028", "Unknown route " + path));
                return;
            }
            final String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !auth.equals("Bearer " + token)) {
                json(ex, 401, error("CRYSTALLON-0001", "Missing or invalid token"));
                return;
            }
            renders.add(render);
            final Failure f;
            synchronized (failures) {
                f = failures.isEmpty() ? null : failures.remove(0);
            }
            if (f != null) {
                json(ex, f.status(), error(f.code(), f.message()));
                return;
            }
            if (render.kind().equals("validate")) {
                json(ex, 200, "{\"valid\":true,\"pages\":1,\"warnings\":[],\"errors\":[],\"warningDetails\":[]}");
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "application/pdf");
            ex.getResponseHeaders().add("X-Crystallon-Pages", "1");
            ex.getResponseHeaders().add("X-Crystallon-Warnings", "0");
            if (render.query() != null && render.query().contains("pdfa=true")) {
                ex.getResponseHeaders().add("X-Crystallon-PDFA", "valid");
            }
            ex.sendResponseHeaders(200, PDF.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(PDF);
            }
        }
        finally {
            ex.close();
        }
    }

    static Render parse(final String method, final String path, final String query, final String body) {
        if (!"POST".equals(method)) {
            return null;
        }
        String p = path;
        String kind = "render";
        if (p.endsWith("/render/validate")) {
            kind = "validate";
            p = p.substring(0, p.length() - "/validate".length());
        }
        if (!p.endsWith("/render")) {
            return null;
        }
        p = p.substring(0, p.length() - "/render".length());
        // un éventuel préfixe de chemin devant /v1 est admis
        final int v1 = p.indexOf("/v1");
        if (v1 < 0) {
            return null;
        }
        p = p.substring(v1);
        if (p.equals("/v1")) {
            return new Render(kind, null, false, query, body);
        }
        if (p.startsWith("/v1/templates/by-name/")) {
            return new Render(kind, decode(p.substring("/v1/templates/by-name/".length())), true, query, body);
        }
        if (p.startsWith("/v1/templates/") && p.indexOf('/', "/v1/templates/".length()) < 0) {
            return new Render(kind, decode(p.substring("/v1/templates/".length())), false, query, body);
        }
        return null;
    }

    private static String decode(final String segment) {
        return URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static String error(final String code, final String message) {
        return "{\"code\":\"" + escape(code) + "\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(final String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void json(final HttpExchange ex, final int status, final String body) throws IOException {
        final byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(b);
        }
    }

    private static byte[] minimalPdf() {
        final String[] objects = {
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << >> >>",
        };
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<Integer> offsets = new ArrayList<>();
        write(out, "%PDF-1.4\n%âãÏÓ\n");
        for (int k = 0; k < objects.length; k++) {
            offsets.add(out.size());
            write(out, (k + 1) + " 0 obj\n" + objects[k] + "\nendobj\n");
        }
        final int xref = out.size();
        final StringBuilder x = new StringBuilder("xref\n0 " + (objects.length + 1) + "\n0000000000 65535 f \n");
        for (final int o : offsets) {
            x.append(String.format("%010d 00000 n \n", o));
        }
        x.append("trailer\n<< /Size ").append(objects.length + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        write(out, x.toString());
        return out.toByteArray();
    }

    private static void write(final ByteArrayOutputStream out, final String s) {
        final byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.write(b, 0, b.length);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
