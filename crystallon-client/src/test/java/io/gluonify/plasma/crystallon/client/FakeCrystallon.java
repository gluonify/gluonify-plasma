package io.gluonify.plasma.crystallon.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Fake Crystallon: a JDK {@link HttpServer} that records the calls and answers what it was programmed to. */
final class FakeCrystallon implements AutoCloseable {
    static final byte[] PDF = "%PDF-1.4\n%fake document\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);

    /** A received call; header names are lowercase. */
    record Call(String method, String rawPath, String rawQuery, Map<String, String> headers, byte[] body) {
        String header(final String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }

        String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** Path and query as sent. */
        String target() {
            return rawPath + (rawQuery == null ? "" : "?" + rawQuery);
        }
    }

    /** A programmed response. {@code chunked}: no {@code Content-Length}. */
    record Reply(int status, Map<String, String> headers, byte[] body, long delayMillis, boolean chunked) {
        static Reply pdf() {
            return new Reply(200, Map.of("Content-Type", "application/pdf", "X-Crystallon-Pages", "2", "X-Crystallon-Warnings", "1", "X-Crystallon-PDFA", "valid"), PDF, 0, false);
        }

        static Reply json(final int status, final String json) {
            return new Reply(status, Map.of("Content-Type", "application/json"), json.getBytes(StandardCharsets.UTF_8), 0, false);
        }

        static Reply empty(final int status) {
            return new Reply(status, Map.of(), new byte[0], 0, false);
        }

        static Reply raw(final int status, final String contentType, final byte[] body) {
            return new Reply(status, Map.of("Content-Type", contentType), body, 0, false);
        }

        Reply with(final String name, final String value) {
            final Map<String, String> h = new LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, h, body, delayMillis, chunked);
        }

        Reply without(final String name) {
            final Map<String, String> h = new LinkedHashMap<>(headers);
            h.remove(name);
            return new Reply(status, h, body, delayMillis, chunked);
        }

        Reply delayed(final long millis) {
            return new Reply(status, headers, body, millis, chunked);
        }

        Reply asChunked() {
            return new Reply(status, headers, body, delayMillis, true);
        }
    }

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();
    private volatile Reply reply = Reply.pdf();

    FakeCrystallon() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        }
        catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            final Thread t = new Thread(r, "fake-crystallon");
            t.setDaemon(true);
            return t;
        }));
        server.createContext("/", this::handle);
        server.start();
    }

    FakeCrystallon reply(final Reply reply) {
        this.reply = reply;
        return this;
    }

    List<Call> calls() {
        return calls;
    }

    Call last() {
        return calls.get(calls.size() - 1);
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    URI uri() {
        return URI.create(url());
    }

    CrystallonClient.Builder client() {
        return CrystallonClient.builder().baseUrl(url());
    }

    private void handle(final HttpExchange ex) throws IOException {
        final Map<String, String> headers = new LinkedHashMap<>();
        ex.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
        final byte[] body = ex.getRequestBody().readAllBytes();
        calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), headers, body));
        final Reply r = reply;
        try {
            if (r.delayMillis() > 0) {
                new CountDownLatch(1).await(r.delayMillis(), TimeUnit.MILLISECONDS); // simulated slow server: a bounded wait that nobody releases
            }
            r.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            if (r.chunked()) {
                ex.sendResponseHeaders(r.status(), 0);
                try (OutputStream out = ex.getResponseBody()) {
                    final int piece = 64 * 1024;
                    for (int i = 0; i < r.body().length; i += piece) {
                        out.write(r.body(), i, Math.min(piece, r.body().length - i));
                        out.flush();
                    }
                }
            }
            else if (r.body().length == 0) {
                ex.sendResponseHeaders(r.status(), -1);
            }
            else {
                ex.sendResponseHeaders(r.status(), r.body().length);
                try (OutputStream out = ex.getResponseBody()) {
                    out.write(r.body());
                }
            }
        }
        catch (final IOException | InterruptedException e) {
            // the client hung up (body too large, timeout): normal in these tests
        }
        finally {
            ex.close();
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
