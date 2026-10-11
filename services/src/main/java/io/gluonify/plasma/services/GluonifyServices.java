package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Comment joindre un service de Gluonify, sans aucune configuration : l'adresse et la source du jeton se déduisent de l'endroit où tourne
 * l'application. Ordre de résolution, le même pour chaque service :
 * <ol>
 *   <li><b>Surcharge explicite</b> ({@link Endpoint.Source#OVERRIDE}) : {@code GLUONIFY_SERVICE_<APP>_URL} en propriété système, ou en variable
 *       d'environnement hors de la plateforme ; jeton {@code GLUONIFY_<APP>_TOKEN} (fixe) ou {@code GLUONIFY_<APP>_TOKEN_FILE} (relu à chaque appel),
 *       propriété système d'abord, sinon aucun jeton.</li>
 *   <li><b>Sur la plateforme</b> ({@link Endpoint.Source#PLATFORM}, {@code GLUONIFY_ENVIRONMENT} présent) : l'adresse injectée pour un service de
 *       {@code uses} et le fichier du jeton d'application ({@code GLUONIFY_<APP>_TOKEN_FILE}, relu à chaque appel). Service absent : le message dit
 *       d'ajouter {@code "<app>"} dans {@code uses} de {@code gluonify.json}.</li>
 *   <li><b>Sur le poste du développeur</b> ({@link Endpoint.Source#DEVELOPER}) : la zone de {@code ~/.gluonify/config.json} (ou
 *       {@code GLUONIFY_ZONE}), écrite par {@code gluonify login <zone>} ; l'adresse publique du service vient du document de découverte
 *       {@code https://<zone>/.well-known/gluonify} (gardé 5 minutes) ; le jeton est celui du développeur ({@code ~/.gluonify/tokens/<zone>.json},
 *       renouvelé par le jeton de rafraîchissement).</li>
 *   <li><b>Sinon</b> : une {@link GluonifyServicesException} qui dit quoi faire ({@code gluonify login}, ou {@code GluonifyTestServices} en test).</li>
 * </ol>
 *
 * <pre>{@code
 * Endpoint crystallon = GluonifyServices.endpoint("crystallon");
 * HttpRequest.newBuilder(crystallon.resolve("/v1/render")).header("Authorization", "Bearer " + crystallon.token().orElseThrow());
 * }</pre>
 *
 * <p>Java 17, aucune dépendance. Sûr entre fils. Aucun {@link HttpClient} dans un champ statique (image native) : celui de l'instance est créé au
 * premier appel réseau, seulement sur le poste du développeur. Rien n'est journalisé, aucun jeton n'apparaît dans un message.
 */
public final class GluonifyServices {
    /** Variable présente sur la plateforme seulement : l'environnement de l'application ({@code SBX}, {@code DEV}, {@code QUA}, {@code PRD}). */
    public static final String ENV_ENVIRONMENT = "GLUONIFY_ENVIRONMENT";
    /** Variable ou propriété : la zone à utiliser sur le poste du développeur, au lieu de la zone courante de {@code config.json}. */
    public static final String ENV_ZONE = "GLUONIFY_ZONE";
    /** Durée de vie du document de découverte en mémoire. */
    public static final Duration DISCOVERY_TTL = Duration.ofMinutes(5);

    private static final Pattern SERVICE = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Object DEFAULTS_LOCK = new Object();
    private static volatile GluonifyServices defaults;

    private final Function<String, String> environment;
    private final Function<String, String> properties;
    private final Path home;
    private final Clock clock;
    private final Duration discoveryTtl;
    private final HttpClient configuredHttp;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<URI, Cached> discoveries = new ConcurrentHashMap<>();

    private record Cached(Discovery discovery, Instant at) {
    }

    private GluonifyServices(final Builder b) {
        this.environment = b.environment;
        this.properties = b.properties;
        this.home = b.home != null ? b.home : DeveloperConfig.home(b.environment, b.properties);
        this.clock = b.clock;
        this.discoveryTtl = b.discoveryTtl;
        this.configuredHttp = b.httpClient;
    }

    /** L'instance du processus : environnement et propriétés système réels, dossier {@code ~/.gluonify} (ou {@code GLUONIFY_HOME}). Créée au premier appel. */
    public static GluonifyServices defaults() {
        GluonifyServices d = defaults;
        if (d == null) {
            synchronized (DEFAULTS_LOCK) {
                d = defaults;
                if (d == null) {
                    d = builder().build();
                    defaults = d;
                }
            }
        }
        return d;
    }

    /** Raccourci de {@code defaults().resolve(service)}. @throws GluonifyServicesException si le service ne peut pas être joint */
    public static Endpoint endpoint(final String service) {
        return defaults().resolve(service);
    }

    /** Raccourci de {@code defaults().resolveOptional(service)}. */
    public static Optional<Endpoint> optional(final String service) {
        return defaults().resolveOptional(service);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Le dossier de Gluonify de cette instance ({@code ~/.gluonify}). */
    public Path home() {
        return home;
    }

    /**
     * Résout le service.
     *
     * @param service nom du service tel qu'écrit dans {@code uses} ({@code crystallon})
     * @throws GluonifyServicesException si le service ne peut pas être joint ; le message dit quoi faire
     * @throws IllegalArgumentException  si le nom n'est pas un nom de service
     */
    public Endpoint resolve(final String service) {
        final String s = name(service);
        final Endpoint override = override(s);
        if (override != null) {
            return override;
        }
        if (onPlatform()) {
            return platform(s);
        }
        return developer(s);
    }

    /**
     * Comme {@link #resolve(String)}, mais vide quand le service n'est simplement pas disponible : absent de {@code uses} sur la plateforme, aucun
     * cluster connu sur le poste, ou zone qui ne publie pas ce service. Pour un service facultatif. Les autres échecs (session expirée, découverte
     * injoignable) lèvent toujours une {@link GluonifyServicesException}.
     */
    public Optional<Endpoint> resolveOptional(final String service) {
        final String s = name(service);
        final Endpoint override = override(s);
        if (override != null) {
            return Optional.of(override);
        }
        if (onPlatform()) {
            return blank(environment.apply(urlKey(s))) ? Optional.empty() : Optional.of(platform(s));
        }
        final DeveloperConfig config = DeveloperConfig.read(home);
        final Optional<DeveloperConfig.Zone> zone = zone(config);
        if (zone.isEmpty()) {
            return Optional.empty();
        }
        final Discovery d = discovery(zone.get());
        if (d.service(s).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(developer(s, zone.get(), d));
    }

    /** Vrai sur la plateforme ({@code GLUONIFY_ENVIRONMENT} présent). */
    public boolean onPlatform() {
        return !blank(environment.apply(ENV_ENVIRONMENT));
    }

    /** Oublie les documents de découverte gardés en mémoire. */
    public void invalidate() {
        discoveries.clear();
    }

    /** {@code crystallon} → {@code GLUONIFY_SERVICE_CRYSTALLON_URL} ; {@code my-svc} → {@code GLUONIFY_SERVICE_MY_SVC_URL}. */
    public static String urlKey(final String service) {
        return "GLUONIFY_SERVICE_" + upper(service) + "_URL";
    }

    /** {@code crystallon} → {@code GLUONIFY_CRYSTALLON_TOKEN}. */
    public static String tokenKey(final String service) {
        return "GLUONIFY_" + upper(service) + "_TOKEN";
    }

    /** {@code crystallon} → {@code GLUONIFY_CRYSTALLON_TOKEN_FILE} (même règle que {@code ServiceToken.envName} du SDK). */
    public static String tokenFileKey(final String service) {
        return tokenKey(service) + "_FILE";
    }

    // ------------------------------------------------------------------------------------------------ 1. surcharge

    private Endpoint override(final String s) {
        final String key = urlKey(s);
        String url = properties.apply(key);
        if (blank(url) && !onPlatform()) {
            url = environment.apply(key);
        }
        if (blank(url)) {
            return null;
        }
        final URI uri = uri(url, key);
        return new Endpoint(s, uri, overrideTokens(s), Endpoint.Source.OVERRIDE, null, null);
    }

    private Supplier<String> overrideTokens(final String s) {
        final String token = first(properties.apply(tokenKey(s)), null);
        if (token != null) {
            return fixed(token);
        }
        final String file = properties.apply(tokenFileKey(s));
        if (!blank(file)) {
            return file(Path.of(file.strip()), s);
        }
        final String envToken = environment.apply(tokenKey(s));
        if (!blank(envToken)) {
            return fixed(envToken);
        }
        final String envFile = environment.apply(tokenFileKey(s));
        if (!blank(envFile)) {
            return file(Path.of(envFile.strip()), s);
        }
        return () -> null;
    }

    // ------------------------------------------------------------------------------------------------ 2. plateforme

    private Endpoint platform(final String s) {
        final String key = urlKey(s);
        final String url = environment.apply(key);
        if (blank(url)) {
            throw new GluonifyServicesException("the service " + s + " is not available to this application: add \"" + s + "\" to \"uses\" in gluonify.json, "
                    + "then deploy again (the platform then provides " + key + " and the application token)");
        }
        final String file = environment.apply(tokenFileKey(s));
        final Supplier<String> tokens;
        if (!blank(file)) {
            tokens = file(Path.of(file.strip()), s);
        }
        else if (!blank(environment.apply(tokenKey(s)))) {
            tokens = fixed(environment.apply(tokenKey(s)));
        }
        else {
            tokens = () -> null; // plateforme sans jetons de service : le service répondra 401 s'il en exige un
        }
        return new Endpoint(s, uri(url, key), tokens, Endpoint.Source.PLATFORM, null, null);
    }

    // ------------------------------------------------------------------------------------------------ 3. poste du développeur

    private Endpoint developer(final String s) {
        final DeveloperConfig config = DeveloperConfig.read(home);
        final DeveloperConfig.Zone zone = zone(config).orElseThrow(() -> nothingKnown(s, config));
        final Discovery d = discovery(zone);
        return developer(s, zone, d);
    }

    private Endpoint developer(final String s, final DeveloperConfig.Zone zone, final Discovery d) {
        final Discovery.Service svc = d.service(s).orElseThrow(() -> new GluonifyServicesException("the zone " + zone.name() + " does not publish the service "
                + s + (d.services().isEmpty() ? " (it publishes no service)" : " (it publishes: " + String.join(", ", d.services().keySet()) + ")")
                + ": is the service deployed in this cluster? Or set " + urlKey(s)));
        final DeveloperTokens.Session session = DeveloperTokens.read(home, zone.name()).orElseThrow(() -> DeveloperTokens.notLoggedIn(zone.name()));
        if (svc.audience() != null && !session.audiences().isEmpty() && !session.audiences().contains(svc.audience())) {
            throw new GluonifyServicesException("the developer token of zone " + zone.name() + " is not issued for " + svc.audience() + " (service " + s
                    + "): run gluonify login " + zone.name() + " again (the Charm client " + d.clientId() + " must list this audience)");
        }
        final Path h = home;
        final String z = zone.name();
        final Path ca = zone.caFile();
        final Supplier<String> tokens = () -> DeveloperTokens.accessToken(h, z, () -> http(ca), clock);
        return new Endpoint(s, svc.url(), tokens, Endpoint.Source.DEVELOPER, svc.audience(), z);
    }

    private Optional<DeveloperConfig.Zone> zone(final DeveloperConfig config) {
        final String wanted = first(properties.apply(ENV_ZONE), environment.apply(ENV_ZONE));
        if (wanted != null) {
            final String w = wanted.toLowerCase(Locale.ROOT);
            return Optional.of(config.zone(w).orElseThrow(() -> new GluonifyServicesException(ENV_ZONE + "=" + w + " but this zone is not known: run gluonify login " + w)));
        }
        return config.current();
    }

    /** Le document de découverte de la zone (mémoire de {@link #DISCOVERY_TTL} ; s'il est injoignable, la dernière copie lue est gardée). */
    public Discovery discovery(final DeveloperConfig.Zone zone) {
        final URI url = zone.discovery();
        final Cached cached = discoveries.get(url);
        final Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.at().plus(discoveryTtl))) {
            return cached.discovery();
        }
        try {
            final Discovery d = fetchDiscovery(url, zone);
            discoveries.put(url, new Cached(d, now));
            return d;
        }
        catch (final GluonifyServicesException e) {
            if (cached != null) {
                return cached.discovery(); // cluster momentanément injoignable : la dernière copie vaut mieux qu'un échec
            }
            throw e;
        }
    }

    private Discovery fetchDiscovery(final URI url, final DeveloperConfig.Zone zone) {
        if (!Http.secure(url)) {
            throw new GluonifyServicesException("the discovery address of zone " + zone.name() + " must be https: " + url);
        }
        final Http.Response r;
        try {
            r = Http.getJson(http(zone.caFile()), url);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot read the discovery document of zone " + zone.name() + " at " + url + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "): is the cluster running?", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GluonifyServicesException("the discovery of zone " + zone.name() + " was interrupted", e);
        }
        if (!r.ok()) {
            throw new GluonifyServicesException("the discovery document of zone " + zone.name() + " at " + url + " is not available (HTTP " + r.status()
                    + "): is the cluster running? Discovery is disabled in PRD (gluonify.higgs.discovery.public)");
        }
        return Discovery.of(r.json());
    }

    private GluonifyServicesException nothingKnown(final String s, final DeveloperConfig config) {
        return new GluonifyServicesException("cannot reach the service " + s + ": this is not the Gluonify platform (" + ENV_ENVIRONMENT + " is not set) and no cluster "
                + "is known (" + (config.isEmpty() ? "no " + home.resolve(DeveloperConfig.FILE) : "no current zone in " + home.resolve(DeveloperConfig.FILE))
                + "). Run gluonify login <zone> (for example gluonify login gluonify.lan), set " + urlKey(s) + ", or use fake services in tests "
                + "(GluonifyTestServices.start())");
    }

    HttpClient http(final Path caFile) {
        if (configuredHttp != null) {
            return configuredHttp;
        }
        return clients.computeIfAbsent(caFile == null ? "" : caFile.toAbsolutePath().toString(), k -> {
            try {
                return Http.client(caFile);
            }
            catch (final IllegalArgumentException e) {
                throw new GluonifyServicesException(e.getMessage() + ": fix caFile in " + home.resolve(DeveloperConfig.FILE), e);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ outils

    private static Supplier<String> fixed(final String token) {
        final String t = token.strip();
        return () -> t;
    }

    /** Le jeton est relu à chaque appel ; fichier absent ou vide : pas de jeton (pas encore déposé par l'agent). */
    private static Supplier<String> file(final Path path, final String service) {
        return () -> {
            try {
                final String v = Files.readString(path, StandardCharsets.UTF_8).strip();
                return v.isEmpty() ? null : v;
            }
            catch (final NoSuchFileException e) {
                return null;
            }
            catch (final IOException | RuntimeException e) {
                throw new GluonifyServicesException("cannot read the token file of " + service + " " + path + " (" + e.getClass().getSimpleName() + ")", e);
            }
        };
    }

    private static URI uri(final String url, final String key) {
        final URI u;
        try {
            u = URI.create(url.strip());
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException(key + " is not a valid URL", e);
        }
        final String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("ws") && !scheme.equals("wss") || u.getHost() == null) {
            throw new GluonifyServicesException(key + " must be an absolute http(s) URL");
        }
        return URI.create(u.toString().replaceAll("/+$", ""));
    }

    private static String name(final String service) {
        Objects.requireNonNull(service, "service");
        final String s = service.strip().toLowerCase(Locale.ROOT);
        if (!SERVICE.matcher(s).matches()) {
            throw new IllegalArgumentException("not a service name: " + service);
        }
        return s;
    }

    private static String upper(final String service) {
        return service.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    private static String first(final String a, final String b) {
        return !blank(a) ? a.strip() : !blank(b) ? b.strip() : null;
    }

    /** Une instance à part : environnement, propriétés, dossier, horloge et client HTTP fournis (tests, outils). */
    public static final class Builder {
        private Function<String, String> environment = System::getenv;
        private Function<String, String> properties = System::getProperty;
        private Path home;
        private Clock clock = Clock.systemUTC();
        private Duration discoveryTtl = DISCOVERY_TTL;
        private HttpClient httpClient;

        private Builder() {
        }

        /** @param env les variables d'environnement (défaut : {@link System#getenv(String)}) */
        public Builder environment(final Function<String, String> env) {
            this.environment = Objects.requireNonNull(env, "env");
            return this;
        }

        /** @param props les propriétés système (défaut : {@link System#getProperty(String)}) */
        public Builder properties(final Function<String, String> props) {
            this.properties = Objects.requireNonNull(props, "props");
            return this;
        }

        /** @param dir le dossier de Gluonify (défaut : {@code GLUONIFY_HOME}, sinon {@code ~/.gluonify}) */
        public Builder home(final Path dir) {
            this.home = Objects.requireNonNull(dir, "dir");
            return this;
        }

        public Builder clock(final Clock c) {
            this.clock = Objects.requireNonNull(c, "clock");
            return this;
        }

        /** @param ttl durée de vie du document de découverte en mémoire (défaut : 5 minutes) */
        public Builder discoveryTtl(final Duration ttl) {
            this.discoveryTtl = Objects.requireNonNull(ttl, "ttl");
            return this;
        }

        /** @param client le client HTTP de la découverte et du renouvellement (défaut : un client créé au premier appel, par autorité de certification) */
        public Builder httpClient(final HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        public GluonifyServices build() {
            return new GluonifyServices(this);
        }
    }
}
