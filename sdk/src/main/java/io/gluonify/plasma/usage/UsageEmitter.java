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
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Émetteur d'usage d'un service ({@code metering.md} § 4.2) : {@link UsageMeter} → {@link UsageSpool} → {@code POST /v1/usage/records} du calorimètre.
 * Le service appelle {@link #tick()} toutes les quelques secondes depuis SON ordonnanceur (aucun fil n'est créé ici) : les fenêtres closes passent dans le spool,
 * puis les lots en attente sont envoyés du plus ancien au plus récent, avec un délai croissant après un échec (5 s, doublé jusqu'à 5 min). Ne lève jamais, ne bloque
 * jamais au-delà du délai d'une requête ; sans {@link Transport}, le spool garde tout dans ses bornes.
 */
public final class UsageEmitter {
    /** Envoi d'un lot ; rend le statut HTTP (une exception : calorimètre injoignable). */
    public interface Transport {
        int post(String body) throws IOException, InterruptedException;
    }

    /** Lots de 1 000 au plus (le calorimètre en accepte 5 000). */
    public static final int MAX_BATCH = 1000;
    static final long MIN_BACKOFF = 5_000, MAX_BACKOFF = 300_000;

    private final UsageMeter meter;
    private final UsageSpool spool;
    private final Supplier<Transport> transport;
    private final LongSupplier clock;
    private long nextTry, backoff;
    private volatile int lastStatus;
    private volatile long sent;

    public UsageEmitter(UsageMeter meter, UsageSpool spool, Supplier<Transport> transport, LongSupplier clock) {
        this.meter = meter;
        this.spool = spool;
        this.transport = transport;
        this.clock = clock;
    }

    public UsageMeter meter() { return meter; }

    public UsageSpool spool() { return spool; }

    /** Dernier statut HTTP du calorimètre (0 : jamais joint, -1 : injoignable). */
    public int lastStatus() { return lastStatus; }

    /** Enregistrements acquittés depuis le démarrage. */
    public long sent() { return sent; }

    /** Bascule les fenêtres closes dans le spool, puis tente l'envoi. */
    public synchronized void tick() {
        try {
            List<UsageRecord> closed = meter.drain(clock.getAsLong());
            for (int i = 0; i < closed.size(); i += MAX_BATCH) spool.append(closed.subList(i, Math.min(closed.size(), i + MAX_BATCH)));
            send();
        } catch (RuntimeException e) {
            // jamais d'exception vers le service
        }
    }

    /** À l'arrêt du service : la fenêtre en cours aussi (incomplète, mais comptée), puis un dernier envoi. */
    public synchronized void close() {
        try {
            List<UsageRecord> all = meter.drain(Long.MAX_VALUE);
            for (int i = 0; i < all.size(); i += MAX_BATCH) spool.append(all.subList(i, Math.min(all.size(), i + MAX_BATCH)));
            nextTry = 0;
            send();
        } catch (RuntimeException e) {
            // arrêt quand même
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
                sent += b.lines().size();
                backoff = 0;
                continue;
            }
            if (status == 400 || status == 413 || status == 422) { // le lot ne passera jamais : abandonné et compté (le trou de seq le montre au calorimètre)
                spool.drop(b);
                continue;
            }
            backoff = backoff == 0 ? MIN_BACKOFF : Math.min(MAX_BACKOFF, backoff * 2); // 401, 403, 5xx, injoignable : on réessaie plus tard
            nextTry = now + backoff;
            return;
        }
    }

    static String body(List<String> lines) {
        StringBuilder b = new StringBuilder(lines.size() * 400 + 16).append("{\"records\":[");
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) b.append(',');
            b.append(lines.get(i));
        }
        return b.append("]}").toString();
    }

    /**
     * Transport HTTP vers {@code <url>/v1/usage/records} avec le jeton relu à chaque envoi dans {@code tokenFile} (un jeton de service est renouvelé par
     * la plateforme sans redémarrage). Client HTTP créé au premier envoi (image native : jamais dans un champ statique). Le jeton n'est jamais journalisé.
     */
    public static Transport http(String url, Path tokenFile, Duration timeout) {
        String target = url.replaceAll("/+$", "") + "/v1/usage/records";
        return new Transport() {
            private HttpClient client;

            @Override
            public synchronized int post(String body) throws IOException, InterruptedException {
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
     * Transport tiré de l'environnement de la plateforme : {@code GLUONIFY_SERVICE_CALORIMETER_URL} et {@code GLUONIFY_CALORIMETER_TOKEN_FILE}
     * ({@code uses: ["calorimeter"]}) ; null sans adresse. Relu à chaque appel par l'émetteur (le fichier du jeton peut arriver après le démarrage).
     */
    public static Supplier<Transport> fromEnvironment(java.util.function.Function<String, String> env, Duration timeout) {
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

    /** Pour les tests : le corps d'un lot. */
    static List<String> lines(List<UsageRecord> records) {
        List<String> out = new ArrayList<>();
        for (UsageRecord r : records) out.add(r.toJson());
        return out;
    }
}
