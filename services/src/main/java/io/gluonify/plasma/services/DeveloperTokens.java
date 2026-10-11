package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import io.gluonify.plasma.services.internal.Json;
import io.gluonify.plasma.services.internal.SecureFiles;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Les jetons du développeur pour une zone : {@code ~/.gluonify/tokens/<zone>.json} (0600), écrit par {@code gluonify login}. Le jeton d'accès
 * (Charm, une heure, audiences des services portées par le client {@code gluonify-cli}) est renouvelé par le jeton de rafraîchissement quand il
 * expire dans moins d'une minute ; Charm fait tourner le jeton de rafraîchissement (usage unique), donc le renouvellement se fait sous verrou (entre
 * fils et entre processus) et le nouveau couple est réécrit atomiquement. Aucun jeton n'apparaît dans un message ni dans un {@code toString()}.
 */
public final class DeveloperTokens {
    /** Marge : un jeton qui expire dans moins de ce délai est renouvelé avant l'appel. */
    public static final Duration MARGIN = Duration.ofSeconds(60);

    /** Un verrou par fichier pour les fils de ce processus (le {@link FileLock} ne protège qu'entre processus). */
    private static final Map<Path, Object> LOCKS = new ConcurrentHashMap<>();

    private DeveloperTokens() {
    }

    /**
     * Les jetons d'une zone.
     *
     * @param zone             zone du cluster
     * @param clientId         client public de Charm ({@code gluonify-cli})
     * @param tokenEndpoint    point de jeton de Charm (renouvellement)
     * @param accessToken      jeton d'accès (JWT)
     * @param expiresAt        expiration du jeton d'accès (secondes depuis l'epoch)
     * @param refreshToken     jeton de rafraîchissement, ou {@code null}
     * @param refreshExpiresAt expiration du jeton de rafraîchissement (secondes depuis l'epoch), {@code 0} si inconnue
     * @param audiences        audiences du jeton d'accès (lues dans le JWT, sans vérification : information seulement)
     * @param subject          personne connectée ({@code preferred_username}, sinon {@code sub}), ou {@code null}
     * @param revocationEndpoint point de révocation de Charm, ou {@code null}
     */
    public record Session(String zone, String clientId, URI tokenEndpoint, String accessToken, long expiresAt, String refreshToken, long refreshExpiresAt,
                          List<String> audiences, String subject, URI revocationEndpoint) {
        public Session {
            audiences = audiences == null ? List.of() : List.copyOf(audiences);
        }

        public Instant expiry() {
            return Instant.ofEpochSecond(expiresAt);
        }

        /** Vrai si le jeton d'accès est encore valable au moins {@code margin}. */
        public boolean fresh(final Clock clock, final Duration margin) {
            return accessToken != null && clock.instant().plus(margin).isBefore(expiry());
        }

        /** Vrai si un jeton de rafraîchissement existe et n'est pas expiré (expiration inconnue : on essaie). */
        public boolean renewable(final Clock clock) {
            return refreshToken != null && (refreshExpiresAt == 0 || clock.instant().isBefore(Instant.ofEpochSecond(refreshExpiresAt)));
        }

        @Override
        public String toString() { // jamais de jeton
            return "Session[zone=" + zone + ", clientId=" + clientId + ", subject=" + subject + ", expiresAt=" + expiry() + ", audiences=" + audiences
                    + ", refreshable=" + (refreshToken != null) + "]";
        }
    }

    /** {@code <home>/tokens/<zone>.json}. */
    public static Path file(final Path home, final String zone) {
        if (!DeveloperConfig.validZone(zone)) {
            throw new IllegalArgumentException("invalid zone name");
        }
        return home.resolve("tokens").resolve(zone + ".json");
    }

