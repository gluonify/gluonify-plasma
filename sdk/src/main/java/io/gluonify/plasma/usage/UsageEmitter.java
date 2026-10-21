package io.gluonify.plasma.usage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Usage emitter of a service ({@code metering.md} § 4.2): {@link UsageMeter} to {@link UsageSpool} to the calorimeter's {@code POST /v1/usage/records}.
 * The service calls {@link #tick()} every few seconds from ITS scheduler (no thread is created here): closed windows go into the spool,
 * then pending batches are sent from the oldest to the newest, with an increasing delay after a failure (5 s, doubled up to 5 min). Never throws, never blocks
 * beyond the timeout of a request; without a {@link Transport}, the spool keeps everything within its bounds.
 */
public final class UsageEmitter {
    /** Sends a batch; returns the HTTP status (an exception: calorimeter unreachable). */
    public interface Transport {
        int post(String body) throws IOException, InterruptedException;
    }

    /** Batches of at most 1,000 (the calorimeter accepts 5,000). */
    public static final int MAX_BATCH = 1000;
    static final long MIN_BACKOFF = 5_000;
    static final long MAX_BACKOFF = 300_000;

    private final UsageMeter meter;
    private final UsageSpool spool;
    private final Supplier<Transport> transport;
    private final LongSupplier clock;
    private long nextTry;
    private long backoff;
    private volatile int lastStatus;
    private final AtomicLong sent = new AtomicLong();

    public UsageEmitter(final UsageMeter meter, final UsageSpool spool, final Supplier<Transport> transport, final LongSupplier clock) {
        this.meter = meter;
        this.spool = spool;
        this.transport = transport;
        this.clock = clock;
    }

    public UsageMeter meter() { return meter; }

    public UsageSpool spool() { return spool; }

    /** Last HTTP status of the calorimeter (0: never reached, -1: unreachable). */
    public int lastStatus() { return lastStatus; }

    /** Records acknowledged since startup. */
    public long sent() { return sent.get(); }

    /** Moves the closed windows into the spool, then tries to send. */
    public synchronized void tick() {
        try {
            List<UsageRecord> closed = meter.drain(clock.getAsLong());
            for (int i = 0; i < closed.size(); i += MAX_BATCH) spool.append(closed.subList(i, Math.min(closed.size(), i + MAX_BATCH)));
            send();
        } catch (RuntimeException e) {
            // never an exception towards the service
        }
    }

    /** On service shutdown: the current window too (incomplete, but counted), then one last send. */
    public synchronized void close() {
        try {
            List<UsageRecord> all = meter.drain(Long.MAX_VALUE);
            for (int i = 0; i < all.size(); i += MAX_BATCH) spool.append(all.subList(i, Math.min(all.size(), i + MAX_BATCH)));
            nextTry = 0;
            send();
        } catch (RuntimeException e) {
            // shut down anyway
        }
    }

    private void send() {
        long now = clock.getAsLong();
        spool.trim(now);
        if (now < nextTry) return;
        Transport t = transport == null ? null : transport.get();
        if (t == null) return;
        for (UsageSpool.Batch b : spool.pending()) {
            int status;
            try {
                status = t.post(body(b.lines()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                status = -1;
            }
            lastStatus = status;
            if (status >= 200 && status < 300) {
                spool.remove(b);
                sent.addAndGet(b.lines().size());
                backoff = 0;
                continue;
            }
            if (status == 400 || status == 413 || status == 422) { // the batch will never go through: dropped and counted (the seq gap shows it to the calorimeter)
                spool.drop(b);
                continue;
            }
            backoff = backoff == 0 ? MIN_BACKOFF : Math.min(MAX_BACKOFF, backoff * 2); // 401, 403, 5xx, unreachable: retry later
            nextTry = now + backoff;
            return;
        }
    }

    static String body(final List<String> lines) {
        StringBuilder b = new StringBuilder(lines.size() * 400 + 16).append("{\"records\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(',');
            b.append(lines.get(i));
        }
        return b.append("]}").toString();
    }

    /**
     * HTTP transport to {@code <url>/v1/usage/records} with the token re-read on each send from {@code tokenFile} (a service token is renewed by
     * the platform without a restart). HTTP client created on the first send (native image: never in a static field). The token is never logged.
     */
    public static Transport http(final String url, final Path tokenFile, final Duration timeout) {
        String target = url.replaceAll("/+$", "") + "/v1/usage/records";
        return new Transport() {
            private HttpClient client;

            @Override
            public synchronized int post(final String body) throws IOException, InterruptedException {
                if (client == null) client = HttpClient.newBuilder().connectTimeout(timeout).build();
                HttpRequest.Builder r = HttpRequest.newBuilder(URI.create(target)).timeout(timeout).header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
                if (tokenFile != null && Files.isReadable(tokenFile)) {
                    String tok = Files.readString(tokenFile, StandardCharsets.UTF_8).trim();
                    if (!tok.isEmpty()) r.header("Authorization", "Bearer " + tok);
                }
                return client.send(r.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            }
        };
    }

    /**
     * Transport taken from the platform environment: {@code GLUONIFY_SERVICE_CALORIMETER_URL} and {@code GLUONIFY_CALORIMETER_TOKEN_FILE}
     * ({@code uses: ["calorimeter"]}); null without an address. Re-read on each call by the emitter (the token file may arrive after startup).
     */
    public static Supplier<Transport> fromEnvironment(final java.util.function.Function<String, String> env, final Duration timeout) {
        Transport[] cached = new Transport[1];
        String[] key = new String[1];
        return () -> {
            String url = env.apply("GLUONIFY_SERVICE_CALORIMETER_URL");
            if (url == null || url.isBlank()) return null;
            String tf = env.apply("GLUONIFY_CALORIMETER_TOKEN_FILE");
            String k = url + "|" + tf;
            if (cached[0] == null || !k.equals(key[0])) {
                cached[0] = http(url, tf == null || tf.isBlank() ? null : Path.of(tf), timeout);
                key[0] = k;
            }
            return cached[0];
        };
    }

    /** For tests: the body of a batch. */
    static List<String> lines(final List<UsageRecord> records) {
        List<String> out = new ArrayList<>();
        for (UsageRecord r : records) out.add(r.toJson());
        return out;
    }
}