    /** Les jetons de la zone, vide si le fichier n'existe pas. @throws GluonifyServicesException si le fichier est illisible */
    public static Optional<Session> read(final Path home, final String zone) {
        final Path file = file(home, zone);
        final String text;
        try {
            text = SecureFiles.read(file);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot read the developer tokens " + file + " (" + e.getClass().getSimpleName() + ")", e);
        }
        if (text == null) {
            return Optional.empty();
        }
        final Map<String, Object> m;
        try {
            m = Json.object(text);
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException("the developer tokens " + file + " are not readable: run gluonify login " + zone + " again");
        }
        final String te = Json.str(m, "tokenEndpoint");
        final String re = Json.str(m, "revocationEndpoint");
        return Optional.of(new Session(zone, Json.str(m, "clientId"), te == null ? null : URI.create(te), Json.str(m, "accessToken"),
                Json.num(m, "expiresAt", 0), Json.str(m, "refreshToken"), Json.num(m, "refreshExpiresAt", 0), Json.strings(m, "audiences"),
                Json.str(m, "subject"), re == null ? null : URI.create(re)));
    }

    /** Écrit les jetons (0600, atomiquement). */
    public static void write(final Path home, final Session s) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("zone", s.zone());
        m.put("clientId", s.clientId());
        m.put("tokenEndpoint", s.tokenEndpoint() == null ? null : s.tokenEndpoint().toString());
        if (s.revocationEndpoint() != null) {
            m.put("revocationEndpoint", s.revocationEndpoint().toString());
        }
        if (s.subject() != null) {
            m.put("subject", s.subject());
        }
        m.put("audiences", s.audiences());
        m.put("accessToken", s.accessToken());
        m.put("expiresAt", s.expiresAt());
        m.put("refreshToken", s.refreshToken());
        m.put("refreshExpiresAt", s.refreshExpiresAt());
        try {
            SecureFiles.write(file(home, s.zone()), Json.write(m) + "\n");
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot write the developer tokens of zone " + s.zone() + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /** Supprime les jetons de la zone. @return vrai si un fichier existait */
    public static boolean delete(final Path home, final String zone) {
        try {
            Files.deleteIfExists(file(home, zone).resolveSibling(zone + ".json.lock"));
            return Files.deleteIfExists(file(home, zone));
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot delete the developer tokens of zone " + zone + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    /**
     * Une session à partir de la réponse du point de jeton de Charm ({@code access_token}, {@code expires_in}, {@code refresh_token},
     * {@code refresh_expires_in}). Sans nouveau jeton de rafraîchissement, celui de {@code previous} est gardé.
     *
     * @throws GluonifyServicesException si la réponse n'a pas de jeton d'accès
     */
    public static Session fromTokenResponse(final String zone, final String clientId, final URI tokenEndpoint, final URI revocationEndpoint,
                                            final Map<String, Object> response, final Clock clock, final Session previous) {
        final String access = Json.str(response, "access_token");
        if (access == null) {
            throw new GluonifyServicesException("Charm answered without an access token");
        }
        final long now = clock.instant().getEpochSecond();
        final long expiresIn = Json.num(response, "expires_in", 3600);
        String refresh = Json.str(response, "refresh_token");
        long refreshExp = response.containsKey("refresh_expires_in") ? now + Json.num(response, "refresh_expires_in", 0) : 0;
        if (refresh == null && previous != null) {
            refresh = previous.refreshToken();
            refreshExp = previous.refreshExpiresAt();
        }
        final Map<String, Object> claims = claims(access);
        String subject = Json.str(claims, "preferred_username");
        if (subject == null) {
            subject = Json.str(claims, "sub");
        }
        return new Session(zone, clientId, tokenEndpoint, access, now + expiresIn, refresh, refreshExp, Json.strings(claims, "aud"), subject, revocationEndpoint);
    }

    /** Les revendications d'un JWT, lues sans vérification (pour information : audiences, personne). Vide si ce n'est pas un JWT. */
    static Map<String, Object> claims(final String jwt) {
        final String[] parts = jwt.split("\\.");
        if (parts.length < 2) {
            return Map.of();
        }
        try {
            return Json.object(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        }
        catch (final IllegalArgumentException e) {
            return Map.of();
        }
    }

    /**
     * Le jeton d'accès du développeur pour la zone, renouvelé s'il expire dans moins de {@link #MARGIN}.
     *
     * @throws GluonifyServicesException pas connecté, session expirée ou révoquée, Charm injoignable : le message dit quoi faire
     */
    public static String accessToken(final Path home, final String zone, final Supplier<HttpClient> http, final Clock clock) {
        final Session s = read(home, zone).orElseThrow(() -> notLoggedIn(zone));
        if (s.fresh(clock, MARGIN)) {
            return s.accessToken();
        }
        return refreshed(home, zone, http, clock, false).accessToken();
    }

    /**
     * Renouvelle les jetons de la zone (sous verrou), sauf si un autre fil ou processus vient de le faire et que {@code force} est faux.
     *
     * @throws GluonifyServicesException pas connecté, session expirée ou révoquée, Charm injoignable
     */
    public static Session refreshed(final Path home, final String zone, final Supplier<HttpClient> http, final Clock clock, final boolean force) {
        final Path file = file(home, zone);
        final Object jvmLock = LOCKS.computeIfAbsent(file.toAbsolutePath().normalize(), k -> new Object());
        synchronized (jvmLock) {
            try {
                SecureFiles.directory(file.getParent());
                try (FileChannel ch = FileChannel.open(file.resolveSibling(zone + ".json.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock lock = ch.lock()) {
                    final Session s = read(home, zone).orElseThrow(() -> notLoggedIn(zone));
                    if (!force && s.fresh(clock, MARGIN)) {
                        return s; // renouvelé entre-temps
                    }
                    if (!s.renewable(clock) || s.tokenEndpoint() == null) {
                        throw expired(zone);
                    }
                    final Session next = refresh(s, http.get(), clock);
                    write(home, next);
                    return next;
                }
            }
            catch (final IOException e) {
                throw new GluonifyServicesException("cannot lock the developer tokens of zone " + zone + " (" + e.getClass().getSimpleName() + ")", e);
            }
        }
    }

    private static Session refresh(final Session s, final HttpClient http, final Clock clock) {
        if (!Http.secure(s.tokenEndpoint())) {
            throw new GluonifyServicesException("the token endpoint of zone " + s.zone() + " is not https: run gluonify login " + s.zone() + " again");
        }
        final Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", s.refreshToken());
        form.put("client_id", s.clientId() == null ? Discovery.DEFAULT_CLIENT_ID : s.clientId());
        final Http.Response r;
        try {
            r = Http.postForm(http, s.tokenEndpoint(), form);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot renew the developer token of zone " + s.zone() + ": Charm is unreachable at " + s.tokenEndpoint()
                    + " (" + e.getClass().getSimpleName() + ")", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GluonifyServicesException("the renewal of the developer token of zone " + s.zone() + " was interrupted", e);
        }
        if (r.ok()) {
            return fromTokenResponse(s.zone(), s.clientId(), s.tokenEndpoint(), s.revocationEndpoint(), r.json(), clock, s);
        }
        final String error = Json.str(r.json(), "error");
        if (r.status() == 400 && "invalid_grant".equals(error) || r.status() == 401) {
            throw expired(s.zone());
        }
        throw new GluonifyServicesException("cannot renew the developer token of zone " + s.zone() + ": Charm answered HTTP " + r.status()
                + (error == null ? "" : " (" + error + ")"));
    }

    static GluonifyServicesException notLoggedIn(final String zone) {
        return new GluonifyServicesException("not logged in to zone " + zone + ": run gluonify login " + zone);
    }

    static GluonifyServicesException expired(final String zone) {
        return new GluonifyServicesException("the developer session of zone " + zone + " has expired or was revoked: run gluonify login " + zone);
    }
}
